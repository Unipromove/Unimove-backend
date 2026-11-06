package com.unimove.domain.maps;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.util.retry.Retry;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Geocoding via Photon (OSM). Forward (autocomplete) bate direto no Photon — queries
 * parciais nao sao cacheaveis (o app faz debounce). Reverse (pin → endereco) e cacheado
 * por coordenada arredondada, igual ao route_cache (regra 11). Erros do provedor viram
 * {@link MapsUnavailableException} (HTTP 503), ja tratado pelo GlobalExceptionHandler.
 *
 * Com o lat/lng do app, o forward vai com {@code bbox} em volta do ponto: o vies
 * de lat/lon sozinho so reordena, e "Hospital" voltava com a Espanha. Falha
 * transitoria (DNS, conexao, 5xx) ganha uma nova tentativa curta antes do 503.
 */
@Service
class PhotonGeocodingService implements GeocodingService {

    private static final Logger log = LoggerFactory.getLogger(PhotonGeocodingService.class);
    /** Teto do request inteiro, nova tentativa incluida: o app nao espera mais que isso. */
    private static final Duration BLOCK_TIMEOUT = Duration.ofSeconds(6);
    private static final Duration RETRY_BACKOFF = Duration.ofMillis(300);
    private static final double KM_PER_DEGREE = 111.32;

    private final WebClient photonWebClient;
    private final GeocodeCacheRepository cacheRepository;
    private final double searchRadiusKm;

    PhotonGeocodingService(WebClient photonWebClient, GeocodeCacheRepository cacheRepository,
                           PhotonProperties props) {
        this.photonWebClient = photonWebClient;
        this.cacheRepository = cacheRepository;
        this.searchRadiusKm = props.searchRadiusKm();
    }

    @Override
    public List<GeoPlace> search(String query, Double biasLat, Double biasLng, int limit) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        PhotonResponse response = call(uri -> {
            uri.path("/api")
                    .queryParam("q", query)
                    .queryParam("limit", limit);
            if (biasLat != null && biasLng != null) {
                uri.queryParam("lat", biasLat).queryParam("lon", biasLng)
                        .queryParam("bbox", bbox(biasLat, biasLng));
            }
            return uri.build();
        });
        if (response == null || response.features() == null) {
            return List.of();
        }
        return response.features().stream()
                .map(PhotonGeocodingService::toGeoPlace)
                .filter(p -> p != null)
                .toList();
    }

    @Override
    public GeoPlace reverse(double lat, double lng) {
        String hash = coordHash(lat, lng);

        Optional<GeocodeCache> cached = cacheRepository.findByCoordHash(hash);
        if (cached.isPresent()) {
            return toGeoPlace(cached.get());
        }

        PhotonResponse response = call(uri -> uri.path("/reverse")
                .queryParam("lat", lat)
                .queryParam("lon", lng)
                .build());
        if (response == null || response.features() == null || response.features().isEmpty()) {
            throw new MapsUnavailableException("Nenhum endereco encontrado para o ponto.");
        }
        GeoPlace place = toGeoPlace(response.features().get(0));
        if (place == null) {
            throw new MapsUnavailableException("Photon retornou ponto sem coordenadas.");
        }
        persist(hash, place);
        return place;
    }

    // ---- internals --------------------------------------------------------

    private PhotonResponse call(UriFn uriFn) {
        try {
            return photonWebClient.get()
                    .uri(uriFn::build)
                    .retrieve()
                    .bodyToMono(PhotonResponse.class)
                    .retryWhen(Retry.backoff(1, RETRY_BACKOFF)
                            .filter(PhotonGeocodingService::isTransient)
                            .doBeforeRetry(s -> log.warn("Photon falhou ({}), tentando de novo",
                                    s.failure().toString()))
                            .onRetryExhaustedThrow((spec, s) -> s.failure()))
                    .block(BLOCK_TIMEOUT);
        } catch (WebClientResponseException e) {
            log.error("Photon retornou status {} para geocoding", e.getStatusCode());
            throw new MapsUnavailableException("Servico de busca de endereco indisponivel.", e);
        } catch (RuntimeException e) {
            log.error("Falha ao chamar Photon", e);
            throw new MapsUnavailableException("Servico de busca de endereco indisponivel.", e);
        }
    }

    /** DNS/conexao/timeout e 5xx passam; 4xx e erro nosso e nao melhora tentando de novo. */
    private static boolean isTransient(Throwable t) {
        return t instanceof WebClientRequestException
                || (t instanceof WebClientResponseException e && e.getStatusCode().is5xxServerError());
    }

    /** "minLon,minLat,maxLon,maxLat" de uma caixa de +-searchRadiusKm em volta do ponto. */
    private String bbox(double lat, double lng) {
        double dLat = searchRadiusKm / KM_PER_DEGREE;
        double dLng = searchRadiusKm / (KM_PER_DEGREE * Math.max(Math.cos(Math.toRadians(lat)), 0.01));
        return String.format(java.util.Locale.ROOT, "%.5f,%.5f,%.5f,%.5f",
                Math.max(lng - dLng, -180), Math.max(lat - dLat, -90),
                Math.min(lng + dLng, 180), Math.min(lat + dLat, 90));
    }

    @FunctionalInterface
    private interface UriFn {
        java.net.URI build(org.springframework.web.util.UriBuilder uri);
    }

    private void persist(String hash, GeoPlace place) {
        try {
            GeocodeCache entity = new GeocodeCache();
            entity.setCoordHash(hash);
            entity.setDisplayName(place.displayName());
            entity.setStreet(place.street());
            entity.setCity(place.city());
            entity.setState(place.state());
            entity.setLat(place.lat());
            entity.setLng(place.lng());
            cacheRepository.save(entity);
        } catch (DataIntegrityViolationException race) {
            log.debug("Race no insert do geocode_cache para hash {} — outro thread venceu.", hash);
        }
    }

    private static String coordHash(double lat, double lng) {
        return round4(lat) + "," + round4(lng);
    }

    private static String round4(double v) {
        return BigDecimal.valueOf(v).setScale(4, RoundingMode.HALF_UP).toPlainString();
    }

    private static GeoPlace toGeoPlace(GeocodeCache c) {
        return new GeoPlace(c.getDisplayName(), c.getLat(), c.getLng(),
                c.getStreet(), c.getCity(), c.getState());
    }

    /** Converte uma feature GeoJSON do Photon num GeoPlace; null se faltar coordenada. */
    private static GeoPlace toGeoPlace(Feature f) {
        if (f == null || f.geometry() == null || f.geometry().coordinates() == null
                || f.geometry().coordinates().size() < 2) {
            return null;
        }
        // GeoJSON: [lon, lat]
        double lng = f.geometry().coordinates().get(0);
        double lat = f.geometry().coordinates().get(1);
        Properties p = f.properties() != null ? f.properties() : new Properties(
                null, null, null, null, null, null, null);
        return new GeoPlace(
                formatLabel(p),
                BigDecimal.valueOf(lat).setScale(7, RoundingMode.HALF_UP),
                BigDecimal.valueOf(lng).setScale(7, RoundingMode.HALF_UP),
                p.street(),
                localityOf(p),
                p.state());
    }

    /** Rotulo amigavel: "Rua, numero — Bairro/Cidade", com fallbacks. */
    private static String formatLabel(Properties p) {
        String head;
        if (p.street() != null && !p.street().isBlank()) {
            head = p.housenumber() != null && !p.housenumber().isBlank()
                    ? p.street() + ", " + p.housenumber()
                    : p.street();
        } else {
            head = p.name();
        }
        String locality = localityOf(p);
        if (head == null || head.isBlank()) {
            head = locality;
        } else if (locality != null && !locality.isBlank() && !head.equals(locality)) {
            head = head + " — " + locality;
        }
        return head != null ? head : "Local sem nome";
    }

    private static String localityOf(Properties p) {
        if (p.city() != null && !p.city().isBlank()) {
            return p.city();
        }
        if (p.district() != null && !p.district().isBlank()) {
            return p.district();
        }
        return p.county();
    }

    // ---- Photon GeoJSON ---------------------------------------------------

    record PhotonResponse(List<Feature> features) {}

    record Feature(Geometry geometry, Properties properties) {}

    record Geometry(List<Double> coordinates) {}

    record Properties(String name, String street, String housenumber,
                      String city, String district, String county, String state) {}
}
