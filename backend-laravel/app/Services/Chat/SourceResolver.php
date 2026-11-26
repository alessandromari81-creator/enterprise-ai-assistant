<?php

namespace App\Services\Chat;

use App\Services\Rag\RetrievedChunk;

/**
 * Decide quali chunk elencare in `sources[]` della risposta.
 *
 * Regole (decisione D13):
 *  1. Se la risposta cita "[file, p. N]", sono fonti i chunk che corrispondono a quelle citazioni.
 *  2. Se è il rifiuto ("non ho trovato...") o è stata eseguita un'azione -> nessuna fonte.
 *  3. Altrimenti (risposta basata sui documenti ma senza citazione leggibile, tipico dei modelli
 *     piccoli) si elencano i chunk recuperati: meglio mostrare le fonti consultate che nessuna.
 */
class SourceResolver
{
    /**
     * @param  list<RetrievedChunk>  $retrieved
     * @return list<RetrievedChunk>
     */
    public function resolve(string $answer, array $retrieved, bool $actionExecuted): array
    {
        if ($retrieved === [] || $actionExecuted || str_contains($answer, PromptBuilder::REFUSAL)) {
            return [];
        }

        $cited = array_values(array_filter(
            $retrieved,
            fn (RetrievedChunk $chunk) => $this->isCited($answer, $chunk),
        ));

        return $cited !== [] ? $cited : $retrieved;
    }

    /** Riconosce "policy.pdf, p. 4", "policy.pdf, pag. 4", "policy.pdf p.4", ecc. */
    private function isCited(string $answer, RetrievedChunk $chunk): bool
    {
        $pattern = sprintf(
            '/%s\s*,?\s*(?:pagina|pag\.?|p\.?)\s*%d(?!\d)/iu',
            preg_quote($chunk->filename, '/'),
            $chunk->page,
        );

        return preg_match($pattern, $answer) === 1;
    }
}
