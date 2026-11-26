<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Facades\Schema;

/*
 * Tabella document_chunks: i frammenti di testo con il loro embedding (pgvector).
 * Migration idempotente (vedi nota in create_documents_table).
 */
return new class extends Migration
{
    public function up(): void
    {
        // Abilita pgvector. Il pacchetto pgvector/pgvector ha già una migration che lo
        // fa, ma lo ripetiamo qui: "IF NOT EXISTS" lo rende innocuo e il file resta
        // leggibile da solo.
        DB::statement('CREATE EXTENSION IF NOT EXISTS vector');

        if (Schema::hasTable('document_chunks')) {
            return;
        }

        Schema::create('document_chunks', function (Blueprint $table) {
            $table->uuid('id')->primary()->default(DB::raw('gen_random_uuid()'));
            $table->foreignUuid('document_id')->constrained('documents')->cascadeOnDelete();
            $table->integer('chunk_index');
            $table->integer('page_number'); // base 1: serve alla citazione "p. N"
            $table->text('content');
            $table->integer('token_count')->nullable();
            // Macro "vector" fornita da pgvector/pgvector. La dimensione viene dalla config
            // e deve coincidere con quella del modello di embedding (768 = nomic-embed-text).
            $table->vector('embedding', config('eaa.ai.embedding_dimensions'));
            $table->timestampTz('created_at', 6)->useCurrent();

            $table->unique(['document_id', 'chunk_index']);
            $table->index('document_id', 'document_chunks_document_idx');
        });

        // Indice HNSW per la ricerca per distanza coseno (operatore <=>).
        DB::statement(
            'CREATE INDEX document_chunks_embedding_idx ON document_chunks USING hnsw (embedding vector_cosine_ops)'
        );
    }

    public function down(): void
    {
        Schema::dropIfExists('document_chunks');
    }
};
