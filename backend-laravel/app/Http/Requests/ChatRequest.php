<?php

namespace App\Http\Requests;

use Illuminate\Foundation\Http\FormRequest;

/**
 * Validazione di POST /api/chat. Specchio dello schema ChatRequest del contratto.
 * Nota: Laravel converte "" in null e rimuove gli spazi ai bordi, quindi un messaggio
 * vuoto o di soli spazi fallisce "required" (400).
 */
class ChatRequest extends FormRequest
{
    public function authorize(): bool
    {
        return true;
    }

    /** @return array<string, array<int, string>> */
    public function rules(): array
    {
        return [
            'message' => ['required', 'string', 'max:4000'],
            'conversationId' => ['nullable', 'uuid'],
            'documentIds' => ['nullable', 'array'],
            'documentIds.*' => ['required', 'uuid', 'distinct'],
        ];
    }
}
