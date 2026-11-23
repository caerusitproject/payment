-- Additive SQL Server improvements for queue selection and transaction history.
IF EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'ix_pymnt_req_pick' AND object_id = OBJECT_ID('pymnt_req'))
    DROP INDEX ix_pymnt_req_pick ON pymnt_req;
CREATE INDEX ix_pymnt_req_pick
    ON pymnt_req (queue_type, proc_status, next_attempt_at, pymnt_req_id);

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'uk_pymnt_txn_req_attempt' AND object_id = OBJECT_ID('c3_pymnt_txn'))
    CREATE UNIQUE INDEX uk_pymnt_txn_req_attempt
        ON c3_pymnt_txn (pymnt_req_id, attempt_num);

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'ix_pymnt_txn_req_latest' AND object_id = OBJECT_ID('c3_pymnt_txn'))
    CREATE INDEX ix_pymnt_txn_req_latest
        ON c3_pymnt_txn (pymnt_req_id, pymnt_txn_id DESC);

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'ix_bank_api_uid' AND object_id = OBJECT_ID('c3_bank_api_interaction'))
    CREATE INDEX ix_bank_api_uid
        ON c3_bank_api_interaction (unique_id, api_operation, initiated_ts);

-- Guard against invalid amounts/retry counters and orphaned transaction attempts.
IF NOT EXISTS (SELECT 1 FROM sys.foreign_keys WHERE name = 'fk_pymnt_txn_req')
    ALTER TABLE c3_pymnt_txn ADD CONSTRAINT fk_pymnt_txn_req
        FOREIGN KEY (pymnt_req_id) REFERENCES pymnt_req(pymnt_req_id);

IF NOT EXISTS (SELECT 1 FROM sys.check_constraints WHERE name = 'ck_pymnt_req_amount_positive')
    ALTER TABLE pymnt_req ADD CONSTRAINT ck_pymnt_req_amount_positive CHECK (amount > 0);
IF NOT EXISTS (SELECT 1 FROM sys.check_constraints WHERE name = 'ck_pymnt_req_retry_count')
    ALTER TABLE pymnt_req ADD CONSTRAINT ck_pymnt_req_retry_count CHECK (retry_count BETWEEN 0 AND 3);
IF NOT EXISTS (SELECT 1 FROM sys.check_constraints WHERE name = 'ck_pymnt_txn_amount_positive')
    ALTER TABLE c3_pymnt_txn ADD CONSTRAINT ck_pymnt_txn_amount_positive CHECK (amount > 0);
IF NOT EXISTS (SELECT 1 FROM sys.check_constraints WHERE name = 'ck_pymnt_txn_attempt_positive')
    ALTER TABLE c3_pymnt_txn ADD CONSTRAINT ck_pymnt_txn_attempt_positive CHECK (attempt_num > 0);
