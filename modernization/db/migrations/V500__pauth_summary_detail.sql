-- Pending authorizations (V500-V599). The schema comes with its range (V001).
CREATE SCHEMA pauth;
GO

-- ENT-AUTH-SUMMARY (IMS root PAUTSUM0, CIPAUSMY). One row per account, created
-- by the first authorization of one of its cards (BR-AUTH-009). PA-AUTH-STATUS
-- and PA-ACCOUNT-STATUS get no columns: no program reads them, and online-
-- created summaries hold leftover bytes in them (DEF-AUTH-013).
CREATE TABLE pauth.auth_summary (
    acct_id            CHAR(11)      COLLATE Latin1_General_100_BIN2 NOT NULL,
    cust_id            CHAR(9)       COLLATE Latin1_General_100_BIN2 NOT NULL,
    credit_limit       DECIMAL(11,2) NOT NULL,
    cash_limit         DECIMAL(11,2) NOT NULL,
    -- Pending approvals held against the credit limit; the purge releases them (D-33).
    credit_balance     DECIMAL(11,2) NOT NULL,
    cash_balance       DECIMAL(11,2) NOT NULL,
    -- Wider than the IMS S9(4) COMP and S9(9)V99: the declined totals grow with
    -- every decline, and one decline at the largest valid amount already
    -- overflows S9(9)V99.
    approved_auth_cnt  INT           NOT NULL,
    declined_auth_cnt  INT           NOT NULL,
    approved_auth_amt  DECIMAL(15,2) NOT NULL,
    declined_auth_amt  DECIMAL(15,2) NOT NULL,
    row_version        ROWVERSION    NOT NULL,
    CONSTRAINT PK_auth_summary PRIMARY KEY (acct_id),
    CONSTRAINT FK_auth_summary_account FOREIGN KEY (acct_id) REFERENCES core.account (acct_id),
    CONSTRAINT CK_auth_summary_acct_id CHECK (LEN(acct_id) = 11 AND acct_id NOT LIKE '%[^0-9]%'),
    CONSTRAINT CK_auth_summary_cust_id CHECK (LEN(cust_id) = 9 AND cust_id NOT LIKE '%[^0-9]%')
);
GO

-- ENT-AUTH-DETAIL (IMS child PAUTDTL1, CIPAUDTY). One row per request whose
-- card was found (BR-AUTH-010). processed_at replaces the 9-complement key for
-- newest-first order. The 9-complement parts stay, unique per account, which
-- reproduces the IMS duplicate-key rule; the legacy fraud timestamp derives
-- from them (NUM-AUFR-002) and the loader carries them.
-- auth_id is never reseeded and the table is never truncated: fraud rows keep
-- the ID after the detail is purged (D-36), so an ID must never come back.
CREATE TABLE pauth.auth_detail (
    auth_id                 BIGINT IDENTITY(1,1) NOT NULL,
    acct_id                 CHAR(11)      COLLATE Latin1_General_100_BIN2 NOT NULL,
    processed_at            DATETIME2(3)  NOT NULL,
    auth_date_9c            INT           NOT NULL,
    auth_time_9c            INT           NOT NULL,
    orig_date               CHAR(6)       NOT NULL,
    orig_time               CHAR(6)       NOT NULL,
    card_num                CHAR(16)      COLLATE Latin1_General_100_BIN2 MASKED WITH (FUNCTION = 'partial(0,"XXXXXXXXXXXX",4)') NOT NULL,
    auth_type               CHAR(4)       NOT NULL,
    card_expiry_date        CHAR(4)       NOT NULL,
    message_type            CHAR(6)       NOT NULL,
    message_source          CHAR(6)       NOT NULL,
    -- New rows: the six rightmost digits of auth_id (D-32); loaded rows keep the
    -- legacy code. Deliberately not unique.
    auth_id_code            CHAR(6)       NOT NULL,
    auth_resp_code          CHAR(2)       COLLATE Latin1_General_100_BIN2 NOT NULL,
    auth_resp_reason        CHAR(4)       COLLATE Latin1_General_100_BIN2 NOT NULL,
    processing_code         CHAR(6)       NOT NULL,
    transaction_amt         DECIMAL(12,2) NOT NULL,
    approved_amt            DECIMAL(12,2) NOT NULL,
    -- The source spells it CATAGORY.
    merchant_category_code  CHAR(4)       NOT NULL,
    acqr_country_code       CHAR(3)       NOT NULL,
    pos_entry_mode          CHAR(2)       NOT NULL,
    merchant_id             CHAR(15)      NOT NULL,
    merchant_name           VARCHAR(22)   NOT NULL,
    merchant_city           VARCHAR(13)   NOT NULL,
    merchant_state          CHAR(2)       NOT NULL,
    merchant_zip            VARCHAR(9)    NOT NULL,
    transaction_id          CHAR(15)      NOT NULL,
    match_status            CHAR(1)       COLLATE Latin1_General_100_BIN2 NOT NULL,
    auth_fraud              CHAR(1)       COLLATE Latin1_General_100_BIN2 NOT NULL CONSTRAINT DF_auth_detail_fraud DEFAULT ' ',
    fraud_rpt_date          DATE          NULL,
    -- DEF-AUDT-006 deviation: the signed-on user who last changed the fraud flag.
    fraud_changed_by        CHAR(8)       COLLATE Latin1_General_100_BIN2 NULL,
    row_version             ROWVERSION    NOT NULL,
    CONSTRAINT PK_auth_detail PRIMARY KEY (auth_id),
    -- The purge deletes a summary with its details.
    CONSTRAINT FK_auth_detail_summary FOREIGN KEY (acct_id) REFERENCES pauth.auth_summary (acct_id) ON DELETE CASCADE,
    CONSTRAINT UQ_auth_detail_9c UNIQUE (acct_id, auth_date_9c, auth_time_9c),
    CONSTRAINT CK_auth_detail_resp CHECK (auth_resp_code IN ('00', '05')),
    CONSTRAINT CK_auth_detail_match CHECK (match_status IN ('P', 'D', 'E', 'M')),
    CONSTRAINT CK_auth_detail_fraud CHECK (auth_fraud IN (' ', 'F', 'R'))
);
GO
-- The summary screen's list (newest first) and the detail's "next older" link.
CREATE INDEX IX_auth_detail_acct_newest ON pauth.auth_detail (acct_id, processed_at DESC, auth_id DESC);
GO
-- The purge by age (D-34).
CREATE INDEX IX_auth_detail_processed ON pauth.auth_detail (processed_at);
GO
