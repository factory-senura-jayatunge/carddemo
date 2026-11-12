-- D-33 matching (decided 2026-11-10, option 1): a pending approval is released
-- when exactly one approval and one transaction posted by the cycle agree on
-- card, amount, merchant and date window. The match is recorded on the detail.
-- matched_tran_pk is the stable key: tran_id can repeat across cycles in legacy
-- ID mode, so it is kept for display only. No foreign keys: retention may
-- delete the transaction, and a cycle rerun clears its matches by cycle.
ALTER TABLE pauth.auth_detail ADD
    matched_tran_pk   BIGINT        NULL,
    matched_tran_id   CHAR(16)      COLLATE Latin1_General_100_BIN2 NULL,
    matched_cycle_id  INT           NULL,
    matched_at        DATETIME2(3)  NULL;
GO
-- (match_status = 'M') = (matched_tran_pk IS NOT NULL), as two clauses; the four
-- columns are set by one match and cleared by one rerun, so they go together.
ALTER TABLE pauth.auth_detail ADD
    CONSTRAINT CK_auth_detail_matched_set CHECK (match_status <> 'M' OR matched_tran_pk IS NOT NULL),
    CONSTRAINT CK_auth_detail_matched_clear CHECK (match_status = 'M' OR matched_tran_pk IS NULL),
    CONSTRAINT CK_auth_detail_match_columns CHECK (
        (matched_tran_pk IS NULL AND matched_tran_id IS NULL AND matched_cycle_id IS NULL AND matched_at IS NULL)
        OR (matched_tran_pk IS NOT NULL AND matched_tran_id IS NOT NULL AND matched_cycle_id IS NOT NULL AND matched_at IS NOT NULL));
GO
-- A transaction settles at most one approval.
CREATE UNIQUE INDEX UX_auth_detail_matched_tran ON pauth.auth_detail (matched_tran_pk) WHERE matched_tran_pk IS NOT NULL;
GO
-- The candidate step's lookup of pending approvals by card and amount.
CREATE INDEX IX_auth_detail_pending ON pauth.auth_detail (card_num, approved_amt)
    INCLUDE (merchant_id, processed_at) WHERE match_status = 'P';
GO
