-- The holder of a batch window (batch-execution-contract.md, section 6): a
-- cycle window names its cycle_id; any other window (reference refresh, data
-- load, import, the CLI) names its run_key, "<dag_id>/<run_id>" or
-- "cli/<host>/<timestamp>/<UUID>". Exactly one of the two is set. Rows opened
-- before this migration named neither; they get a run key of their own so the
-- check can be enforced on every row.
ALTER TABLE ops.batch_window ADD run_key VARCHAR(250) NULL;
GO

UPDATE ops.batch_window SET run_key = CONCAT('pre-v406/', window_id) WHERE cycle_id IS NULL AND run_key IS NULL;
ALTER TABLE ops.batch_window WITH CHECK ADD
    CONSTRAINT CK_batch_window_holder CHECK ((cycle_id IS NOT NULL AND run_key IS NULL)
        OR (cycle_id IS NULL AND run_key IS NOT NULL)),
    CONSTRAINT CK_batch_window_run_key CHECK (run_key IS NULL OR LEN(run_key) >= 1);
GO
