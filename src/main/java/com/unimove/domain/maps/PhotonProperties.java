package com.unimove.domain.maps;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * @param searchRadiusKm meia-largura da caixa (bbox) em volta do ponto do app na
 *                       busca de endereco: fora dela o Photon nao devolve nada.
 */
@Validated
@ConfigurationProperties(prefix = "app.photon")
public record PhotonProperties(
        @NotBlank String baseUrl,
        @DefaultValue("30") @Positive double searchRadiusKm
) {}
