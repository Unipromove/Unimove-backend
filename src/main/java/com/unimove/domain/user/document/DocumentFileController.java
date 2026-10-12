package com.unimove.domain.user.document;

import com.unimove.shared.exception.BusinessException;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * Entrega o arquivo de uma URL assinada ({@link DocumentUrlSigner}). Rota
 * publica no Spring Security: a assinatura e a autorizacao. O formato do
 * caminho e o mesmo que o {@link DriverDocumentService} grava.
 */
@RestController
class DocumentFileController {

    private static final Map<String, MediaType> TYPES = Map.of(
            "jpg", MediaType.IMAGE_JPEG,
            "png", MediaType.IMAGE_PNG,
            "pdf", MediaType.APPLICATION_PDF);

    private final DocumentUrlSigner signer;
    private final DocumentStorage storage;

    DocumentFileController(DocumentUrlSigner signer, DocumentStorage storage) {
        this.signer = signer;
        this.storage = storage;
    }

    @GetMapping(DocumentUrlSigner.PATH_PREFIX + "{driverId}/{type:[a-z]+}/{documentId}.{ext:[a-z]+}")
    ResponseEntity<byte[]> file(@PathVariable UUID driverId,
                                @PathVariable String type,
                                @PathVariable UUID documentId,
                                @PathVariable String ext,
                                @RequestParam long exp,
                                @RequestParam String sig) {
        MediaType mediaType = TYPES.get(ext);
        // Remontado so de UUIDs e [a-z]+: nada de ".." chega ao storage.
        String path = driverId + "/" + type + "/" + documentId + "." + ext;
        if (mediaType == null || !signer.verify(path, exp, sig)) {
            throw new InvalidDocumentLinkException();
        }
        byte[] content = storage.read(path).orElseThrow(DriverDocumentNotFoundException::new);
        return ResponseEntity.ok()
                .contentType(mediaType)
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Disposition", ContentDisposition.inline().build().toString())
                .body(content);
    }

    static class InvalidDocumentLinkException extends BusinessException {
        InvalidDocumentLinkException() {
            super(HttpStatus.FORBIDDEN, "Link do documento inválido ou expirado. Gere um novo.");
        }
    }
}
