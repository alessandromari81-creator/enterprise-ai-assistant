<?php

namespace App\Services\Chat;

use App\Models\ReimbursementRequest;
use App\Services\ReimbursementService;
use InvalidArgumentException;
use Prism\Prism\Tool;

/**
 * Il TOOL "createReimbursementRequest" esposto al modello (Function / Tool Calling).
 *
 * Come funziona il tool calling con Prism:
 *  1. Prism manda al modello nome, descrizione e parametri del tool (schema JSON).
 *  2. Se l'utente chiede un rimborso, il modello NON risponde a parole ma restituisce
 *     "voglio chiamare createReimbursementRequest con amount=50".
 *  3. Prism esegue la closure qui sotto (codice nostro, con validazione e INSERT).
 *  4. La stringa restituita torna al modello, che la usa per scrivere la risposta finale.
 *
 * Il modello non scrive mai direttamente nel database: passa sempre da ReimbursementService.
 */
class ReimbursementTool
{
    /** @var list<array<string, mixed>> */
    private array $actions = [];

    /**
     * @param  list<string>  $userTexts  i messaggi DELL'UTENTE (il corrente e quelli precedenti della
     *                                   conversazione): servono a verificare che l'importo non sia inventato
     * @param  string  $currentMessage  l'ultimo messaggio dell'utente: deve chiedere esplicitamente l'azione
     */
    public function __construct(
        private readonly ReimbursementService $service,
        private readonly string $conversationId,
        private readonly array $userTexts = [],
        private readonly string $currentMessage = '',
    ) {}

    /** Costruisce il Tool da passare a Prism::text()->withTools([...]). */
    public function build(): Tool
    {
        return (new Tool)
            ->as('createReimbursementRequest')
            ->for(
                'Apre una richiesta di rimborso spese per l\'utente. '
                .'Da usare quando l\'utente ordina con un verbo esplicito di aprire, aggiungere, creare, inserire o registrare '
                .'una richiesta di rimborso e ha indicato l\'importo. '
                .'Non va usato per domande sui limiti, sui permessi o ipotetiche, né per aggiornare un rimborso esistente.'
            )
            ->withNumberParameter('amount', 'Importo in euro, numero positivo (es. 50 oppure 12.5).')
            // Niente esempi nella descrizione dei parametri: i modelli piccoli li copiano (provato dal
            // vivo: "es. cena col cliente" finiva nel database anche se l'utente non lo aveva scritto).
            ->withStringParameter('description', 'Motivo della spesa, con le parole dell\'utente. Ometti questo parametro se l\'utente non lo indica.', required: false)
            // `...$ignored` assorbe eventuali argomenti inventati dal modello senza andare in errore.
            ->using(function (mixed $amount, ?string $description = null, mixed ...$ignored): string {
                return $this->execute($amount, $description);
            });
    }

    /**
     * Azioni eseguite durante la richiesta: finiscono in `actions[]` della risposta della chat.
     *
     * @return list<array<string, mixed>>
     */
    public function actions(): array
    {
        return $this->actions;
    }

    /** Il risultato testuale viene letto dal modello: deve dire chiaramente cos'è successo. */
    private function execute(mixed $amount, ?string $description): string
    {
        // Barriera 1 — intento: l'ultimo messaggio deve CHIEDERE l'azione, non solo parlarne. Accetta i verbi
        // naturali di creazione (apri, aggiungi, crea, inserisci, registra, richiedi...) e la forma breve
        // imperativa con importo; blocca domande informative, di permesso e ipotesi (vedi ReimbursementIntent).
        // Provato dal vivo: un modello piccolo aprì un rimborso da 200 € in risposta alla domanda
        // "Posso farmi rimborsare 200€ per una cena?". Un'azione con effetti non si esegue su una domanda.
        if (! ReimbursementIntent::isActionRequest($this->currentMessage)) {
            return 'ERRORE: l\'ultimo messaggio dell\'utente non chiede di aprire una richiesta di rimborso (è una domanda). '
                .'Nessuna richiesta è stata creata. Rispondi alla domanda usando gli estratti, senza strumenti.';
        }

        // Barriera 2 — anti-allucinazione, indipendente dal modello: l'importo deve averlo scritto
        // l'UTENTE. Provato dal vivo: llama3.2 (3B) ha aperto un rimborso da 50 € a una semplice
        // domanda sul limite delle cene, prendendo la cifra dai documenti. Una richiesta di
        // rimborso senza importo esplicito non si esegue mai: si chiede all'utente.
        if (! $this->amountWrittenByUser($amount)) {
            return 'ERRORE: l\'importo indicato non compare nei messaggi dell\'utente. Nessuna richiesta è stata creata. '
                .'Se l\'utente vuole un rimborso, chiedigli di scrivere l\'importo; altrimenti rispondi alla sua domanda senza strumenti.';
        }

        try {
            $request = $this->service->create($amount, $description, $this->conversationId);
        } catch (InvalidArgumentException $e) {
            // Errore "atteso" (importo non valido): lo si restituisce al modello, che lo spiega all'utente.
            return 'ERRORE: '.$e->getMessage().' Nessuna richiesta è stata creata. Chiedi all\'utente un importo valido.';
        }

        $this->actions[] = [
            'type' => 'REIMBURSEMENT_CREATED',
            'reimbursementId' => $request->id,
            'number' => (int) $request->number,
            'amount' => $request->amount,
            'currency' => $request->currency,
            'description' => $request->description,
            'status' => $request->status->value,
        ];

        return $this->successMessage($request);
    }

    /**
     * L'importo proposto dal modello coincide con un numero scritto dall'utente?
     * Riconosce "50", "50€", "12,5", "12.50". Cifre scritte a parole ("cinquanta") non valgono:
     * in quel caso il modello chiede di scrivere l'importo in cifre (scelta prudente, D21).
     */
    private function amountWrittenByUser(mixed $amount): bool
    {
        $amount = is_string($amount) ? str_replace(',', '.', preg_replace('/[^\d,.\-]/', '', $amount)) : $amount;
        if (! is_numeric($amount)) {
            return false; // non è nemmeno un numero: non può essere stato scritto dall'utente
        }

        foreach ($this->userTexts as $text) {
            preg_match_all('/\d+(?:[.,]\d{1,2})?/', $text, $matches);
            foreach ($matches[0] as $number) {
                if (abs((float) str_replace(',', '.', $number) - (float) $amount) < 0.005) {
                    return true;
                }
            }
        }

        return false;
    }

    private function successMessage(ReimbursementRequest $request): string
    {
        return sprintf(
            'OK: richiesta di rimborso creata. id=%s, importo=%s %s, stato=%s.',
            $request->id,
            $request->amount,
            $request->currency,
            $request->status->value,
        );
    }
}
