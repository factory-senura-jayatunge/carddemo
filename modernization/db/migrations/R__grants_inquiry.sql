-- Grants of inquiry-service (database user inquiry_svc). db-migrate creates the
-- logins and users before Flyway runs; this file only grants. It first revokes
-- every permission of inquiry_svc except CONNECT, so this file is the whole
-- truth for that user. Each owner keeps its own R__grants_<owner>.sql and
-- revokes only its own users.

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
WHERE u.name = N'inquiry_svc'
  AND p.class IN (0, 1, 3)
  AND NOT (p.class = 0 AND p.permission_name = N'CONNECT')
  AND p.state IN ('G', 'W');
EXEC sp_executesql @revoke;
GO

-- The account inquiry (COACCT01) answers with the account's status, balances,
-- limits, dates and group, and nothing else: addr_zip and legacy_filler are not
-- sent (BR-MQAC-002), and the date inquiry (CODATE01) reads no table. Granted
-- by column, so a column added later, such as the load's ZIP and group
-- provenance (DEF-ACUP-002), stays out of this login. No write, no UNMASK, no
-- other table.
GRANT SELECT ON OBJECT::core.account (acct_id, active_status, curr_bal, credit_limit, cash_credit_limit,
    open_date, expiration_date, reissue_date, curr_cyc_credit, curr_cyc_debit, group_id) TO inquiry_svc;
GO
