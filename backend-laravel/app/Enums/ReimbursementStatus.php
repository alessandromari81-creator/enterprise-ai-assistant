<?php

namespace App\Enums;

/** Stato di una richiesta di rimborso. Le richieste nascono sempre OPEN: l'approvazione è umana. */
enum ReimbursementStatus: string
{
    case Open = 'OPEN';
    case Approved = 'APPROVED';
    case Rejected = 'REJECTED';
}
