package com.unimove.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.unimove.domain.user.Role;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Painel de admin no servidor real: estatico com CSP e metricas de periodo vazio. */
@PostgresIntegrationTest
class AdminPanelIT extends IntegrationTestSupport {

    @Test
    void panelIsServedWithCspWithoutRedirect() {
        var response = get("/painel", null);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("/painel/js/main.js");
        assertThat(response.headers().firstValue("Content-Security-Policy")).hasValueSatisfying(
                csp -> assertThat(csp).contains("script-src 'self'"));
    }

    @Test
    void metricsOfEmptyPeriodAreZeroNotServerError() {
        var response = get("/admin/metrics?from=2000-01-01&to=2000-01-02", token(newUser(Role.ADMIN)));
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = json(response);
        assertThat(body.at("/rides/total").asLong()).isZero();
        assertThat(body.at("/rides/completed").asLong()).isZero();
        assertThat(body.at("/payments/cashPending").asLong()).isZero();
        assertThat(body.at("/payments/cashDivergent").asLong()).isZero();
    }

    @Test
    void metricsStayClosedToNonAdmin() {
        assertThat(get("/admin/metrics", token(newUser(Role.PASSAGEIRO))).statusCode()).isEqualTo(403);
        assertThat(get("/admin/metrics", null).statusCode()).isEqualTo(401);
    }
}
