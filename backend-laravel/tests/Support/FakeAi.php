<?php

namespace Tests\Support;

use Closure;
use Illuminate\Http\Client\Factory;
use Illuminate\Http\Client\Request;
use Illuminate\Support\Facades\Http;

/**
 * Simula Ollama (embedding e chat) per i test: NESSUNA chiamata di rete reale.
 *
 * Gli embedding finti sono "bag of words hashing": ogni parola incrementa una delle 768 componenti
 * (indice = crc32(parola) % 768). Due testi con parole in comune hanno quindi similarità coseno alta,
 * quanto basta per testare il retrieval vettoriale in modo deterministico, ma su pgvector vero.
 */
final class FakeAi
{
    public const OLLAMA = 'http://ollama.test';

    /** @return list<float> */
    public static function vector(string $text): array
    {
        $vector = array_fill(0, 768, 0.0);

        foreach (preg_split('/[^\p{L}\p{N}]+/u', mb_strtolower($text), -1, PREG_SPLIT_NO_EMPTY) as $word) {
            $vector[crc32($word) % 768] += 1.0;
        }

        if (array_sum($vector) === 0.0) {
            $vector[0] = 1.0; // il vettore nullo non ha direzione: il coseno non sarebbe definito
        }

        return $vector;
    }

    /** Risposta di /api/embed: un vettore per ogni testo in input. */
    public static function embeddings(): Closure
    {
        return fn (Request $request) => Http::response([
            'model' => 'nomic-embed-text',
            'embeddings' => array_map(self::vector(...), $request->data()['input']),
            'prompt_eval_count' => 1,
        ]);
    }

    /** @return array<string, mixed> corpo di una risposta /api/chat con solo testo */
    public static function chatText(string $text): array
    {
        return [
            'model' => 'llama3.2',
            'message' => ['role' => 'assistant', 'content' => $text],
            'done' => true,
            'done_reason' => 'stop',
            'prompt_eval_count' => 1,
            'eval_count' => 1,
        ];
    }

    /**
     * Risposta /api/chat in cui il modello chiede di eseguire un tool.
     *
     * @param  array<string, mixed>  $arguments
     * @return array<string, mixed>
     */
    public static function chatToolCall(string $tool, array $arguments): array
    {
        return [
            'model' => 'llama3.2',
            'message' => [
                'role' => 'assistant',
                'content' => '',
                'tool_calls' => [['function' => ['name' => $tool, 'arguments' => $arguments]]],
            ],
            'done' => true,
            'done_reason' => 'stop',
            'prompt_eval_count' => 1,
            'eval_count' => 1,
        ];
    }

    /**
     * Registra i fake: embedding sempre disponibili, chat con la sequenza di risposte indicata.
     *
     * Parte sempre da un client HTTP "pulito": Http::fake() chiamato due volte ACCODA le regole e
     * vince la prima che corrisponde, quindi un fake precedente nasconderebbe quello nuovo.
     * Come effetto collaterale Http::assertSent() vede solo le richieste fatte dopo questa chiamata.
     */
    public static function fake(array $chatResponses = []): void
    {
        self::reset();

        $stubs = [self::OLLAMA.'/api/embed' => self::embeddings()];

        if ($chatResponses !== []) {
            $sequence = Http::sequence();
            foreach ($chatResponses as $body) {
                $sequence->push($body);
            }
            $stubs[self::OLLAMA.'/api/chat'] = $sequence;
        }

        Http::fake($stubs);
    }

    /** Embedding disponibili, ma la chat risponde con un errore HTTP (modello non disponibile). */
    public static function fakeChatDown(): void
    {
        self::reset();
        Http::fake([
            self::OLLAMA.'/api/embed' => self::embeddings(),
            self::OLLAMA.'/api/chat' => Http::response('boom', 500),
        ]);
    }

    /** Il servizio di embedding risponde con un errore HTTP. */
    public static function fakeEmbeddingDown(): void
    {
        self::reset();
        Http::fake([self::OLLAMA.'/api/embed' => Http::response('boom', 500)]);
    }

    private static function reset(): void
    {
        app()->forgetInstance(Factory::class);
        Http::clearResolvedInstances();
    }
}
