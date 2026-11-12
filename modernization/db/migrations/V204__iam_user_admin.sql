-- User administration (identity-service.md, Versions).
--
-- Logical delete (D-56, Q-USRD-003): a deleted user keeps its row, so the audit
-- trail and every created_by naming it stay resolvable. Every read of
-- identity-service and sign-on skips such rows; adding the same ID again reuses
-- the row and clears both columns.
ALTER TABLE iam.app_user ADD
    deleted_at DATETIME2(3) NULL,
    deleted_by NCHAR(8)     COLLATE Latin1_General_100_BIN2 NULL;
GO

ALTER TABLE iam.app_user ADD CONSTRAINT CK_app_user_deleted CHECK
    ((deleted_at IS NULL AND deleted_by IS NULL) OR (deleted_at IS NOT NULL AND deleted_by IS NOT NULL));
GO

-- The last-administrator check of user delete (D-06) counts active type-A users.
CREATE INDEX IX_app_user_admins ON iam.app_user (user_type) WHERE deleted_at IS NULL;
GO

-- The user list orders by user_id under BIN2 in every mode (D-54); no list
-- follows EBCDIC order, so the EBCDIC sort key has no reader.
DROP INDEX IX_app_user_ebcdic ON iam.app_user;
ALTER TABLE iam.app_user DROP COLUMN ebcdic_sort_key;
GO
