-- Execution ownership (batch-execution-contract.md, section 4): one row per job
-- execution, inserted in the transaction that creates the execution. The
-- owner renews renewed_at on the database clock; every job transaction ends
-- with the same renewal as its fence. A launch that finds the lease not renewed
-- for TTL + grace revokes it (revoked_at, revoked_reason) and fails the
-- execution in one transaction; revoked rows stay as the record of the takeover.
CREATE TABLE batch.execution_lease (
    job_execution_id BIGINT           NOT NULL,
    -- Random per batch-service process.
    owner_id         UNIQUEIDENTIFIER NOT NULL,
    owner_host       VARCHAR(255)     NOT NULL,
    owner_pid        INT              NOT NULL,
    acquired_at      DATETIME2(3)     NOT NULL CONSTRAINT DF_execution_lease_acquired_at DEFAULT SYSUTCDATETIME(),
    renewed_at       DATETIME2(3)     NOT NULL CONSTRAINT DF_execution_lease_renewed_at DEFAULT SYSUTCDATETIME(),
    -- The owner's TTL + grace: the lease is recoverable once renewed_at is this
    -- much older than the database clock. Stored, so that the database objects
    -- that report stalled runs (batch.v_report_run_stalled) need no settings.
    recoverable_after_ms INT          NOT NULL,
    revoked_at       DATETIME2(3)     NULL,
    revoked_reason   VARCHAR(100)     NULL,
    CONSTRAINT PK_execution_lease PRIMARY KEY (job_execution_id),
    CONSTRAINT FK_execution_lease_execution FOREIGN KEY (job_execution_id)
        REFERENCES batch.BATCH_JOB_EXECUTION (JOB_EXECUTION_ID),
    CONSTRAINT CK_execution_lease_recoverable_after CHECK (recoverable_after_ms > 0),
    CONSTRAINT CK_execution_lease_revoked CHECK ((revoked_at IS NULL AND revoked_reason IS NULL)
        OR (revoked_at IS NOT NULL AND revoked_reason IS NOT NULL))
);
GO

-- Every reader judges the lease by the owner's stored limit, so it never changes
-- after the insert.
CREATE TRIGGER batch.TR_execution_lease_recoverable_after ON batch.execution_lease AFTER UPDATE
AS
BEGIN
    SET NOCOUNT ON;
    IF UPDATE(recoverable_after_ms) AND EXISTS (
            SELECT 1 FROM inserted AS i JOIN deleted AS d ON d.job_execution_id = i.job_execution_id
            WHERE i.recoverable_after_ms <> d.recoverable_after_ms)
        THROW 50040, 'batch.execution_lease.recoverable_after_ms never changes after the insert', 1;
END;
GO
