<?php

namespace App\Services\Chat;

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
 * La logica è IDENTICA nel backend Spring Boot (ReimbursementIntent.java): le due implementazioni hanno la
 * stessa tabella di frasi nei rispettivi test. Se si modifica una, va modificata anche l'altra.
 *
 * Questa è solo la barriera sull'INTENTO. L'importo scritto dall'utente è la barriera 2 (ReimbursementTool).
 */
final class ReimbursementIntent
{
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
        'se\s+(?:io\s+)?(?:apro|apriamo|aprissi|creo|creassi|aggiungo|aggiungessi|inserisco|registro|faccio|richiedo|invio|metto|presento|posso|devo)',
        'nel caso', 'supponiamo', 'ipotizziamo', 'mettiamo che',
        'can i', 'may i', 'could i', 'should i', 'what', 'which', 'how', 'where', 'when', 'why', 'who', 'is there', 'are there', 'do i', 'does', 'is it',
    ];

    /** Verbi naturali con cui si chiede di creare un rimborso (italiano e inglese). */
    private const CREATION_VERBS =
        'apr(?:i|ire|imi|irmi|ite|ila|ilo|ine|iamo)|aggiung(?:i|ere|imi|ermi|ine|ete|iamo)|cre(?:a|are|ami|armi|ate|ane|iamo)'
        .'|inserisc(?:i|imi|ine|ite)|inser(?:ire|irmi)|registr(?:a|are|ami|armi|ate)|richied(?:i|ere|imi|erne)'
        .'|invi(?:a|are|ami|armi|ate)|fai|fare|fammi|farmi|facci|fate|fatemi|mett(?:i|ere|imi|ete)|segn(?:a|are|ami)'
        .'|salv(?:a|are|ami)|gener(?:a|are)|inoltr(?:a|are)|present(?:a|are)|predispon(?:i|ere)|prepar(?:a|are)'
        .'|compil(?:a|are)|emett(?:i|ere)|open|add|create|submit|file|register|record|raise|request|enter|send';

    private const REIMBURSEMENT_NOUN = 'rimbors\w*|refund\w*|reimburse\w*|nota spese|note spese';

    /** Parole di una frase che AFFERMA o descrive (non ordina): "il rimborso hotel è di 150 euro". */
    private const STATEMENT_WORDS = 'è|sono|vale|valgono|era|erano|sarà|prevede|prevedono|previsto|massimo|massima|massimale|limite|limiti|tetto|fino a|al massimo';

    public static function isActionRequest(string $message): bool
    {
        // Apostrofo tipografico -> apostrofo semplice, così "cos’è" e "cos'è" sono la stessa cosa.
        $text = trim(str_replace(['’', '‘'], "'", $message));
        if ($text === '') {
            return false;
        }

        // 1. Domande informative, di permesso, ipotesi: mai un ordine, anche se contengono un verbo di creazione.
        $info = sprintf('~^(?:[\s,.;:!-]*\b(?:%s)\b)*[\s,.;:!-]*(?:%s)\b~iu', self::FILLERS, implode('|', self::INFO_STARTERS));
        if (preg_match($info, $text)) {
            return false;
        }

        // 2. Frasi negate: "non aprire nessun rimborso", "don't create...".
        if (preg_match(sprintf('~\bnon\s+(?:\w+\s+)?(?:%s)\b~iu', self::CREATION_VERBS), $text)
            || preg_match("~\\b(?:don't|do not|never)\\b~iu", $text)) {
            return false;
        }

        // 3. Un verbo naturale di creazione.
        if (preg_match(sprintf('~\b(?:%s)\b~iu', self::CREATION_VERBS), $text)) {
            return true;
        }

        // 4. Forma breve imperativa senza verbo: rimborso + importo, senza punto interrogativo e senza
        //    parole da affermazione ("un rimborso per hotel da 120 euro", "rimborso taxi 20 euro").
        return ! str_contains($text, '?')
            && preg_match(sprintf('~\b(?:%s)\b~iu', self::REIMBURSEMENT_NOUN), $text) === 1
            && preg_match('/\d/', $text) === 1
            && preg_match(sprintf('~\b(?:%s)\b~iu', self::STATEMENT_WORDS), $text) !== 1;
    }

    /** Verbi (participi e prima persona) con cui una risposta DICHIARA che un rimborso è stato creato. */
    private const CLAIM_VERBS = '(?:creat|registrat|inserit|aggiunt|salvat|emess)[oaie]|apert[oaie]|creo|apro|registro|inserisco|aggiungo';

    /**
     * La risposta DICHIARA che un rimborso è stato creato / registrato?
     *
     * Serve a smascherare una conferma FALSA: provato dal vivo, a volte il modello non chiama il tool ma SCRIVE
     * a parole la chiamata e il suo risultato inventato ("Lo strumento ha risposto: { id: RR123456 } ...
     * rimborso registrato con successo"), e all'utente arriva un'azione mai avvenuta. Si usa SOLO quando la
     * richiesta era un ordine e nessun rimborso è stato salvato: in quel caso qualsiasi dichiarazione di
     * successo è per definizione falsa. Una risposta con una negazione ("non è stato creato...") è onesta.
     */
    public static function claimsCreation(string $answer): bool
    {
        if (preg_match('~\bnon\b~iu', $answer)) {
            return false;
        }

        // Finta chiamata del tool scritta come testo, oppure identificativo inventato (es. RR123456).
        if (preg_match('~\bstrumento\s+ha\s+risposto\b|reimbursement_request_id~iu', $answer)
            || preg_match('~\b[A-Z]{1,4}-?\d{5,}\b~u', $answer)) {
            return true;
        }

        return preg_match(sprintf('~\b(?:%s)\b~iu', self::CLAIM_VERBS), $answer) === 1
            && preg_match('~\b(?:rimbors\w*|richiest\w*)\b~iu', $answer) === 1;
    }

    /**
     * Ordine DIRETTO e non ambiguo: l'ultimo messaggio è una richiesta di creare un rimborso e contiene UN SOLO
     * importo (positivo). Restituisce importo (stringa decimale "20.00") e descrizione ricavata dal "per ...".
     *
     * Serve alla rete di sicurezza di ChatService: se il modello non chiama il tool (con le frasi brevi senza
     * verbo, tipo "un rimborso per hotel da 120 euro", un modello piccolo a volte NARRA la chiamata invece di
     * farla), il backend crea lui il rimborso passando dallo stesso ReimbursementService e dalle stesse
     * barriere. L'importo è per costruzione quello scritto dall'utente.
     *
     * Null (= nessun fallback) se: non è un ordine, non c'è importo, ce ne sono più di uno ("40 o 50 euro"),
     * oppure è negativo ("meno 5 euro", "-5").
     *
     * @return array{amount: string, description: ?string}|null
     */
    public static function parseDirectRequest(string $message): ?array
    {
        $text = trim(str_replace(['’', '‘'], "'", $message));
        if (! self::isActionRequest($text) || preg_match('~(?:\bmeno\b|-)\s*\d~iu', $text)) {
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
            "~\bper\s+(?:(?:il|lo|la|un|una|uno|i|le|gli)\s+|l')?(.+?)(?=\s+(?:da|di)\s+\d|\s+\d|\$)~iu",
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
}
