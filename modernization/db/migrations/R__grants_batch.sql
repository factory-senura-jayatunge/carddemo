-- Grants of batch-service (database user batch_svc). db-migrate creates the
-- logins and users before Flyway runs; this file only grants and denies. It
-- first revokes every grant and every denial of batch_svc except CONNECT, so
-- this file is the whole truth for that user. Each owner keeps its own
-- R__grants_<owner>.sql and revokes only its own users.
-- Applied again after V411__core_text_repertoire.sql, which altered the text
-- columns of core.account, customer, card, tcat_bal, transaction, daily_tran
-- and daily_reject, and core.v_daily_reject; and after
-- V415__user_id_family.sql, which altered core.transaction.created_by,
-- core.daily_tran.created_by, batch.report_run.requested_by and
-- core.v_daily_reject.

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
  AND p.state IN ('G', 'W', 'D');
EXEC sp_executesql @revoke;
GO

-- dataLoadJob: empties and reloads the core tables (D-41 limits when).
GRANT SELECT, INSERT, DELETE ON OBJECT::core.account TO batch_svc;
GRANT SELECT, INSERT, DELETE ON OBJECT::core.customer TO batch_svc;
GRANT SELECT, INSERT, DELETE ON OBJECT::core.card TO batch_svc;
GRANT SELECT, INSERT, DELETE ON OBJECT::core.card_xref TO batch_svc;
GRANT SELECT, INSERT, DELETE ON OBJECT::core.tcat_bal TO batch_svc;
GRANT SELECT, INSERT, DELETE ON OBJECT::core.[transaction] TO batch_svc;
-- archiveMasterJob moves the master's rows out of it (TRANBKP); the rows stay as the backup.
GRANT UPDATE ON OBJECT::core.[transaction] (in_master) TO batch_svc;
-- The reference tables, emptied and reloaded with the core tables.
GRANT SELECT, INSERT, DELETE ON OBJECT::ref.tran_type TO batch_svc;
GRANT SELECT, INSERT, DELETE ON OBJECT::ref.tran_category TO batch_svc;
GRANT SELECT, INSERT, DELETE ON OBJECT::ref.disclosure_group TO batch_svc;
GRANT SELECT, INSERT, DELETE ON OBJECT::ref.us_state TO batch_svc;
GRANT SELECT, INSERT, DELETE ON OBJECT::ref.us_state_zip_prefix TO batch_svc;
GRANT SELECT, INSERT, DELETE ON OBJECT::ref.nanp_area_code TO batch_svc;
-- The load restarts the sequence above the highest loaded transaction ID (D-59);
-- interest in fixed mode draws its IDs from it (DEF-INTC-010).
GRANT ALTER, UPDATE ON OBJECT::core.tran_id_seq TO batch_svc;
GO

-- Posting reads the staged card number as stored and writes it into the
-- master; interest, the statements and the export read the master's card
-- numbers, and the export the customer's masked PII, which travels only inside
-- its encrypted file (batch.md, export and import). UNMASK is granted by
-- column, so a masked column added later stays masked. The CVV is never
-- unmasked (D-DATA-04): core.card gets no UNMASK, and cvv_cd reads as xxx.
GRANT UNMASK ON OBJECT::core.customer (ssn, govt_issued_id, dob, eft_account_id) TO batch_svc;
GRANT UNMASK ON OBJECT::core.[transaction] (card_num) TO batch_svc;
GRANT UNMASK ON OBJECT::core.daily_tran (card_num) TO batch_svc;
GO

-- Posting (CBTRN02C) changes balances only through ledger-core: the account's
-- current and cycle balances and the category balance; the transaction and
-- category rows it inserts are covered above.
GRANT UPDATE ON OBJECT::core.account (curr_bal, curr_cyc_credit, curr_cyc_debit) TO batch_svc;
GRANT UPDATE ON OBJECT::core.tcat_bal (balance) TO batch_svc;
GO

-- The cycle's staging and reject tables, with the retention purge; the claim
-- of waiting online adds (D-27) sets only cycle_id and seq.
GRANT SELECT, INSERT, DELETE ON OBJECT::core.daily_tran TO batch_svc;
GRANT UPDATE ON OBJECT::core.daily_tran (cycle_id, seq) TO batch_svc;
GRANT SELECT, INSERT, DELETE ON OBJECT::core.daily_reject TO batch_svc;
GRANT SELECT ON OBJECT::core.v_daily_reject TO batch_svc;
GO

-- Pending authorizations: the sample load (pauthLoadStep) replaces summaries
-- and details; D-33 matching sets the match columns and releases the credit
-- balance; the purge (CBPAUP0C) deletes expired details, adjusts the summary
-- totals and deletes empty summaries, old processed messages and sent outbox
-- rows (D-34). No fraud table and no UNMASK: matching compares card numbers
-- inside the database, and nothing here returns them.
GRANT SELECT, INSERT, DELETE ON OBJECT::pauth.auth_summary TO batch_svc;
GRANT UPDATE ON OBJECT::pauth.auth_summary (credit_balance, approved_auth_cnt, approved_auth_amt,
    declined_auth_cnt, declined_auth_amt) TO batch_svc;
GRANT SELECT, INSERT, DELETE ON OBJECT::pauth.auth_detail TO batch_svc;
GRANT UPDATE ON OBJECT::pauth.auth_detail (match_status, matched_tran_pk, matched_tran_id, matched_cycle_id,
    matched_at) TO batch_svc;
GRANT SELECT, DELETE ON OBJECT::pauth.processed_message TO batch_svc;
GRANT SELECT, DELETE ON OBJECT::pauth.outbox TO batch_svc;
GO

-- The global batch window (contract section 6): opened and closed by batch-service.
GRANT SELECT, INSERT ON OBJECT::ops.batch_window TO batch_svc;
GRANT UPDATE ON OBJECT::ops.batch_window (closed_at) TO batch_svc;
GO

-- batch.cycle and the Spring Batch job repository; UPDATE on the schema covers
-- NEXT VALUE FOR on the repository's ID sequences.
GRANT SELECT, INSERT, UPDATE, DELETE ON SCHEMA::batch TO batch_svc;
-- A statement capture is written once (V419): an object DENY overrides the
-- schema's UPDATE. DELETE is not denied; this grant does not authorize a
-- retention implementation.
DENY UPDATE ON OBJECT::batch.statement_capture TO batch_svc;
GO

-- The database instance marker (V420): every output root is derived from it,
-- and each fenced transaction reads it again (batch-execution-contract.md
-- section 4.10). Read only: db-migrate, as the owner, rotates it.
GRANT SELECT ON OBJECT::ops.database_instance TO batch_svc;
GO
