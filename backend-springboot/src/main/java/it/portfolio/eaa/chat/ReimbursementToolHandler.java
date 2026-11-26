package it.portfolio.eaa.chat;

import it.portfolio.eaa.reimbursement.ReimbursementRequest;
import it.portfolio.eaa.reimbursement.ReimbursementService;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.ai.chat.model.ToolContext;

/**
 * Logica del tool "createReimbursementRequest" invocato dal modello (Function / Tool Calling).
 *
 * Come funziona il tool calling in Spring AI:
 *  1. al modello si inviano nome, descrizione e parametri del tool (schema JSON);
 *  2. se l'utente chiede un rimborso, il modello NON risponde a parole ma chiede "chiama
 *     createReimbursementRequest con amount=40";
 *  3. Spring AI esegue la funzione (questa classe: validazione + INSERT) e rimanda l'esito al modello;
 *  4. il modello scrive la risposta finale.
 *
 * Il modello non scrive mai direttamente nel database: passa sempre da ReimbursementService.
 *
 * Due barriere nel codice, INDIPENDENTI dal modello (decisione D21, trovate collaudando dal vivo con un
 * modello piccolo che apriva rimborsi su semplici domande): intento esplicito e importo scritto dall'utente.
 */
class ReimbursementToolHandler {

    /** Chiavi del ToolContext: i dati della richiesta corrente che la funzione non può ricavare da sola. */
    static final String CONVERSATION_ID = "conversationId";
    static final String USER_TEXTS = "userTexts";
    static final String CURRENT_MESSAGE = "currentMessage";
    static final String ACTIONS = "actions";

    private static final Pattern NUMBER = Pattern.compile("\\d+(?:[.,]\\d{1,2})?");
    private static final BigDecimal TOLERANCE = new BigDecimal("0.005");

    private final ReimbursementService service;

    ReimbursementToolHandler(ReimbursementService service) {
        this.service = service;
    }

    /** Il risultato testuale viene letto dal modello: deve dire chiaramente cos'è successo. */
    @SuppressWarnings("unchecked")
    String execute(ReimbursementToolArgs args, ToolContext toolContext) {
        var context = toolContext.getContext();
        String currentMessage = (String) context.get(CURRENT_MESSAGE);
        List<String> userTexts = (List<String>) context.get(USER_TEXTS);
        List<ActionDto> actions = (List<ActionDto>) context.get(ACTIONS);
        UUID conversationId = (UUID) context.get(CONVERSATION_ID);

        // Barriera 1 — intento: l'ultimo messaggio deve CHIEDERE l'azione, non solo parlarne. Accetta i verbi
        // naturali di creazione (apri, aggiungi, crea, inserisci, registra, richiedi...) e la forma breve
        // imperativa con importo; blocca domande informative, di permesso e ipotesi (vedi ReimbursementIntent).
        if (!ReimbursementIntent.isActionRequest(currentMessage)) {
            return "ERRORE: l'ultimo messaggio dell'utente non chiede di aprire una richiesta di rimborso (è una domanda). "
                    + "Nessuna richiesta è stata creata. Rispondi alla domanda usando gli estratti, senza strumenti.";
        }

        // Barriera 2 — anti-allucinazione: l'importo deve averlo scritto l'UTENTE.
        if (!amountWrittenByUser(args.amount(), userTexts)) {
            return "ERRORE: l'importo indicato non compare nei messaggi dell'utente. Nessuna richiesta è stata creata. "
                    + "Se l'utente vuole un rimborso, chiedigli di scrivere l'importo; altrimenti rispondi alla sua domanda senza strumenti.";
        }

        ReimbursementRequest request;
        try {
            request = service.create(args.amount(), args.description(), conversationId);
        } catch (IllegalArgumentException e) {
            // Errore "atteso" (importo non valido): lo si restituisce al modello, che lo spiega all'utente.
            return "ERRORE: " + e.getMessage() + " Nessuna richiesta è stata creata. Chiedi all'utente un importo valido.";
        }

        String amount = request.getAmount().toPlainString();
        actions.add(new ActionDto(ActionDto.REIMBURSEMENT_CREATED, request.getId().toString(), amount,
                request.getCurrency(), request.getDescription()));

        return "OK: richiesta di rimborso creata. id=%s, importo=%s %s, stato=%s."
                .formatted(request.getId(), amount, request.getCurrency(), request.getStatus().name());
    }

    /**
     * L'importo proposto dal modello coincide con un numero scritto dall'utente?
     * Riconosce "50", "50€", "12,5", "12.50". Cifre scritte a parole ("cinquanta") non valgono: in quel caso
     * il modello chiede di scrivere l'importo in cifre (scelta prudente, D21).
     */
    static boolean amountWrittenByUser(String rawAmount, List<String> userTexts) {
        if (rawAmount == null) {
            return false;
        }
        BigDecimal amount;
        try {
            amount = new BigDecimal(rawAmount.replaceAll("[^\\d,.\\-]", "").replace(',', '.'));
        } catch (NumberFormatException e) {
            return false; // non è nemmeno un numero: non può essere stato scritto dall'utente
        }

        for (String text : userTexts) {
            Matcher m = NUMBER.matcher(text);
            while (m.find()) {
                if (new BigDecimal(m.group().replace(',', '.')).subtract(amount).abs().compareTo(TOLERANCE) < 0) {
                    return true;
                }
            }
        }
        return false;
    }
}
