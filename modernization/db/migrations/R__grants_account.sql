-- Grants of account-service (database user account_svc). db-migrate creates the
-- logins and users before Flyway runs; this file only grants. It first revokes
-- every permission of account_svc except CONNECT, so this file is the whole
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
WHERE u.name = N'account_svc'
  AND p.class IN (0, 1, 3)
  AND NOT (p.class = 0 AND p.permission_name = N'CONNECT')
  AND p.state IN ('G', 'W');
EXEC sp_executesql @revoke;
GO

-- Account view (COACTVWC): the account, its lowest card's cross-reference row
-- and card ID, the customer, and the batch window. No login reads the CVV
-- (D-DATA-04), so core.card is granted by column. core.account is granted by
-- column too, so a column added later, such as the load's ZIP and group
-- provenance (DEF-ACUP-002), stays out of this login until a grant names it.
GRANT SELECT ON OBJECT::core.account (acct_id, active_status, curr_bal, credit_limit, cash_credit_limit,
    open_date, expiration_date, reissue_date, curr_cyc_credit, curr_cyc_debit, addr_zip, group_id,
    legacy_filler, row_version) TO account_svc;
GRANT SELECT ON OBJECT::core.customer TO account_svc;
GRANT SELECT ON OBJECT::core.card (card_num, card_id, acct_id, embossed_name, expiration_date, active_status,
    legacy_filler, row_version) TO account_svc;
GRANT SELECT ON OBJECT::core.card_xref TO account_svc;
GRANT SELECT ON OBJECT::ops.batch_window TO account_svc;
GO

-- Card update (COCRDUPC) writes the name, status and expiry only: never the
-- account (D-13), the CVV (D-12) or the filler (DEF-CRDU-003).
GRANT UPDATE ON OBJECT::core.card (embossed_name, active_status, expiration_date) TO account_svc;
GO

-- Account update (COACTUPC) writes the columns its screen owns: never the
-- balances (D-08), addr_zip or the filler (D-11), the keys, or country_cd
-- (BR-ACUP-008).
GRANT UPDATE ON OBJECT::core.account (active_status, credit_limit, cash_credit_limit, open_date,
    expiration_date, reissue_date, group_id) TO account_svc;
GRANT UPDATE ON OBJECT::core.customer (first_name, middle_name, last_name, addr_line_1, addr_line_2,
    addr_line_3, state_cd, zip, phone_1, phone_2, ssn, govt_issued_id, dob, eft_account_id,
    pri_card_holder_ind, fico_score) TO account_svc;
GO

-- The audited unmask of an ADMIN (D-09, D-SEC-05) needs the clear values; the
-- service masks them in every other response.
GRANT UNMASK ON OBJECT::core.customer (ssn, govt_issued_id, dob, eft_account_id) TO account_svc;
GRANT INSERT ON OBJECT::ops.audit TO account_svc;
GO
