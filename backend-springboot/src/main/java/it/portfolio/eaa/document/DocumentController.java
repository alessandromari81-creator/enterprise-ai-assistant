package it.portfolio.eaa.document;

import it.portfolio.eaa.common.ApiProblem;
import it.portfolio.eaa.config.EaaProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Endpoint /api/documents. Il controller resta sottile: la logica è in DocumentIngestionService.
 *
 * Il contratto distingue più errori con status diversi (stessi controlli del backend Laravel):
 *   campo "file" assente ........... 400  (ApiExceptionHandler)
 *   file oltre 20 MB ............... 413
 *   non è un PDF ................... 415
 */
@RestController
@RequestMapping("/api/documents")
class DocumentController {

    /** Ogni PDF inizia con "%PDF-" (la specifica ammette qualche byte di intestazione prima). */
    private static final String PDF_MAGIC = "%PDF-";
    private static final int PDF_MAGIC_SEARCH_WINDOW = 1024;

    private final DocumentIngestionService ingestion;
    private final IndexedDocumentRepository documents;
    private final EaaProperties properties;

    DocumentController(DocumentIngestionService ingestion, IndexedDocumentRepository documents, EaaProperties properties) {
        this.ingestion = ingestion;
        this.documents = documents;
        this.properties = properties;
    }

    /** POST /api/documents -> 201 con il documento indicizzato (READY). */
    @PostMapping
    ResponseEntity<DocumentDto> upload(@RequestPart("file") MultipartFile file) {
        if (file.isEmpty()) {
            throw ApiProblem.validation(List.of(new ApiProblem.FieldError("file", "Il campo file è obbligatorio.")));
        }
        if (file.getSize() > properties.upload().maxBytes()) {
            throw ApiProblem.fileTooLarge();
        }

        byte[] content = read(file);

        // Si guarda il CONTENUTO (magic bytes), non l'estensione né il Content-Type dichiarato dal client:
        // rinominare un eseguibile in .pdf non basta.
        if (!looksLikePdf(content)) {
            throw ApiProblem.unsupportedMediaType();
        }

        IndexedDocument document = ingestion.ingest(file.getOriginalFilename(), content);
        return ResponseEntity.status(HttpStatus.CREATED).body(DocumentDto.from(document));
    }

    /** GET /api/documents -> { items: [...] }, il più recente per primo. */
    @GetMapping
    Map<String, List<DocumentDto>> list() {
        return Map.of("items", documents.findAllByOrderByCreatedAtDescIdDesc().stream().map(DocumentDto::from).toList());
    }

    private static byte[] read(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static boolean looksLikePdf(byte[] content) {
        int window = Math.min(content.length, PDF_MAGIC_SEARCH_WINDOW);
        return new String(content, 0, window, StandardCharsets.ISO_8859_1).contains(PDF_MAGIC);
    }
}
