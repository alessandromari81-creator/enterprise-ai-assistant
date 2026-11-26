<?php

namespace App\Services\Chat;

use App\Enums\ReimbursementStatus;
use App\Services\ReimbursementService;
use InvalidArgumentException;
use Prism\Prism\Tool;

/**
 * Il TOOL "updateReimbursementStatus": aggiorna lo stato di un rimborso già esistente (Approvato, Rifiutato,
 * Eseguito) su ordine esplicito dell'utente ("Segna come eseguito il rimborso #2", "Approva il rimborso della cena").
 *
 * Stesso principio dei guardrail di createReimbursementRequest: il modello PROPONE, il codice DECIDE.
 *  1. Intento: il messaggio deve ORDINARE l'aggiornamento con un verbo esplicito (segna, approva, rifiuta, esegui,
 *     aggiorna, chiudi...). Una domanda o un'ipotesi non si esegue mai.
 *  2. Stato: lo decide il TESTO DELL'UTENTE (requestedStatus), non il modello. Se non è chiaro o è ambiguo
 *     ("approva e poi esegui") non si fa nulla e si chiede all'utente.
 *  3. Riferimento: il numero (#2) o la parola della descrizione devono comparire nel messaggio dell'utente: il
 *     modello non può sceglierli o inventarli.
 * Poi ReimbursementService::updateStatus applica le regole di business (stati finali, rimborso inesistente,
 * più corrispondenze) e il CHECK del database fa da ultima difesa.
 */
class ReimbursementStatusTool
{
    /** @var list<array<string, mixed>> */
    private array $actions = [];

    public function __construct(
        private readonly ReimbursementService $service,
        private readonly string $currentMessage = '',
    ) {}

    /** Costruisce il Tool da passare a Prism::text()->withTools([...]). */
    public function build(): Tool
    {
        return (new Tool)
            ->as('updateReimbursementStatus')
            ->for(
                'Aggiorna lo stato di un rimborso già esistente (approvato, rifiutato o eseguito). '
                .'Da usare quando l\'utente ordina di segnare, approvare, rifiutare, eseguire, aggiornare o chiudere un rimborso, '
                .'indicandolo con il numero (quello dopo il #) oppure con una parola della sua descrizione. '
                .'Non va usato per domande.'
            )
            ->withStringParameter('status', 'Nuovo stato richiesto dall\'utente: APPROVED (approvato), REJECTED (rifiutato) oppure EXECUTED (eseguito, pagato, chiuso).')
            ->withNumberParameter('number', 'Numero del rimborso (quello dopo il #), solo se l\'utente lo ha scritto.', required: false)
            ->withStringParameter('keyword', 'Una parola della descrizione del rimborso, solo se l\'utente non ha indicato il numero.', required: false)
            // `...$ignored` assorbe eventuali argomenti inventati dal modello senza andare in errore.
            ->using(function (mixed $status = null, mixed $number = null, ?string $keyword = null, mixed ...$ignored): string {
                return $this->execute($number, $keyword);
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

    private function execute(mixed $number, ?string $keyword): string
    {
        // Barriera 1 — intento.
        if (! ReimbursementIntent::isStatusUpdateRequest($this->currentMessage)) {
            return 'ERRORE: l\'ultimo messaggio dell\'utente non ordina di aggiornare un rimborso (è una domanda). '
                .'Nessun rimborso è stato modificato. Rispondi alla domanda senza strumenti.';
        }

        // Barriera 2 — lo stato lo decide il testo dell'utente.
        $status = ReimbursementIntent::requestedStatus($this->currentMessage);
        if ($status === null) {
            return 'ERRORE: dal messaggio non si capisce quale stato impostare. Nessun rimborso è stato modificato. '
                .'Chiedi all\'utente se vuole approvare, rifiutare o segnare come eseguito il rimborso.';
        }

        // Barriera 3 — il riferimento al rimborso deve averlo scritto l'utente.
        $numberValue = is_numeric($number) ? (int) $number : null;
        $keywordValue = trim((string) $keyword);

        if ($numberValue !== null && ! $this->numberWrittenByUser($numberValue)) {
            return 'ERRORE: il numero indicato non compare nel messaggio dell\'utente. Nessun rimborso è stato modificato. '
                .'Chiedi all\'utente il numero del rimborso (quello dopo il #).';
        }
        if ($numberValue === null && $keywordValue !== '' && mb_stripos($this->currentMessage, $keywordValue) === false) {
            return 'ERRORE: la parola indicata non compare nel messaggio dell\'utente. Nessun rimborso è stato modificato. '
                .'Chiedi all\'utente il numero del rimborso (quello dopo il #).';
        }
        if ($numberValue === null && $keywordValue === '') {
            return 'ERRORE: l\'utente non ha indicato quale rimborso. Nessun rimborso è stato modificato. '
                .'Chiedi il numero (quello dopo il #) oppure una parola della descrizione.';
        }

        try {
            $request = $this->service->updateStatus($numberValue, $numberValue === null ? $keywordValue : null, ReimbursementStatus::from($status));
        } catch (InvalidArgumentException $e) {
            // Errore "atteso" (inesistente, ambiguo, stato finale...): lo si restituisce al modello, che lo spiega.
            return 'ERRORE: '.$e->getMessage().' Nessun rimborso è stato modificato.';
        }

        $this->actions[] = ChatActions::updated($request);

        return sprintf('OK: rimborso #%d aggiornato, ora è «%s».', $request->number, $request->status->label());
    }

    private function numberWrittenByUser(int $number): bool
    {
        preg_match_all('/\d+/', $this->currentMessage, $found);

        return in_array((string) $number, array_map(fn (string $n) => (string) (int) $n, $found[0]), true);
    }
}
