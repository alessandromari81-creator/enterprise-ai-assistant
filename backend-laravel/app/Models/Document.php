<?php

namespace App\Models;

use App\Enums\DocumentStatus;
use Illuminate\Database\Eloquent\Concerns\HasUuids;
use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\HasMany;
use Illuminate\Support\Carbon;

/**
 * Un PDF caricato dall'utente. I suoi frammenti indicizzati stanno in document_chunks.
 *
 * @property string $id
 * @property string $filename
 * @property int $size_bytes
 * @property string $content_hash
 * @property int|null $page_count
 * @property int $chunk_count
 * @property DocumentStatus $status
 * @property string|null $error_message
 * @property Carbon $created_at
 */
class Document extends Model
{
    use HasUuids;

    // La tabella ha solo created_at (niente updated_at).
    public const UPDATED_AT = null;

    protected $fillable = [
        'filename', 'content_type', 'size_bytes', 'content_hash',
        'page_count', 'chunk_count', 'status', 'error_message',
    ];

    protected function casts(): array
    {
        return ['status' => DocumentStatus::class];
    }

    public function chunks(): HasMany
    {
        return $this->hasMany(DocumentChunk::class);
    }
}
