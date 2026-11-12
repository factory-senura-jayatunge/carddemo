-- Sign-on state apart from the user record (identity-service.md, Versions).
--
-- A sign-on wrote failed_attempts, locked_until and last_sign_on_at on
-- iam.app_user and so moved its row_version, the version user administration
-- saves and deletes against: an administrator who fetched a user before that
-- user signed on got 409 RECORD_CHANGED. The three columns move to their own
-- table, so app_user.row_version changes only when the record itself is
-- written. The service adds the row with the user; a user without one has no
-- failed attempts, no lock and no sign-on yet, and sign-on adds it.
CREATE TABLE iam.user_signon_state (
    user_id          NCHAR(8)     COLLATE Latin1_General_100_BIN2 NOT NULL,
    failed_attempts  SMALLINT     NOT NULL CONSTRAINT DF_user_signon_state_failed DEFAULT 0,
    locked_until     DATETIME2(3) NULL,
    last_sign_on_at  DATETIME2(3) NULL,
    CONSTRAINT PK_user_signon_state PRIMARY KEY (user_id),
    -- Users are deleted logically and identity_svc has no DELETE; the cascade
    -- only lets an administrator's or a test's cleanup delete user rows.
    CONSTRAINT FK_signon_state_user FOREIGN KEY (user_id) REFERENCES iam.app_user (user_id)
        ON DELETE CASCADE,
    CONSTRAINT CK_user_signon_state_failed CHECK (failed_attempts >= 0)
);
GO

-- Every user keeps its values, deleted users included (re-adding the ID
-- resets them).
INSERT INTO iam.user_signon_state (user_id, failed_attempts, locked_until, last_sign_on_at)
SELECT user_id, failed_attempts, locked_until, last_sign_on_at FROM iam.app_user;
GO

ALTER TABLE iam.app_user DROP CONSTRAINT CK_app_user_failed, DF_app_user_failed;
ALTER TABLE iam.app_user DROP COLUMN failed_attempts, locked_until, last_sign_on_at;
GO
