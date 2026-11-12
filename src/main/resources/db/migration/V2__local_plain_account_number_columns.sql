-- Local development mode: keep account numbers as supplied, without application-level encryption.
-- Legacy encrypted columns are retained for rollback/production migration planning; new code uses *_plain.
ALTER TABLE c3_payer ADD COLUMN IF NOT EXISTS acc_num_plain VARCHAR(18);
ALTER TABLE c3_benef_snapshot ADD COLUMN IF NOT EXISTS ben_acc_plain VARCHAR(18);

-- Allow the legacy encrypted field to remain empty for new local-mode rows.
ALTER TABLE c3_benef_snapshot ALTER COLUMN ben_acc_encrypted DROP NOT NULL;
