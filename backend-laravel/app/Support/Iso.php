<?php

namespace App\Support;

use Carbon\CarbonImmutable;
use DateTimeInterface;

/**
 * Formattazione delle date come richiede il contratto: ISO-8601 in UTC, senza frazioni
 * di secondo (es. "2026-11-25T10:15:30Z"). Il formato JSON predefinito di Laravel
 * aggiungerebbe i microsecondi, e Spring Boot no: la differenza si vedrebbe nel frontend.
 */
final class Iso
{
    public static function utc(?DateTimeInterface $date): ?string
    {
        if ($date === null) {
            return null;
        }

        return CarbonImmutable::instance($date)->utc()->format('Y-m-d\TH:i:s\Z');
    }
}
