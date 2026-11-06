package com.unimove.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.unimove.domain.user.Role;
import com.unimove.domain.user.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contra Postgres real: o que so o banco prova. A V37 aplicada, o AT TIME ZONE
 * do agrupamento por dia, a soma do dinheiro sem o troco e o RideResponse com
 * os participantes montado pela API.
 */
@PostgresIntegrationTest
class RideDetailsAndEarningsIT extends IntegrationTestSupport {

    private static final String POSITION = "{\"lat\":-9.6200,\"lng\":-42.0700}";
    private static final String RIDE_WITH_ADDRESSES = """
            {"latOrigem":-9.6300,"lngOrigem":-42.0800,"latDestino":-9.6400,"lngDestino":-42.0900,
             "origemEndereco":"Praça da Matriz","destinoEndereco":"Rodoviária"}
            """;

    @Test
    @DisplayName("enderecos no mural; apos o aceite, passageiro ve motorista/veiculo e motorista ve passageiro; chat com nome")
    void rideCarriesAddressesAndParticipants() {
        User passengerUser = newUser(Role.PASSAGEIRO);
        String passenger = token(passengerUser);
        User driverUser = newOperationalDriver();
        tx.executeWithoutResult(s -> driverRepository.findById(driverUser.getId()).ifPresent(d -> {
            d.setVehicleBrand("Fiat");
            d.setVehicleModel("Uno");
            d.setVehicleColor("Prata");
        }));
        String driver = token(driverUser);

        String rideId = json(send(postJson("/rides", passenger, RIDE_WITH_ADDRESSES))).get("id").asText();
        send(postJson("/rides/" + rideId + "/confirm-payment", passenger, "{\"method\":\"DINHEIRO\"}"));

        JsonNode beforeAccept = json(get("/rides/" + rideId, passenger));
        assertThat(beforeAccept.get("origemEndereco").asText()).isEqualTo("Praça da Matriz");
        assertThat(beforeAccept.get("motoristaNome").isNull()).isTrue();
        assertThat(beforeAccept.get("passageiroNome").isNull()).as("mural nunca expoe o passageiro").isTrue();

        String mural = get("/rides/mural", driver).body();
        assertThat(mural).contains(rideId).contains("Praça da Matriz").contains("Rodoviária")
                .doesNotContain("passageiroNome");

        assertThat(send(postJson("/rides/" + rideId + "/accept", driver, POSITION)).statusCode()).isEqualTo(200);

        JsonNode forPassenger = json(get("/rides/" + rideId, passenger));
        assertThat(forPassenger.get("motoristaNome").asText()).isEqualTo("IT");
        assertThat(forPassenger.get("veiculoMarca").asText()).isEqualTo("Fiat");
        assertThat(forPassenger.get("veiculoModelo").asText()).isEqualTo("Uno");
        assertThat(forPassenger.get("veiculoCor").asText()).isEqualTo("Prata");
        assertThat(forPassenger.get("veiculoPlaca").asText()).isEqualTo("ABC1D23");
        assertThat(forPassenger.get("destinoEndereco").asText()).isEqualTo("Rodoviária");

        JsonNode forDriver = json(get("/rides/" + rideId, driver));
        assertThat(forDriver.get("passageiroNome").asText()).isEqualTo("IT");
        assertThat(forDriver.get("passageiroRatingCount").asInt()).isZero();

        HttpResponse<String> msg = send(postJson("/chat/rides/" + rideId + "/messages", passenger,
                "{\"body\":\"Estou no portao azul\"}"));
        assertThat(json(msg).get("senderName").asText()).isEqualTo("IT");
    }

    @Test
    @DisplayName("ganhos: corrida das 23h em Remanso fica no dia local; dinheiro soma o preco e o troco sai separado")
    void earningsUseCityDayAndExcludeChange() {
        jdbc.update("UPDATE served_cities SET time_zone = 'America/Bahia' WHERE slug = ?", city);
        String passenger = token(newUser(Role.PASSAGEIRO));
        String driver = token(newOperationalDriver());
        String rideId = rideInMural(passenger);
        send(postJson("/rides/" + rideId + "/accept", driver, POSITION));
        send(postJson("/rides/" + rideId + "/start", driver, "{}"));
        send(postJson("/rides/" + rideId + "/complete", driver, "{}"));

        BigDecimal price = json(get("/rides/" + rideId, passenger)).get("preco").decimalValue();
        BigDecimal handed = price.add(new BigDecimal("10.00"));
        assertThat(send(postJson("/rides/" + rideId + "/cash-payment", driver,
                "{\"receivedAmount\":" + handed + "}")).statusCode()).isEqualTo(200);

        // 05/11 23:30 em Remanso = 06/11 02:30 UTC.
        jdbc.update("UPDATE rides SET completed_at = ? WHERE id = ?",
                Timestamp.from(Instant.parse("2026-11-06T02:30:00Z")), UUID.fromString(rideId));

        JsonNode day5 = json(get("/drivers/me/earnings?from=2026-11-05&to=2026-11-05", driver));
        assertThat(day5.get("totalRides").asLong()).isEqualTo(1);
        assertThat(day5.get("byDay").get(0).get("day").asText()).isEqualTo("2026-11-05");
        assertThat(day5.get("payments").get("cashReceived").decimalValue()).isEqualByComparingTo(price);
        assertThat(day5.get("payments").get("cashChangeReturned").decimalValue()).isEqualByComparingTo("10.00");

        JsonNode day6 = json(get("/drivers/me/earnings?from=2026-11-06&to=2026-11-06", driver));
        assertThat(day6.get("totalRides").asLong()).as("em UTC ela caia no dia 06").isZero();

        String admin = token(newUser(Role.ADMIN));
        assertThat(get("/admin/metrics?from=2026-11-01&to=2026-11-06", admin).statusCode())
                .as("serie diaria do painel com AT TIME ZONE").isEqualTo(200);
    }

    @Test
    @DisplayName("POST /admin/cities: fuso IANA valido e gravado; invalido → 400")
    void openCityWithTimeZone() {
        String admin = token(newUser(Role.ADMIN));
        String name = "Cidade " + UUID.randomUUID().toString().substring(0, 6) + "/MT";

        HttpResponse<String> ok = send(postJson("/admin/cities", admin,
                "{\"cidade\":\"" + name + "\",\"timeZone\":\"America/Cuiaba\"}"));
        assertThat(ok.statusCode()).isEqualTo(201);
        assertThat(json(ok).get("timeZone").asText()).isEqualTo("America/Cuiaba");

        assertThat(send(postJson("/admin/cities", admin,
                "{\"cidade\":\"" + name + "\",\"timeZone\":\"Mato Grosso\"}")).statusCode()).isEqualTo(400);
    }
}
