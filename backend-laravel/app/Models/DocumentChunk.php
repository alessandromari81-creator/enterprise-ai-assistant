<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Concerns\HasUuids;
use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\BelongsTo;
use Pgvector\Laravel\Vector;

/**
 * Un frammento (~500 token) di una singola pagina di un documento, con il suo embedding.
 *
 * @property string $id
 * @property string $document_id
 * @property int $chunk_index
 * @property int $page_number
 * @property string $content
 * @property Vector $embedding
 */
class DocumentChunk extends Model
{
    use HasUuids;

    public const UPDATED_AT = null;

    protected $fillable = [
        'document_id', 'chunk_index', 'page_number', 'content', 'token_count', 'embedding',
    ];

    protected function casts(): array
    {
        // Il cast di pgvector converte array di float <-> tipo "vector" di PostgreSQL.
        return ['embedding' => Vector::class];
    }

    public function document(): BelongsTo
    {
        return $this->belongsTo(Document::class);
    }
}
