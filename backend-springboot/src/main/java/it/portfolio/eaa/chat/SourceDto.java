package it.portfolio.eaa.chat;

/** Forma "Source" del contratto: un frammento di documento usato dall'IA per rispondere (citazione). */
public record SourceDto(String chunkId, String documentId, String filename, int page, double score, String snippet) {}
