-- The account and category-balance state a cycle's posting starts from, which
-- cycle-rerun restores (batch.md, "Deliberate rerun"; make-targets.md,
-- cycle-rerun). postDailyTransactionsJob takes it once per cycle, in the
-- transaction that sets batch.cycle.snapshot_at, before its first posting; a
-- restart or a later attempt finds snapshot_at set and keeps the snapshot, so
-- every attempt restores the same state. Only the columns a cycle writes are
-- kept (posting and interest, CBTRN02C and CBACT04C). A category balance the
-- cycle created has no row here, and the restore deletes it. No foreign keys:
-- the test fixtures and retention delete batch.cycle rows and snapshots
-- independently.
ALTER TABLE batch.cycle ADD snapshot_at DATETIME2(3) NULL;
GO

CREATE TABLE batch.snapshot_account (
    cycle_id         INT            NOT NULL,
    acct_id          CHAR(11)       COLLATE Latin1_General_100_BIN2 NOT NULL,
    curr_bal         DECIMAL(12,2)  NOT NULL,
    curr_cyc_credit  DECIMAL(12,2)  NOT NULL,
    curr_cyc_debit   DECIMAL(12,2)  NOT NULL,
    CONSTRAINT PK_snapshot_account PRIMARY KEY (cycle_id, acct_id)
);
GO

-- legacy_filler is kept, with the type of core.tcat_bal's (V411), because
-- posting gives a new category balance the FILLER of the last one it read or
-- wrote (DEF-POST-010), so a rerun must start from the same bytes.
CREATE TABLE batch.snapshot_tcat_bal (
    cycle_id       INT            NOT NULL,
    acct_id        CHAR(11)       COLLATE Latin1_General_100_BIN2 NOT NULL,
    type_cd        CHAR(2)        COLLATE Latin1_General_100_BIN2 NOT NULL,
    cat_cd         CHAR(4)        COLLATE Latin1_General_100_BIN2 NOT NULL,
    balance        DECIMAL(11,2)  NOT NULL,
    legacy_filler  NCHAR(22)      COLLATE Latin1_General_100_BIN2 NULL,
    CONSTRAINT PK_snapshot_tcat_bal PRIMARY KEY (cycle_id, acct_id, type_cd, cat_cd)
);
GO
