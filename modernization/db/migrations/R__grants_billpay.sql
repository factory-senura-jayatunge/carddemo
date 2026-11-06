-- Grants of billpay-service (database user billpay_svc). db-migrate creates the
-- logins and users before Flyway runs; this file only grants. It first revokes
-- every permission of billpay_svc except CONNECT, so this file is the whole
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
WHERE u.name = N'billpay_svc'
  AND p.class IN (0, 1, 3)
  AND NOT (p.class = 0 AND p.permission_name = N'CONNECT')
  AND p.state IN ('G', 'W');
EXEC sp_executesql @revoke;
GO

-- Reads: the account, its cards, the master (legacy ID allocation), the
-- category balances and the batch window.
GRANT SELECT ON OBJECT::core.account TO billpay_svc;
GRANT SELECT ON OBJECT::core.card_xref TO billpay_svc;
GRANT SELECT ON OBJECT::core.[transaction] TO billpay_svc;
GRANT SELECT ON OBJECT::core.tcat_bal TO billpay_svc;
GRANT SELECT ON OBJECT::ops.batch_window TO billpay_svc;
GO

-- Writes, through ledger-core: the payment transaction and its posting. Bill
-- Pay may change the balance and the cycle totals of an account and no other
-- column (INV-004), and the category balance (D-28, DEF-BILL-010).
GRANT INSERT ON OBJECT::core.[transaction] TO billpay_svc;
GRANT UPDATE ON OBJECT::core.account (curr_bal, curr_cyc_credit, curr_cyc_debit) TO billpay_svc;
GRANT INSERT ON OBJECT::core.tcat_bal TO billpay_svc;
GRANT UPDATE ON OBJECT::core.tcat_bal (balance) TO billpay_svc;
-- NEXT VALUE FOR needs UPDATE on the sequence (BR-TRNA-003 fixed).
GRANT UPDATE ON OBJECT::core.tran_id_seq TO billpay_svc;
GO

GRANT SELECT, INSERT ON OBJECT::billpay.payment TO billpay_svc;
GO
