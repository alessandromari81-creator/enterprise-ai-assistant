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
