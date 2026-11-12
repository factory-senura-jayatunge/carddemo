-- Statement runs (batch.md, "Statements (statementJob)", "Runs, restart and
-- rerun"; batch-execution-contract.md sections 4.3, 4.4 and 4.8). One row per
-- cycle attempt: the capture's watermark and status, written only by
-- statementJob, compare-and-set on the expected status and on the owning
-- job_execution_id in a fenced transaction; lease recovery fails the RUNNING
-- row of the recovered execution (OWNER_LOST), found by job_execution_id
-- only; cycle-rerun and dataLoadJob supersede or invalidate rows. The CHECKs tie each status to
-- its columns. The final folder is statements/<cycle_id>-<attempt> under the
-- instance root; work_folder names the working folder under statements/.work
-- whose bytes the intent (PUBLISHING) commits.
CREATE TABLE batch.statement_run (
    statement_run_id   BIGINT IDENTITY(1,1) NOT NULL,
    cycle_id           INT           NOT NULL,
    attempt            INT           NOT NULL,
    mode               VARCHAR(6)    NOT NULL,
    status             VARCHAR(11)   NOT NULL,
    -- MAX(core.transaction.tran_pk) at the capture, 0 for an empty table.
    watermark_tran_pk  BIGINT        NOT NULL,
    -- The latest COMPLETED fixed run of an earlier cycle at the capture; never a legacy run.
    previous_run_id    BIGINT        NULL,
    folder             VARCHAR(24)   COLLATE Latin1_General_100_BIN2 NOT NULL,
    work_folder        VARCHAR(48)   COLLATE Latin1_General_100_BIN2 NULL,
    manifest_sha256    CHAR(64)      COLLATE Latin1_General_100_BIN2 NULL,
    -- A code only, never a message or a value from the data.
    failure_code       VARCHAR(40)   NULL,
    statements         INT           NULL,
    transactions       BIGINT        NULL,
    unassigned         BIGINT        NULL,
    adjusted           INT           NULL,
    -- The one execution that may write the row: the capture's, then each
    -- restart's claim. An execution owns at most one run (its instance's).
    job_execution_id   BIGINT        NOT NULL,
    started_at         DATETIME2(3)  NOT NULL CONSTRAINT DF_statement_run_started_at DEFAULT SYSUTCDATETIME(),
    changed_at         DATETIME2(3)  NOT NULL CONSTRAINT DF_statement_run_changed_at DEFAULT SYSUTCDATETIME(),
    completed_at       DATETIME2(3)  NULL,
    CONSTRAINT PK_statement_run PRIMARY KEY (statement_run_id),
    CONSTRAINT UQ_statement_run_cycle_attempt UNIQUE (cycle_id, attempt),
    CONSTRAINT FK_statement_run_cycle FOREIGN KEY (cycle_id) REFERENCES batch.cycle (cycle_id),
    CONSTRAINT FK_statement_run_previous FOREIGN KEY (previous_run_id)
        REFERENCES batch.statement_run (statement_run_id),
    CONSTRAINT FK_statement_run_execution FOREIGN KEY (job_execution_id)
        REFERENCES batch.BATCH_JOB_EXECUTION (JOB_EXECUTION_ID),
    CONSTRAINT CK_statement_run_attempt CHECK (attempt >= 1),
    CONSTRAINT CK_statement_run_mode CHECK (mode IN ('FIXED', 'LEGACY')),
    CONSTRAINT CK_statement_run_previous CHECK (previous_run_id IS NULL OR mode = 'FIXED'),
    CONSTRAINT CK_statement_run_watermark CHECK (watermark_tran_pk >= 0),
    CONSTRAINT CK_statement_run_folder CHECK (folder = CONCAT(cycle_id, '-', attempt)),
    -- <folder>.<jobExecutionId> in canonical digits; a fresh job repository's first execution is 0.
    CONSTRAINT CK_statement_run_work_folder CHECK (work_folder IS NULL
        OR (work_folder LIKE CONCAT(folder, '.[0-9]%')
            AND SUBSTRING(work_folder, LEN(folder) + 2, 48) NOT LIKE '%[^0-9]%'
            AND (SUBSTRING(work_folder, LEN(folder) + 2, 48) = '0'
                OR SUBSTRING(work_folder, LEN(folder) + 2, 48) NOT LIKE '0%'))),
    CONSTRAINT CK_statement_run_manifest CHECK (manifest_sha256 IS NULL
        OR (LEN(manifest_sha256) = 64 AND manifest_sha256 NOT LIKE '%[^0-9a-f]%')),
    CONSTRAINT CK_statement_run_counts CHECK (
        (statements IS NULL AND transactions IS NULL AND unassigned IS NULL AND adjusted IS NULL)
        OR (statements >= 0 AND transactions >= 0 AND unassigned >= 0 AND adjusted >= 0)),
    CONSTRAINT CK_statement_run_status CHECK (
           (status = 'RUNNING' AND work_folder IS NULL AND manifest_sha256 IS NULL AND failure_code IS NULL
                AND statements IS NULL AND completed_at IS NULL)
        OR (status = 'FAILED' AND work_folder IS NULL AND manifest_sha256 IS NULL AND failure_code IS NOT NULL
                AND statements IS NULL AND completed_at IS NULL)
        OR (status = 'PUBLISHING' AND work_folder IS NOT NULL AND manifest_sha256 IS NOT NULL
                AND failure_code IS NULL AND statements IS NOT NULL AND completed_at IS NULL)
        OR (status IN ('COMPLETED', 'SUPERSEDED') AND work_folder IS NOT NULL AND manifest_sha256 IS NOT NULL
                AND failure_code IS NULL AND statements IS NOT NULL AND completed_at IS NOT NULL)
        OR status = 'INVALIDATED'),
    CONSTRAINT CK_statement_run_failure_code CHECK (failure_code IS NULL OR failure_code IN ('OWNER_LOST'))
);
GO

-- Recovery and every owner check seek one run by its execution.
CREATE UNIQUE INDEX UX_statement_run_execution ON batch.statement_run (job_execution_id);
GO

-- Each account's figures at the capture, written once in the capture's
-- transaction and never changed: every execution of the run uses them.
CREATE TABLE batch.statement_capture (
    statement_run_id   BIGINT         NOT NULL,
    acct_id            CHAR(11)       COLLATE Latin1_General_100_BIN2 NOT NULL,
    curr_bal           DECIMAL(12,2)  NOT NULL,
    credit_limit       DECIMAL(12,2)  NOT NULL,
    CONSTRAINT PK_statement_capture PRIMARY KEY (statement_run_id, acct_id),
    CONSTRAINT FK_statement_capture_run FOREIGN KEY (statement_run_id)
        REFERENCES batch.statement_run (statement_run_id)
);
GO

-- One row per statement of a fixed run, inserted by the render chunk that
-- wrote its PDF; a restart that renders again deletes the run's rows first.
-- Money columns are DECIMAL(13,2): a balance near the DECIMAL(12,2) maximum
-- plus a period's activity must not overflow.
CREATE TABLE batch.account_statement (
    statement_run_id   BIGINT         NOT NULL,
    acct_id            CHAR(11)       COLLATE Latin1_General_100_BIN2 NOT NULL,
    period_start       DATE           NOT NULL,
    period_end         DATE           NOT NULL,
    opening_balance    DECIMAL(13,2)  NOT NULL,
    closing_balance    DECIMAL(13,2)  NOT NULL,
    net_amount         DECIMAL(13,2)  NOT NULL,
    interest_amount    DECIMAL(13,2)  NOT NULL,
    adjustment_amount  DECIMAL(13,2)  NOT NULL,
    minimum_payment    DECIMAL(13,2)  NOT NULL,
    credit_limit       DECIMAL(13,2)  NOT NULL,
    tran_count         INT            NOT NULL,
    file_name          VARCHAR(16)    COLLATE Latin1_General_100_BIN2 NOT NULL,
    file_sha256        CHAR(64)       COLLATE Latin1_General_100_BIN2 NOT NULL,
    CONSTRAINT PK_account_statement PRIMARY KEY (statement_run_id, acct_id),
    CONSTRAINT FK_account_statement_capture FOREIGN KEY (statement_run_id, acct_id)
        REFERENCES batch.statement_capture (statement_run_id, acct_id),
    CONSTRAINT CK_account_statement_period CHECK (period_start <= period_end),
    CONSTRAINT CK_account_statement_reconciled CHECK (closing_balance = opening_balance + net_amount + adjustment_amount),
    CONSTRAINT CK_account_statement_minimum CHECK (minimum_payment >= 0
        AND (minimum_payment = 0 OR minimum_payment <= closing_balance)),
    CONSTRAINT CK_account_statement_tran_count CHECK (tran_count >= 0),
    CONSTRAINT CK_account_statement_file_name CHECK (file_name = CONCAT(acct_id, '.pdf')),
    CONSTRAINT CK_account_statement_file_sha256 CHECK (LEN(file_sha256) = 64 AND file_sha256 NOT LIKE '%[^0-9a-f]%')
);
GO
