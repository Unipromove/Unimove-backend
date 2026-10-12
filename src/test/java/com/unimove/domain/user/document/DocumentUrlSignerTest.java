package com.unimove.domain.user.document;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentUrlSignerTest {

    private static final Instant NOW = Instant.parse("2026-10-11T12:00:00Z");
    private static final String PATH = "11111111-1111-1111-1111-111111111111/cnh/22222222-2222-2222-2222-222222222222.jpg";

    private static DocumentUrlSigner signer(Instant now, String baseUrl) {
        return new DocumentUrlSigner(LocalDocumentStorageTest.CRYPTO,
                new DocumentStorageProperties("/data", baseUrl, 300, 1024), Clock.fixed(now, ZoneOffset.UTC));
    }

    private static String param(String url, String name) {
        for (String kv : URI.create(url).getQuery().split("&")) {
            if (kv.startsWith(name + "=")) {
                return kv.substring(name.length() + 1);
            }
        }
        throw new AssertionError("sem " + name);
    }

    @Test
    @DisplayName("assina com base, caminho, exp e sig; a propria assinatura confere")
    void signAndVerify() {
        DocumentUrlSigner.SignedUrl signed = signer(NOW, "https://api.x/").sign(PATH, Duration.ofSeconds(300));

        assertThat(signed.url()).startsWith("https://api.x/files/documents/" + PATH + "?exp=");
        assertThat(signed.expiresAt()).isEqualTo(NOW.plusSeconds(300));
        long exp = Long.parseLong(param(signed.url(), "exp"));
        assertThat(signer(NOW, "").verify(PATH, exp, param(signed.url(), "sig"))).isTrue();
    }

    @Test
    @DisplayName("sem base configurada a URL e relativa")
    void relativeWithoutBase() {
        assertThat(signer(NOW, "").sign(PATH, Duration.ofSeconds(60)).url()).startsWith("/files/documents/");
    }

    @Test
    @DisplayName("expirada, caminho/exp/sig adulterados ou sig ausente → recusada")
    void rejectsTamperedOrExpired() {
        String url = signer(NOW, "").sign(PATH, Duration.ofSeconds(300)).url();
        long exp = Long.parseLong(param(url, "exp"));
        String sig = param(url, "sig");

        assertThat(signer(NOW.plusSeconds(301), "").verify(PATH, exp, sig)).isFalse();
        assertThat(signer(NOW, "").verify(PATH.replace("cnh", "selfie"), exp, sig)).isFalse();
        assertThat(signer(NOW, "").verify(PATH, exp + 3600, sig)).isFalse();
        assertThat(signer(NOW, "").verify(PATH, exp, sig.substring(1) + "0")).isFalse();
        assertThat(signer(NOW, "").verify(PATH, exp, null)).isFalse();
    }

    @Test
    @DisplayName("assinatura nao coincide com o fingerprint puro do mesmo texto (dominio separado)")
    void domainSeparated() {
        String url = signer(NOW, "").sign(PATH, Duration.ofSeconds(300)).url();
        String exp = param(url, "exp");

        assertThat(param(url, "sig")).isNotEqualTo(LocalDocumentStorageTest.CRYPTO.fingerprint(PATH + ":" + exp));
    }
}
