-- Grants of batch-service (database user batch_svc). db-migrate creates the
-- logins and users before Flyway runs; this file only grants. It first revokes
-- every permission of batch_svc except CONNECT, so this file is the whole
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
WHERE u.name = N'batch_svc'
  AND p.class IN (0, 1, 3)
  AND NOT (p.class = 0 AND p.permission_name = N'CONNECT')
  AND p.state IN ('G', 'W');
EXEC sp_executesql @revoke;
GO

-- dataLoadJob: empties and reloads the core tables (D-41 limits when).
GRANT SELECT, INSERT, DELETE ON OBJECT::core.account TO batch_svc;
GRANT SELECT, INSERT, DELETE ON OBJECT::core.customer TO batch_svc;
GRANT SELECT, INSERT, DELETE ON OBJECT::core.card TO batch_svc;
GRANT SELECT, INSERT, DELETE ON OBJECT::core.card_xref TO batch_svc;
GRANT SELECT, INSERT, DELETE ON OBJECT::core.tcat_bal TO batch_svc;
GRANT SELECT, INSERT, DELETE ON OBJECT::core.[transaction] TO batch_svc;
-- The load restarts the sequence above the highest loaded transaction ID (D-59).
GRANT ALTER ON OBJECT::core.tran_id_seq TO batch_svc;
GO

-- Posting, interest and the exports read card numbers and customer PII as
-- stored; the load does not need it, verify-data's re-export does.
GRANT UNMASK ON OBJECT::core.card TO batch_svc;
GRANT UNMASK ON OBJECT::core.customer TO batch_svc;
GRANT UNMASK ON OBJECT::core.[transaction] TO batch_svc;
GRANT UNMASK ON OBJECT::core.daily_tran TO batch_svc;
GO

-- The cycle's staging and reject tables, with the retention purge; the claim
-- of waiting online adds (D-27) sets only cycle_id and seq.
GRANT SELECT, INSERT, DELETE ON OBJECT::core.daily_tran TO batch_svc;
GRANT UPDATE ON OBJECT::core.daily_tran (cycle_id, seq) TO batch_svc;
GRANT SELECT, INSERT, DELETE ON OBJECT::core.daily_reject TO batch_svc;
GRANT SELECT ON OBJECT::core.v_daily_reject TO batch_svc;
GO

-- batch.cycle and the Spring Batch job repository; UPDATE on the schema covers
-- NEXT VALUE FOR on the repository's ID sequences.
GRANT SELECT, INSERT, UPDATE, DELETE ON SCHEMA::batch TO batch_svc;
GO
