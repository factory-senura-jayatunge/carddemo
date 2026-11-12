-- Grants of identity-service (database user identity_svc, login
-- carddemo_identity). db-migrate creates the logins and users before Flyway
-- runs; this file only grants. It first revokes every permission of
-- identity_svc except CONNECT, so this file is the whole truth for that user:
-- a grant removed here is gone after the next migrate.
--
-- V203 (user ID family NCHAR(8), names NVARCHAR), V204 (deleted_at,
-- deleted_by, ebcdic_sort_key dropped) and V205 (password_hash VARCHAR(255))
-- keep these object grants; V206 adds iam.user_signon_state. This file runs
-- again after them (data-model.md, Migrations).

DECLARE @revoke NVARCHAR(MAX) = N'';
SELECT @revoke += N'REVOKE ' + p.permission_name
       + CASE p.class
           WHEN 0 THEN N''
           WHEN 1 THEN N' ON OBJECT::' + QUOTENAME(OBJECT_SCHEMA_NAME(p.major_id)) + N'.' + QUOTENAME(OBJECT_NAME(p.major_id))
                       + CASE WHEN p.minor_id = 0 THEN N''
                              ELSE N' (' + QUOTENAME(COL_NAME(p.major_id, p.minor_id)) + N')' END
           WHEN 3 THEN N' ON SCHEMA::' + QUOTENAME(SCHEMA_NAME(p.major_id))
         END
       + N' FROM ' + QUOTENAME(u.name) + N';' + NCHAR(10)
FROM sys.database_permissions AS p
JOIN sys.database_principals AS u ON u.principal_id = p.grantee_principal_id
WHERE u.name = N'identity_svc'
  AND p.class IN (0, 1, 3)
  AND NOT (p.class = 0 AND p.permission_name = N'CONNECT')
  AND p.state IN ('G', 'W');
EXEC sp_executesql @revoke;
GO

-- No DELETE: sessions end through ended_at, and a deleted user keeps its row
-- (deleted_at, D-56).
GRANT SELECT, INSERT, UPDATE ON OBJECT::iam.app_user TO identity_svc;
GRANT SELECT, INSERT, UPDATE ON OBJECT::iam.session TO identity_svc;
-- Failed attempts, lockout and last sign-on (V206); a re-added user's row is
-- reset, never deleted.
GRANT SELECT, INSERT, UPDATE ON OBJECT::iam.user_signon_state TO identity_svc;
-- Audit rows are append-only for the service.
GRANT INSERT ON OBJECT::iam.audit_event TO identity_svc;
GRANT INSERT ON OBJECT::ops.audit TO identity_svc;
-- User add, update and delete wait while a batch window is open (D-BAT-05).
GRANT SELECT ON OBJECT::ops.batch_window TO identity_svc;
GO
