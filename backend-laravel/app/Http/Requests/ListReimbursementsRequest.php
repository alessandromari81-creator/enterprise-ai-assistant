<?php

namespace App\Http\Requests;

use App\Enums\ReimbursementStatus;
use Illuminate\Foundation\Http\FormRequest;
use Illuminate\Validation\Rule;

/** Validazione di GET /api/reimbursements?status=&page=&size= (page base 0, size 1-100). */
class ListReimbursementsRequest extends FormRequest
{
    public function authorize(): bool
    {
        return true;
    }

    /** @return array<string, array<int, mixed>> */
    public function rules(): array
    {
        return [
            'status' => ['nullable', Rule::enum(ReimbursementStatus::class)],
            'page' => ['nullable', 'integer', 'min:0'],
            'size' => ['nullable', 'integer', 'min:1', 'max:100'],
        ];
    }

    public function statusFilter(): ?ReimbursementStatus
    {
        return $this->filled('status') ? ReimbursementStatus::from($this->input('status')) : null;
    }

    public function pageIndex(): int
    {
        return (int) ($this->input('page') ?? 0);
    }

    public function pageSize(): int
    {
        return (int) ($this->input('size') ?? 20);
    }
}
