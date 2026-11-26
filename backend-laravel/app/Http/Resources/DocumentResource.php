<?php

namespace App\Http\Resources;

use App\Models\Document;
use App\Support\Iso;
use Illuminate\Http\Request;
use Illuminate\Http\Resources\Json\JsonResource;

/**
 * Forma "Document" del contratto (camelCase). Tutti i campi sono sempre presenti:
 * quelli senza valore valgono null, non vengono omessi.
 *
 * @mixin Document
 */
class DocumentResource extends JsonResource
{
    /** @return array<string, mixed> */
    public function toArray(Request $request): array
    {
        return [
            'id' => $this->id,
            'filename' => $this->filename,
            'sizeBytes' => (int) $this->size_bytes,
            'pageCount' => $this->page_count,
            'chunkCount' => (int) $this->chunk_count,
            'status' => $this->status->value,
            'errorMessage' => $this->error_message,
            'createdAt' => Iso::utc($this->created_at),
        ];
    }
}
