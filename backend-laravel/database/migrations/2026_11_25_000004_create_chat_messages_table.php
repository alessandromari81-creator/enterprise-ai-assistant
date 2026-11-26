<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Facades\Schema;

/*
 * Tabella chat_messages: memoria di conversazione e audit delle azioni dell'IA.
 * Migration idempotente (vedi nota in create_documents_table).
 */
return new class extends Migration
{
    public function up(): void
    {
        if (Schema::hasTable('chat_messages')) {
            return;
        }

        Schema::create('chat_messages', function (Blueprint $table) {
            $table->uuid('id')->primary()->default(DB::raw('gen_random_uuid()'));
            $table->uuid('conversation_id');
            $table->string('role', 10);
            $table->text('content');
            $table->jsonb('sources')->nullable(); // chunk citati
            $table->jsonb('actions')->nullable(); // tool eseguiti
            $table->timestampTz('created_at', 6)->useCurrent();

            $table->index(['conversation_id', 'created_at'], 'chat_messages_conversation_idx');
        });

        DB::statement("ALTER TABLE chat_messages ADD CONSTRAINT chat_messages_role_check CHECK (role IN ('USER', 'ASSISTANT'))");
    }

    public function down(): void
    {
        Schema::dropIfExists('chat_messages');
    }
};
