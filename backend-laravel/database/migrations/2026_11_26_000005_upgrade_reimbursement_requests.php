<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Facades\Schema;

/*
 * Aggiorna reimbursement_requests per l'aggiornamento di stato dalla chat:
 *   - colonna `number`: numero progressivo leggibile (#1, #2...) con cui l'utente indica un rimborso;
 *   - nuovo stato EXECUTED ("Eseguito") nel vincolo CHECK dello stato.
 *
 * IDEMPOTENTE: non fa nulla se la colonna e il vincolo sono già aggiornati (database creato dallo SQL di
 * init aggiornato). Serve ai database esistenti. Deve restare equivalente a db/init/01-init.sql e allo script
 * di upgrade del backend Spring (backend-springboot/src/main/resources/db/upgrade-reimbursements.sql).
 */
return new class extends Migration
{
    public function up(): void
    {
        if (! Schema::hasColumn('reimbursement_requests', 'number')) {
            // I rimborsi già presenti ricevono un numero automaticamente.
            DB::statement('ALTER TABLE reimbursement_requests ADD COLUMN number BIGINT GENERATED ALWAYS AS IDENTITY');
            DB::statement('ALTER TABLE reimbursement_requests ADD CONSTRAINT reimbursement_requests_number_key UNIQUE (number)');
        }

        DB::unprepared(<<<'SQL'
            DO $$
            BEGIN
              IF NOT EXISTS (
                SELECT 1 FROM pg_constraint
                WHERE conname = 'reimbursement_requests_status_check' AND pg_get_constraintdef(oid) LIKE '%EXECUTED%'
              ) THEN
                ALTER TABLE reimbursement_requests DROP CONSTRAINT IF EXISTS reimbursement_requests_status_check;
                ALTER TABLE reimbursement_requests ADD CONSTRAINT reimbursement_requests_status_check
                  CHECK (status IN ('OPEN', 'APPROVED', 'REJECTED', 'EXECUTED'));
              END IF;
            END $$;
            SQL);
    }

    public function down(): void
    {
        // Volutamente vuoto: tornare indietro cancellerebbe i numeri assegnati e gli stati EXECUTED.
    }
};
