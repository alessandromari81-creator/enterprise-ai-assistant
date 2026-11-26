<?php

namespace App\Models;

use App\Enums\ChatRole;
use Illuminate\Database\Eloquent\Concerns\HasUuids;
use Illuminate\Database\Eloquent\Model;
use Illuminate\Support\Carbon;

/**
 * Messaggio di una conversazione. Per i messaggi dell'assistente conserva anche le fonti
 * citate (sources) e le azioni eseguite (actions), utili come audit.
 *
 * @property string $id
 * @property string $conversation_id
 * @property ChatRole $role
 * @property string $content
 * @property array|null $sources
 * @property array|null $actions
 * @property Carbon $created_at
 */
class ChatMessage extends Model
{
    use HasUuids;

    public const UPDATED_AT = null;

    protected $fillable = ['conversation_id', 'role', 'content', 'sources', 'actions', 'created_at'];

    protected function casts(): array
    {
        return [
            'role' => ChatRole::class,
            'sources' => 'array',
            'actions' => 'array',
        ];
    }
}
