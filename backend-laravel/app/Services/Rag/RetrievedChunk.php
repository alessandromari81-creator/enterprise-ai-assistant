<?php

namespace App\Services\Rag;

/** Un chunk restituito dalla ricerca vettoriale, con il punteggio di similarità. */
final readonly class RetrievedChunk
{
    public function __construct(
        public string $chunkId,
        public string $documentId,
        public string $filename,
        public int $page,
        public string $content,
        public float $score, // 1 = identico, 0 = nessuna somiglianza (1 - distanza coseno)
    ) {}
}
