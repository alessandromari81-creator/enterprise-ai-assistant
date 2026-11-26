<?php

use App\Http\Controllers\ChatController;
use App\Http\Controllers\DocumentController;
use App\Http\Controllers\ReimbursementController;
use Illuminate\Support\Facades\Route;

/*
|--------------------------------------------------------------------------
| API Routes — implementano docs/api/openapi.yaml
|--------------------------------------------------------------------------
| Laravel aggiunge da solo il prefisso /api (vedi bootstrap/app.php).
| Nessuna autenticazione in v1 (decisione D10).
*/

Route::post('/documents', [DocumentController::class, 'store']);   // carica e indicizza un PDF
Route::get('/documents', [DocumentController::class, 'index']);    // elenco documenti

Route::post('/chat', [ChatController::class, 'store']);            // domanda RAG o azione

Route::get('/reimbursements', [ReimbursementController::class, 'index']); // elenco rimborsi
