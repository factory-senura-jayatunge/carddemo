-- ENT-TCAT-BAL (TCATBALF, CVTRA01Y, 50 bytes).
CREATE TABLE core.tcat_bal (
    acct_id        CHAR(11)       COLLATE Latin1_General_100_BIN2 NOT NULL,
    type_cd        CHAR(2)        COLLATE Latin1_General_100_BIN2 NOT NULL,
    cat_cd         CHAR(4)        COLLATE Latin1_General_100_BIN2 NOT NULL,
    balance        DECIMAL(11,2)  NOT NULL,
    legacy_filler  CHAR(22)       NULL,
    row_version    ROWVERSION     NOT NULL,
    CONSTRAINT PK_tcat_bal PRIMARY KEY (acct_id, type_cd, cat_cd),
    CONSTRAINT FK_tcat_bal_account FOREIGN KEY (acct_id) REFERENCES core.account (acct_id)
);
