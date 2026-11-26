<?php

namespace App\Http\Controllers;

use App\Http\Requests\UploadDocumentRequest;
use App\Http\Resources\DocumentResource;
use App\Models\Document;
use App\Services\Rag\DocumentIngestionService;
use Illuminate\Http\JsonResponse;

/** Endpoint /api/documents. Il controller resta sottile: la logica è in DocumentIngestionService. */
class DocumentController extends Controller
{
    /** POST /api/documents -> 201 con il documento indicizzato (READY). */
    public function store(UploadDocumentRequest $request, DocumentIngestionService $ingestion): JsonResponse
    {
        $document = $ingestion->ingest($request->pdf());

        return (new DocumentResource($document))->response()->setStatusCode(201);
    }

    /** GET /api/documents -> { items: [...] }, il più recente per primo. */
    public function index(): JsonResponse
    {
        $items = Document::query()
            ->orderByDesc('created_at')
            ->orderByDesc('id')
            ->get();

        return response()->json(['items' => DocumentResource::collection($items)->resolve()]);
    }
}
