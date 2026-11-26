<?php

namespace App\Providers;

use Illuminate\Http\Resources\Json\JsonResource;
use Illuminate\Support\ServiceProvider;

class AppServiceProvider extends ServiceProvider
{
    public function register(): void
    {
        //
    }

    public function boot(): void
    {
        // Per default Laravel avvolge le risorse in {"data": ...}. Il contratto NON lo prevede:
        // la risposta di POST /api/documents è direttamente l'oggetto Document.
        JsonResource::withoutWrapping();
    }
}
