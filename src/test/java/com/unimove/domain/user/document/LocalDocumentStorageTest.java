package com.unimove.domain.user.document;

import com.unimove.shared.security.FieldCrypto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalDocumentStorageTest {

    static final FieldCrypto CRYPTO =
            new FieldCrypto(Base64.getEncoder().encodeToString(new byte[32]), new MockEnvironment());
    private static final String PATH = "11111111-1111-1111-1111-111111111111/cnh/22222222-2222-2222-2222-222222222222.jpg";
    private static final byte[] CONTENT = "conteudo-da-cnh".getBytes();

    @TempDir Path root;
    private LocalDocumentStorage storage;

    @BeforeEach
    void setUp() {
        storage = new LocalDocumentStorage(root, CRYPTO);
    }

    @Test
    @DisplayName("grava cifrado no disco e le de volta o original")
    void roundTripEncryptedAtRest() throws Exception {
        storage.put(PATH, CONTENT, "image/jpeg");

        byte[] onDisk = Files.readAllBytes(root.resolve(PATH));
        assertThat(new String(onDisk)).doesNotContain("conteudo-da-cnh");
        assertThat(storage.read(PATH)).hasValue(CONTENT);
        // Sem sobra de arquivo temporario.
        try (var files = Files.list(root.resolve(PATH).getParent())) {
            assertThat(files).hasSize(1);
        }
    }

    @Test
    @DisplayName("nunca sobrescreve um envio existente → 503")
    void putExistingFails() {
        storage.put(PATH, CONTENT, "image/jpeg");

        assertThatThrownBy(() -> storage.put(PATH, "outro".getBytes(), "image/jpeg"))
                .isInstanceOf(DocumentStorageUnavailableException.class);
        assertThat(storage.read(PATH)).hasValue(CONTENT);
    }

    @Test
    @DisplayName("arquivo inexistente → vazio")
    void readMissing() {
        assertThat(storage.read(PATH)).isEmpty();
    }

    @Test
    @DisplayName("arquivo corrompido (ou chave trocada) → 503, nunca bytes lixo")
    void readCorruptedFails() throws Exception {
        storage.put(PATH, CONTENT, "image/jpeg");
        Files.write(root.resolve(PATH), "lixo".getBytes());

        assertThatThrownBy(() -> storage.read(PATH)).isInstanceOf(DocumentStorageUnavailableException.class);
    }

    @Test
    @DisplayName("caminho com .. que sai da raiz e recusado")
    void traversalRejected() {
        assertThatThrownBy(() -> storage.read("../fora.jpg")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.put("a/../../fora.jpg", CONTENT, "image/jpeg"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.delete(List.of("."))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("delete apaga e e idempotente")
    void deleteIdempotent() {
        storage.put(PATH, CONTENT, "image/jpeg");

        storage.delete(List.of(PATH, "nao/existe.jpg"));
        storage.delete(List.of(PATH));

        assertThat(storage.read(PATH)).isEmpty();
    }
}
