-- Bill Pay's own record of each payment: who paid (the legacy record has no
-- user, DEF-BILL-009), the balance it cleared, and the idempotency key that
-- makes a retried POST return the first result instead of paying twice.
CREATE TABLE billpay.payment (
    payment_id       BIGINT IDENTITY(1,1) NOT NULL,
    tran_pk          BIGINT            NOT NULL,
    tran_id          CHAR(16)          COLLATE Latin1_General_100_BIN2 NOT NULL,
    acct_id          CHAR(11)          COLLATE Latin1_General_100_BIN2 NOT NULL,
    paid_by          CHAR(8)           COLLATE Latin1_General_100_BIN2 NOT NULL,
    idempotency_key  UNIQUEIDENTIFIER  NOT NULL,
    -- As stored on the transaction: positive in legacy mode, negative in fixed (DEF-BILL-010).
    amount           DECIMAL(11,2)     NOT NULL,
    balance_before   DECIMAL(12,2)     NOT NULL,
    created_at       DATETIME2(6)      NOT NULL,
    CONSTRAINT PK_payment PRIMARY KEY (payment_id),
    CONSTRAINT UQ_payment_tran_pk UNIQUE (tran_pk),
    CONSTRAINT FK_payment_transaction FOREIGN KEY (tran_pk) REFERENCES core.[transaction] (tran_pk),
    CONSTRAINT UQ_payment_idempotency_key UNIQUE (idempotency_key)
);
GO
CREATE INDEX IX_payment_acct ON billpay.payment (acct_id, created_at);
GO
