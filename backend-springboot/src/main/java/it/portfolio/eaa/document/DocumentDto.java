package it.portfolio.eaa.document;

import it.portfolio.eaa.common.Iso;

/**
 * Forma "Document" del contratto (camelCase). Tutti i campi sono sempre presenti: quelli senza valore
 * valgono null, non vengono omessi.
 */
public record DocumentDto(
        String id,
        String filename,
        long sizeBytes,
        Integer pageCount,
        int chunkCount,
        String status,
        String errorMessage,
        String createdAt) {

    public static DocumentDto from(IndexedDocument d) {
        return new DocumentDto(
                d.getId().toString(),
                d.getFilename(),
                d.getSizeBytes(),
                d.getPageCount(),
                d.getChunkCount(),
                d.getStatus().name(),
                d.getErrorMessage(),
                Iso.utc(d.getCreatedAt()));
    }
}
