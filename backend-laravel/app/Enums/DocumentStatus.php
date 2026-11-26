<?php

namespace App\Enums;

/** Stato di indicizzazione di un documento (colonna documents.status). */
enum DocumentStatus: string
{
    case Processing = 'PROCESSING';
    case Ready = 'READY';
    case Failed = 'FAILED';
}
