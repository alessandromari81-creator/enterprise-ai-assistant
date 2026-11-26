<?php

namespace App\Services\Chat;

/**
 * Barriera sull'INTENTO per i due tool dei rimborsi (decisione D21, D46 e D48).
 *
 * Un'azione con effetti non si esegue su una domanda, e NEMMENO su una frase senza un verbo esplicito:
 *  - CREARE un rimborso:      apri, aggiungi, crea, inserisci, registra, richiedi, invia, fai, metti...
 *  - AGGIORNARNE lo stato:    segna, approva, rifiuta, esegui, aggiorna, chiudi, paga, marca, imposta...
 *
 * BLOCCA sempre: domande informative ("Qual è il limite per l'hotel?"), di permesso o ipotesi ("Posso farmi
 * rimborsare 200€?", "Se apro un rimborso..."), frasi negate ("non aprire...") e affermazioni.
 *
 * FRASE SENZA VERBO ("allora un rimborso per hotel da 120 euro", "rimborso #2 eseguito"): non esegue nulla e
 * non interpella il modello. {@see self::verblessHint()} produce un avviso concreto per l'utente: servono i verbi
 * giusti. È una scelta di prodotto: meglio un avviso chiaro che indovinare un'azione dalle parole.
 *
 * La logica è IDENTICA nel backend Spring Boot (ReimbursementIntent.java): le due implementazioni hanno la
 * stessa tabella di frasi nei rispettivi test. Se si modifica una, va modificata anche l'altra.
 */
final class ReimbursementIntent
{
    public const HINT_WARNING = 'Manca un verbo esplicito: nessuna azione eseguita.';

    /** Intercalari che possono precedere una domanda: "allora posso farmi rimborsare...?" */
    private const FILLERS = 'allora|ok|okay|scusa|scusami|senti|dunque|ciao|salve|beh|dai|quindi|ma|e';

    /** Aperture tipiche di una domanda informativa, di permesso o ipotetica. */
    private const INFO_STARTERS = [
        'posso', 'possiamo', 'potrei', 'potremmo', 'si può', 'si puo', 'è possibile', 'e possibile', 'è consentito', 'è permesso',
        'devo', 'dobbiamo', 'dovrei', 'ho diritto', 'abbiamo diritto', 'mi spetta', 'ci spetta', 'spetta',
        'qual', 'quale', 'quali', 'quanto', 'quanti', 'quanta', 'quante', 'cosa', 'che cosa', "cos'è", "cos'",
        'come\s+(?:posso|possiamo|si|faccio|devo|funziona|fare|mai)',
        'dove\s+(?:posso|possiamo|si|trovo|vedo|devo|è|sono)',
        'quando\s+(?:posso|possiamo|si|devo|è|scade|arriva|viene|verrà|finisce|ricevo)',
        'perché', 'perche', 'chi', "c'è", 'ci sono', 'esiste', 'esistono', 'è previsto', 'sono previsti',
        'vale', 'valgono', 'serve', 'servono', 'bisogna', 'occorre',
        'se\s+(?:io\s+)?(?:apro|apriamo|aprissi|creo|creassi|aggiungo|aggiungessi|inserisco|registro|faccio|richiedo|invio|metto|presento|posso|devo|approvo|rifiuto|eseguo|segno|aggiorno|chiudo)',
        'nel caso', 'supponiamo', 'ipotizziamo', 'mettiamo che',
        'can i', 'may i', 'could i', 'should i', 'what', 'which', 'how', 'where', 'when', 'why', 'who', 'is there', 'are there', 'do i', 'does', 'is it',
    ];

    /** Verbi naturali con cui si chiede di CREARE un rimborso (italiano e inglese). */
    private const CREATION_VERBS =
        'apr(?:i|ire|imi|irmi|ite|ila|ilo|ine|iamo)|aggiung(?:i|ere|imi|ermi|ine|ete|iamo)|cre(?:a|are|ami|armi|ate|ane|iamo)'
        .'|inserisc(?:i|imi|ine|ite)|inser(?:ire|irmi)|registr(?:a|are|ami|armi|ate)|richied(?:i|ere|imi|erne)'
        .'|invi(?:a|are|ami|armi|ate)|fai|fare|fammi|farmi|facci|fate|fatemi|mett(?:i|ere|imi|ete)|segn(?:a|are|ami)'
        .'|salv(?:a|are|ami)|gener(?:a|are)|inoltr(?:a|are)|present(?:a|are)|predispon(?:i|ere)|prepar(?:a|are)'
        .'|compil(?:a|are)|emett(?:i|ere)|open|add|create|submit|file|register|record|raise|request|enter|send';

    /** Verbi che ordinano di AGGIORNARE lo stato di un rimborso esistente (mai ambigui con la creazione). */
    private const UPDATE_VERBS =
        'approv(?:a|are|ami|armi|ate|alo|ala)|rifiut(?:a|are|ami|ate|alo|ala)|respingi|respingere|respingilo'
        .'|esegui|eseguire|eseguilo|eseguila|aggiorn(?:a|are|ami|alo|ala|ate)|chiud(?:i|ere|ilo|ila)|cambi(?:a|are|ami)'
        .'|conferm(?:a|are|alo|ala)|pag(?:a|are|alo|ala)|marc(?:a|are|alo|ala)|impost(?:a|are|alo|ala)|liquid(?:a|are)'
        .'|conclud(?:i|ere)|complet(?:a|are)|approve|reject|deny|execute|update|close|pay|mark|set|complete|confirm|change';

    /** Verbi che possono voler dire creare O aggiornare: aggiornamento solo se c'è un riferimento a un rimborso esistente. */
    private const AMBIGUOUS_VERBS = 'segn(?:a|are|ami|alo|ala)|mett(?:i|ere|ilo|ila)';

    /** Parole che indicano il NUOVO STATO richiesto. */
    private const STATUS_WORDS = [
        'APPROVED' => 'approv\w*',
        'REJECTED' => 'rifiut\w*|respin\w*|reject\w*|deny|denied',
        'EXECUTED' => 'esegu\w*|pagat\w*|pag(?:a|are|alo|ala)|liquid\w*|chius\w*|chiud\w*|conclus\w*|conclud\w*|complet\w*|execut\w*|paid|pay',
    ];

    private const REIMBURSEMENT_NOUN = 'rimbors\w*|refund\w*|reimburse\w*|nota spese|note spese';

    /** Parole di una frase che AFFERMA o descrive (non ordina): "il rimborso hotel è di 150 euro". */
    private const STATEMENT_WORDS = 'è|sono|vale|valgono|era|erano|sarà|prevede|prevedono|previsto|massimo|massima|massimale|limite|limiti|tetto|fino a|al massimo';

    /** Verbi (participi e prima persona) con cui una risposta DICHIARA che un rimborso è stato creato. */
    private const CLAIM_VERBS = '(?:creat|registrat|inserit|aggiunt|salvat|emess)[oaie]|apert[oaie]|creo|apro|registro|inserisco|aggiungo';

    /** Verbi con cui una risposta DICHIARA che lo stato di un rimborso è stato aggiornato. */
    private const UPDATE_CLAIM_VERBS = '(?:aggiornat|approvat|rifiutat|eseguit|segnat|impostat|modificat|chius|pagat|liquidat|conclus)[oaie]|aggiorno|approvo|rifiuto|eseguo|segno|chiudo|imposto|modifico';

    // ---------------------------------------------------------------------------------------------------------
    //  Classificazione dell'intento
    // ---------------------------------------------------------------------------------------------------------

    /** È una richiesta di CREARE un rimborso? (verbo esplicito di creazione, non una domanda, non una negazione) */
    public static function isActionRequest(string $message): bool
    {
        $text = self::normalize($message);
        if ($text === '' || self::isQuestionOrHypothesis($text) || self::isNegated($text)) {
            return false;
        }
        // "Segna come eseguito il rimborso #2" contiene un verbo ambiguo ma è un AGGIORNAMENTO, non una creazione.
        if (self::isStatusUpdateRequest($text)) {
            return false;
        }

        return preg_match(sprintf('~\b(?:%s)\b~iu', self::CREATION_VERBS), $text) === 1;
    }

    /**
     * È un ordine di AGGIORNARE lo stato di un rimborso esistente?
     * Serve un verbo esplicito di aggiornamento (approva, rifiuta, esegui, aggiorna, chiudi...) oppure un verbo
     * ambiguo (segna, metti) accompagnato da un riferimento a un rimborso esistente (#2, "eseguito"...), e il
     * messaggio deve parlare di un rimborso.
     */
    public static function isStatusUpdateRequest(string $message): bool
    {
        $text = self::normalize($message);
        if ($text === '' || self::isQuestionOrHypothesis($text) || self::isNegated($text)) {
            return false;
        }

        $refersToReimbursement = preg_match(sprintf('~\b(?:%s)\b~iu', self::REIMBURSEMENT_NOUN), $text) === 1
            || preg_match('~#\s*\d+~u', $text) === 1;
        if (! $refersToReimbursement) {
            return false;
        }

        $clearVerb = preg_match(sprintf('~\b(?:%s)\b~iu', self::UPDATE_VERBS), $text) === 1;
        $ambiguousVerb = preg_match(sprintf('~\b(?:%s)\b~iu', self::AMBIGUOUS_VERBS), $text) === 1;

        return $clearVerb || ($ambiguousVerb && self::hasUpdateMarker($text));
    }

    /**
     * Il nuovo stato richiesto dal messaggio (APPROVED | REJECTED | EXECUTED), oppure null se non c'è o se ce ne
     * sono più di uno ("approva e poi esegui"). Lo stato lo decide il TESTO DELL'UTENTE, non il modello.
     */
    public static function requestedStatus(string $message): ?string
    {
        $text = self::normalize($message);
        $found = [];
        foreach (self::STATUS_WORDS as $status => $pattern) {
            if (preg_match(sprintf('~\b(?:%s)\b~iu', $pattern), $text)) {
                $found[] = $status;
            }
        }

        return count($found) === 1 ? $found[0] : null;
    }

    // ---------------------------------------------------------------------------------------------------------
    //  Interpretazione degli ordini diretti (rete di sicurezza di ChatService)
    // ---------------------------------------------------------------------------------------------------------

    /**
     * Ordine DIRETTO e non ambiguo di CREARE un rimborso: un solo importo positivo e un verbo esplicito.
     * Restituisce importo ("20.00") e descrizione ricavata dal "per ...".
     *
     * Serve alla rete di sicurezza di ChatService: se il modello non chiama il tool (a volte lo NARRA invece di
     * farlo), il backend crea lui il rimborso passando dallo stesso servizio e dalle stesse barriere.
     *
     * @return array{amount: string, description: ?string}|null
     */
    public static function parseDirectRequest(string $message): ?array
    {
        $text = self::normalize($message);
        if (! self::isActionRequest($text)) {
            return null;
        }

        return self::extractAmountAndDescription($text);
    }

    /**
     * Ordine DIRETTO e non ambiguo di AGGIORNARE lo stato: verbo esplicito, un solo stato richiesto e il
     * riferimento al rimborso (numero "#2" oppure una parola della descrizione: "il rimborso della cena").
     *
     * @return array{status: string, number: ?int, keyword: ?string}|null
     */
    public static function parseDirectUpdate(string $message): ?array
    {
        $text = self::normalize($message);
        $status = self::requestedStatus($text);
        if ($status === null || ! self::isStatusUpdateRequest($text)) {
            return null;
        }

        $number = self::referencedNumber($text);
        $keyword = $number === null ? self::referencedKeyword($text) : null;
        if ($number === null && $keyword === null) {
            return null;
        }

        return ['status' => $status, 'number' => $number, 'keyword' => $keyword];
    }

    // ---------------------------------------------------------------------------------------------------------
    //  Frase senza verbo -> avviso
    // ---------------------------------------------------------------------------------------------------------

    /**
     * Se il messaggio SEMBRA un ordine ma manca il verbo ("allora un rimborso per hotel da 120 euro",
     * "rimborso #2 eseguito") restituisce l'avviso da mostrare all'utente, altrimenti null.
     *
     * Non viene eseguito nulla e il modello NON viene interpellato: la risposta è immediata e dice
     * concretamente quali verbi usare, con una frase d'esempio costruita sul messaggio dell'utente.
     *
     * @return array{warning: string, answer: string}|null
     */
    public static function verblessHint(string $message): ?array
    {
        $text = self::normalize($message);
        if ($text === '' || str_contains($text, '?') || self::isQuestionOrHypothesis($text) || self::isNegated($text)
            || self::isActionRequest($text) || self::isStatusUpdateRequest($text)
            || preg_match(sprintf('~\b(?:%s)\b~iu', self::STATEMENT_WORDS), $text)) {
            return null;
        }

        $hasNoun = preg_match(sprintf('~\b(?:%s)\b~iu', self::REIMBURSEMENT_NOUN), $text) === 1;
        $status = self::requestedStatus($text);
        $number = self::referencedNumber($text);

        if ($status !== null && ($hasNoun || $number !== null)) {
            // Aggiornamento senza verbo: "rimborso #2 eseguito", "il rimborso della cena approvato".
            $verb = ['EXECUTED' => 'Segna come eseguito', 'APPROVED' => 'Approva', 'REJECTED' => 'Rifiuta'][$status];
            $keyword = self::referencedKeyword($text);
            $reference = $number !== null ? 'il rimborso #'.$number : ($keyword !== null ? 'il rimborso della '.$keyword : 'il rimborso #1');

            return self::hint($verb.' '.$reference);
        }

        if ($hasNoun && preg_match('/\d/', $text) === 1) {
            // Creazione senza verbo: "un rimborso per hotel da 120 euro".
            $parsed = self::extractAmountAndDescription($text);
            $suggestion = $parsed !== null
                ? 'Aggiungi un rimborso da '.self::friendlyAmount($parsed['amount']).' €'.($parsed['description'] !== null ? ' per '.$parsed['description'] : '')
                : 'Aggiungi un rimborso da 40 € per cena';

            return self::hint($suggestion);
        }

        return null;
    }

    // ---------------------------------------------------------------------------------------------------------
    //  Risposte che dichiarano un successo
    // ---------------------------------------------------------------------------------------------------------

    /**
     * La risposta DICHIARA che un rimborso è stato creato / registrato?
     *
     * Serve a smascherare una conferma FALSA: a volte il modello non chiama il tool ma SCRIVE a parole la chiamata
     * e il suo risultato inventato ("Lo strumento ha risposto: { id: RR123456 } ... registrato con successo").
     * Si usa SOLO quando la richiesta era un ordine e nulla è stato salvato: qualsiasi dichiarazione di successo è
     * per definizione falsa. Una risposta con una negazione ("non è stato creato...") è onesta.
     */
    public static function claimsCreation(string $answer): bool
    {
        return self::claims($answer, self::CLAIM_VERBS, '(?:rimbors\w*|richiest\w*)');
    }

    /** Come {@see self::claimsCreation()} ma per l'aggiornamento di stato ("rimborso approvato", "ho segnato..."). */
    public static function claimsUpdate(string $answer): bool
    {
        return self::claims($answer, self::UPDATE_CLAIM_VERBS, '(?:rimbors\w*|richiest\w*|stato)');
    }

    // ---------------------------------------------------------------------------------------------------------
    //  Dettagli
    // ---------------------------------------------------------------------------------------------------------

    private static function claims(string $answer, string $verbs, string $noun): bool
    {
        if (preg_match('~\bnon\b~iu', $answer)) {
            return false;
        }
        // Finta chiamata del tool scritta come testo, oppure identificativo inventato (es. RR123456).
        if (preg_match('~\bstrumento\s+ha\s+risposto\b|reimbursement_request_id~iu', $answer)
            || preg_match('~\b[A-Z]{1,4}-?\d{5,}\b~u', $answer)) {
            return true;
        }

        return preg_match(sprintf('~\b(?:%s)\b~iu', $verbs), $answer) === 1
            && preg_match(sprintf('~\b%s\b~iu', $noun), $answer) === 1;
    }

    private static function normalize(string $message): string
    {
        // Apostrofo tipografico -> apostrofo semplice, così "cos’è" e "cos'è" sono la stessa cosa.
        return trim(str_replace(['’', '‘'], "'", $message));
    }

    /** Domande informative, di permesso, ipotesi: mai un ordine, anche se contengono un verbo. */
    private static function isQuestionOrHypothesis(string $text): bool
    {
        $info = sprintf('~^(?:[\s,.;:!-]*\b(?:%s)\b)*[\s,.;:!-]*(?:%s)\b~iu', self::FILLERS, implode('|', self::INFO_STARTERS));

        return preg_match($info, $text) === 1;
    }

    /** Frasi negate: "non aprire nessun rimborso", "non approvare...", "don't create...". */
    private static function isNegated(string $text): bool
    {
        return preg_match(sprintf('~\bnon\s+(?:\w+\s+)?(?:%s|%s|%s)\b~iu', self::CREATION_VERBS, self::UPDATE_VERBS, self::AMBIGUOUS_VERBS), $text) === 1
            || preg_match("~\\b(?:don't|do not|never)\\b~iu", $text) === 1;
    }

    /** Un riferimento a un rimborso esistente o a un nuovo stato: "#2", "eseguito", "rimborso n. 2". */
    private static function hasUpdateMarker(string $text): bool
    {
        if (preg_match('~#\s*\d+~u', $text) === 1 || preg_match('~\brimbors\w*\s+(?:n\.?|nr\.?|numero)\s*\d+~iu', $text) === 1) {
            return true;
        }
        foreach (self::STATUS_WORDS as $pattern) {
            if (preg_match(sprintf('~\b(?:%s)\b~iu', $pattern), $text)) {
                return true;
            }
        }

        return false;
    }

    /** Il numero del rimborso citato: "#2" oppure "rimborso n. 2". */
    private static function referencedNumber(string $text): ?int
    {
        if (preg_match('~#\s*(\d+)~u', $text, $m) || preg_match('~\brimbors\w*\s+(?:n\.?|nr\.?|numero)\s*(\d+)~iu', $text, $m)) {
            return (int) $m[1];
        }

        return null;
    }

    /** Una parola della descrizione citata: "il rimborso della cena" -> "cena". */
    private static function referencedKeyword(string $text): ?string
    {
        if (preg_match("~\\brimbors\\w*\\s+(?:della|del|dello|dei|delle|di|per)\\s+(?:(?:la|il|lo|un|una)\\s+|l')?(\\p{L}{3,})~iu", $text, $m)) {
            return mb_strtolower($m[1]);
        }

        return null;
    }

    /**
     * Un solo importo positivo e la descrizione ricavata dal "per ...". Null se non c'è importo, se ce ne sono più
     * di uno ("40 o 50 euro") oppure se è negativo ("meno 5 euro", "-5").
     *
     * @return array{amount: string, description: ?string}|null
     */
    private static function extractAmountAndDescription(string $text): ?array
    {
        if (preg_match('~(?:\bmeno\b|-)\s*\d~iu', $text)) {
            return null;
        }

        preg_match_all('/\d+(?:[.,]\d{1,2})?/', $text, $numbers);
        $amounts = array_values(array_unique(array_map(
            fn (string $n) => number_format((float) str_replace(',', '.', $n), 2, '.', ''),
            $numbers[0],
        )));
        if (count($amounts) !== 1) {
            return null;
        }

        // Descrizione: l'ultimo "per ..." che precede l'importo o la fine ("per taxi", "per hotel da 120 euro").
        $description = null;
        preg_match_all(
            "~\\bper\\s+(?:(?:il|lo|la|un|una|uno|i|le|gli)\\s+|l')?(.+?)(?=\\s+(?:da|di)\\s+\\d|\\s+\\d|\$)~iu",
            $text,
            $matches,
        );
        if ($matches[1] !== []) {
            $candidate = trim((string) preg_replace('~[\s.,;:!?€]+$~u', '', end($matches[1])));
            // "per favore / per piacere" è una cortesia, non un motivo di spesa.
            if ($candidate !== '' && ! preg_match('~^(?:favore|piacere|cortesia)\b~iu', $candidate)) {
                $description = mb_substr($candidate, 0, 100);
            }
        }

        return ['amount' => $amounts[0], 'description' => $description];
    }

    /** "120.00" -> "120", "15.50" -> "15,50" (per l'esempio mostrato all'utente). */
    private static function friendlyAmount(string $amount): string
    {
        return str_ends_with($amount, '.00') ? substr($amount, 0, -3) : str_replace('.', ',', $amount);
    }

    /** @return array{warning: string, answer: string} */
    private static function hint(string $suggestion): array
    {
        return [
            'warning' => self::HINT_WARNING,
            'answer' => 'Non ho eseguito nessuna azione perché nel messaggio manca un verbo esplicito. '
                .'Per creare un rimborso usa apri, aggiungi, crea, inserisci, registra o richiedi; '
                .'per aggiornarlo usa segna, approva, rifiuta, esegui, aggiorna o chiudi. '
                ."Prova così: «{$suggestion}».",
        ];
    }
}
