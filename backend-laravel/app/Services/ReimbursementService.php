<?php

namespace App\Services;

use App\Enums\ReimbursementStatus;
use App\Models\ReimbursementRequest;
use InvalidArgumentException;

/**
 * Logica di business dei rimborsi. È l'UNICO punto che scrive su reimbursement_requests:
 * il tool dell'IA e (in futuro) un eventuale endpoint manuale passano da qui.
 *
 * Principio di sicurezza: l'IA PROPONE, il codice DECIDE. L'importo proposto dal modello
 * viene validato qui (positivo, entro il tetto) e, in ultima istanza, dal CHECK del database.
 */
class ReimbursementService
{
    /**
     * @param  mixed  $amount  valore grezzo dal modello (numero o stringa)
     *
     * @throws InvalidArgumentException con un messaggio leggibile dal modello/utente
     */
    public function create(mixed $amount, ?string $description, ?string $conversationId): ReimbursementRequest
    {
        $normalized = $this->normalizeAmount($amount);

        $description = $description !== null ? trim($description) : null;

        return ReimbursementRequest::query()->create([
            'amount' => $normalized,
            'currency' => config('eaa.reimbursement.currency'),
            'description' => $description !== '' ? mb_substr((string) $description, 0, 500) : null,
            'status' => ReimbursementStatus::Open, // sempre OPEN: l'approvazione è umana
            'requested_by' => config('eaa.reimbursement.default_user'),
            'source_conversation_id' => $conversationId,
        ])->refresh(); // refresh: rilegge dal DB l'importo con 2 decimali esatti
    }

    /**
     * Aggiorna lo stato di un rimborso ESISTENTE, indicato dal numero (#2) oppure da una parola della descrizione.
     * È l'UNICO punto che modifica lo stato: il tool updateReimbursementStatus passa da qui.
     *
     * Regole: un rimborso già nello stato richiesto non si "aggiorna"; EXECUTED e REJECTED sono stati finali.
     *
     * @throws InvalidArgumentException con un messaggio leggibile dal modello e dall'utente
     */
    public function updateStatus(?int $number, ?string $keyword, ReimbursementStatus $newStatus): ReimbursementRequest
    {
        $target = $this->findTarget($number, $keyword);

        if ($target->status === $newStatus) {
            throw new InvalidArgumentException(sprintf('Il rimborso #%d è già «%s».', $target->number, $newStatus->label()));
        }
        if ($target->status->isFinal()) {
            throw new InvalidArgumentException(sprintf(
                'Il rimborso #%d è già «%s» e non può più essere modificato.',
                $target->number,
                $target->status->label(),
            ));
        }

        $target->update(['status' => $newStatus]);

        return $target->refresh();
    }

    /** Trova il rimborso per numero o per parola della descrizione (una sola corrispondenza, altrimenti errore). */
    private function findTarget(?int $number, ?string $keyword): ReimbursementRequest
    {
        if ($number !== null) {
            return ReimbursementRequest::query()->where('number', $number)->first()
                ?? throw new InvalidArgumentException("Non esiste nessun rimborso con numero {$number}.");
        }

        $keyword = trim((string) $keyword);
        if ($keyword === '') {
            throw new InvalidArgumentException('Indica il numero del rimborso (quello dopo il #) oppure una parola della sua descrizione.');
        }

        $matches = ReimbursementRequest::query()
            ->where('description', 'ilike', '%'.addcslashes($keyword, '\\%_').'%')
            ->orderBy('number')
            ->get();

        if ($matches->isEmpty()) {
            throw new InvalidArgumentException("Nessun rimborso ha «{$keyword}» nella descrizione.");
        }
        if ($matches->count() > 1) {
            throw new InvalidArgumentException(sprintf(
                'Più rimborsi corrispondono a «%s»: %s. Indica il numero del rimborso.',
                $keyword,
                '#'.$matches->pluck('number')->implode(', #'),
            ));
        }

        return $matches->first();
    }

    /**
     * Converte l'importo in stringa decimale "50.00" evitando errori di virgola mobile.
     * Accetta anche "50,5" (virgola decimale italiana) e il simbolo €.
     */
    private function normalizeAmount(mixed $amount): string
    {
        if (is_string($amount)) {
            $amount = str_replace([' ', '€', 'EUR', 'eur'], '', $amount);
            $amount = str_replace(',', '.', $amount);
        }

        if (! is_numeric($amount)) {
            throw new InvalidArgumentException('L\'importo non è un numero valido.');
        }

        $value = round((float) $amount, 2);
        $max = (float) config('eaa.reimbursement.max_amount');

        if ($value <= 0) {
            throw new InvalidArgumentException('L\'importo deve essere maggiore di zero.');
        }
        if ($value > $max) {
            throw new InvalidArgumentException(sprintf(
                'L\'importo supera il massimo consentito di %s %s per richiesta.',
                number_format($max, 2, '.', ''),
                config('eaa.reimbursement.currency'),
            ));
        }

        return number_format($value, 2, '.', '');
    }
}
