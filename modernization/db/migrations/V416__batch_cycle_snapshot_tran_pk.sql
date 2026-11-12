-- The highest core.transaction tran_pk when postDailyTransactionsJob took the
-- cycle's snapshot (V412), set in the same statement as snapshot_at.
-- cycle-rerun restores the snapshot's balances, so it refuses once a
-- transaction outside the cycle was written after it: a bill payment's balance
-- change would be lost while its transaction stays. NULL for a snapshot taken
-- before this column existed; the rerun then falls back to the first row the
-- cycle wrote.
ALTER TABLE batch.cycle ADD snapshot_tran_pk BIGINT NULL;
GO
