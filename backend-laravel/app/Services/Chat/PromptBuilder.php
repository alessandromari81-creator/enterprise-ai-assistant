<?php

namespace App\Services\Chat;

use App\Services\Rag\RetrievedChunk;

/**
 * Costruisce il system prompt del RAG: regole anti-allucinazione + estratti recuperati.
 *
 * Il testo delle regole sta in resources/prompts/rag-system.txt, così si può rifinire
 * senza toccare il codice.
 */
class PromptBuilder
{
    /**
     * Frase di rifiuto: è anche il segnale che usa SourceResolver per capire che la risposta
     * NON si basa sui documenti (e quindi sources deve essere vuoto).
     */
    public const REFUSAL = 'Non ho trovato questa informazione nei documenti caricati.';

    /** @param  list<RetrievedChunk>  $chunks */
    public function system(array $chunks): string
    {
        $template = file_get_contents(resource_path('prompts/rag-system.txt'));

        return str_replace(
            ['{{REFUSAL}}', '{{CONTEXT}}'],
            [self::REFUSAL, $this->context($chunks)],
            $template,
        );
    }

    /**
     * Ogni estratto ha un'intestazione con file e pagina: il modello la copia nella citazione.
     *
     * @param  list<RetrievedChunk>  $chunks
     */
    private function context(array $chunks): string
    {
        if ($chunks === []) {
            return '(nessun estratto pertinente trovato)';
        }

        $blocks = [];
        foreach ($chunks as $i => $chunk) {
            $blocks[] = sprintf(
                "[ESTRATTO %d] file: %s, pagina: %d\n%s",
                $i + 1,
                $chunk->filename,
                $chunk->page,
                $chunk->content,
            );
        }

        return implode("\n---\n", $blocks);
    }
}
