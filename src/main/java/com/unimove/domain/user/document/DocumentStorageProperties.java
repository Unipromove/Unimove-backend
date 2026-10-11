package com.unimove.domain.user.document;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Documentos de motorista em disco local, cifrados (ver {@link LocalDocumentStorage}).
 *
 * @param dir                  diretorio raiz (volume Docker em prod). Vazio desliga o upload (503).
 * @param publicBaseUrl        prefixo da URL assinada (https://dominio). Vazio = URL relativa.
 * @param signedUrlTtlSeconds  vida da URL assinada entregue ao admin.
 * @param maxFileBytes         teto por arquivo.
 */
@Validated
@ConfigurationProperties(prefix = "app.storage.documents")
public record DocumentStorageProperties(
        @DefaultValue("") String dir,
        @DefaultValue("") String publicBaseUrl,
        @DefaultValue("300") @Positive long signedUrlTtlSeconds,
        @DefaultValue("5242880") @Positive long maxFileBytes
) {
    public boolean configured() {
        return !dir.isBlank();
    }
}
