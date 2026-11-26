<?php

namespace App\Services\Rag;

use App\Enums\DocumentStatus;
use App\Exceptions\ApiProblem;
use App\Models\Document;
use App\Models\DocumentChunk;
use Illuminate\Database\UniqueConstraintViolationException;
use Illuminate\Http\UploadedFile;
use Illuminate\Support\Facades\DB;
use Pgvector\Laravel\Vector;
use Throwable;

/**
 * Pipeline di ingestione di un PDF:
 *
 *   hash SHA-256 -> (duplicato?) -> estrazione testo per pagina -> chunking
 *   -> embedding -> salvataggio atomico dei chunk -> documento READY
 *
 * Il lavoro è sincrono (decisione D7): la richiesta HTTP risponde solo a indicizzazione finita.
 */
class DocumentIngestionService
{
    public function __construct(
        private readonly PdfTextExtractor $extractor,
        private readonly EmbeddingService $embeddings,
    ) {}

    /**
     * @throws ApiProblem 409 duplicato, 422 PDF illeggibile, 502 provider embedding non raggiungibile
     */
    public function ingest(UploadedFile $file): Document
    {
        $hash = hash_file('sha256', $file->getRealPath());

        $document = $this->createProcessingDocument($file, $hash);

        try {
            $extracted = $this->extractor->extract($file->getRealPath());
            $pieces = $this->buildChunks($extracted['pages']);

            if ($pieces === []) {
                throw ApiProblem::unreadablePdf(
                    'Nel PDF non c\'è testo estraibile. Se è una scansione serve prima un OCR.'
                );
            }

            // Prima si calcolano TUTTI gli embedding (operazione lenta, può fallire)...
            $vectors = $this->embeddings->embedMany(array_column($pieces, 'text'));

            // ...poi si scrive tutto in una sola transazione: o il documento è completo, o non c'è nulla.
            DB::transaction(function () use ($document, $pieces, $vectors, $extracted) {
                $rows = [];
                foreach ($pieces as $i => $piece) {
                    $rows[] = [
                        'document_id' => $document->id,
                        'chunk_index' => $i,
                        'page_number' => $piece['page'],
                        'content' => $piece['text'],
                        'token_count' => TextChunker::estimateTokens($piece['text']),
                        // Rappresentazione testuale "[0.1,0.2,...]" che PostgreSQL converte in vector.
                        'embedding' => (string) new Vector($vectors[$i]),
                    ];
                }

                foreach (array_chunk($rows, 100) as $batch) {
                    DocumentChunk::query()->insert($batch);
                }

                $document->update([
                    'page_count' => $extracted['pageCount'],
                    'chunk_count' => count($pieces),
                    'status' => DocumentStatus::Ready,
                    'error_message' => null,
                ]);
            });
        } catch (Throwable $e) {
            // Il documento resta visibile come FAILED con il motivo: la UI può mostrarlo e
            // l'utente può ricaricare lo stesso file (vedi createProcessingDocument).
            $document->update([
                'status' => DocumentStatus::Failed,
                'error_message' => $e instanceof ApiProblem ? $e->detail : 'Errore interno durante l\'indicizzazione.',
            ]);

            throw $e;
        }

        return $document->refresh();
    }

    /**
     * Registra il documento in stato PROCESSING.
     *
     * Regola sui duplicati (stesso SHA-256):
     *  - esiste già READY o PROCESSING  -> 409;
     *  - esiste ma FAILED               -> si elimina il vecchio record e si riprova.
     *
     * La UNIQUE su content_hash protegge anche dalle richieste concorrenti: se due upload
     * identici arrivano insieme, il secondo fallisce sull'INSERT e viene trattato come duplicato.
     */
    private function createProcessingDocument(UploadedFile $file, string $hash): Document
    {
        $existing = Document::query()->where('content_hash', $hash)->first();

        if ($existing !== null) {
            if ($existing->status !== DocumentStatus::Failed) {
                throw ApiProblem::duplicateDocument(
                    sprintf('Questo PDF è già stato caricato come "%s".', $existing->filename)
                );
            }
            $existing->delete(); // i chunk (se ce ne fossero) spariscono con ON DELETE CASCADE
        }

        try {
            return Document::query()->create([
                'filename' => $this->safeFilename($file),
                'content_type' => 'application/pdf',
                'size_bytes' => (int) $file->getSize(),
                'content_hash' => $hash,
                'chunk_count' => 0,
                'status' => DocumentStatus::Processing,
            ]);
        } catch (UniqueConstraintViolationException) {
            throw ApiProblem::duplicateDocument('Questo PDF è già stato caricato.');
        }
    }

    /**
     * Applica il chunking pagina per pagina: un chunk non attraversa mai due pagine,
     * così ogni chunk ha UN numero di pagina certo per la citazione.
     *
     * @param  list<array{number: int, text: string}>  $pages
     * @return list<array{page: int, text: string}>
     */
    private function buildChunks(array $pages): array
    {
        $chunker = TextChunker::fromConfig();
        $pieces = [];

        foreach ($pages as $page) {
            foreach ($chunker->split($page['text']) as $text) {
                $pieces[] = ['page' => $page['number'], 'text' => $text];
            }
        }

        return $pieces;
    }

    /** Solo il nome del file (niente percorsi), massimo 255 caratteri, UTF-8 valido. */
    private function safeFilename(UploadedFile $file): string
    {
        $name = basename(str_replace('\\', '/', $file->getClientOriginalName()));
        $name = mb_scrub($name, 'UTF-8');

        return mb_substr($name !== '' ? $name : 'documento.pdf', 0, 255);
    }
}
