<?php

namespace Tests\Unit;

use App\Services\Chat\PromptBuilder;
use App\Services\Chat\SourceResolver;
use App\Services\Rag\RetrievedChunk;
use PHPUnit\Framework\TestCase;

class SourceResolverTest extends TestCase
{
    private function chunk(string $file, int $page): RetrievedChunk
    {
        return new RetrievedChunk("c-{$file}-{$page}", 'd-'.$file, $file, $page, 'testo', 0.9);
    }

    public function test_restituisce_solo_i_chunk_citati(): void
    {
        $retrieved = [$this->chunk('policy.pdf', 4), $this->chunk('policy.pdf', 5), $this->chunk('manuale.pdf', 1)];

        $sources = (new SourceResolver)->resolve('Il limite è 40 € [policy.pdf, p. 4].', $retrieved, false);

        $this->assertCount(1, $sources);
        $this->assertSame(4, $sources[0]->page);
    }

    public function test_riconosce_le_varianti_di_citazione(): void
    {
        $retrieved = [$this->chunk('policy.pdf', 4)];
        $resolver = new SourceResolver;

        foreach (['[policy.pdf, p. 4]', '[policy.pdf, pag. 4]', '[policy.pdf p.4]', '(policy.pdf, pagina 4)'] as $citation) {
            $this->assertCount(1, $resolver->resolve("Risposta {$citation}", $retrieved, false), $citation);
        }
    }

    public function test_pagina_14_non_conta_come_citazione_della_pagina_1(): void
    {
        $retrieved = [$this->chunk('policy.pdf', 1), $this->chunk('policy.pdf', 14)];

        $sources = (new SourceResolver)->resolve('Vedi [policy.pdf, p. 14].', $retrieved, false);

        $this->assertCount(1, $sources);
        $this->assertSame(14, $sources[0]->page);
    }

    public function test_senza_citazione_leggibile_restituisce_i_chunk_consultati(): void
    {
        $retrieved = [$this->chunk('policy.pdf', 4)];

        $this->assertCount(1, (new SourceResolver)->resolve('Il limite è 40 €.', $retrieved, false));
    }

    public function test_il_rifiuto_non_ha_fonti(): void
    {
        $retrieved = [$this->chunk('policy.pdf', 4)];

        $this->assertSame([], (new SourceResolver)->resolve(PromptBuilder::REFUSAL, $retrieved, false));
    }

    public function test_un_azione_eseguita_non_ha_fonti(): void
    {
        $retrieved = [$this->chunk('policy.pdf', 4)];

        $this->assertSame([], (new SourceResolver)->resolve('Ho aperto la richiesta.', $retrieved, true));
    }

    public function test_senza_chunk_recuperati_non_ci_sono_fonti(): void
    {
        $this->assertSame([], (new SourceResolver)->resolve('Qualsiasi cosa [policy.pdf, p. 4]', [], false));
    }
}
