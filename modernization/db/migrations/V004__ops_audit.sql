-- Application audit (D-56, D-NFR-10, D-SEC-05): changes made through the
-- services and every reveal of masked PII. A row names the fields involved,
-- never their values. Services get INSERT only, in their R__grants_<service>.sql,
-- so no service can read or change another's rows.
CREATE TABLE ops.audit (
    audit_id        BIGINT IDENTITY(1,1) NOT NULL,
    occurred_at     DATETIME2(3) NOT NULL CONSTRAINT DF_audit_occurred_at DEFAULT SYSUTCDATETIME(),
    service         VARCHAR(40)  NOT NULL,
    actor_user_id   CHAR(8)      COLLATE Latin1_General_100_BIN2 NOT NULL,
    action          VARCHAR(20)  NOT NULL,
    subject_type    VARCHAR(20)  NOT NULL,
    -- The record's business key, or card_id for cards: never a card number.
    subject_key     VARCHAR(40)  NOT NULL,
    field_names     VARCHAR(400) NULL,
    correlation_id  VARCHAR(64)  NULL,
    CONSTRAINT PK_audit PRIMARY KEY (audit_id),
    CONSTRAINT CK_audit_action CHECK (action IN ('UNMASK', 'CREATE', 'UPDATE', 'DELETE'))
);
GO

CREATE INDEX IX_audit_subject ON ops.audit (subject_type, subject_key, occurred_at);
GO
