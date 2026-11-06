-- ENT-DAILY-TRAN (DALYTRAN.PS, CVTRA06Y, 350 bytes): the posting input, from
-- the daily file and, by D-27, from online adds. An online add inserts a
-- waiting row (cycle_id and seq NULL); ingestDailyFileJob stages the file rows
-- of a cycle with seq from 1, then claims every waiting row with the next seq
-- numbers in daily_pk order, so a cycle without online adds posts exactly as
-- before. The columns hold the fields as read, without validation, so staging
-- never refuses a record that posting must reject; raw_record is the record as
-- read, which a reject carries unchanged (it holds the card number, so the
-- table is granted only to the services that write it). amount is NULL when
-- DALYTRAN-AMT is not valid zoned decimal.
-- The batch range adds the foreign key to batch.cycle (V402).
CREATE TABLE core.daily_tran (
    daily_pk        BIGINT IDENTITY(1,1) NOT NULL,
    cycle_id        INT            NULL,
    seq             INT            NULL,
    origin          VARCHAR(12)    NOT NULL,
    -- The signed-on user of an online add; the file has none.
    created_by      CHAR(8)        COLLATE Latin1_General_100_BIN2 NULL,
    created_at      DATETIME2(3)   NOT NULL CONSTRAINT DF_daily_tran_created_at DEFAULT SYSUTCDATETIME(),
    tran_id         CHAR(16)       COLLATE Latin1_General_100_BIN2 NOT NULL,
    type_cd         CHAR(2)        COLLATE Latin1_General_100_BIN2 NOT NULL,
    cat_cd          CHAR(4)        COLLATE Latin1_General_100_BIN2 NOT NULL,
    source          VARCHAR(10)    NOT NULL,
    description     VARCHAR(100)   NOT NULL,
    amount          DECIMAL(11,2)  NULL,
    merchant_id     CHAR(9)        NOT NULL,
    merchant_name   VARCHAR(50)    NOT NULL,
    merchant_city   VARCHAR(50)    NOT NULL,
    merchant_zip    VARCHAR(10)    NOT NULL,
    card_num        CHAR(16)       COLLATE Latin1_General_100_BIN2 MASKED WITH (FUNCTION = 'partial(0,"XXXXXXXXXXXX",4)') NOT NULL,
    -- Text as in the file: DALYTRAN-PROC-TS is blank in the sample input.
    orig_ts         CHAR(26)       NOT NULL,
    proc_ts         CHAR(26)       NOT NULL,
    raw_record      CHAR(350)      COLLATE Latin1_General_100_BIN2 NOT NULL,
    CONSTRAINT PK_daily_tran PRIMARY KEY (daily_pk),
    CONSTRAINT CK_daily_tran_origin CHECK (origin IN ('DAILY_FILE', 'ONLINE_ADD')),
    -- seq IS NOT NULL is spelled out: a NULL seq would make seq >= 1 unknown, which a CHECK accepts.
    CONSTRAINT CK_daily_tran_claimed CHECK ((cycle_id IS NULL AND seq IS NULL) OR (cycle_id IS NOT NULL AND seq IS NOT NULL AND seq >= 1)),
    -- Only online adds wait; file rows are staged into their cycle.
    CONSTRAINT CK_daily_tran_waiting CHECK (cycle_id IS NOT NULL OR origin = 'ONLINE_ADD')
);
GO
-- The posting order of a cycle (BR-POST-007).
CREATE UNIQUE INDEX UX_daily_claimed ON core.daily_tran (cycle_id, seq) WHERE cycle_id IS NOT NULL;
GO
-- Legacy "highest + 1" reads master rows and waiting adds; no two waiting adds share an ID.
CREATE UNIQUE INDEX UX_daily_waiting ON core.daily_tran (tran_id) WHERE cycle_id IS NULL;
GO

-- ENT-DAILY-REJECT (DALYREJS, CBTRN02C's 430-byte reject record): the input
-- record as read plus the validation trailer. daily_pk ties the reject to its
-- input row; (cycle_id, seq) is its position in the cycle's reject file.
-- The batch range adds the foreign key to batch.cycle (V402) and the
-- operations view core.v_daily_reject (V403).
CREATE TABLE core.daily_reject (
    cycle_id        INT            NOT NULL,
    seq             INT            NOT NULL,
    daily_pk        BIGINT         NOT NULL,
    reason_cd       CHAR(4)        COLLATE Latin1_General_100_BIN2 NOT NULL,
    reason_desc     VARCHAR(76)    NOT NULL,
    raw_record      CHAR(350)      COLLATE Latin1_General_100_BIN2 NOT NULL,
    created_at      DATETIME2(3)   NOT NULL CONSTRAINT DF_daily_reject_created_at DEFAULT SYSUTCDATETIME(),
    CONSTRAINT PK_daily_reject PRIMARY KEY (cycle_id, seq),
    CONSTRAINT UQ_daily_reject_daily_pk UNIQUE (daily_pk),
    CONSTRAINT FK_daily_reject_daily_tran FOREIGN KEY (daily_pk) REFERENCES core.daily_tran (daily_pk),
    CONSTRAINT CK_daily_reject_reason CHECK (reason_cd IN ('0100', '0101', '0102', '0103'))
);
GO
