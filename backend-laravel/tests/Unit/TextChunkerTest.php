<?php

namespace Tests\Unit;

use App\Services\Rag\TextChunker;
use InvalidArgumentException;
use PHPUnit\Framework\TestCase;

class TextChunkerTest extends TestCase
{
    public function test_testo_vuoto_non_produce_chunk(): void
    {
        $this->assertSame([], (new TextChunker(200, 40))->split("  \n "));
    }

    public function test_testo_breve_resta_un_solo_chunk(): void
    {
        $this->assertSame(['Il taxi è rimborsabile.'], (new TextChunker(200, 40))->split('Il taxi è rimborsabile.'));
    }

    public function test_testo_lungo_rispetta_la_dimensione_massima_e_non_perde_parole(): void
    {
        $words = array_map(fn ($i) => "w{$i}", range(1, 400));
        $chunks = (new TextChunker(200, 40))->split(implode(' ', $words));

        $this->assertGreaterThan(1, count($chunks));

        $seen = [];
        foreach ($chunks as $chunk) {
            $this->assertLessThanOrEqual(200, mb_strlen($chunk));
            foreach (explode(' ', $chunk) as $token) {
                // Nessun chunk inizia o finisce a metà parola: ogni token è una parola intera.
                $this->assertMatchesRegularExpression('/^w\d+$/', $token);
                $seen[$token] = true;
            }
        }

        // Copertura completa: ogni parola del testo originale compare in almeno un chunk.
        foreach ($words as $word) {
            $this->assertArrayHasKey($word, $seen, "La parola {$word} è andata persa");
        }
    }

    public function test_i_chunk_consecutivi_si_sovrappongono(): void
    {
        $words = array_map(fn ($i) => "w{$i}", range(1, 400));
        $chunks = (new TextChunker(200, 40))->split(implode(' ', $words));

        for ($i = 0; $i < count($chunks) - 1; $i++) {
            $firstOfNext = explode(' ', $chunks[$i + 1])[0];
            $this->assertStringContainsString($firstOfNext, $chunks[$i], 'Il chunk successivo deve ripartire da testo già presente nel precedente');
        }
    }

    public function test_testo_senza_spazi_termina_e_viene_coperto_per_intero(): void
    {
        $text = str_repeat('a', 1000);
        $chunks = (new TextChunker(200, 40))->split($text);

        $this->assertGreaterThan(1, count($chunks));
        foreach ($chunks as $chunk) {
            $this->assertLessThanOrEqual(200, mb_strlen($chunk));
        }
        // L'ultimo chunk arriva fino alla fine del testo.
        $this->assertStringEndsWith('a', end($chunks));
        $this->assertGreaterThanOrEqual(1000, array_sum(array_map('mb_strlen', $chunks)));
    }

    public function test_preferisce_tagliare_a_fine_frase(): void
    {
        $sentence = 'Questa è una frase di prova abbastanza lunga. ';
        $chunks = (new TextChunker(200, 40))->split(str_repeat($sentence, 20));

        // Il primo chunk finisce con un punto: non a metà frase.
        $this->assertStringEndsWith('.', $chunks[0]);
    }

    public function test_parametri_non_validi_vengono_rifiutati(): void
    {
        $this->expectException(InvalidArgumentException::class);
        new TextChunker(200, 150); // overlap >= metà del chunk: il chunker non avanzerebbe
    }
}
