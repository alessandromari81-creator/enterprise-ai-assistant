package it.portfolio.eaa.rag;

import org.springframework.ai.document.Document;

/** Un chunk restituito dalla ricerca vettoriale, con il punteggio di similarità. */
public record RetrievedChunk(
        String chunkId,
        String documentId,
        String filename,
        int page,
        String content,
        /** 1 = identico, 0 = nessuna somiglianza (1 - distanza coseno). */
        double score) {

    /** Converte un Document restituito dal VectorStore (vedi DocumentChunkVectorStore per i metadati). */
    public static RetrievedChunk from(Document document) {
        var meta = document.getMetadata();
        return new RetrievedChunk(
                document.getId(),
                String.valueOf(meta.get(DocumentChunkVectorStore.META_DOCUMENT_ID)),
                String.valueOf(meta.get(DocumentChunkVectorStore.META_FILE_NAME)),
                ((Number) meta.get(DocumentChunkVectorStore.META_PAGE_NUMBER)).intValue(),
                document.getText(),
                Math.round(document.getScore() * 10_000d) / 10_000d);
    }
}
