package it.portfolio.eaa.chat;

import it.portfolio.eaa.rag.RetrievedChunk;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Costruisce il system prompt del RAG: regole anti-allucinazione + estratti recuperati.
 *
 * Il testo delle regole sta in resources/prompts/rag-system.txt ed è IDENTICO a quello del backend Laravel
 * (un test lo verifica): le risposte dei due stack devono essere confrontabili.
 */
@Component
class PromptBuilder {

    /**
     * Frase di rifiuto: è anche il segnale che usa SourceResolver per capire che la risposta NON si basa
     * sui documenti (e quindi sources deve essere vuoto).
     */
    static final String REFUSAL = "Non ho trovato questa informazione nei documenti caricati.";

    private final String template;

    PromptBuilder() {
        try {
            this.template = new ClassPathResource("prompts/rag-system.txt").getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Prompt di sistema non trovato", e);
        }
    }

    String system(List<RetrievedChunk> chunks) {
        return template.replace("{{REFUSAL}}", REFUSAL).replace("{{CONTEXT}}", context(chunks));
    }

    /** Ogni estratto ha un'intestazione con file e pagina: il modello la copia nella citazione. */
    private static String context(List<RetrievedChunk> chunks) {
        if (chunks.isEmpty()) {
            return "(nessun estratto pertinente trovato)";
        }
        List<String> blocks = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            RetrievedChunk c = chunks.get(i);
            blocks.add("[ESTRATTO %d] file: %s, pagina: %d\n%s".formatted(i + 1, c.filename(), c.page(), c.content()));
        }
        return String.join("\n---\n", blocks);
    }
}
