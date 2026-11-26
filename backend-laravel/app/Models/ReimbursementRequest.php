<?php

namespace App\Models;

use App\Enums\ReimbursementStatus;
use Illuminate\Database\Eloquent\Concerns\HasUuids;
use Illuminate\Database\Eloquent\Model;
use Illuminate\Support\Carbon;

/**
 * Richiesta di rimborso, creata dal tool createReimbursementRequest invocato dal modello.
 *
 * @property string $id
 * @property string $amount decimale come stringa, es. "50.00" (mai float)
 * @property string $currency
 * @property string|null $description
 * @property ReimbursementStatus $status
 * @property string $requested_by
 * @property string|null $source_conversation_id
 * @property Carbon $created_at
 */
class ReimbursementRequest extends Model
{
    use HasUuids;

    public const UPDATED_AT = null;

    protected $fillable = [
        'amount', 'currency', 'description', 'status', 'requested_by', 'source_conversation_id',
    ];

    protected function casts(): array
    {
        return [
            // "decimal:2" restituisce sempre una stringa con 2 decimali ("50.00"), come richiede il contratto.
            'number' => 'integer',
            'amount' => 'decimal:2',
            'status' => ReimbursementStatus::class,
        ];
    }
}
