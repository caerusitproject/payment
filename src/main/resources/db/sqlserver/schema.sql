-- SQL Server schema for payment-service. Use with the "sqlserver" profile (ddl-auto=validate).

CREATE TABLE c3_payer (
    payer_id            BIGINT IDENTITY(1,1) PRIMARY KEY,
    payer_bank_name     NVARCHAR(150) NOT NULL,
    payer_branch        NVARCHAR(150) NULL,
    payer_name          NVARCHAR(150) NULL,
    acc_num_encrypted   VARBINARY(MAX) NULL,
    acc_num_masked      VARCHAR(30) NULL,
    acc_num_plain       VARCHAR(18) NULL,
    ifsc_code           VARCHAR(11) NULL,
    currency            CHAR(3) NOT NULL,
    adapter_prvdr       NVARCHAR(150) NOT NULL,
    active_status_cdid  BIT NOT NULL,
    created_ts          DATETIME2(3) NOT NULL,
    updated_ts          DATETIME2(3) NULL
);

CREATE TABLE c3_pymnt_mode (
    pymnt_mode_id       BIGINT IDENTITY(1,1) PRIMARY KEY,
    mode_code           VARCHAR(10) NOT NULL,
    mode_name           VARCHAR(50) NOT NULL,
    min_amount          DECIMAL(18,2) NOT NULL,
    max_amount          DECIMAL(18,2) NOT NULL,
    active_status_cdid  BIT NOT NULL,
    created_ts          DATETIME2(3) NOT NULL
);

CREATE TABLE pymnt_req (
    pymnt_req_id        BIGINT IDENTITY(1,1) PRIMARY KEY,
    req_ref_name        VARCHAR(50) NOT NULL,
    req_ref_id          BIGINT NOT NULL,
    ben_ref_entity_name VARCHAR(50) NOT NULL,
    ben_ref_entity_id   BIGINT NOT NULL,
    payment_mode_id     BIGINT NULL,
    txn_categ_cdid      VARCHAR(20) NOT NULL,
    ben_acct_id         BIGINT NOT NULL,
    amount              DECIMAL(18,2) NOT NULL,
    currency            CHAR(3) NOT NULL,
    is_completed        BIT NOT NULL DEFAULT 0,
    initiated_ts        DATETIME2(3) NOT NULL,
    complete_ts         DATETIME2(3) NULL,
    queue_type          VARCHAR(20) NOT NULL,
    proc_status         VARCHAR(30) NOT NULL,
    retry_count         INT NOT NULL DEFAULT 0,
    next_attempt_at     DATETIME2(3) NULL,
    last_reason         VARCHAR(1000) NULL,
    CONSTRAINT uk_pymnt_req_ref UNIQUE (req_ref_name, req_ref_id)
);
CREATE INDEX ix_pymnt_req_pick ON pymnt_req (queue_type, proc_status, next_attempt_at);

CREATE TABLE c3_benef_snapshot (
    beni_snap_id        BIGINT IDENTITY(1,1) PRIMARY KEY,
    ben_ref_id          BIGINT NOT NULL,
    ben_acct_hol_name   NVARCHAR(150) NOT NULL,
    ben_acc_encrypted   VARBINARY(MAX) NULL,
    ben_acc_masked      VARCHAR(30) NOT NULL,
    ben_acc_plain       VARCHAR(18) NULL,
    ifsc_code           VARCHAR(11) NOT NULL,
    bank_name           NVARCHAR(150) NULL,
    txn_categ_cdid      VARCHAR(20) NULL,
    created_ts          DATETIME2(3) NOT NULL,
    update_ts           DATETIME2(3) NOT NULL
);

CREATE TABLE c3_pymnt_txn (
    pymnt_txn_id        BIGINT IDENTITY(1,1) PRIMARY KEY,
    pymnt_req_id        BIGINT NOT NULL REFERENCES pymnt_req(pymnt_req_id),
    payer_id            BIGINT NOT NULL REFERENCES c3_payer(payer_id),
    beneficiary_id      BIGINT NOT NULL,
    beni_snap_id        BIGINT NULL REFERENCES c3_benef_snapshot(beni_snap_id),
    payment_mode_id     BIGINT NOT NULL REFERENCES c3_pymnt_mode(pymnt_mode_id),
    amount              DECIMAL(18,2) NOT NULL,
    remarks             VARCHAR(100) NULL,
    currency            CHAR(3) NOT NULL,
    attempt_num         INT NOT NULL,
    transaction_type    VARCHAR(3) NOT NULL,
    unique_id           VARCHAR(40) NOT NULL,
    urn                 VARCHAR(50) NULL,
    txn_status          VARCHAR(30) NOT NULL,
    response            VARCHAR(30) NOT NULL,
    resp_code           VARCHAR(30) NULL,
    error_code          VARCHAR(30) NULL,
    failure_message     VARCHAR(1000) NULL,
    initiated_ts        DATETIME2(3) NOT NULL,
    updated_ts          DATETIME2(3) NULL,
    wkl_task_id         BIGINT NULL,
    wkl_task_created_ts DATETIME2(3) NULL,
    otp_task_expires_ts DATETIME2(3) NULL,
    CONSTRAINT uk_pymnt_txn_uid UNIQUE (unique_id)
);

CREATE TABLE c3_bank_api_interaction (
    api_interaction_id  BIGINT IDENTITY(1,1) PRIMARY KEY,
    api_operation       VARCHAR(30) NOT NULL,
    unique_id           VARCHAR(40) NULL,
    attempt_num         INT NOT NULL,
    http_status_code    INT NULL,
    response            VARCHAR(4000) NOT NULL,
    initiated_ts        DATETIME2(3) NOT NULL,
    completed_ts        DATETIME2(3) NULL
);
CREATE INDEX ix_bank_api_uid ON c3_bank_api_interaction (unique_id, api_operation);

-- Legacy local manual-intervention fallback only. OTP tasks are owned by the external Worklist Service.
CREATE TABLE c3_pymnt_worklist_item (
    id                  BIGINT IDENTITY(1,1) PRIMARY KEY,
    item_type           VARCHAR(30) NOT NULL,
    pymnt_req_id        BIGINT NOT NULL,
    pymnt_txn_id        BIGINT NULL,
    unique_id           VARCHAR(40) NULL,
    title               VARCHAR(500) NOT NULL,
    detail              VARCHAR(1000) NULL,
    amount              DECIMAL(18,2) NULL,
    status              VARCHAR(20) NOT NULL,
    otp_encrypted       VARBINARY(MAX) NULL,
    created_ts          DATETIME2(3) NOT NULL,
    expires_ts          DATETIME2(3) NULL,
    completed_ts        DATETIME2(3) NULL
);

CREATE UNIQUE INDEX ux_c3_pymnt_txn_wkl_task_id
    ON c3_pymnt_txn(wkl_task_id)
    WHERE wkl_task_id IS NOT NULL;
