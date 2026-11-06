-- iam schema (V200-V299, identity-service). ENT-USER-SECURITY: the USRSEC KSDS
-- (KEYS(8 0)) becomes iam.app_user. Passwords are never stored in clear: the
-- hash is in Spring's DelegatingPasswordEncoder format ({bcrypt}..., strength 12).
IF SCHEMA_ID(N'iam') IS NULL
    EXEC (N'CREATE SCHEMA iam');
GO

CREATE TABLE iam.app_user (
    user_id              CHAR(8)      COLLATE Latin1_General_100_BIN2 NOT NULL,
    first_name           VARCHAR(20)  NOT NULL,
    last_name            VARCHAR(20)  NOT NULL,
    password_hash        VARCHAR(100) NOT NULL,
    -- As loaded, no check constraint: legacy mode of DEF-SGN-005 signs on any
    -- type other than A as a regular user.
    user_type            CHAR(1)      COLLATE Latin1_General_100_BIN2 NOT NULL,
    must_change_password BIT          NOT NULL CONSTRAINT DF_app_user_must_change DEFAULT 0,
    failed_attempts      SMALLINT     NOT NULL CONSTRAINT DF_app_user_failed DEFAULT 0,
    locked_until         DATETIME2(3) NULL,
    last_sign_on_at      DATETIME2(3) NULL,
    -- The user ID in EBCDIC (CP037), padded with X'40': the VSAM key order of
    -- the user list (NUM-USRL-002).
    ebcdic_sort_key      BINARY(8)    NOT NULL,
    created_at           DATETIME2(3) NOT NULL CONSTRAINT DF_app_user_created_at DEFAULT SYSUTCDATETIME(),
    created_by           CHAR(8)      COLLATE Latin1_General_100_BIN2 NULL,
    updated_at           DATETIME2(3) NULL,
    updated_by           CHAR(8)      COLLATE Latin1_General_100_BIN2 NULL,
    row_version          ROWVERSION   NOT NULL,
    CONSTRAINT PK_app_user PRIMARY KEY (user_id),
    -- BR-SGN-001: IDs are stored upper case. Only a-z, as COBOL UPPER-CASE;
    -- under BIN2 the range matches code points, so other letters pass.
    CONSTRAINT CK_app_user_upper CHECK (user_id NOT LIKE '%[a-z]%'),
    CONSTRAINT CK_app_user_not_blank CHECK (user_id <> ''),
    CONSTRAINT CK_app_user_failed CHECK (failed_attempts >= 0)
);
GO

CREATE INDEX IX_app_user_ebcdic ON iam.app_user (ebcdic_sort_key);
GO
