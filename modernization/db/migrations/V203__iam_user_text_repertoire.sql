-- Text repertoire of the iam tables (data-model.md, Text repertoire and width).
-- A legacy X(n) field holds any of the 256 characters U+0000 to U+00FF. Under
-- Latin1_General_100_BIN2, CHAR stores code page 1252, which has no U+0080 to
-- U+009F; under the UTF-8 default collation, VARCHAR(20) holds 20 bytes, not 20
-- accented characters. So the user ID family becomes NCHAR(8) BIN2 (same order:
-- code point order), the names NVARCHAR(20), the type NCHAR(1) BIN2 and the
-- audit subject NVARCHAR(40).
--
-- Only iam: no object outside iam references these columns and no migration
-- above V202 creates anything that depends on them, so this file finds the same
-- dependencies on a fresh database and on an upgraded one. The user-ID columns
-- of the other ranges change in their own migrations (V415, V505).
--
-- Every dependency is dropped, the columns altered, and the dependency
-- recreated with the name and definition of V200 to V202. Nullability, defaults
-- and the object grants of R__grants_iam.sql stay; the stored values (ASCII,
-- and code page 1252 at most) convert to the same characters, so password
-- hashes, sessions and audit rows are unchanged.
ALTER TABLE iam.session DROP CONSTRAINT FK_session_user;
DROP INDEX IX_session_user ON iam.session;
ALTER TABLE iam.app_user DROP CONSTRAINT PK_app_user;
ALTER TABLE iam.app_user DROP CONSTRAINT CK_app_user_upper;
ALTER TABLE iam.app_user DROP CONSTRAINT CK_app_user_not_blank;
DROP INDEX IX_audit_event_subject ON iam.audit_event;
GO

ALTER TABLE iam.app_user ALTER COLUMN user_id    NCHAR(8)     COLLATE Latin1_General_100_BIN2 NOT NULL;
ALTER TABLE iam.app_user ALTER COLUMN first_name NVARCHAR(20) NOT NULL;
ALTER TABLE iam.app_user ALTER COLUMN last_name  NVARCHAR(20) NOT NULL;
ALTER TABLE iam.app_user ALTER COLUMN user_type  NCHAR(1)     COLLATE Latin1_General_100_BIN2 NOT NULL;
ALTER TABLE iam.app_user ALTER COLUMN created_by NCHAR(8)     COLLATE Latin1_General_100_BIN2 NULL;
ALTER TABLE iam.app_user ALTER COLUMN updated_by NCHAR(8)     COLLATE Latin1_General_100_BIN2 NULL;
ALTER TABLE iam.session ALTER COLUMN user_id NCHAR(8) COLLATE Latin1_General_100_BIN2 NOT NULL;
ALTER TABLE iam.audit_event ALTER COLUMN actor_user_id NCHAR(8)     COLLATE Latin1_General_100_BIN2 NULL;
ALTER TABLE iam.audit_event ALTER COLUMN subject_key   NVARCHAR(40) NULL;
GO

ALTER TABLE iam.app_user ADD
    CONSTRAINT PK_app_user PRIMARY KEY (user_id),
    -- BR-SGN-001: IDs are stored upper case. Only a-z, as COBOL UPPER-CASE;
    -- under BIN2 the range matches code points, so other letters pass.
    CONSTRAINT CK_app_user_upper CHECK (user_id NOT LIKE '%[a-z]%'),
    CONSTRAINT CK_app_user_not_blank CHECK (user_id <> '');
GO

ALTER TABLE iam.session ADD CONSTRAINT FK_session_user FOREIGN KEY (user_id) REFERENCES iam.app_user (user_id);
GO
CREATE INDEX IX_session_user ON iam.session (user_id, ended_at);
GO
CREATE INDEX IX_audit_event_subject ON iam.audit_event (subject_key, occurred_at);
GO
