-- Report runs and export runs (data-model.md, "Report runs"; contract 2.1, 2.3
-- and 4.4). batch.report_run is the transaction report's request and status:
-- transaction-service inserts it (REQUESTED) and records only a trigger
-- failure, through batch.report_run_trigger_failed; batch-service writes
-- RUNNING, COMPLETED and FAILED compare-and-set from the job, and lease
-- recovery fails the row of the recovered execution. The CHECKs tie each status
-- to its columns. The output folder is derived from the ID, never stored.
CREATE TABLE batch.report_run (
    report_run_id    BIGINT IDENTITY(1,1) NOT NULL,
    -- For display only: transaction-service resolves MONTHLY and YEARLY to the dates.
    report_type      VARCHAR(7)    NOT NULL,
    start_date       DATE          NOT NULL,
    end_date         DATE          NOT NULL,
    requested_by     CHAR(8)       COLLATE Latin1_General_100_BIN2 NOT NULL,
    requested_at     DATETIME2(3)  NOT NULL CONSTRAINT DF_report_run_requested_at DEFAULT SYSUTCDATETIME(),
    status           VARCHAR(10)   NOT NULL CONSTRAINT DF_report_run_status DEFAULT 'REQUESTED',
    -- The latest execution; a restart replaces it.
    job_execution_id BIGINT        NULL,
    started_at       DATETIME2(3)  NULL,
    finished_at      DATETIME2(3)  NULL,
    -- A code only, never a message or a value from the data.
    failure_code     VARCHAR(40)   NULL,
    row_version      ROWVERSION    NOT NULL,
    CONSTRAINT PK_report_run PRIMARY KEY (report_run_id),
    CONSTRAINT FK_report_run_execution FOREIGN KEY (job_execution_id)
        REFERENCES batch.BATCH_JOB_EXECUTION (JOB_EXECUTION_ID),
    CONSTRAINT CK_report_run_type CHECK (report_type IN ('MONTHLY', 'YEARLY', 'CUSTOM')),
    CONSTRAINT CK_report_run_dates CHECK (start_date <= end_date),
    CONSTRAINT CK_report_run_status CHECK (
           (status = 'REQUESTED' AND job_execution_id IS NULL AND started_at IS NULL AND finished_at IS NULL
                AND failure_code IS NULL)
        OR (status = 'RUNNING' AND job_execution_id IS NOT NULL AND started_at IS NOT NULL AND finished_at IS NULL
                AND failure_code IS NULL)
        OR (status = 'COMPLETED' AND job_execution_id IS NOT NULL AND started_at IS NOT NULL
                AND finished_at IS NOT NULL AND failure_code IS NULL)
        OR (status = 'FAILED' AND finished_at IS NOT NULL AND failure_code IS NOT NULL)),
    CONSTRAINT CK_report_run_failure_code CHECK (failure_code IS NULL
        OR failure_code IN ('TRIGGER_FAILED', 'JOB_FAILED', 'JOB_STOPPED', 'OWNER_LOST')),
    -- A FAILED row without an execution is a trigger failure, and only that.
    CONSTRAINT CK_report_run_trigger_failed CHECK (
        (failure_code IS NULL OR failure_code <> 'TRIGGER_FAILED' OR (status = 'FAILED' AND job_execution_id IS NULL))
        AND (status <> 'FAILED' OR job_execution_id IS NOT NULL
             OR (failure_code IS NOT NULL AND failure_code = 'TRIGGER_FAILED')))
);
GO
CREATE INDEX IX_report_run_requester ON batch.report_run (requested_by, requested_at DESC);
GO
CREATE INDEX IX_report_run_execution ON batch.report_run (job_execution_id) WHERE job_execution_id IS NOT NULL;
GO

-- The only change transaction-service makes to a row: a definite trigger
-- failure of a row that is still REQUESTED. Owned by dbo like the table, so the
-- caller needs EXECUTE only. Returns the number of rows changed (0 or 1) as its
-- result set and its return value; 0 means the job has already started.
CREATE PROCEDURE batch.report_run_trigger_failed
    @report_run_id BIGINT
AS
BEGIN
    SET NOCOUNT ON;
    DECLARE @changed INT;
    UPDATE batch.report_run
    SET status = 'FAILED', failure_code = 'TRIGGER_FAILED', finished_at = SYSUTCDATETIME()
    WHERE report_run_id = @report_run_id AND status = 'REQUESTED';
    SET @changed = @@ROWCOUNT;
    SELECT @changed AS changed;
    RETURN @changed;
END;
GO

-- A RUNNING row whose execution is non-terminal and whose lease is recoverable
-- by the contract predicate (4.1), with the lease row's recoverable_after_ms:
-- the owner crashed after the task retries were used up, and no launch remains
-- to recover it. An execution without a lease row counts from its LAST_UPDATED
-- (UTC, as batch-service writes it) with the fixed 120 s of lease-less recovery.
CREATE VIEW batch.v_report_run_stalled AS
SELECT r.report_run_id,
       CAST(CASE WHEN r.status = 'RUNNING'
                  AND e.STATUS IN ('STARTING', 'STARTED', 'STOPPING')
                  AND ((l.job_execution_id IS NOT NULL AND l.revoked_at IS NULL
                        AND l.renewed_at < DATEADD(MILLISECOND, -l.recoverable_after_ms, SYSUTCDATETIME()))
                       OR (l.job_execution_id IS NULL
                        AND e.LAST_UPDATED < DATEADD(MILLISECOND, -120000, SYSUTCDATETIME())))
                 THEN 1 ELSE 0 END AS BIT) AS stalled
FROM batch.report_run AS r
LEFT JOIN batch.BATCH_JOB_EXECUTION AS e ON e.JOB_EXECUTION_ID = r.job_execution_id
LEFT JOIN batch.execution_lease AS l ON l.job_execution_id = r.job_execution_id;
GO

-- ENT-EXPORT-RUN: one row per export job instance (exportMastersJob), created
-- by its first step, so a restart of the same instance finds the row. The
-- export writes to exports/<export_id>; importMigrationJob takes the export_id
-- and refuses one that is not COMPLETED.
CREATE TABLE batch.export_run (
    export_id        BIGINT IDENTITY(1,1) NOT NULL,
    job_instance_id  BIGINT        NOT NULL,
    -- The latest execution; a restart replaces it.
    job_execution_id BIGINT        NOT NULL,
    business_date    DATE          NOT NULL,
    status           VARCHAR(10)   NOT NULL CONSTRAINT DF_export_run_status DEFAULT 'RUNNING',
    started_at       DATETIME2(3)  NOT NULL CONSTRAINT DF_export_run_started_at DEFAULT SYSUTCDATETIME(),
    finished_at      DATETIME2(3)  NULL,
    failure_code     VARCHAR(40)   NULL,
    CONSTRAINT PK_export_run PRIMARY KEY (export_id),
    CONSTRAINT UQ_export_run_job_instance UNIQUE (job_instance_id),
    CONSTRAINT FK_export_run_job_instance FOREIGN KEY (job_instance_id)
        REFERENCES batch.BATCH_JOB_INSTANCE (JOB_INSTANCE_ID),
    CONSTRAINT FK_export_run_execution FOREIGN KEY (job_execution_id)
        REFERENCES batch.BATCH_JOB_EXECUTION (JOB_EXECUTION_ID),
    CONSTRAINT CK_export_run_status CHECK (
           (status = 'RUNNING' AND finished_at IS NULL AND failure_code IS NULL)
        OR (status = 'COMPLETED' AND finished_at IS NOT NULL AND failure_code IS NULL)
        OR (status = 'FAILED' AND finished_at IS NOT NULL AND failure_code IS NOT NULL)),
    CONSTRAINT CK_export_run_failure_code CHECK (failure_code IS NULL
        OR failure_code IN ('JOB_FAILED', 'JOB_STOPPED', 'OWNER_LOST'))
);
GO
CREATE INDEX IX_export_run_execution ON batch.export_run (job_execution_id);
GO
