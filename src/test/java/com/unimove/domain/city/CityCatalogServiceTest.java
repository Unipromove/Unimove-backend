package com.unimove.domain.city;

import com.unimove.domain.city.dto.AdminCityItem;
import com.unimove.domain.city.dto.CityItem;
import com.unimove.domain.user.UserDirectory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Testes do catálogo de cidades atendidas: a guarda usada pelos caminhos de
 * cadastro/edição de perfil e a administração (abrir, reabrir, desativar).
 */
@ExtendWith(MockitoExtension.class)
class CityCatalogServiceTest {

    private static final UUID ADMIN_ID = UUID.randomUUID();

    @Mock ServedCityRepository repository;
    @Mock UserDirectory userDirectory;
    @Mock com.unimove.domain.audit.AdminAuditLog auditLog;

    CityCatalogService service;

    @BeforeEach
    void setUp() {
        service = new CityCatalogService(repository, userDirectory, auditLog, "America/Sao_Paulo");
    }

    private static ServedCity city(String slug, String displayName, boolean active) {
        ServedCity c = new ServedCity();
        c.setSlug(slug);
        c.setDisplayName(displayName);
        c.setActive(active);
        c.setTimeZone("America/Bahia");
        return c;
    }

    @Test
    @DisplayName("assertServed passa para cidade ativa")
    void assertServedActiveCity() {
        when(repository.existsBySlugAndActiveTrue("remanso-ba")).thenReturn(true);

        service.assertServed("remanso-ba");
    }

    @Test
    @DisplayName("assertServed rejeita cidade inexistente ou desativada")
    void assertServedRejectsUnknownOrInactive() {
        // existsBySlugAndActiveTrue colapsa os dois casos de propósito: para
        // quem se cadastra, "não existe" e "foi fechada" são a mesma resposta.
        when(repository.existsBySlugAndActiveTrue("xique-xique")).thenReturn(false);

        assertThatThrownBy(() -> service.assertServed("xique-xique"))
                .isInstanceOf(CityNotServedException.class);
    }

    @Test
    @DisplayName("listActive devolve slug e nome de exibição das cidades abertas")
    void listActiveMapsCities() {
        when(repository.findAllByActiveTrueOrderByDisplayNameAsc())
                .thenReturn(List.of(city("remanso-ba", "Remanso/BA", true)));

        List<CityItem> items = service.listActive();

        assertThat(items).containsExactly(new CityItem("remanso-ba", "Remanso/BA"));
    }

    @Test
    @DisplayName("listAll inclui desativadas e a contagem de contas em cada cidade")
    void listAllIncludesInactiveAndUserCount() {
        when(repository.findAllByOrderByDisplayNameAsc())
                .thenReturn(List.of(city("casa-nova-ba", "Casa Nova/BA", false)));
        when(userDirectory.countByCidade("casa-nova-ba")).thenReturn(7L);

        List<AdminCityItem> items = service.listAll();

        assertThat(items).containsExactly(
                new AdminCityItem("casa-nova-ba", "Casa Nova/BA", "America/Bahia", false, 7L));
    }

    @Test
    @DisplayName("open normaliza o slug, guarda o nome digitado e sinaliza cidade nova")
    void openCreatesNewCity() {
        when(repository.findById("casa-nova-ba")).thenReturn(Optional.empty());

        CityCatalogService.OpenResult result = service.open(ADMIN_ID, "  Casa Nova/BA  ", null);

        ArgumentCaptor<ServedCity> captor = ArgumentCaptor.forClass(ServedCity.class);
        verify(repository).save(captor.capture());
        ServedCity saved = captor.getValue();
        assertThat(saved.getSlug()).isEqualTo("casa-nova-ba");
        assertThat(saved.getDisplayName()).isEqualTo("Casa Nova/BA");
        assertThat(saved.isActive()).isTrue();
        assertThat(saved.getTimeZone()).isEqualTo("America/Sao_Paulo");

        assertThat(result.created()).isTrue();
        assertThat(result.city().slug()).isEqualTo("casa-nova-ba");
        verify(auditLog).record(ADMIN_ID, com.unimove.domain.audit.AuditAction.CITY_OPENED,
                "CITY", "casa-nova-ba", "nova");
    }

    @Test
    @DisplayName("open sobre cidade desativada reativa a mesma linha em vez de duplicar")
    void openReactivatesExistingCity() {
        ServedCity existing = city("casa-nova-ba", "Casa Nova", false);
        when(repository.findById("casa-nova-ba")).thenReturn(Optional.of(existing));

        CityCatalogService.OpenResult result = service.open(ADMIN_ID, "Casa Nova/BA", null);

        // Sem timeZone no pedido: mantem o fuso ja cadastrado.
        assertThat(existing.getTimeZone()).isEqualTo("America/Bahia");
        // O slug é a identidade e já está gravado em users.cidade/rides.cidade.
        assertThat(existing.isActive()).isTrue();
        assertThat(existing.getDisplayName()).isEqualTo("Casa Nova/BA");
        assertThat(result.created()).isFalse();
        verify(repository).save(existing);
    }

    @Test
    @DisplayName("open rejeita nome que não produz slug algum")
    void openRejectsUnnormalizableName() {
        assertThatThrownBy(() -> service.open(ADMIN_ID, "///", null))
                .isInstanceOf(InvalidCityNameException.class);

        verify(repository, never()).save(any());
        verifyNoInteractions(auditLog);
    }

    @Test
    @DisplayName("open com fuso IANA valido grava o fuso informado")
    void openWithTimeZone() {
        when(repository.findById("cuiaba-mt")).thenReturn(Optional.empty());

        CityCatalogService.OpenResult result = service.open(ADMIN_ID, "Cuiabá/MT", " America/Cuiaba ");

        assertThat(result.city().timeZone()).isEqualTo("America/Cuiaba");
    }

    @Test
    @DisplayName("open rejeita fuso que nao e IANA, sem gravar nada")
    void openRejectsInvalidTimeZone() {
        assertThatThrownBy(() -> service.open(ADMIN_ID, "Casa Nova/BA", "Bahia"))
                .isInstanceOf(InvalidTimeZoneException.class);

        verify(repository, never()).save(any());
        verifyNoInteractions(auditLog);
    }

    @Test
    @DisplayName("zoneOf devolve o fuso da cidade e cai no padrao para cidade desconhecida")
    void zoneOfFallsBackToDefault() {
        when(repository.findById("remanso-ba")).thenReturn(Optional.of(city("remanso-ba", "Remanso/BA", true)));
        when(repository.findById("nao-existe")).thenReturn(Optional.empty());

        assertThat(service.zoneOf("remanso-ba")).isEqualTo(ZoneId.of("America/Bahia"));
        assertThat(service.zoneOf("nao-existe")).isEqualTo(ZoneId.of("America/Sao_Paulo"));
        assertThat(service.defaultZone()).isEqualTo(ZoneId.of("America/Sao_Paulo"));
    }

    @Test
    @DisplayName("deactivate faz soft delete e aceita o nome legível na URL")
    void deactivateSoftDeletes() {
        ServedCity existing = city("casa-nova-ba", "Casa Nova/BA", true);
        when(repository.findById("casa-nova-ba")).thenReturn(Optional.of(existing));

        service.deactivate(ADMIN_ID, "Casa Nova/BA");

        assertThat(existing.isActive()).isFalse();
        verify(auditLog).record(ADMIN_ID, com.unimove.domain.audit.AuditAction.CITY_DEACTIVATED,
                "CITY", "casa-nova-ba", null);
    }

    @Test
    @DisplayName("deactivate de slug inexistente devolve 404")
    void deactivateUnknownCity() {
        when(repository.findById("nao-existe")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deactivate(ADMIN_ID, "nao-existe"))
                .isInstanceOf(CityNotFoundException.class);
    }
}
