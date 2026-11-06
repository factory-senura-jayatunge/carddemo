-- iam schema: users (replaces USRSEC), refresh tokens and sign-on audit.
-- Owned by identity-service; see docs/modernization/architecture/data-model.md.
CREATE SCHEMA iam;
GO

CREATE TABLE iam.app_user (
    user_id              CHAR(8) COLLATE Latin1_General_100_BIN2 NOT NULL,
    first_name           VARCHAR(20) NOT NULL,
    last_name            VARCHAR(20) NOT NULL,
    -- Spring Security DelegatingPasswordEncoder format, for example {bcrypt}$2a$12$...
    password_hash        VARCHAR(100) NOT NULL,
    -- How the hash input was prepared: U = upper-cased first (legacy BR-SGN-001/003),
    -- S = as typed (case-sensitive, D-04). Stored per row so a hash stays verifiable
    -- after the fidelity switch changes; only new passwords follow the current switch.
    password_case        CHAR(1) NOT NULL
        CONSTRAINT CK_app_user_password_case CHECK (password_case IN ('U', 'S')),
    must_change_password BIT NOT NULL CONSTRAINT DF_app_user_must_change DEFAULT 0,
    password_changed_at  DATETIME2(3) NULL,
    -- Any character, as USRSEC accepts any type (DEF-USRA-002); sign-on decides
    -- what an unknown type means (DEF-SGN-005, D-02).
    user_type            CHAR(1) COLLATE Latin1_General_100_BIN2 NOT NULL,
    -- EBCDIC byte image of user_id for the legacy user-list order (NUM-USRL-002).
    ebcdic_sort_key      BINARY(8) NOT NULL,
    failed_attempts      SMALLINT NOT NULL CONSTRAINT DF_app_user_failed DEFAULT 0,
    last_failed_at       DATETIME2(3) NULL,
    locked_until         DATETIME2(3) NULL,
    last_sign_on_at      DATETIME2(3) NULL,
    created_at           DATETIME2(3) NOT NULL CONSTRAINT DF_app_user_created_at DEFAULT SYSUTCDATETIME(),
    created_by           CHAR(8) COLLATE Latin1_General_100_BIN2 NULL,
    updated_at           DATETIME2(3) NULL,
    updated_by           CHAR(8) COLLATE Latin1_General_100_BIN2 NULL,
    deleted_at           DATETIME2(3) NULL,
    deleted_by           CHAR(8) COLLATE Latin1_General_100_BIN2 NULL,
    row_version          ROWVERSION NOT NULL,
    CONSTRAINT PK_app_user PRIMARY KEY (user_id)
);
GO

CREATE UNIQUE INDEX UX_app_user_ebcdic ON iam.app_user (ebcdic_sort_key);
GO

CREATE TABLE iam.refresh_token (
    token_id      BIGINT IDENTITY NOT NULL,
    -- SHA-256 of the opaque cookie value; the value itself is never stored.
    token_hash    BINARY(32) NOT NULL,
    -- All tokens of one sign-on share a family; reuse of a rotated token revokes it.
    family_id     UNIQUEIDENTIFIER NOT NULL,
    user_id       CHAR(8) COLLATE Latin1_General_100_BIN2 NOT NULL,
    issued_at     DATETIME2(3) NOT NULL,
    expires_at    DATETIME2(3) NOT NULL,
    family_expires_at DATETIME2(3) NOT NULL,
    rotated_at    DATETIME2(3) NULL,
    revoked_at    DATETIME2(3) NULL,
    revoke_reason VARCHAR(20) NULL,
    CONSTRAINT PK_refresh_token PRIMARY KEY (token_id),
    CONSTRAINT FK_refresh_token_user FOREIGN KEY (user_id) REFERENCES iam.app_user (user_id)
);
GO

CREATE UNIQUE INDEX UX_refresh_token_hash ON iam.refresh_token (token_hash);
CREATE INDEX IX_refresh_token_family ON iam.refresh_token (family_id);
CREATE INDEX IX_refresh_token_user ON iam.refresh_token (user_id, revoked_at);
GO

CREATE TABLE iam.audit_event (
    event_id        BIGINT IDENTITY NOT NULL,
    occurred_at     DATETIME2(3) NOT NULL CONSTRAINT DF_audit_event_at DEFAULT SYSUTCDATETIME(),
    event_type      VARCHAR(40) NOT NULL,
    outcome         VARCHAR(10) NOT NULL
        CONSTRAINT CK_audit_event_outcome CHECK (outcome IN ('SUCCESS', 'FAILURE')),
    reason_code     VARCHAR(40) NULL,
    -- Who acted (NULL before sign-on) and whose record the event is about. No FK:
    -- failed sign-ons name IDs that do not exist, and users may be deleted.
    actor_user_id   CHAR(8) COLLATE Latin1_General_100_BIN2 NULL,
    subject_user_id CHAR(8) COLLATE Latin1_General_100_BIN2 NULL,
    -- Names of changed fields only, never their values.
    changed_fields  VARCHAR(400) NULL,
    client_ip       VARCHAR(45) NULL,
    session_family  UNIQUEIDENTIFIER NULL,
    CONSTRAINT PK_audit_event PRIMARY KEY (event_id)
);
GO

CREATE INDEX IX_audit_event_subject ON iam.audit_event (subject_user_id, occurred_at);
CREATE INDEX IX_audit_event_type ON iam.audit_event (event_type, occurred_at);
GO
