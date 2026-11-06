-- ENT-ACCOUNT (ACCTDATA, CVACT01Y, 300 bytes). Identifiers are text with a
-- digits check, so '123' is not '00000000123' (VAL-BILL-003, DEF-BILL-004).
CREATE TABLE core.account (
    acct_id            CHAR(11)       COLLATE Latin1_General_100_BIN2 NOT NULL,
    active_status      CHAR(1)        NOT NULL,
    curr_bal           DECIMAL(12,2)  NOT NULL,
    credit_limit       DECIMAL(12,2)  NOT NULL,
    cash_credit_limit  DECIMAL(12,2)  NOT NULL,
    open_date          DATE           NULL,
    expiration_date    DATE           NULL,
    reissue_date       DATE           NULL,
    curr_cyc_credit    DECIMAL(12,2)  NOT NULL,
    curr_cyc_debit     DECIMAL(12,2)  NOT NULL,
    -- Positions 103-112; holds A000000000 in every sample account (INV-039).
    addr_zip           VARCHAR(10)    NOT NULL CONSTRAINT DF_account_addr_zip DEFAULT '',
    group_id           CHAR(10)       COLLATE Latin1_General_100_BIN2 NOT NULL CONSTRAINT DF_account_group_id DEFAULT '',
    -- FILLER 123-300, only for byte-exact export (D-DATA-03).
    legacy_filler      CHAR(178)      NULL,
    row_version        ROWVERSION     NOT NULL,
    CONSTRAINT PK_account PRIMARY KEY (acct_id),
    CONSTRAINT CK_account_acct_id CHECK (LEN(acct_id) = 11 AND acct_id NOT LIKE '%[^0-9]%')
);
