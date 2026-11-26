package it.portfolio.eaa.document;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import it.portfolio.eaa.common.ApiProblem;
import it.portfolio.eaa.config.EaaProperties;
import it.portfolio.eaa.rag.DocumentChunkVectorStore;
import it.portfolio.eaa.rag.EmbeddingService;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.EmbeddedDocument;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Pipeline di ingestione di un PDF:
 *
 * <pre>
 *   hash SHA-256 -> (duplicato?) -> estrazione testo per pagina (PagePdfDocumentReader)
 *   -> chunking (TokenTextSplitter) -> embedding -> salvataggio atomico (VectorStore) -> documento READY
 * </pre>
 *
 * Il lavoro è sincrono (decisione D7): la richiesta HTTP risponde solo a indicizzazione finita.
 * Stesse regole del backend Laravel sui duplicati e sui fallimenti (decisioni D15 e D16).
 */
@Service
public class DocumentIngestionService {

    private static final Logger log = LoggerFactory.getLogger(DocumentIngestionService.class);

    private final IndexedDocumentRepository documents;
    private final PdfTextExtractor extractor;
    private final EmbeddingService embeddings;
    private final DocumentChunkVectorStore vectorStore;
    private final TransactionTemplate tx;
    private final TokenTextSplitter splitter;
    private final Encoding tokenEncoding = Encodings.newDefaultEncodingRegistry().getEncoding(EncodingType.CL100K_BASE);

    public DocumentIngestionService(
            IndexedDocumentRepository documents,
            PdfTextExtractor extractor,
            EmbeddingService embeddings,
            DocumentChunkVectorStore vectorStore,
            TransactionTemplate tx,
            EaaProperties properties) {
        this.documents = documents;
        this.extractor = extractor;
        this.embeddings = embeddings;
        this.vectorStore = vectorStore;
        this.tx = tx;

        // Chunk da ~500 token con 50 di sovrapposizione (come Laravel). La sovrapposizione evita di perdere
        // il senso di una regola che cade a cavallo di due chunk.
        this.splitter = TokenTextSplitter.builder()
                .withChunkSize(properties.rag().chunkSizeTokens())
                .withChunkOverlap(properties.rag().chunkOverlapTokens())
                .withMinChunkSizeChars(350)
                .withMinChunkLengthToEmbed(5)
                .withMaxNumChunks(10_000)
                .withKeepSeparator(true)
                .build();
    }

    /**
     * @throws ApiProblem 409 duplicato, 422 PDF illeggibile, 502 provider di embedding non raggiungibile
     */
    public IndexedDocument ingest(String filename, byte[] pdfBytes) {
        String hash = sha256(pdfBytes);
        IndexedDocument document = createProcessingDocument(filename, pdfBytes.length, hash);

        try {
            PdfTextExtractor.Extracted extracted = extractor.extract(pdfBytes);
            List<Document> chunks = splitIntoChunks(document, extracted.pages());

            if (chunks.isEmpty()) {
                throw ApiProblem.unreadablePdf("Nel PDF non c'è testo estraibile. Se è una scansione serve prima un OCR.");
            }

            // Prima TUTTI gli embedding (operazione lenta, può fallire)...
            List<float[]> vectors = embeddings.embedAll(chunks.stream().map(Document::getText).toList());
            List<EmbeddedDocument> embedded = new ArrayList<>(chunks.size());
            for (int i = 0; i < chunks.size(); i++) {
                embedded.add(new EmbeddedDocument(chunks.get(i), vectors.get(i)));
            }

            // ...poi si scrive tutto in UNA transazione: o il documento è completo, o non c'è nulla (D16).
            tx.executeWithoutResult(status -> {
                vectorStore.upsert(embedded);

                IndexedDocument saved = documents.findById(document.getId()).orElseThrow();
                saved.setPageCount(extracted.pageCount());
                saved.setChunkCount(chunks.size());
                saved.setStatus(DocumentStatus.READY);
                saved.setErrorMessage(null);
                documents.save(saved);
            });
        } catch (RuntimeException e) {
            // Il documento resta visibile come FAILED con il motivo: la UI lo mostra e l'utente può
            // ricaricare lo stesso file (vedi createProcessingDocument).
            markFailed(document, e);
            throw e;
        }

        return documents.findById(document.getId()).orElseThrow();
    }

    /**
     * Registra il documento in stato PROCESSING.
     *
     * Regola sui duplicati (stesso SHA-256):
     *  - esiste già READY o PROCESSING  -> 409;
     *  - esiste ma FAILED               -> si elimina il vecchio record e si riprova.
     *
     * La UNIQUE su content_hash protegge anche dalle richieste concorrenti: se due upload identici
     * arrivano insieme, il secondo fallisce sull'INSERT e viene trattato come duplicato.
     */
    private IndexedDocument createProcessingDocument(String filename, long sizeBytes, String hash) {
        documents.findByContentHash(hash).ifPresent(existing -> {
            if (existing.getStatus() != DocumentStatus.FAILED) {
                throw ApiProblem.duplicateDocument("Questo PDF è già stato caricato come \"" + existing.getFilename() + "\".");
            }
            documents.delete(existing); // i chunk (se ce ne fossero) spariscono con ON DELETE CASCADE
            documents.flush();
        });

        IndexedDocument fresh = new IndexedDocument();
        fresh.setFilename(safeFilename(filename));
        fresh.setSizeBytes(sizeBytes);
        fresh.setContentHash(hash);
        fresh.setStatus(DocumentStatus.PROCESSING);

        try {
            return documents.saveAndFlush(fresh);
        } catch (DataIntegrityViolationException e) {
            throw ApiProblem.duplicateDocument("Questo PDF è già stato caricato.");
        }
    }

    /**
     * Divide ogni pagina in chunk con TokenTextSplitter. Il splitter lavora su ogni Document (pagina) e i chunk
     * ereditano i suoi metadata: un chunk NON attraversa mai due pagine, quindi ha UN numero di pagina certo.
     */
    private List<Document> splitIntoChunks(IndexedDocument document, List<PdfTextExtractor.Page> pages) {
        List<Document> pageDocuments = pages.stream()
                .map(page -> Document.builder()
                        .text(page.text())
                        .metadata(DocumentChunkVectorStore.META_PAGE_NUMBER, page.number())
                        .build())
                .toList();

        List<Document> split = splitter.apply(pageDocuments);

        List<Document> chunks = new ArrayList<>(split.size());
        for (Document piece : split) {
            String text = PdfTextExtractor.normalize(piece.getText());
            if (text.isEmpty()) {
                continue;
            }
            Map<String, Object> metadata = new HashMap<>();
            metadata.put(DocumentChunkVectorStore.META_DOCUMENT_ID, document.getId().toString());
            metadata.put(DocumentChunkVectorStore.META_PAGE_NUMBER, piece.getMetadata().get(DocumentChunkVectorStore.META_PAGE_NUMBER));
            metadata.put(DocumentChunkVectorStore.META_CHUNK_INDEX, chunks.size());
            metadata.put("token_count", tokenEncoding.countTokens(text));
            chunks.add(Document.builder().text(text).metadata(metadata).build());
        }
        return chunks;
    }

    private void markFailed(IndexedDocument document, RuntimeException cause) {
        String message = cause instanceof ApiProblem problem ? problem.detail() : "Errore interno durante l'indicizzazione.";
        try {
            documents.findById(document.getId()).ifPresent(failed -> {
                failed.setStatus(DocumentStatus.FAILED);
                failed.setErrorMessage(message);
                documents.save(failed);
            });
        } catch (RuntimeException e) {
            // Non si deve mai nascondere l'errore originale con uno secondario.
            log.warn("Impossibile marcare FAILED il documento {}", document.getId(), e);
        }
    }

    /** Solo il nome del file (niente percorsi), massimo 255 caratteri. */
    static String safeFilename(String original) {
        String name = original == null ? "" : original.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).strip();
        if (name.isEmpty()) {
            name = "documento.pdf";
        }
        return name.length() > 255 ? name.substring(0, 255) : name;
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // SHA-256 è garantito dalla piattaforma Java
        }
    }
}
