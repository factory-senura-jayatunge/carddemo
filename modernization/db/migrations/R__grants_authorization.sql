-- Grants of authorization-service (database user authorization_svc). db-migrate
-- creates the logins and users before Flyway runs; this file only grants. It
-- first revokes every permission of authorization_svc except CONNECT, so this
-- file is the whole truth for that user. Each owner keeps its own
-- R__grants_<owner>.sql and revokes only its own users.
-- Applied again after V415__user_id_family.sql, which altered
-- ops.audit.actor_user_id and subject_key.
-- V505 changed the text columns of pauth.auth_detail, auth_fraud, processed_message
-- and outbox to NCHAR/NVARCHAR; this file runs again after it.

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
WHERE u.name = N'authorization_svc'
  AND p.class IN (0, 1, 3)
  AND NOT (p.class = 0 AND p.permission_name = N'CONNECT')
  AND p.state IN ('G', 'W');
EXEC sp_executesql @revoke;
GO

-- The decision's reads: card to account and customer (BR-AUTH-003), limits
-- and balance (D-33), card status and expiry (D-32). cvv_cd is never granted
-- (D-DATA-04): the request carries no CVV. Of the customer only the name, address
-- and phone that the summary shows are granted; the SSN, government ID, date of
-- birth, EFT account and FICO score are never read. core.account is granted by
-- column, so a column added later, such as the load's ZIP and group provenance
-- (DEF-ACUP-002), stays out of this login until a grant names it.
GRANT SELECT ON OBJECT::core.card_xref TO authorization_svc;
GRANT SELECT ON OBJECT::core.account (acct_id, active_status, curr_bal, credit_limit, cash_credit_limit,
    open_date, expiration_date, reissue_date, curr_cyc_credit, curr_cyc_debit, addr_zip, group_id,
    legacy_filler, row_version) TO authorization_svc;
GRANT SELECT ON OBJECT::core.customer (cust_id, first_name, middle_name, last_name, addr_line_1, addr_line_2,
    addr_line_3, state_cd, zip, phone_1) TO authorization_svc;
GRANT SELECT ON OBJECT::core.card (card_num, acct_id, active_status, expiration_date) TO authorization_svc;
GO

GRANT SELECT, INSERT, UPDATE ON OBJECT::pauth.auth_summary TO authorization_svc;
GRANT SELECT, INSERT ON OBJECT::pauth.auth_detail TO authorization_svc;
GRANT UPDATE ON OBJECT::pauth.auth_detail (auth_fraud, fraud_rpt_date, fraud_changed_by) TO authorization_svc;
-- New rows get the six rightmost digits of the IDENTITY auth_id (D-32), known only after the insert.
GRANT UPDATE ON OBJECT::pauth.auth_detail (auth_id_code) TO authorization_svc;
GRANT SELECT, INSERT, UPDATE ON OBJECT::pauth.auth_fraud TO authorization_svc;
GRANT SELECT, INSERT ON OBJECT::pauth.processed_message TO authorization_svc;
GRANT SELECT, INSERT ON OBJECT::pauth.outbox TO authorization_svc;
GRANT UPDATE ON OBJECT::pauth.outbox (attempts, sent_at) TO authorization_svc;
GRANT INSERT ON OBJECT::ops.audit TO authorization_svc;
GO

-- INSERT ... SELECT of a masked column stores the mask, so the fraud copy and
-- the resent replies need the clear values. Screens mask in the service.
GRANT UNMASK ON OBJECT::pauth.auth_detail (card_num) TO authorization_svc;
GRANT UNMASK ON OBJECT::pauth.processed_message (reply_body) TO authorization_svc;
GRANT UNMASK ON OBJECT::pauth.outbox (body) TO authorization_svc;
GO
