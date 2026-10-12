package com.unimove.domain.user.document;

import java.util.List;
import java.util.Optional;

/**
 * Armazenamento PRIVADO dos arquivos de documento. Nenhum arquivo tem URL
 * publica: a leitura para o admin passa por URL assinada de vida curta
 * ({@link DocumentUrlSigner}), emitida so para ele e auditada.
 */
public interface DocumentStorage {

    /** Nunca sobrescreve: caminho ja ocupado e erro. */
    void put(String path, byte[] content, String contentType);

    /** Conteudo original do arquivo; vazio se ele nao existe. */
    Optional<byte[]> read(String path);

    /** Idempotente: caminho inexistente nao e erro. */
    void delete(List<String> paths);
}
