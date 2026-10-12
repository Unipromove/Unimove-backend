package com.unimove.domain.user.document;

import com.unimove.shared.security.FieldCrypto;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * URL assinada de vida curta para o admin abrir um documento:
 * {@code {base}/files/documents/{path}?exp={epoch}&sig={hmac}}. Quem tem a URL
 * le o arquivo ate {@code exp}, sem token — por isso ela so e emitida ao admin,
 * com auditoria, e dura minutos.
 *
 * <p>O HMAC usa a chave de fingerprint do {@link FieldCrypto} com prefixo de
 * dominio proprio, entao nenhuma assinatura de URL coincide com o fingerprint
 * de um CPF (e vice-versa).
 */
@Component
class DocumentUrlSigner {

    static final String PATH_PREFIX = "/files/documents/";
    private static final String DOMAIN = "document-url:";

    private final FieldCrypto crypto;
    private final DocumentStorageProperties props;
    private final Clock clock;

    DocumentUrlSigner(FieldCrypto crypto, DocumentStorageProperties props, Clock clock) {
        this.crypto = crypto;
        this.props = props;
        this.clock = clock;
    }

    record SignedUrl(String url, Instant expiresAt) {}

    SignedUrl sign(String path, Duration ttl) {
        Instant expiresAt = clock.instant().plus(ttl);
        long exp = expiresAt.getEpochSecond();
        String base = props.publicBaseUrl().replaceAll("/+$", "");
        return new SignedUrl(base + PATH_PREFIX + path + "?exp=" + exp + "&sig=" + signature(path, exp),
                Instant.ofEpochSecond(exp));
    }

    /** Assinatura confere e ainda nao expirou. Comparacao em tempo constante. */
    boolean verify(String path, long exp, String sig) {
        if (sig == null || clock.instant().getEpochSecond() > exp) {
            return false;
        }
        return MessageDigest.isEqual(signature(path, exp).getBytes(StandardCharsets.US_ASCII),
                sig.getBytes(StandardCharsets.US_ASCII));
    }

    private String signature(String path, long exp) {
        return crypto.fingerprint(DOMAIN + path + ":" + exp);
    }
}
