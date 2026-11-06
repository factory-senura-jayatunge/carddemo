-- ENT-CARD-XREF (CARDXREF, CVACT03Y, 50 bytes).
CREATE TABLE core.card_xref (
    card_num  CHAR(16)  COLLATE Latin1_General_100_BIN2 NOT NULL,
    cust_id   CHAR(9)   COLLATE Latin1_General_100_BIN2 NOT NULL,
    acct_id   CHAR(11)  COLLATE Latin1_General_100_BIN2 NOT NULL,
    CONSTRAINT PK_card_xref PRIMARY KEY (card_num),
    CONSTRAINT FK_card_xref_customer FOREIGN KEY (cust_id) REFERENCES core.customer (cust_id),
    CONSTRAINT FK_card_xref_account FOREIGN KEY (acct_id) REFERENCES core.account (acct_id)
);
GO
-- Replaces CXACAIX. The first card of an account is the lowest card number,
-- the order a VSAM BLDINDEX gives duplicate alternate keys (BR-BILL-005).
CREATE INDEX IX_xref_acct ON core.card_xref (acct_id, card_num);
GO
