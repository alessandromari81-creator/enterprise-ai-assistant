<?php

namespace App\Http\Resources;

use App\Models\ReimbursementRequest;
use App\Support\Iso;
use Illuminate\Http\Request;
use Illuminate\Http\Resources\Json\JsonResource;

/**
 * Forma "Reimbursement" del contratto. Nota: la colonna source_conversation_id
 * viene esposta come `conversationId`.
 *
 * @mixin ReimbursementRequest
 */
class ReimbursementResource extends JsonResource
{
    /** @return array<string, mixed> */
    public function toArray(Request $request): array
    {
        return [
            'id' => $this->id,
            'number' => (int) $this->number,
            'amount' => $this->amount, // stringa "50.00"
            'currency' => $this->currency,
            'description' => $this->description,
            'status' => $this->status->value,
            'requestedBy' => $this->requested_by,
            'conversationId' => $this->source_conversation_id,
            'createdAt' => Iso::utc($this->created_at),
        ];
    }
}
