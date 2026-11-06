-- ENT-TRANSACTION (TRANSACT, CVTRA05Y, 350 bytes). All cycles stay in one
-- table; the legacy transaction master is the rows with in_master = 1.
-- Timestamps are DATETIME2(6) (D-60); the legacy text layouts are produced
-- only by the exporter.
CREATE TABLE core.[transaction] (
    tran_pk         BIGINT IDENTITY(1,1) NOT NULL,
    tran_id         CHAR(16)       COLLATE Latin1_General_100_BIN2 NOT NULL,
    type_cd         CHAR(2)        COLLATE Latin1_General_100_BIN2 NOT NULL,
    cat_cd          CHAR(4)        COLLATE Latin1_General_100_BIN2 NOT NULL,
    source          VARCHAR(10)    NOT NULL,
    description     VARCHAR(100)   NOT NULL,
    amount          DECIMAL(11,2)  NOT NULL,
    merchant_id     CHAR(9)        NOT NULL,
    merchant_name   VARCHAR(50)    NOT NULL,
    merchant_city   VARCHAR(50)    NOT NULL,
    merchant_zip    VARCHAR(10)    NOT NULL,
    card_num        CHAR(16)       COLLATE Latin1_General_100_BIN2 MASKED WITH (FUNCTION = 'partial(0,"XXXXXXXXXXXX",4)') NOT NULL,
    orig_ts         DATETIME2(6)   NOT NULL,
    proc_ts         DATETIME2(6)   NOT NULL,
    proc_date       AS CAST(proc_ts AS DATE) PERSISTED,
    -- Inherited by online writers in legacy mode (DEF-BILL-006); NULL otherwise.
    legacy_filler   CHAR(20)       NULL,
    origin          VARCHAR(12)    NOT NULL,
    -- The batch range adds the foreign key to batch.cycle and the stamping rule.
    cycle_id        INT            NULL,
    in_master       BIT            NOT NULL CONSTRAINT DF_transaction_in_master DEFAULT 1,
    -- The signed-on user of an online write; the legacy record has none (DEF-BILL-009).
    created_by      CHAR(8)        COLLATE Latin1_General_100_BIN2 NULL,
    CONSTRAINT PK_transaction PRIMARY KEY (tran_pk),
    CONSTRAINT CK_transaction_tran_id CHECK (LEN(tran_id) = 16),
    CONSTRAINT CK_transaction_origin CHECK (origin IN ('LOADED', 'POSTED', 'INTEREST', 'ONLINE_ADD', 'BILL_PAY'))
);
GO
CREATE UNIQUE INDEX UX_tran_master ON core.[transaction] (tran_id) WHERE in_master = 1;
GO
CREATE INDEX IX_tran_card_master ON core.[transaction] (card_num, tran_id) WHERE in_master = 1;
GO
CREATE INDEX IX_tran_proc_date ON core.[transaction] (proc_date, card_num);
GO
