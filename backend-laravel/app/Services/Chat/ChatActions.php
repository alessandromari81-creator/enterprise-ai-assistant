<?php

namespace App\Services\Chat;

use App\Enums\ReimbursementStatus;
use App\Models\ReimbursementRequest;

/**
 * Forma delle azioni restituite in `actions[]` della chat (schema Action del contratto) e frasi di conferma.
 * Una sola definizione, usata dai tool e dalle reti di sicurezza di ChatService.
 */
final class ChatActions
{
    public const CREATED = 'REIMBURSEMENT_CREATED';

    public const UPDATED = 'REIMBURSEMENT_UPDATED';

    /** @return array<string, mixed> */
    public static function created(ReimbursementRequest $r): array
    {
        return self::build(self::CREATED, $r);
    }

    /** @return array<string, mixed> */
    public static function updated(ReimbursementRequest $r): array
    {
        return self::build(self::UPDATED, $r);
    }

    /**
     * Conferma costruita dal codice con i dati realmente salvati.
     *
     * @param  array<string, mixed>  $action
     */
    public static function confirmation(array $action): string
    {
        $amount = number_format((float) $action['amount'], 2, ',', '.');

        if ($action['type'] === self::UPDATED) {
            return sprintf(
                'Ho aggiornato il rimborso #%d (%s, %s %s): ora è «%s».',
                $action['number'],
                $action['description'] ?? 'senza descrizione',
                $amount,
                $action['currency'],
                ReimbursementStatus::from($action['status'])->label(),
            );
        }

        return sprintf(
            'Ho aperto la richiesta di rimborso #%d di %s %s%s. Identificativo: %s. Resta in stato OPEN in attesa di approvazione.',
            $action['number'],
            $amount,
            $action['currency'],
            $action['description'] !== null ? " ({$action['description']})" : '',
            $action['reimbursementId'],
        );
    }

    /** @return array<string, mixed> */
    private static function build(string $type, ReimbursementRequest $r): array
    {
        return [
            'type' => $type,
            'reimbursementId' => $r->id,
            'number' => (int) $r->number,
            'amount' => $r->amount,
            'currency' => $r->currency,
            'description' => $r->description,
            'status' => $r->status->value,
        ];
    }
}
