-- batch.cycle for the execution contract (batch-execution-contract.md,
-- sections 1, 4.3 and 7): attempt is raised by each deliberate cycle-rerun and
-- is an identifying parameter of every cycle job; the inbound daily file is
-- identified by its name and SHA-256 once stage starts; status is OPEN until
-- the DAG's completing window close, then COMPLETED. Job outcomes live in the
-- job repository, so RUNNING and FAILED go.
ALTER TABLE batch.cycle ADD
    attempt           INT          NOT NULL CONSTRAINT DF_cycle_attempt DEFAULT 1,
    daily_file_name   VARCHAR(255) NULL,
    daily_file_sha256 BINARY(32)   NULL;
GO

UPDATE batch.cycle SET status = 'OPEN', closed_at = NULL WHERE status <> 'COMPLETED';
UPDATE batch.cycle SET closed_at = opened_at WHERE status = 'COMPLETED' AND closed_at IS NULL;
ALTER TABLE batch.cycle DROP CONSTRAINT CK_cycle_status;
GO

ALTER TABLE batch.cycle ADD
    CONSTRAINT CK_cycle_status CHECK ((status = 'OPEN' AND closed_at IS NULL)
        OR (status = 'COMPLETED' AND closed_at IS NOT NULL)),
    CONSTRAINT CK_cycle_attempt CHECK (attempt >= 1),
    CONSTRAINT CK_cycle_daily_file CHECK ((daily_file_name IS NULL AND daily_file_sha256 IS NULL)
        OR (daily_file_name IS NOT NULL AND daily_file_sha256 IS NOT NULL));
GO
