<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Facades\Schema;

/*
 * Tabella reimbursement_requests: scritta dal tool createReimbursementRequest.
 * Migration idempotente (vedi nota in create_documents_table).
 */
return new class extends Migration
{
    public function up(): void
    {
        if (Schema::hasTable('reimbursement_requests')) {
            return;
        }

        Schema::create('reimbursement_requests', function (Blueprint $table) {
            $table->uuid('id')->primary()->default(DB::raw('gen_random_uuid()'));
            $table->decimal('amount', 10, 2);
            $table->char('currency', 3)->default('EUR');
            $table->text('description')->nullable();
            $table->string('status', 10)->default('OPEN');
            $table->string('requested_by', 100)->default('demo-user');
            // Chat da cui l'IA ha creato la richiesta (tracciabilità).
            $table->uuid('source_conversation_id')->nullable();
            $table->timestampTz('created_at', 6)->useCurrent();

            $table->index('status', 'reimbursement_requests_status_idx');
        });

        // Difesa in profondità: anche se l'IA o il codice sbagliano, il DB rifiuta importi fuori limite.
        DB::statement('ALTER TABLE reimbursement_requests ADD CONSTRAINT reimbursement_requests_amount_check CHECK (amount > 0 AND amount <= 10000)');
        DB::statement("ALTER TABLE reimbursement_requests ADD CONSTRAINT reimbursement_requests_status_check CHECK (status IN ('OPEN', 'APPROVED', 'REJECTED'))");
        DB::statement('CREATE INDEX reimbursement_requests_created_idx ON reimbursement_requests (created_at DESC)');
    }

    public function down(): void
    {
        Schema::dropIfExists('reimbursement_requests');
    }
};
