package it.portfolio.eaa.chat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Barriera 1 del tool dei rimborsi (decisione D21): l'ultimo messaggio dell'utente è una RICHIESTA di creare
 * un rimborso, oppure è una domanda / un'ipotesi?
 *
 * Un'azione con effetti non si esegue su una domanda. Provato dal vivo: un modello piccolo aprì un rimborso da
 * 200 € in risposta a "Posso farmi rimborsare 200€ per una cena?".
 *
 * ACCETTA (true):
 *  - un verbo naturale di creazione: apri, aggiungi, crea, inserisci, registra, richiedi, invia, fai, metti,
 *    presenta... (anche nelle forme "aprimi", "aggiungimi", "puoi aprirmi...", "vorrei creare...");
 *  - la forma breve imperativa senza verbo, ma con rimborso e importo: "un rimborso da 120 euro per hotel".
 *
 * BLOCCA (false):
 *  - domande informative: "Qual è il limite per l'hotel?", "Quanto posso spendere...?";
 *  - domande di permesso e ipotesi: "Posso farmi rimborsare 200€?", "Mi spetta un rimborso?", "Se apro un rimborso...";
 *  - frasi negate ("non aprire...") e affermazioni ("il rimborso hotel è di 150 euro").
 *
 * La logica è IDENTICA nel backend Laravel (ReimbursementIntent.php): le due implementazioni hanno la stessa
 * tabella di frasi nei rispettivi test. Se si modifica una, va modificata anche l'altra.
 *
 * Questa è solo la barriera sull'INTENTO. L'importo scritto dall'utente è la barriera 2 (ReimbursementToolHandler).
 */
final class ReimbursementIntent {

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
            "se\\s+(?:io\\s+)?(?:apro|apriamo|aprissi|creo|creassi|aggiungo|aggiungessi|inserisco|registro|faccio|richiedo|invio|metto|presento|posso|devo)",
            "nel caso", "supponiamo", "ipotizziamo", "mettiamo che",
            "can i", "may i", "could i", "should i", "what", "which", "how", "where", "when", "why", "who", "is there", "are there", "do i", "does", "is it");

    /** Verbi naturali con cui si chiede di creare un rimborso (italiano e inglese). */
    private static final String CREATION_VERBS =
            "apr(?:i|ire|imi|irmi|ite|ila|ilo|ine|iamo)|aggiung(?:i|ere|imi|ermi|ine|ete|iamo)|cre(?:a|are|ami|armi|ate|ane|iamo)"
                    + "|inserisc(?:i|imi|ine|ite)|inser(?:ire|irmi)|registr(?:a|are|ami|armi|ate)|richied(?:i|ere|imi|erne)"
                    + "|invi(?:a|are|ami|armi|ate)|fai|fare|fammi|farmi|facci|fate|fatemi|mett(?:i|ere|imi|ete)|segn(?:a|are|ami)"
                    + "|salv(?:a|are|ami)|gener(?:a|are)|inoltr(?:a|are)|present(?:a|are)|predispon(?:i|ere)|prepar(?:a|are)"
                    + "|compil(?:a|are)|emett(?:i|ere)|open|add|create|submit|file|register|record|raise|request|enter|send";

    private static final String REIMBURSEMENT_NOUN = "rimbors\\w*|refund\\w*|reimburse\\w*|nota spese|note spese";

    /** Parole di una frase che AFFERMA o descrive (non ordina): "il rimborso hotel è di 150 euro". */
    private static final String STATEMENT_WORDS =
            "è|sono|vale|valgono|era|erano|sarà|prevede|prevedono|previsto|massimo|massima|massimale|limite|limiti|tetto|fino a|al massimo";

    private static final Pattern INFO = Pattern.compile(
            "^(?:[\\s,.;:!-]*\\b(?:" + FILLERS + ")\\b)*[\\s,.;:!-]*(?:" + String.join("|", INFO_STARTERS) + ")\\b", FLAGS);
    private static final Pattern NEGATED_VERB = Pattern.compile("\\bnon\\s+(?:\\w+\\s+)?(?:" + CREATION_VERBS + ")\\b", FLAGS);
    private static final Pattern NEGATION_EN = Pattern.compile("\\b(?:don't|do not|never)\\b", FLAGS);
    private static final Pattern CREATION_VERB = Pattern.compile("\\b(?:" + CREATION_VERBS + ")\\b", FLAGS);
    private static final Pattern NOUN = Pattern.compile("\\b(?:" + REIMBURSEMENT_NOUN + ")\\b", FLAGS);
    private static final Pattern STATEMENT = Pattern.compile("\\b(?:" + STATEMENT_WORDS + ")\\b", FLAGS);
    private static final Pattern DIGIT = Pattern.compile("\\d");

    /** Verbi (participi e prima persona) con cui una risposta DICHIARA che un rimborso è stato creato. */
    private static final String CLAIM_VERBS =
            "(?:creat|registrat|inserit|aggiunt|salvat|emess)[oaie]|apert[oaie]|creo|apro|registro|inserisco|aggiungo";
    private static final Pattern NON = Pattern.compile("\\bnon\\b", FLAGS);
    private static final Pattern FAKE_TOOL_TEXT = Pattern.compile("\\bstrumento\\s+ha\\s+risposto\\b|reimbursement_request_id", FLAGS);
    /** Identificativo inventato tipo RR123456 (maiuscole, senza CASE_INSENSITIVE: gli UUID reali sono minuscoli). */
    private static final Pattern FAKE_ID = Pattern.compile("\\b[A-Z]{1,4}-?\\d{5,}\\b", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern CLAIM_VERB = Pattern.compile("\\b(?:" + CLAIM_VERBS + ")\\b", FLAGS);
    private static final Pattern CLAIM_NOUN = Pattern.compile("\\b(?:rimbors\\w*|richiest\\w*)\\b", FLAGS);

    private ReimbursementIntent() {}

    static boolean isActionRequest(String message) {
        // Apostrofo tipografico -> apostrofo semplice, così "cos’è" e "cos'è" sono la stessa cosa.
        String text = message == null ? "" : message.replace('’', '\'').replace('‘', '\'').strip();
        if (text.isEmpty()) {
            return false;
        }

        // 1. Domande informative, di permesso, ipotesi: mai un ordine, anche se contengono un verbo di creazione.
        if (INFO.matcher(text).find()) {
            return false;
        }

        // 2. Frasi negate: "non aprire nessun rimborso", "don't create...".
        if (NEGATED_VERB.matcher(text).find() || NEGATION_EN.matcher(text).find()) {
            return false;
        }

        // 3. Un verbo naturale di creazione.
        if (CREATION_VERB.matcher(text).find()) {
            return true;
        }

        // 4. Forma breve imperativa senza verbo: rimborso + importo, senza punto interrogativo e senza
        //    parole da affermazione ("un rimborso per hotel da 120 euro", "rimborso taxi 20 euro").
        return !text.contains("?")
                && NOUN.matcher(text).find()
                && DIGIT.matcher(text).find()
                && !STATEMENT.matcher(text).find();
    }

    /**
     * La risposta DICHIARA che un rimborso è stato creato / registrato?
     *
     * Serve a smascherare una conferma FALSA: provato dal vivo, a volte il modello non chiama il tool ma SCRIVE
     * a parole la chiamata e il suo risultato inventato ("Lo strumento ha risposto: { id: RR123456 } ...
     * rimborso registrato con successo"), e all'utente arriva un'azione mai avvenuta. Si usa SOLO quando la
     * richiesta era un ordine e nessun rimborso è stato salvato: in quel caso qualsiasi dichiarazione di
     * successo è per definizione falsa. Una risposta con una negazione ("non è stato creato...") è onesta.
     */
    static boolean claimsCreation(String answer) {
        if (answer == null || NON.matcher(answer).find()) {
            return false;
        }
        // Finta chiamata del tool scritta come testo, oppure identificativo inventato (es. RR123456).
        if (FAKE_TOOL_TEXT.matcher(answer).find() || FAKE_ID.matcher(answer).find()) {
            return true;
        }
        return CLAIM_VERB.matcher(answer).find() && CLAIM_NOUN.matcher(answer).find();
    }

    /** Ordine DIRETTO e non ambiguo: importo (stringa decimale "20.00") e descrizione ricavata dal "per ...". */
    record DirectRequest(String amount, String description) {}

    private static final Pattern NEGATIVE = Pattern.compile("(?:\\bmeno\\b|-)\\s*\\d", FLAGS);
    private static final Pattern AMOUNT = Pattern.compile("\\d+(?:[.,]\\d{1,2})?");
    private static final Pattern PER_CLAUSE = Pattern.compile(
            "\\bper\\s+(?:(?:il|lo|la|un|una|uno|i|le|gli)\\s+|l')?(.+?)(?=\\s+(?:da|di)\\s+\\d|\\s+\\d|$)", FLAGS);
    private static final Pattern TRAILING = Pattern.compile("[\\s.,;:!?€]+$");
    private static final Pattern COURTESY = Pattern.compile("^(?:favore|piacere|cortesia)\\b", FLAGS);

    /**
     * Ordine DIRETTO e non ambiguo: l'ultimo messaggio è una richiesta di creare un rimborso e contiene UN SOLO
     * importo (positivo). Restituisce importo e descrizione ricavata dal "per ...".
     *
     * Serve alla rete di sicurezza di ChatService: se il modello non chiama il tool (con le frasi brevi senza
     * verbo, tipo "un rimborso per hotel da 120 euro", un modello piccolo a volte NARRA la chiamata invece di
     * farla), il backend crea lui il rimborso passando dallo stesso ReimbursementService e dalle stesse
     * barriere. L'importo è per costruzione quello scritto dall'utente.
     *
     * Null (= nessun fallback) se: non è un ordine, non c'è importo, ce ne sono più di uno ("40 o 50 euro"),
     * oppure è negativo ("meno 5 euro", "-5").
     */
    static DirectRequest parseDirectRequest(String message) {
        String text = message == null ? "" : message.replace('’', '\'').replace('‘', '\'').strip();
        if (!isActionRequest(text) || NEGATIVE.matcher(text).find()) {
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
}
