package it.portfolio.eaa.config;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Parametri applicativi (prefisso "eaa" in application.yml). Specchio di config/eaa.php di Laravel:
 * gli stessi valori nei due backend rendono le risposte confrontabili (decisione D8).
 *
 * Le impostazioni dei modelli (URL di Ollama, nome del modello, chiave OpenAI...) NON sono qui:
 * sono proprietà standard di Spring AI (spring.ai.*).
 */
@ConfigurationProperties(prefix = "eaa")
public record EaaProperties(
        @DefaultValue Ai ai,
        @DefaultValue Rag rag,
        @DefaultValue Upload upload,
        @DefaultValue Reimbursement reimbursement,
        @DefaultValue Cors cors) {

    public record Ai(
            /** Dimensione dei vettori: deve coincidere con VECTOR(n) nello schema SQL. */
            @DefaultValue("768") int embeddingDimensions,
            /** Quanti testi mandare al provider in una sola chiamata di embedding. */
            @DefaultValue("32") int embeddingBatchSize) {}

    public record Rag(
            @DefaultValue("500") int chunkSizeTokens,
            @DefaultValue("50") int chunkOverlapTokens,
            /** Quanti chunk recuperare. */
            @DefaultValue("5") int topK,
            /** Similarità coseno minima (0..1). */
            @DefaultValue("0.62") double minScore,
            /** Quanti messaggi precedenti rimandare al modello. */
            @DefaultValue("10") int historyMessages,
            /** Lunghezza massima dello snippet in sources[]. */
            @DefaultValue("300") int snippetLength) {}

    public record Upload(@DefaultValue("20971520") long maxBytes) {}

    public record Reimbursement(
            @DefaultValue("10000") BigDecimal maxAmount,
            @DefaultValue("EUR") String currency,
            @DefaultValue("demo-user") String defaultUser) {}

    public record Cors(@DefaultValue("http://localhost:4200") List<String> allowedOrigins) {}
}
