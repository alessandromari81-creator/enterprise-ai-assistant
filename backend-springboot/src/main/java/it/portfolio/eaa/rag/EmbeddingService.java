package it.portfolio.eaa.rag;

import it.portfolio.eaa.common.ApiProblem;
import it.portfolio.eaa.config.EaaProperties;
import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

/**
 * Trasforma testo in vettori (embedding) tramite l'EmbeddingModel di Spring AI.
 *
 * Il modello concreto (Ollama o OpenAI) lo sceglie spring.ai.model.embedding in application.yml:
 * questo codice non cambia. Qui si aggiungono tre cose che Spring AI non fa:
 *  - invio a lotti (meno chiamate HTTP, richieste di dimensione contenuta);
 *  - traduzione degli errori del provider in un 502 con il formato del contratto;
 *  - controllo che i vettori abbiano la dimensione della colonna VECTOR(n) del database.
 */
@Service
public class EmbeddingService {

    private final EmbeddingModel model;
    private final EaaProperties properties;

    public EmbeddingService(EmbeddingModel model, EaaProperties properties) {
        this.model = model;
        this.properties = properties;
    }

    /**
     * @return un vettore per testo, nello stesso ordine
     * @throws ApiProblem 502 se il provider non risponde
     */
    public List<float[]> embedAll(List<String> texts) {
        List<float[]> vectors = new ArrayList<>(texts.size());
        int batchSize = properties.ai().embeddingBatchSize();

        for (int from = 0; from < texts.size(); from += batchSize) {
            vectors.addAll(embedBatch(texts.subList(from, Math.min(from + batchSize, texts.size()))));
        }
        return vectors;
    }

    public float[] embed(String text) {
        return embedAll(List.of(text)).get(0);
    }

    private List<float[]> embedBatch(List<String> batch) {
        List<float[]> result;
        try {
            result = model.embed(batch);
        } catch (RuntimeException e) {
            // Confine con un sistema esterno: qualunque errore del provider (rete, HTTP 5xx, risposta
            // non valida) è per il client un "servizio non raggiungibile". Il dettaglio resta nel log.
            throw ApiProblem.llmUnavailable("Il servizio di embedding non è raggiungibile o ha risposto con un errore.", e);
        }

        if (result.size() != batch.size()) {
            throw ApiProblem.llmUnavailable("Il servizio di embedding ha restituito un numero di vettori inatteso.", null);
        }

        int expected = properties.ai().embeddingDimensions();
        for (float[] vector : result) {
            // Errore di CONFIGURAZIONE (modello diverso dalla colonna del DB), non del provider:
            // si segnala chiaramente invece di far fallire l'INSERT con un messaggio criptico.
            if (vector.length != expected) {
                throw new IllegalStateException(String.format(
                        "Il modello di embedding restituisce vettori da %d dimensioni, ma il database ne attende %d. "
                                + "Allinea il modello (spring.ai.*.embedding.options) e eaa.ai.embedding-dimensions allo schema.",
                        vector.length, expected));
            }
        }
        return result;
    }
}
