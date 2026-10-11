package com.unimove.domain.user.document;

import com.unimove.shared.security.FieldCrypto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Sem {@code app.storage.documents.dir} o storage nasce "indisponivel": dev e
 * testes sobem sem diretorio e o upload responde 503, mesma estrategia do
 * login social.
 */
@Configuration
@EnableConfigurationProperties(DocumentStorageProperties.class)
class DocumentStorageConfig {

    private static final Logger log = LoggerFactory.getLogger(DocumentStorageConfig.class);

    @Bean
    DocumentStorage documentStorage(DocumentStorageProperties props, FieldCrypto crypto) {
        if (!props.configured()) {
            log.warn("Storage de documentos nao configurado (app.storage.documents.dir): upload de documentos respondera 503.");
            return new UnavailableDocumentStorage();
        }
        return new LocalDocumentStorage(Path.of(props.dir()), crypto);
    }

    static final class UnavailableDocumentStorage implements DocumentStorage {
        @Override
        public void put(String path, byte[] content, String contentType) {
            throw new DocumentStorageUnavailableException();
        }

        @Override
        public Optional<byte[]> read(String path) {
            throw new DocumentStorageUnavailableException();
        }

        @Override
        public void delete(List<String> paths) {
            if (!paths.isEmpty()) {
                throw new DocumentStorageUnavailableException();
            }
        }
    }
}
