package it.portfolio.eaa.chat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Barriera sull'INTENTO per i due tool dei rimborsi (decisioni D21, D46 e D48).
 *
 * Un'azione con effetti non si esegue su una domanda, e NEMMENO su una frase senza un verbo esplicito:
 *  - CREARE un rimborso:      apri, aggiungi, crea, inserisci, registra, richiedi, invia, fai, metti...
 *  - AGGIORNARNE lo stato:    segna, approva, rifiuta, esegui, aggiorna, chiudi, paga, marca, imposta...
 *
 * BLOCCA sempre: domande informative, di permesso o ipotesi, frasi negate e affermazioni.
 *
 * FRASE SENZA VERBO ("allora un rimborso per hotel da 120 euro", "rimborso #2 eseguito"): non esegue nulla e non
 * interpella il modello. {@link #verblessHint(String)} produce un avviso concreto: servono i verbi giusti.
 *
 * La logica è IDENTICA nel backend Laravel (ReimbursementIntent.php): le due implementazioni hanno la stessa
 * tabella di frasi nei rispettivi test. Se si modifica una, va modificata anche l'altra.
 */
final class ReimbursementIntent {

    static final String HINT_WARNING = "Manca un verbo esplicito: nessuna azione eseguita.";

    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS;

    /** Intercalari che possono precedere una domanda: "allora posso farmi rimborsare...?" */
    private static final String FILLERS = "allora|ok|okay|scusa|scusami|senti|dunque|ciao|salve|beh|dai|quindi|ma|e";

    /** Aperture tipiche di una domanda informativa, di permesso o ipotetica. */
    private static final List<String> INFO_STARTERS = List.of(
            "posso", "possiamo", "potrei", "potremmo", "si può", "si puo", "è possibile", "e possibile", "è consentito", "è permesso",
            "devo", "dobbiamo", "dovrei", "ho diritto", "abbiamo diritto", "mi spetta", "ci spetta", "spetta",
            "qual", "quale", "quali", "quanto", "quanti", "quanta", "quante", "cosa", "che cosa", "cos'è", "cos'",
            "come\\s+(?:posso|possiamo|si|faccio|devo|funziona|fare|mai)",
            "dove\\s+(?:posso|possiamo|si|trovo|vedo|devo|è|sono)",
            "quando\\s+(?:posso|possiamo|si|devo|è|scade|arriva|viene|verrà|finisce|ricevo)",
            "perché", "perche", "chi", "c'è", "ci sono", "esiste", "esistono", "è previsto", "sono previsti",
            "vale", "valgono", "serve", "servono", "bisogna", "occorre",
            "se\\s+(?:io\\s+)?(?:apro|apriamo|aprissi|creo|creassi|aggiungo|aggiungessi|inserisco|registro|faccio|richiedo|invio|metto|presento|posso|devo|approvo|rifiuto|eseguo|segno|aggiorno|chiudo)",
            "nel caso", "supponiamo", "ipotizziamo", "mettiamo che",
            "can i", "may i", "could i", "should i", "what", "which", "how", "where", "when", "why", "who", "is there", "are there", "do i", "does", "is it");

    /** Verbi naturali con cui si chiede di CREARE un rimborso (italiano e inglese). */
    private static final String CREATION_VERBS =
            "apr(?:i|ire|imi|irmi|ite|ila|ilo|ine|iamo)|aggiung(?:i|ere|imi|ermi|ine|ete|iamo)|cre(?:a|are|ami|armi|ate|ane|iamo)"
                    + "|inserisc(?:i|imi|ine|ite)|inser(?:ire|irmi)|registr(?:a|are|ami|armi|ate)|richied(?:i|ere|imi|erne)"
                    + "|invi(?:a|are|ami|armi|ate)|fai|fare|fammi|farmi|facci|fate|fatemi|mett(?:i|ere|imi|ete)|segn(?:a|are|ami)"
                    + "|salv(?:a|are|ami)|gener(?:a|are)|inoltr(?:a|are)|present(?:a|are)|predispon(?:i|ere)|prepar(?:a|are)"
                    + "|compil(?:a|are)|emett(?:i|ere)|open|add|create|submit|file|register|record|raise|request|enter|send";

    /** Verbi che ordinano di AGGIORNARE lo stato di un rimborso esistente (mai ambigui con la creazione). */
    private static final String UPDATE_VERBS =
            "approv(?:a|are|ami|armi|ate|alo|ala)|rifiut(?:a|are|ami|ate|alo|ala)|respingi|respingere|respingilo"
                    + "|esegui|eseguire|eseguilo|eseguila|aggiorn(?:a|are|ami|alo|ala|ate)|chiud(?:i|ere|ilo|ila)|cambi(?:a|are|ami)"
                    + "|conferm(?:a|are|alo|ala)|pag(?:a|are|alo|ala)|marc(?:a|are|alo|ala)|impost(?:a|are|alo|ala)|liquid(?:a|are)"
                    + "|conclud(?:i|ere)|complet(?:a|are)|approve|reject|deny|execute|update|close|pay|mark|set|complete|confirm|change";

    /** Verbi che possono voler dire creare O aggiornare: aggiornamento solo se c'è un riferimento a un rimborso esistente. */
    private static final String AMBIGUOUS_VERBS = "segn(?:a|are|ami|alo|ala)|mett(?:i|ere|ilo|ila)";

    /** Parole che indicano il NUOVO STATO richiesto (ordine di inserimento = ordine di controllo). */
    private static final Map<String, Pattern> STATUS_WORDS = new LinkedHashMap<>();

    static {
        STATUS_WORDS.put("APPROVED", word("approv\\w*"));
        STATUS_WORDS.put("REJECTED", word("rifiut\\w*|respin\\w*|reject\\w*|deny|denied"));
        STATUS_WORDS.put("EXECUTED", word("esegu\\w*|pagat\\w*|pag(?:a|are|alo|ala)|liquid\\w*|chius\\w*|chiud\\w*|conclus\\w*|conclud\\w*|complet\\w*|execut\\w*|paid|pay"));
    }

    private static final String REIMBURSEMENT_NOUN = "rimbors\\w*|refund\\w*|reimburse\\w*|nota spese|note spese";

    /** Parole di una frase che AFFERMA o descrive (non ordina): "il rimborso hotel è di 150 euro". */
    private static final String STATEMENT_WORDS =
            "è|sono|vale|valgono|era|erano|sarà|prevede|prevedono|previsto|massimo|massima|massimale|limite|limiti|tetto|fino a|al massimo";

    /** Verbi (participi e prima persona) con cui una risposta DICHIARA che un rimborso è stato creato. */
    private static final String CLAIM_VERBS = "(?:creat|registrat|inserit|aggiunt|salvat|emess)[oaie]|apert[oaie]|creo|apro|registro|inserisco|aggiungo";

    /** Verbi con cui una risposta DICHIARA che lo stato di un rimborso è stato aggiornato. */
    private static final String UPDATE_CLAIM_VERBS =
            "(?:aggiornat|approvat|rifiutat|eseguit|segnat|impostat|modificat|chius|pagat|liquidat|conclus)[oaie]|aggiorno|approvo|rifiuto|eseguo|segno|chiudo|imposto|modifico";

    private static final Pattern INFO = Pattern.compile(
            "^(?:[\\s,.;:!-]*\\b(?:" + FILLERS + ")\\b)*[\\s,.;:!-]*(?:" + String.join("|", INFO_STARTERS) + ")\\b", FLAGS);
    private static final Pattern NEGATED = Pattern.compile(
            "\\bnon\\s+(?:\\w+\\s+)?(?:" + CREATION_VERBS + "|" + UPDATE_VERBS + "|" + AMBIGUOUS_VERBS + ")\\b|\\b(?:don't|do not|never)\\b", FLAGS);
    private static final Pattern CREATION_VERB = word(CREATION_VERBS);
    private static final Pattern UPDATE_VERB = word(UPDATE_VERBS);
    private static final Pattern AMBIGUOUS_VERB = word(AMBIGUOUS_VERBS);
    private static final Pattern NOUN = word(REIMBURSEMENT_NOUN);
    private static final Pattern STATEMENT = word(STATEMENT_WORDS);
    private static final Pattern HASH_NUMBER = Pattern.compile("#\\s*(\\d+)");
    private static final Pattern NUMBERED_NOUN = Pattern.compile("\\brimbors\\w*\\s+(?:n\\.?|nr\\.?|numero)\\s*(\\d+)", FLAGS);
    private static final Pattern KEYWORD = Pattern.compile(
            "\\brimbors\\w*\\s+(?:della|del|dello|dei|delle|di|per)\\s+(?:(?:la|il|lo|un|una)\\s+|l')?(\\p{L}{3,})", FLAGS);
    private static final Pattern NON = Pattern.compile("\\bnon\\b", FLAGS);
    private static final Pattern FAKE_TOOL_TEXT = Pattern.compile("\\bstrumento\\s+ha\\s+risposto\\b|reimbursement_request_id", FLAGS);
    /** Identificativo inventato tipo RR123456 (maiuscole, senza CASE_INSENSITIVE: gli UUID reali sono minuscoli). */
    private static final Pattern FAKE_ID = Pattern.compile("\\b[A-Z]{1,4}-?\\d{5,}\\b", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern CREATE_CLAIM_VERB = word(CLAIM_VERBS);
    private static final Pattern CREATE_CLAIM_NOUN = word("(?:rimbors\\w*|richiest\\w*)");
    private static final Pattern UPDATE_CLAIM_VERB = word(UPDATE_CLAIM_VERBS);
    private static final Pattern UPDATE_CLAIM_NOUN = word("(?:rimbors\\w*|richiest\\w*|stato)");

    private static final Pattern NEGATIVE = Pattern.compile("(?:\\bmeno\\b|-)\\s*\\d", FLAGS);
    private static final Pattern AMOUNT = Pattern.compile("\\d+(?:[.,]\\d{1,2})?");
    private static final Pattern PER_CLAUSE = Pattern.compile(
            "\\bper\\s+(?:(?:il|lo|la|un|una|uno|i|le|gli)\\s+|l')?(.+?)(?=\\s+(?:da|di)\\s+\\d|\\s+\\d|$)", FLAGS);
    private static final Pattern TRAILING = Pattern.compile("[\\s.,;:!?€]+$");
    private static final Pattern COURTESY = Pattern.compile("^(?:favore|piacere|cortesia)\\b", FLAGS);
    private static final Pattern DIGIT = Pattern.compile("\\d");

    private ReimbursementIntent() {}

    private static Pattern word(String alternatives) {
        return Pattern.compile("\\b(?:" + alternatives + ")\\b", FLAGS);
    }

    // ---------------------------------------------------------------------------------------------------------
    //  Classificazione dell'intento
    // ---------------------------------------------------------------------------------------------------------

    /** È una richiesta di CREARE un rimborso? (verbo esplicito di creazione, non una domanda, non una negazione) */
    static boolean isActionRequest(String message) {
        String text = normalize(message);
        if (text.isEmpty() || isQuestionOrHypothesis(text) || NEGATED.matcher(text).find()) {
            return false;
        }
        // "Segna come eseguito il rimborso #2" contiene un verbo ambiguo ma è un AGGIORNAMENTO, non una creazione.
        if (isStatusUpdateRequest(text)) {
            return false;
        }
        return CREATION_VERB.matcher(text).find();
    }

    /**
     * È un ordine di AGGIORNARE lo stato di un rimborso esistente? Serve un verbo esplicito di aggiornamento
     * (approva, rifiuta, esegui, aggiorna, chiudi...) oppure un verbo ambiguo (segna, metti) accompagnato da un
     * riferimento a un rimborso esistente (#2, "eseguito"...); e il messaggio deve parlare di un rimborso.
     */
    static boolean isStatusUpdateRequest(String message) {
        String text = normalize(message);
        if (text.isEmpty() || isQuestionOrHypothesis(text) || NEGATED.matcher(text).find()) {
            return false;
        }
        boolean refersToReimbursement = NOUN.matcher(text).find() || HASH_NUMBER.matcher(text).find();
        if (!refersToReimbursement) {
            return false;
        }
        boolean clearVerb = UPDATE_VERB.matcher(text).find();
        boolean ambiguousVerb = AMBIGUOUS_VERB.matcher(text).find();
        return clearVerb || (ambiguousVerb && hasUpdateMarker(text));
    }

    /**
     * Il nuovo stato richiesto dal messaggio (APPROVED | REJECTED | EXECUTED), oppure null se non c'è o se ce ne
     * sono più di uno ("approva e poi esegui"). Lo stato lo decide il TESTO DELL'UTENTE, non il modello.
     */
    static String requestedStatus(String message) {
        String text = normalize(message);
        List<String> found = new ArrayList<>();
        STATUS_WORDS.forEach((status, pattern) -> {
            if (pattern.matcher(text).find()) {
                found.add(status);
            }
        });
        return found.size() == 1 ? found.get(0) : null;
    }

    // ---------------------------------------------------------------------------------------------------------
    //  Ordini diretti (rete di sicurezza di ChatService)
    // ---------------------------------------------------------------------------------------------------------

    /** Ordine DIRETTO di CREARE un rimborso: importo (stringa decimale "20.00") e descrizione ricavata dal "per ...". */
    record DirectRequest(String amount, String description) {}

    /** Ordine DIRETTO di AGGIORNARE lo stato: nuovo stato e riferimento (numero oppure parola della descrizione). */
    record DirectUpdate(String status, Long number, String keyword) {}

    /**
     * Ordine DIRETTO e non ambiguo di CREARE un rimborso: un solo importo positivo e un verbo esplicito. Serve alla
     * rete di sicurezza di ChatService: se il modello non chiama il tool (a volte lo NARRA invece di farlo), il
     * backend crea lui il rimborso passando dallo stesso servizio e dalle stesse barriere.
     */
    static DirectRequest parseDirectRequest(String message) {
        String text = normalize(message);
        if (!isActionRequest(text)) {
            return null;
        }
        return extractAmountAndDescription(text);
    }

    /**
     * Ordine DIRETTO e non ambiguo di AGGIORNARE lo stato: verbo esplicito, un solo stato richiesto e il
     * riferimento al rimborso (numero "#2" oppure una parola della descrizione: "il rimborso della cena").
     */
    static DirectUpdate parseDirectUpdate(String message) {
        String text = normalize(message);
        String status = requestedStatus(text);
        if (status == null || !isStatusUpdateRequest(text)) {
            return null;
        }
        Long number = referencedNumber(text);
        String keyword = number == null ? referencedKeyword(text) : null;
        if (number == null && keyword == null) {
            return null;
        }
        return new DirectUpdate(status, number, keyword);
    }

    // ---------------------------------------------------------------------------------------------------------
    //  Frase senza verbo -> avviso
    // ---------------------------------------------------------------------------------------------------------

    /** Avviso per una frase che SEMBRA un ordine ma manca il verbo: `warning` breve da mostrare a video + `answer`. */
    record Hint(String warning, String answer) {}

    /**
     * Se il messaggio SEMBRA un ordine ma manca il verbo ("allora un rimborso per hotel da 120 euro",
     * "rimborso #2 eseguito") restituisce l'avviso da mostrare all'utente, altrimenti null. Non viene eseguito
     * nulla e il modello NON viene interpellato.
     */
    static Hint verblessHint(String message) {
        String text = normalize(message);
        if (text.isEmpty() || text.contains("?") || isQuestionOrHypothesis(text) || NEGATED.matcher(text).find()
                || isActionRequest(text) || isStatusUpdateRequest(text) || STATEMENT.matcher(text).find()) {
            return null;
        }

        boolean hasNoun = NOUN.matcher(text).find();
        String status = requestedStatus(text);
        Long number = referencedNumber(text);

        if (status != null && (hasNoun || number != null)) {
            // Aggiornamento senza verbo: "rimborso #2 eseguito", "il rimborso della cena approvato".
            String verb = switch (status) {
                case "EXECUTED" -> "Segna come eseguito";
                case "APPROVED" -> "Approva";
                default -> "Rifiuta";
            };
            String keyword = referencedKeyword(text);
            String reference = number != null ? "il rimborso #" + number : (keyword != null ? "il rimborso della " + keyword : "il rimborso #1");
            return hint(verb + " " + reference);
        }

        if (hasNoun && DIGIT.matcher(text).find()) {
            // Creazione senza verbo: "un rimborso per hotel da 120 euro".
            DirectRequest parsed = extractAmountAndDescription(text);
            String suggestion = parsed != null
                    ? "Aggiungi un rimborso da " + friendlyAmount(parsed.amount()) + " €" + (parsed.description() != null ? " per " + parsed.description() : "")
                    : "Aggiungi un rimborso da 40 € per cena";
            return hint(suggestion);
        }
        return null;
    }

    // ---------------------------------------------------------------------------------------------------------
    //  Risposte che dichiarano un successo
    // ---------------------------------------------------------------------------------------------------------

    /**
     * La risposta DICHIARA che un rimborso è stato creato / registrato? Serve a smascherare una conferma FALSA: a
     * volte il modello non chiama il tool ma SCRIVE a parole la chiamata e il suo risultato inventato. Si usa SOLO
     * quando la richiesta era un ordine e nulla è stato salvato. Una risposta con una negazione è onesta.
     */
    static boolean claimsCreation(String answer) {
        return claims(answer, CREATE_CLAIM_VERB, CREATE_CLAIM_NOUN);
    }

    /** Come {@link #claimsCreation(String)} ma per l'aggiornamento di stato ("rimborso approvato", "ho segnato..."). */
    static boolean claimsUpdate(String answer) {
        return claims(answer, UPDATE_CLAIM_VERB, UPDATE_CLAIM_NOUN);
    }

    // ---------------------------------------------------------------------------------------------------------
    //  Dettagli
    // ---------------------------------------------------------------------------------------------------------

    private static boolean claims(String answer, Pattern verbs, Pattern noun) {
        if (answer == null || NON.matcher(answer).find()) {
            return false;
        }
        // Finta chiamata del tool scritta come testo, oppure identificativo inventato (es. RR123456).
        if (FAKE_TOOL_TEXT.matcher(answer).find() || FAKE_ID.matcher(answer).find()) {
            return true;
        }
        return verbs.matcher(answer).find() && noun.matcher(answer).find();
    }

    private static String normalize(String message) {
        // Apostrofo tipografico -> apostrofo semplice, così "cos’è" e "cos'è" sono la stessa cosa.
        return message == null ? "" : message.replace('’', '\'').replace('‘', '\'').strip();
    }

    /** Domande informative, di permesso, ipotesi: mai un ordine, anche se contengono un verbo. */
    private static boolean isQuestionOrHypothesis(String text) {
        return INFO.matcher(text).find();
    }

    /** Un riferimento a un rimborso esistente o a un nuovo stato: "#2", "eseguito", "rimborso n. 2". */
    private static boolean hasUpdateMarker(String text) {
        if (HASH_NUMBER.matcher(text).find() || NUMBERED_NOUN.matcher(text).find()) {
            return true;
        }
        return STATUS_WORDS.values().stream().anyMatch(p -> p.matcher(text).find());
    }

    /** Il numero del rimborso citato: "#2" oppure "rimborso n. 2". */
    private static Long referencedNumber(String text) {
        Matcher m = HASH_NUMBER.matcher(text);
        if (m.find()) {
            return Long.parseLong(m.group(1));
        }
        m = NUMBERED_NOUN.matcher(text);
        return m.find() ? Long.parseLong(m.group(1)) : null;
    }

    /** Una parola della descrizione citata: "il rimborso della cena" -> "cena". */
    private static String referencedKeyword(String text) {
        Matcher m = KEYWORD.matcher(text);
        return m.find() ? m.group(1).toLowerCase(java.util.Locale.ROOT) : null;
    }

    /**
     * Un solo importo positivo e la descrizione ricavata dal "per ...". Null se non c'è importo, se ce ne sono più
     * di uno ("40 o 50 euro") oppure se è negativo ("meno 5 euro", "-5").
     */
    private static DirectRequest extractAmountAndDescription(String text) {
        if (NEGATIVE.matcher(text).find()) {
            return null;
        }
        Set<String> amounts = new LinkedHashSet<>();
        Matcher numbers = AMOUNT.matcher(text);
        while (numbers.find()) {
            amounts.add(new BigDecimal(numbers.group().replace(',', '.')).setScale(2, RoundingMode.HALF_UP).toPlainString());
        }
        if (amounts.size() != 1) {
            return null;
        }

        // Descrizione: l'ultimo "per ..." che precede l'importo o la fine ("per taxi", "per hotel da 120 euro").
        String description = null;
        Matcher per = PER_CLAUSE.matcher(text);
        String last = null;
        while (per.find()) {
            last = per.group(1);
        }
        if (last != null) {
            String candidate = TRAILING.matcher(last).replaceAll("").strip();
            // "per favore / per piacere" è una cortesia, non un motivo di spesa.
            if (!candidate.isEmpty() && !COURTESY.matcher(candidate).find()) {
                description = candidate.substring(0, Math.min(candidate.length(), 100));
            }
        }
        return new DirectRequest(amounts.iterator().next(), description);
    }

    /** "120.00" -> "120", "15.50" -> "15,50" (per l'esempio mostrato all'utente). */
    private static String friendlyAmount(String amount) {
        return amount.endsWith(".00") ? amount.substring(0, amount.length() - 3) : amount.replace('.', ',');
    }

    private static Hint hint(String suggestion) {
        return new Hint(HINT_WARNING,
                "Non ho eseguito nessuna azione perché nel messaggio manca un verbo esplicito. "
                        + "Per creare un rimborso usa apri, aggiungi, crea, inserisci, registra o richiedi; "
                        + "per aggiornarlo usa segna, approva, rifiuta, esegui, aggiorna o chiudi. "
                        + "Prova così: «" + suggestion + "».");
    }
}
