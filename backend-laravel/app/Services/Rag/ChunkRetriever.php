<?php

namespace App\Services\Rag;

use Illuminate\Support\Facades\DB;
use Pgvector\Laravel\Vector;

/**
 * Ricerca vettoriale: dato l'embedding di una domanda, trova i chunk più simili.
 *
 * Usa la SIMILARITÀ COSENO di pgvector. L'operatore `<=>` restituisce la DISTANZA coseno
 * (0 = identico, 2 = opposto); lo score del contratto è `1 - distanza`.
 * L'ORDER BY sulla distanza è ciò che permette di usare l'indice HNSW.
 */
class ChunkRetriever
{
    /**
     * @param  list<float>  $queryVector
     * @param  list<string>|null  $documentIds  se valorizzato restringe la ricerca a questi documenti
     * @return list<RetrievedChunk> ordinati dal più al meno simile, solo con score >= $minScore
     */
    public function search(array $queryVector, int $topK, float $minScore, ?array $documentIds = null): array
    {
        $vector = (string) new Vector($queryVector);

        $sql = <<<'SQL'
            SELECT c.id, c.document_id, d.filename, c.page_number, c.content,
                   1 - (c.embedding <=> ?::vector) AS score
            FROM document_chunks c
            JOIN documents d ON d.id = c.document_id
            WHERE d.status = 'READY'
        SQL;
        $bindings = [$vector];

        if ($documentIds !== null && $documentIds !== []) {
            $sql .= ' AND c.document_id IN ('.implode(',', array_fill(0, count($documentIds), '?')).')';
            array_push($bindings, ...$documentIds);
        }

        $sql .= ' ORDER BY c.embedding <=> ?::vector LIMIT ?';
        array_push($bindings, $vector, $topK);

        // pgvector 0.8: con un filtro (WHERE) l'indice HNSW potrebbe restituire meno di $topK
        // risultati. "iterative_scan" lo fa continuare finché ne trova abbastanza.
        // SET LOCAL vale solo dentro questa transazione.
        $rows = DB::transaction(function () use ($sql, $bindings) {
            DB::statement("SET LOCAL hnsw.iterative_scan = 'strict_order'");

            return DB::select($sql, $bindings);
        });

        $chunks = [];
        foreach ($rows as $row) {
            $score = (float) $row->score;
            if ($score >= $minScore) {
                $chunks[] = new RetrievedChunk(
                    chunkId: $row->id,
                    documentId: $row->document_id,
                    filename: $row->filename,
                    page: (int) $row->page_number,
                    content: $row->content,
                    score: round($score, 4),
                );
            }
        }

        return $chunks;
    }
}
