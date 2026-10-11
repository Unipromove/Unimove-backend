package com.unimove.domain.user.document;

import com.unimove.shared.security.FieldCrypto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;

/**
 * Adapter em disco local (volume Docker na VPS). Cada arquivo e gravado
 * cifrado com AES-256-GCM pela mesma chave do CPF/CNH: quem copia o volume ou
 * um backup sem a DATA_ENCRYPTION_KEY nao le CNH nem selfie.
 */
class LocalDocumentStorage implements DocumentStorage {

    private static final Logger log = LoggerFactory.getLogger(LocalDocumentStorage.class);

    private final Path root;
    private final FieldCrypto crypto;

    LocalDocumentStorage(Path root, FieldCrypto crypto) {
        try {
            this.root = Files.createDirectories(root).toRealPath();
        } catch (IOException e) {
            throw new IllegalStateException("Diretorio de documentos inacessivel: " + root, e);
        }
        this.crypto = crypto;
    }

    @Override
    public void put(String path, byte[] content, String contentType) {
        Path target = resolve(path);
        try {
            if (Files.exists(target)) {
                throw new IllegalStateException("arquivo ja existe: " + path);
            }
            Files.createDirectories(target.getParent());
            // tmp + move atomico: um upload interrompido nunca deixa arquivo pela metade.
            Path tmp = Files.createTempFile(target.getParent(), ".upload-", ".tmp");
            try {
                Files.write(tmp, crypto.encryptBytes(content));
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException | IllegalStateException e) {
            log.error("Falha ao gravar documento no storage ({}): {}", path, e.toString());
            throw new DocumentStorageUnavailableException();
        }
    }

    @Override
    public Optional<byte[]> read(String path) {
        Path file = resolve(path);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(crypto.decryptBytes(Files.readAllBytes(file)));
        } catch (IOException | IllegalStateException e) {
            log.error("Falha ao ler documento do storage ({}): {}", path, e.toString());
            throw new DocumentStorageUnavailableException();
        }
    }

    @Override
    public void delete(List<String> paths) {
        for (String path : paths) {
            try {
                Files.deleteIfExists(resolve(path));
            } catch (IOException e) {
                log.error("Falha ao apagar documento do storage ({}): {}", path, e.toString());
                throw new DocumentStorageUnavailableException();
            }
        }
    }

    /** Defesa em profundidade: nenhum caminho sai da raiz, nem com "..". */
    private Path resolve(String path) {
        Path resolved = root.resolve(path).normalize();
        if (!resolved.startsWith(root) || resolved.equals(root)) {
            throw new IllegalArgumentException("Caminho fora do storage de documentos: " + path);
        }
        return resolved;
    }
}
