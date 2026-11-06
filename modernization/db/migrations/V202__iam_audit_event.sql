-- Security events of identity-service. detail holds reason codes only, never a
-- password or another typed value; subject_key is the user ID the event is about.
CREATE TABLE iam.audit_event (
    event_id      BIGINT IDENTITY(1,1) NOT NULL,
    occurred_at   DATETIME2(3) NOT NULL,
    actor_user_id CHAR(8)      COLLATE Latin1_General_100_BIN2 NULL,
    event_type    VARCHAR(40)  NOT NULL,
    subject_key   VARCHAR(40)  NULL,
    detail        VARCHAR(200) NULL,
    client_ip     VARCHAR(45)  NULL,
    CONSTRAINT PK_audit_event PRIMARY KEY (event_id),
    CONSTRAINT CK_audit_event_type CHECK (event_type IN
        ('SIGN_ON_OK', 'SIGN_ON_FAILED', 'USER_LOCKED', 'SIGN_OFF', 'SESSION_EXPIRED',
         'PASSWORD_CHANGED', 'REFRESH_REUSE'))
);
GO

CREATE INDEX IX_audit_event_subject ON iam.audit_event (subject_key, occurred_at);
GO
