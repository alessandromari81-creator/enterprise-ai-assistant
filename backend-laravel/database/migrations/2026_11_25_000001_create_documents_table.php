<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Facades\Schema;

/*
 * Tabella documents: un record per PDF caricato.
 *
 * NOTA (decisione D1 aggiornata): lo schema è definito anche in db/init/01-init.sql,
 * che Docker esegue al primo avvio. Questa migration è IDEMPOTENTE: se la tabella
 * esiste già (creata dall'init SQL) non fa nulla. Le due definizioni devono restare
 * equivalenti.
 */
return new class extends Migration
{
    public function up(): void
    {
        if (Schema::hasTable('documents')) {
            return;
        }

        Schema::create('documents', function (Blueprint $table) {
            $table->uuid('id')->primary()->default(DB::raw('gen_random_uuid()'));
            $table->string('filename', 255);
            $table->string('content_type', 100)->default('application/pdf');
            $table->bigInteger('size_bytes');
            // SHA-256 esadecimale del file: impedisce di indicizzare due volte lo stesso PDF.
            $table->char('content_hash', 64)->unique();
            $table->integer('page_count')->nullable();
            $table->integer('chunk_count')->default(0);
            $table->string('status', 12)->default('PROCESSING');
            $table->text('error_message')->nullable();
            $table->timestampTz('created_at', 6)->useCurrent();
        });

        DB::statement('ALTER TABLE documents ADD CONSTRAINT documents_size_bytes_check CHECK (size_bytes > 0)');
        DB::statement("ALTER TABLE documents ADD CONSTRAINT documents_status_check CHECK (status IN ('PROCESSING', 'READY', 'FAILED'))");
    }

    public function down(): void
    {
        Schema::dropIfExists('documents');
    }
};
