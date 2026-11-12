-- The user ID family outside iam (data-model.md, "Text repertoire and width",
-- and identity-service.md, "Rule"): every column that stores a user ID is
-- NCHAR(8) COLLATE Latin1_General_100_BIN2, the type of iam.app_user.user_id
-- after V203, so a user ID that user add or update accepts fits every column
-- that later stores it. ops.audit.subject_key may hold a user ID and becomes
-- NVARCHAR(40). Nullability is unchanged; the ASCII values convert in place.
-- iam's own members are V203's, pauth's are V505's: no foreign key joins
-- members in different ranges.
--
-- This version is above every migration that creates one of these columns or
-- an object depending on one (V004, V014, V018, V100, V403, V407, V408, V411,
-- V413), so a fresh and an upgraded database drop and recreate the same
-- objects. What names an altered column is dropped and recreated with the same
-- name and definition: CK_daily_tran_idempotency, UX_daily_idempotency,
-- IX_report_run_requester and IX_audit_subject; core.v_daily_reject is
-- recreated as V413 left it. No default or mask is on these columns. Grants
-- survive ALTER COLUMN and ALTER VIEW; R__grants_batch.sql names this
-- migration, so its grants are applied again after it.

ALTER TABLE core.[transaction] ALTER COLUMN created_by NCHAR(8) COLLATE Latin1_General_100_BIN2 NULL;
GO

DROP INDEX UX_daily_idempotency ON core.daily_tran;
ALTER TABLE core.daily_tran DROP CONSTRAINT CK_daily_tran_idempotency;
GO
ALTER TABLE core.daily_tran ALTER COLUMN created_by NCHAR(8) COLLATE Latin1_General_100_BIN2 NULL;
GO
ALTER TABLE core.daily_tran ADD CONSTRAINT CK_daily_tran_idempotency
    CHECK (idempotency_key IS NULL OR (origin = 'ONLINE_ADD' AND created_by IS NOT NULL));
GO
CREATE UNIQUE INDEX UX_daily_idempotency ON core.daily_tran (created_by, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
GO

-- As V413.
CREATE OR ALTER VIEW core.v_daily_reject AS
SELECT r.cycle_id,
       c.business_date,
       r.seq,
       t.origin,
       t.created_by,
       r.reason_cd,
       r.reason_desc,
       r.reason_cds,
       CAST(STUFF(r.raw_record, 263, 12, N'XXXXXXXXXXXX') AS NCHAR(350)) AS raw_record_masked,
       r.created_at
FROM core.daily_reject AS r
JOIN batch.cycle AS c ON c.cycle_id = r.cycle_id
JOIN core.daily_tran AS t ON t.daily_pk = r.daily_pk;
GO

DROP INDEX IX_report_run_requester ON batch.report_run;
GO
ALTER TABLE batch.report_run ALTER COLUMN requested_by NCHAR(8) COLLATE Latin1_General_100_BIN2 NOT NULL;
GO
CREATE INDEX IX_report_run_requester ON batch.report_run (requested_by, requested_at DESC);
GO

DROP INDEX IX_audit_subject ON ops.audit;
GO
ALTER TABLE ops.audit ALTER COLUMN actor_user_id NCHAR(8) COLLATE Latin1_General_100_BIN2 NOT NULL;
ALTER TABLE ops.audit ALTER COLUMN subject_key NVARCHAR(40) NOT NULL;
GO
CREATE INDEX IX_audit_subject ON ops.audit (subject_type, subject_key, occurred_at);
GO

ALTER TABLE billpay.payment ALTER COLUMN paid_by NCHAR(8) COLLATE Latin1_General_100_BIN2 NOT NULL;
GO
