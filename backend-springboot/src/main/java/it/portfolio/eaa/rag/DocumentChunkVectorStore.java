package it.portfolio.eaa.rag;

import it.portfolio.eaa.common.ApiProblem;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.EmbeddedDocument;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Implementazione dell'interfaccia VectorStore di Spring AI sopra la tabella document_chunks.
 *
 * PERCHÉ non il PgVectorStore ufficiale: crea e usa una SUA tabella (id, content, metadata, embedding).
 * Lo schema del progetto è condiviso con il backend Laravel (decisione D1) e document_chunks ha colonne
 * proprie (document_id, chunk_index, page_number...). Con il PgVectorStore i PDF caricati da un backend
 * sarebbero invisibili all'altro. Implementando l'interfaccia sulle nostre tabelle il resto del codice
 * usa l'API standard (add / upsert / similaritySearch / SearchRequest) e i due backend condividono l'indice.
 *
 * Metadati di ogni Document: document_id, page_number (base 1), chunk_index, file_name.
 * Filtri supportati in SearchRequest: solo `document_id IN (...)` / `document_id == ...`.
 *
 * La ricerca usa la distanza coseno di pgvector: l'operatore {@code <=>} restituisce la DISTANZA
 * (0 = identico), lo score del contratto è {@code 1 - distanza}. L'ORDER BY sulla distanza permette
 * di usare l'indice HNSW.
 */
@Component
public class DocumentChunkVectorStore implements VectorStore {

    public static final String META_DOCUMENT_ID = "document_id";
    public static final String META_PAGE_NUMBER = "page_number";
    public static final String META_CHUNK_INDEX = "chunk_index";
    public static final String META_FILE_NAME = "file_name";

    private static final String INSERT = """
            INSERT INTO document_chunks (document_id, chunk_index, page_number, content, token_count, embedding)
            VALUES (:documentId, :chunkIndex, :pageNumber, :content, :tokenCount, CAST(:embedding AS vector))
            """;

    private final JdbcClient jdbc;
    private final EmbeddingService embeddings;
    private final TransactionTemplate tx;

    public DocumentChunkVectorStore(JdbcClient jdbc, EmbeddingService embeddings, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.embeddings = embeddings;
        this.tx = tx;
    }

    @Override
    public String getName() {
        return "document-chunks-pgvector";
    }

    /** Calcola gli embedding dei testi e salva i chunk. */
    @Override
    public void add(List<Document> documents) {
        List<float[]> vectors = embeddings.embedAll(documents.stream().map(Document::getText).toList());
        List<EmbeddedDocument> embedded = new ArrayList<>(documents.size());
        for (int i = 0; i < documents.size(); i++) {
            embedded.add(new EmbeddedDocument(documents.get(i), vectors.get(i)));
        }
        upsert(embedded);
    }

    /**
     * Salva chunk con l'embedding GIÀ calcolato. L'ingestione lo usa per fare prima tutte le chiamate lente
     * al modello e poi scrivere tutto in una sola transazione (un documento è completo o non c'è).
     * Si unisce alla transazione del chiamante se ce n'è una, altrimenti ne apre una.
     */
    @Override
    public void upsert(List<EmbeddedDocument> documents) {
        tx.executeWithoutResult(status -> {
            for (EmbeddedDocument embedded : documents) {
                Map<String, Object> meta = embedded.document().getMetadata();
                jdbc.sql(INSERT)
                        .param("documentId", UUID.fromString(String.valueOf(meta.get(META_DOCUMENT_ID))))
                        .param("chunkIndex", ((Number) meta.get(META_CHUNK_INDEX)).intValue())
                        .param("pageNumber", ((Number) meta.get(META_PAGE_NUMBER)).intValue())
                        .param("content", embedded.document().getText())
                        .param("tokenCount", meta.get("token_count") instanceof Number n ? n.intValue() : null)
                        // Rappresentazione testuale "[0.1,0.2,...]": PostgreSQL la converte nel tipo vector.
                        .param("embedding", toVectorLiteral(embedded.embedding()))
                        .update();
            }
        });
    }

    @Override
    public void delete(List<String> idList) {
        for (String id : idList) {
            jdbc.sql("DELETE FROM document_chunks WHERE id = :id").param("id", UUID.fromString(id)).update();
        }
    }

    /** Supporta solo l'uguaglianza su document_id (basta per cancellare i chunk di un documento). */
    @Override
    public void delete(Filter.Expression filterExpression) {
        List<UUID> ids = documentIdsFrom(filterExpression);
        jdbc.sql("DELETE FROM document_chunks WHERE document_id IN (:ids)").param("ids", ids).update();
    }

    /** Embedding della query (SearchRequest.query) e ricerca. */
    @Override
    public List<Document> similaritySearch(SearchRequest request) {
        return similaritySearch(embeddings.embed(request.getQuery()), request);
    }

    /**
     * Ricerca con un vettore già calcolato.
     *
     * @return i chunk più simili, dal più al meno simile, con score >= similarityThreshold
     */
    @Override
    public List<Document> similaritySearch(float[] queryEmbedding, SearchRequest request) {
        String vector = toVectorLiteral(queryEmbedding);

        List<UUID> documentIds = request.hasFilterExpression() ? documentIdsFrom(request.getFilterExpression()) : null;
        String filter = documentIds == null ? "" : " AND c.document_id IN (:ids)";

        String sql = """
                SELECT c.id, c.document_id, d.filename, c.page_number, c.chunk_index, c.content,
                       1 - (c.embedding <=> CAST(:vector AS vector)) AS score
                FROM document_chunks c
                JOIN documents d ON d.id = c.document_id
                WHERE d.status = 'READY'""" + filter + """

                ORDER BY c.embedding <=> CAST(:vector AS vector)
                LIMIT :k
                """;

        // pgvector 0.8: con un filtro (WHERE) l'indice HNSW potrebbe restituire meno di topK risultati.
        // "iterative_scan" lo fa continuare finché ne trova abbastanza. SET LOCAL vale solo in questa transazione.
        List<Document> hits = tx.execute(status -> {
            jdbc.sql("SET LOCAL hnsw.iterative_scan = 'strict_order'").update();

            JdbcClient.StatementSpec spec = jdbc.sql(sql).param("vector", vector).param("k", request.getTopK());
            if (documentIds != null) {
                spec = spec.param("ids", documentIds);
            }
            return spec.query((rs, rowNum) -> {
                Map<String, Object> metadata = new HashMap<>();
                metadata.put(META_DOCUMENT_ID, rs.getObject("document_id", UUID.class).toString());
                metadata.put(META_FILE_NAME, rs.getString("filename"));
                metadata.put(META_PAGE_NUMBER, rs.getInt("page_number"));
                metadata.put(META_CHUNK_INDEX, rs.getInt("chunk_index"));
                return Document.builder()
                        .id(rs.getObject("id", UUID.class).toString())
                        .text(rs.getString("content"))
                        .metadata(metadata)
                        .score(rs.getDouble("score"))
                        .build();
            }).list();
        });

        // La soglia si applica dopo la query: nel WHERE impedirebbe di usare l'indice.
        return hits.stream().filter(d -> d.getScore() != null && d.getScore() >= request.getSimilarityThreshold()).toList();
    }

    /** Estrae gli id documento da `document_id IN [...]` oppure `document_id == x`. */
    private static List<UUID> documentIdsFrom(Filter.Expression expression) {
        boolean keyOk = expression.left() instanceof Filter.Key key && META_DOCUMENT_ID.equals(key.key());
        if (!keyOk || !(expression.right() instanceof Filter.Value value)) {
            throw new UnsupportedOperationException("Il vector store supporta solo filtri su " + META_DOCUMENT_ID + ": " + expression);
        }

        List<UUID> ids = new ArrayList<>();
        switch (expression.type()) {
            case IN -> ((Iterable<?>) value.value()).forEach(v -> ids.add(parseUuid(v)));
            case EQ -> ids.add(parseUuid(value.value()));
            default -> throw new UnsupportedOperationException("Operatore di filtro non supportato: " + expression.type());
        }
        // Lista vuota: nessun documento ammissibile (e `IN ()` non è SQL valido).
        return ids.isEmpty() ? List.of(new UUID(0, 0)) : ids;
    }

    private static UUID parseUuid(Object value) {
        try {
            return UUID.fromString(String.valueOf(value));
        } catch (IllegalArgumentException e) {
            throw ApiProblem.validation(List.of(new ApiProblem.FieldError("documentIds", "Identificativo documento non valido.")));
        }
    }

    /** float[] -> "[0.1,0.2,...]" (formato testuale di pgvector). */
    static String toVectorLiteral(float[] vector) {
        StringBuilder sb = new StringBuilder(vector.length * 8).append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(vector[i]);
        }
        return sb.append(']').toString();
    }
}
