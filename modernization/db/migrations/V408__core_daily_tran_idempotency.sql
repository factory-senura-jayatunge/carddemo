-- An online add (D-27) may carry the client's idempotency key, so that a
-- retry by the same user finds the row it already added instead of adding a
-- second one. Daily-file rows carry none.
ALTER TABLE core.daily_tran ADD idempotency_key UNIQUEIDENTIFIER NULL;
GO
ALTER TABLE core.daily_tran ADD CONSTRAINT CK_daily_tran_idempotency
    CHECK (idempotency_key IS NULL OR (origin = 'ONLINE_ADD' AND created_by IS NOT NULL));
GO
CREATE UNIQUE INDEX UX_daily_idempotency ON core.daily_tran (created_by, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
GO
