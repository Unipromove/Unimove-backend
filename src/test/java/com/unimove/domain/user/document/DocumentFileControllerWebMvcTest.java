package com.unimove.domain.user.document;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Contrato HTTP da entrega de arquivo por URL assinada. */
@WebMvcTest(controllers = DocumentFileController.class)
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@Import(com.unimove.shared.exception.GlobalExceptionHandler.class)
class DocumentFileControllerWebMvcTest {

    private static final String PATH = "11111111-1111-1111-1111-111111111111/cnh/22222222-2222-2222-2222-222222222222";
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2, 3};

    @Autowired MockMvc mvc;

    @MockBean DocumentUrlSigner signer;
    @MockBean DocumentStorage storage;
    @MockBean com.unimove.shared.security.JwtService jwtService;
    @MockBean com.unimove.shared.security.JwtAuthenticationFilter jwtAuthenticationFilter;
    @MockBean com.unimove.domain.user.DriverService driverService;

    @Test
    @DisplayName("assinatura valida → 200 com o arquivo, tipo pela extensao e sem cache")
    void validSignatureServesFile() throws Exception {
        when(signer.verify(PATH + ".jpg", 1800000000L, "abc")).thenReturn(true);
        when(storage.read(PATH + ".jpg")).thenReturn(Optional.of(JPEG));

        mvc.perform(get("/files/documents/" + PATH + ".jpg").param("exp", "1800000000").param("sig", "abc"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/jpeg"))
                .andExpect(content().bytes(JPEG))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Disposition", "inline"));
    }

    @Test
    @DisplayName("assinatura invalida ou expirada → 403 sem tocar no storage")
    void invalidSignature403() throws Exception {
        when(signer.verify(anyString(), anyLong(), anyString())).thenReturn(false);

        mvc.perform(get("/files/documents/" + PATH + ".jpg").param("exp", "1").param("sig", "x"))
                .andExpect(status().isForbidden());
        verify(storage, never()).read(any());
    }

    @Test
    @DisplayName("extensao fora de jpg/png/pdf → 403 mesmo com assinatura")
    void unknownExtension403() throws Exception {
        when(signer.verify(anyString(), anyLong(), anyString())).thenReturn(true);

        mvc.perform(get("/files/documents/" + PATH + ".html").param("exp", "1").param("sig", "x"))
                .andExpect(status().isForbidden());
        verify(storage, never()).read(any());
    }

    @Test
    @DisplayName("arquivo ja expurgado → 404")
    void missingFile404() throws Exception {
        when(signer.verify(eq(PATH + ".pdf"), anyLong(), anyString())).thenReturn(true);
        when(storage.read(PATH + ".pdf")).thenReturn(Optional.empty());

        mvc.perform(get("/files/documents/" + PATH + ".pdf").param("exp", "1").param("sig", "x"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("storage desligado → 503")
    void storageUnavailable503() throws Exception {
        when(signer.verify(anyString(), anyLong(), anyString())).thenReturn(true);
        when(storage.read(anyString())).thenThrow(new DocumentStorageUnavailableException());

        mvc.perform(get("/files/documents/" + PATH + ".png").param("exp", "1").param("sig", "x"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    @DisplayName("sem exp/sig ou com id que nao e UUID → 400")
    void malformed400() throws Exception {
        mvc.perform(get("/files/documents/" + PATH + ".jpg"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/files/documents/nao-uuid/cnh/22222222-2222-2222-2222-222222222222.jpg")
                        .param("exp", "1").param("sig", "x"))
                .andExpect(status().isBadRequest());
    }
}
