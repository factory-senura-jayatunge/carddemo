-- Grants of transaction-service (database user transaction_svc). db-migrate
-- creates the logins and users before Flyway runs; this file only grants. It
-- first revokes every permission of transaction_svc except CONNECT, so this
-- file is the whole truth for that user. Each owner keeps its own
-- R__grants_<owner>.sql and revokes only its own users.

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
WHERE u.name = N'transaction_svc'
  AND p.class IN (0, 1, 3)
  AND NOT (p.class = 0 AND p.permission_name = N'CONNECT')
  AND p.state IN ('G', 'W');
EXEC sp_executesql @revoke;
GO

-- What ledger-core needs for transaction add, before the service itself
-- exists: the transaction ID in both BR-TRNA-003 modes and "copy last"
-- (BR-TRNA-006), both over the master and the waiting online adds (D-27).
-- Copy last reads the data fields only, so neither card_num nor raw_record is
-- granted on core.daily_tran.
GRANT SELECT ON OBJECT::core.[transaction] (tran_id, in_master, type_cd, cat_cd, source, description, amount,
    merchant_id, merchant_name, merchant_city, merchant_zip, orig_ts, proc_ts) TO transaction_svc;
GRANT SELECT ON OBJECT::core.daily_tran (tran_id, cycle_id, origin, type_cd, cat_cd, source, description, amount,
    merchant_id, merchant_name, merchant_city, merchant_zip, orig_ts, proc_ts) TO transaction_svc;
-- NEXT VALUE FOR needs UPDATE on the sequence (BR-TRNA-003 fixed).
GRANT UPDATE ON OBJECT::core.tran_id_seq TO transaction_svc;
GO
