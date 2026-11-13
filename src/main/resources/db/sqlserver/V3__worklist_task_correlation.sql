-- Apply once to an existing SQL Server Payment Service database.
IF COL_LENGTH('c3_payer', 'acc_num_plain') IS NULL
    ALTER TABLE c3_payer ADD acc_num_plain VARCHAR(18) NULL;
IF COL_LENGTH('c3_benef_snapshot', 'ben_acc_plain') IS NULL
    ALTER TABLE c3_benef_snapshot ADD ben_acc_plain VARCHAR(18) NULL;
IF COL_LENGTH('c3_pymnt_txn', 'wkl_task_id') IS NULL
    ALTER TABLE c3_pymnt_txn ADD wkl_task_id BIGINT NULL;
IF COL_LENGTH('c3_pymnt_txn', 'wkl_task_created_ts') IS NULL
    ALTER TABLE c3_pymnt_txn ADD wkl_task_created_ts DATETIME2(3) NULL;
IF COL_LENGTH('c3_pymnt_txn', 'otp_task_expires_ts') IS NULL
    ALTER TABLE c3_pymnt_txn ADD otp_task_expires_ts DATETIME2(3) NULL;

IF NOT EXISTS (
    SELECT 1 FROM sys.indexes
    WHERE name = 'ux_c3_pymnt_txn_wkl_task_id'
      AND object_id = OBJECT_ID('c3_pymnt_txn')
)
    CREATE UNIQUE INDEX ux_c3_pymnt_txn_wkl_task_id
    ON c3_pymnt_txn(wkl_task_id)
    WHERE wkl_task_id IS NOT NULL;

-- Current local/development persistence uses ben_acc_plain; encrypted column is retained
-- for compatibility but must be nullable when plain-column mode is used.
ALTER TABLE c3_benef_snapshot ALTER COLUMN ben_acc_encrypted VARBINARY(MAX) NULL;
