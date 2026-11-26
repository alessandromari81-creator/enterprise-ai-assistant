<?php

namespace App\Enums;

/**
 * Stato di una richiesta di rimborso. Le richieste nascono sempre OPEN: l'approvazione è umana.
 * EXECUTED ("Eseguito") e REJECTED sono stati finali.
 */
enum ReimbursementStatus: string
{
    case Open = 'OPEN';
    case Approved = 'APPROVED';
    case Rejected = 'REJECTED';
    case Executed = 'EXECUTED';

    /** Etichetta italiana, la stessa che mostra il frontend. */
    public function label(): string
    {
        return match ($this) {
            self::Open => 'In attesa',
            self::Approved => 'Approvato',
            self::Rejected => 'Rifiutato',
            self::Executed => 'Eseguito',
        };
    }

    /** Uno stato finale non si può più cambiare. */
    public function isFinal(): bool
    {
        return $this === self::Rejected || $this === self::Executed;
    }
}
