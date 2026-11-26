<?php

namespace App\Services\Rag;

use App\Exceptions\ApiProblem;
use Illuminate\Http\Client\HttpClientException;
use Prism\Prism\Exceptions\PrismException;
use Prism\Prism\Facades\Prism;
use RuntimeException;

/**
 * Trasforma testo in vettori (embedding) tramite Prism.
 *
 * Prism espone la stessa API per Ollama e OpenAI: il provider si sceglie con AI_PROVIDER
 * in .env, il codice non cambia.
 */
class EmbeddingService
{
    /**
     * @param  list<string>  $texts
     * @return list<list<float>> un vettore per testo, nello stesso ordine
     *
     * @throws ApiProblem 502 se il provider non risponde
     */
    public function embedMany(array $texts): array
    {
        $vectors = [];

        // Si manda un lotto per volta: meno chiamate HTTP, ma richieste di dimensione contenuta.
        foreach (array_chunk($texts, (int) config('eaa.ai.embedding_batch_size')) as $batch) {
            foreach ($this->embedBatch($batch) as $vector) {
                $vectors[] = $vector;
            }
        }

        return $vectors;
    }

    /** @return list<float> */
    public function embed(string $text): array
    {
        return $this->embedMany([$text])[0];
    }

    /**
     * @param  list<string>  $batch
     * @return list<list<float>>
     */
    private function embedBatch(array $batch): array
    {
        $provider = (string) config('eaa.ai.provider');
        $dimensions = (int) config('eaa.ai.embedding_dimensions');

        $request = Prism::embeddings()
            ->using($provider, (string) config('eaa.ai.embedding_model'))
            ->fromArray($batch);

        // OpenAI permette di ridurre la dimensione del vettore (text-embedding-3-*),
        // così resta compatibile con la colonna VECTOR(768). Ollama non ne ha bisogno.
        if ($provider === 'openai') {
            $request->withProviderOptions(['dimensions' => $dimensions]);
        }

        try {
            $response = $request->asEmbeddings();
        } catch (PrismException|HttpClientException $e) {
            throw ApiProblem::llmUnavailable('Il servizio di embedding non è raggiungibile o ha risposto con un errore.', $e);
        }

        if (count($response->embeddings) !== count($batch)) {
            throw ApiProblem::llmUnavailable('Il servizio di embedding ha restituito un numero di vettori inatteso.');
        }

        $vectors = [];
        foreach ($response->embeddings as $embedding) {
            $vector = array_map('floatval', $embedding->embedding);

            // Errore di CONFIGURAZIONE (modello diverso dalla colonna del DB), non del provider:
            // si segnala chiaramente invece di far fallire l'INSERT con un messaggio criptico.
            if (count($vector) !== $dimensions) {
                throw new RuntimeException(sprintf(
                    'Il modello di embedding "%s" restituisce vettori da %d dimensioni, ma il database ne attende %d. '
                    .'Allinea AI_EMBEDDING_MODEL / AI_EMBEDDING_DIMENSIONS e lo schema.',
                    config('eaa.ai.embedding_model'),
                    count($vector),
                    $dimensions,
                ));
            }

            $vectors[] = $vector;
        }

        return $vectors;
    }
}
