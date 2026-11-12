-- ENT-AUTH-FRAUD (Db2 AUTHFRDS): inserted, or updated when the authorization
-- is already reported (COPAUS2C absorbed into the fraud service). Keyed by the
-- detail's auth_id (D-36), so two authorizations never share a row
-- (DEF-AUFR-001). No foreign key: the row outlives the purged detail.
CREATE TABLE pauth.auth_fraud (
    auth_id                 BIGINT        NOT NULL,
    card_num                CHAR(16)      COLLATE Latin1_General_100_BIN2 MASKED WITH (FUNCTION = 'partial(0,"XXXXXXXXXXXX",4)') NOT NULL,
    -- Built from the authorization's processing timestamp (NUM-AUFR-002 fixed).
    auth_ts                 DATETIME2(6)  NOT NULL,
    auth_type               CHAR(4)       NULL,
    card_expiry_date        CHAR(4)       NULL,
    message_type            CHAR(6)       NULL,
    message_source          CHAR(6)       NULL,
    auth_id_code            CHAR(6)       NULL,
    auth_resp_code          CHAR(2)       NULL,
    auth_resp_reason        CHAR(4)       NULL,
    processing_code         CHAR(6)       NULL,
    transaction_amt         DECIMAL(12,2) NULL,
    approved_amt            DECIMAL(12,2) NULL,
    merchant_category_code  CHAR(4)       NULL,
    acqr_country_code       CHAR(3)       NULL,
    pos_entry_mode          SMALLINT      NULL,
    merchant_id             CHAR(15)      NULL,
    merchant_name           VARCHAR(22)   NULL,
    merchant_city           CHAR(13)      NULL,
    merchant_state          CHAR(2)       NULL,
    merchant_zip            CHAR(9)       NULL,
    transaction_id          CHAR(15)      NULL,
    match_status            CHAR(1)       NULL,
    auth_fraud              CHAR(1)       COLLATE Latin1_General_100_BIN2 NOT NULL,
    fraud_rpt_date          DATE          NOT NULL,
    acct_id                 CHAR(11)      COLLATE Latin1_General_100_BIN2 NOT NULL,
    -- The customer from the account's cross-reference, as COPAUS1C passes it.
    cust_id                 CHAR(9)       COLLATE Latin1_General_100_BIN2 NULL,
    -- DEF-AUDT-006 deviation.
    reported_by             CHAR(8)       COLLATE Latin1_General_100_BIN2 NOT NULL,
    reported_at             DATETIME2(3)  NOT NULL CONSTRAINT DF_auth_fraud_reported_at DEFAULT SYSUTCDATETIME(),
    row_version             ROWVERSION    NOT NULL,
    CONSTRAINT PK_auth_fraud PRIMARY KEY (auth_id),
    CONSTRAINT CK_auth_fraud_flag CHECK (auth_fraud IN ('F', 'R'))
);
GO
-- The Db2 key, no longer unique.
CREATE INDEX IX_auth_fraud_card_ts ON pauth.auth_fraud (card_num, auth_ts DESC);
GO
