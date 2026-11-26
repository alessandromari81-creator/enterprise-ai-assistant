<?php

namespace App\Http\Controllers;

use App\Http\Requests\ListReimbursementsRequest;
use App\Http\Resources\ReimbursementResource;
use App\Models\ReimbursementRequest;
use Illuminate\Http\JsonResponse;

/** GET /api/reimbursements: elenco paginato (page base 0), filtrabile per stato. */
class ReimbursementController extends Controller
{
    public function index(ListReimbursementsRequest $request): JsonResponse
    {
        $query = ReimbursementRequest::query()
            ->when($request->statusFilter(), fn ($q, $status) => $q->where('status', $status))
            ->orderByDesc('created_at')
            ->orderByDesc('id'); // spareggio stabile quando due righe hanno lo stesso istante

        $size = $request->pageSize();
        $page = $request->pageIndex();

        $total = (clone $query)->toBase()->getCountForPagination();
        $items = $query->forPage($page + 1, $size)->get(); // forPage è base 1, il contratto base 0

        return response()->json([
            'items' => ReimbursementResource::collection($items)->resolve(),
            'page' => $page,
            'size' => $size,
            'totalItems' => $total,
            'totalPages' => (int) ceil($total / $size),
        ]);
    }
}
