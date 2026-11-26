package it.portfolio.eaa.chat;

import it.portfolio.eaa.rag.RetrievedChunk;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Decide quali chunk elencare in `sources[]` della risposta. Stesse regole del backend Laravel (decisione D13):
 *  1. se la risposta cita "[file, p. N]", sono fonti i chunk che corrispondono a quelle citazioni;
 *  2. se è il rifiuto ("non ho trovato...") o è stata eseguita un'azione -> nessuna fonte;
 *  3. altrimenti (risposta basata sui documenti ma senza citazione leggibile, tipico dei modelli piccoli) si
 *     elencano i chunk recuperati: meglio mostrare le fonti consultate che nessuna.
 */
@Component
class SourceResolver {

    List<RetrievedChunk> resolve(String answer, List<RetrievedChunk> retrieved, boolean actionExecuted) {
        if (retrieved.isEmpty() || actionExecuted || answer.contains(PromptBuilder.REFUSAL)) {
            return List.of();
        }
        List<RetrievedChunk> cited = retrieved.stream().filter(chunk -> isCited(answer, chunk)).toList();
        return cited.isEmpty() ? retrieved : cited;
    }

    /** Riconosce "policy.pdf, p. 4", "policy.pdf, pag. 4", "policy.pdf p.4", "policy.pdf, pagina 4"... */
    private static boolean isCited(String answer, RetrievedChunk chunk) {
        Pattern pattern = Pattern.compile(
                Pattern.quote(chunk.filename()) + "\\s*,?\\s*(?:pagina|pag\\.?|p\\.?)\\s*" + chunk.page() + "(?!\\d)",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        return pattern.matcher(answer).find();
    }
}
