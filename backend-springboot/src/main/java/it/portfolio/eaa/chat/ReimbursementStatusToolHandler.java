package it.portfolio.eaa.chat;

import it.portfolio.eaa.reimbursement.ReimbursementRequest;
import it.portfolio.eaa.reimbursement.ReimbursementService;
import it.portfolio.eaa.reimbursement.ReimbursementStatus;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.ai.chat.model.ToolContext;

/**
 * Logica del tool "updateReimbursementStatus": aggiorna lo stato di un rimborso già esistente (Approvato,
 * Rifiutato, Eseguito) su ordine esplicito dell'utente ("Segna come eseguito il rimborso #2", "Approva il
 * rimborso della cena").
 *
 * Stesso principio dei guardrail di createReimbursementRequest: il modello PROPONE, il codice DECIDE.
 *  1. Intento: il messaggio deve ORDINARE l'aggiornamento con un verbo esplicito. Una domanda non si esegue mai.
 *  2. Stato: lo decide il TESTO DELL'UTENTE (requestedStatus), non il modello.
 *  3. Riferimento: il numero (#2) o la parola della descrizione devono comparire nel messaggio dell'utente.
 * Poi ReimbursementService.updateStatus applica le regole di business e il CHECK del database fa da ultima difesa.
 */
class ReimbursementStatusToolHandler {

    private static final Pattern DIGITS = Pattern.compile("\\d+");

    private final ReimbursementService service;

    ReimbursementStatusToolHandler(ReimbursementService service) {
        this.service = service;
    }

    @SuppressWarnings("unchecked")
    String execute(ReimbursementStatusToolArgs args, ToolContext toolContext) {
        var context = toolContext.getContext();
        String currentMessage = (String) context.get(ReimbursementToolHandler.CURRENT_MESSAGE);
        List<ActionDto> actions = (List<ActionDto>) context.get(ReimbursementToolHandler.ACTIONS);

        // Barriera 1 — intento.
        if (!ReimbursementIntent.isStatusUpdateRequest(currentMessage)) {
            return "ERRORE: l'ultimo messaggio dell'utente non ordina di aggiornare un rimborso (è una domanda). "
                    + "Nessun rimborso è stato modificato. Rispondi alla domanda senza strumenti.";
        }

        // Barriera 2 — lo stato lo decide il testo dell'utente.
        String status = ReimbursementIntent.requestedStatus(currentMessage);
        if (status == null) {
            return "ERRORE: dal messaggio non si capisce quale stato impostare. Nessun rimborso è stato modificato. "
                    + "Chiedi all'utente se vuole approvare, rifiutare o segnare come eseguito il rimborso.";
        }

        // Barriera 3 — il riferimento al rimborso deve averlo scritto l'utente.
        Long number = parseNumber(args.number());
        String keyword = args.keyword() == null ? "" : args.keyword().strip();

        if (number != null && !numberWrittenByUser(number, currentMessage)) {
            return "ERRORE: il numero indicato non compare nel messaggio dell'utente. Nessun rimborso è stato modificato. "
                    + "Chiedi all'utente il numero del rimborso (quello dopo il #).";
        }
        if (number == null && !keyword.isEmpty() && !currentMessage.toLowerCase().contains(keyword.toLowerCase())) {
            return "ERRORE: la parola indicata non compare nel messaggio dell'utente. Nessun rimborso è stato modificato. "
                    + "Chiedi all'utente il numero del rimborso (quello dopo il #).";
        }
        if (number == null && keyword.isEmpty()) {
            return "ERRORE: l'utente non ha indicato quale rimborso. Nessun rimborso è stato modificato. "
                    + "Chiedi il numero (quello dopo il #) oppure una parola della descrizione.";
        }

        ReimbursementRequest request;
        try {
            request = service.updateStatus(number, number == null ? keyword : null, ReimbursementStatus.valueOf(status));
        } catch (IllegalArgumentException e) {
            // Errore "atteso" (inesistente, ambiguo, stato finale...): lo si restituisce al modello, che lo spiega.
            return "ERRORE: " + e.getMessage() + " Nessun rimborso è stato modificato.";
        }

        actions.add(ActionDto.updated(request));
        return "OK: rimborso #%d aggiornato, ora è «%s».".formatted(request.getNumber(), request.getStatus().label());
    }

    /** "2", "#2", " 2 " -> 2; qualunque altra cosa -> null. */
    private static Long parseNumber(String raw) {
        if (raw == null) {
            return null;
        }
        String cleaned = raw.replace("#", "").strip();
        return cleaned.matches("\\d{1,18}") ? Long.parseLong(cleaned) : null;
    }

    private static boolean numberWrittenByUser(long number, String message) {
        Matcher m = DIGITS.matcher(message);
        while (m.find()) {
            if (m.group().length() <= 18 && Long.parseLong(m.group()) == number) {
                return true;
            }
        }
        return false;
    }
}
