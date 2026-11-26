<?php

namespace Tests;

use Illuminate\Foundation\Testing\TestCase as BaseTestCase;
use RuntimeException;

abstract class TestCase extends BaseTestCase
{
    /**
     * Protezione: i test con RefreshDatabase eseguono "migrate:fresh", che CANCELLA tutte le tabelle.
     * Si rifiuta di partire se il database non è quello di test (nome che finisce con "_test"),
     * per non distruggere per errore i dati di sviluppo.
     */
    protected function setUpTraits(): array
    {
        $connection = config('database.default');
        $database = (string) config("database.connections.{$connection}.database");

        if (! str_ends_with($database, '_test')) {
            throw new RuntimeException(
                "Rifiuto di eseguire i test sul database \"{$database}\": deve terminare con \"_test\" (es. eaa_test)."
            );
        }

        return parent::setUpTraits();
    }
}
