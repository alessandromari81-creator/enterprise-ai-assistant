-- Upgrade IDEMPOTENTE di reimbursement_requests, eseguito a ogni avvio (spring.sql.init in application.yml).
-- Serve ai database creati prima dell'aggiornamento di stato dalla chat; sui database nuovi (creati da
-- db/init/01-init.sql) non fa nulla. Deve restare equivalente a db/init/01-init.sql e alla migration di Laravel
-- (backend-laravel/database/migrations/2026_11_26_000005_upgrade_reimbursement_requests.php).
-- Il separatore tra i comandi è ^^^ (i blocchi DO contengono punti e virgola).

ALTER TABLE reimbursement_requests ADD COLUMN IF NOT EXISTS number BIGINT GENERATED ALWAYS AS IDENTITY
^^^
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'reimbursement_requests_number_key') THEN
    ALTER TABLE reimbursement_requests ADD CONSTRAINT reimbursement_requests_number_key UNIQUE (number);
  END IF;
END $$
^^^
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
END $$
