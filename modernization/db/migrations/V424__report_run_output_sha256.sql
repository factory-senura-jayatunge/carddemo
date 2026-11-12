-- The committed sha256 of a completed transaction report (data-model.md,
-- "Report runs"; batch-execution-contract.md 4.3 and 4.10; transaction-service.md
-- section 16). batch-service sets output_sha256 in the UPDATE that writes
-- COMPLETED, in both completion forms, to the intent's sha256 that the
-- publication verified. transaction-service serves a report only when the bytes
-- it read back hash to this value. Only a COMPLETED row carries a hash, and a
-- COMPLETED row is never changed afterwards.
--
-- CK_report_run_sha256_completed (a hash only on a COMPLETED row) holds for
-- every existing row, which has no hash yet, so it is added checked and trusted.
-- CK_report_run_completed_sha256 (every COMPLETED row has a hash) is added
-- WITH NOCHECK: a row completed before this migration has no hash, and nothing
-- infers, backfills or repairs one; the download refuses such a row (log reason
-- NO_SHA256) and the user requests a new run. Every row inserted or updated from
-- now on is checked, so a producer that completes a run without the hash fails
-- that UPDATE: db-migrate runs before batch-service starts. The constraint stays
-- untrusted, so no plan relies on it for the old rows.

ALTER TABLE batch.report_run ADD output_sha256 BINARY(32) NULL;
GO

ALTER TABLE batch.report_run WITH CHECK ADD CONSTRAINT CK_report_run_sha256_completed
    CHECK (output_sha256 IS NULL OR status = 'COMPLETED');
ALTER TABLE batch.report_run WITH NOCHECK ADD CONSTRAINT CK_report_run_completed_sha256
    CHECK (status <> 'COMPLETED' OR output_sha256 IS NOT NULL);
GO
