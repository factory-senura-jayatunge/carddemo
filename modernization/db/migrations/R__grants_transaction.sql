-- Grants of transaction-service (database user transaction_svc). db-migrate
-- creates the logins and users before Flyway runs; this file only grants. It
-- first revokes every permission of transaction_svc except CONNECT, so this
-- file is the whole truth for that user. Each owner keeps its own
-- R__grants_<owner>.sql and revokes only its own users.
-- Applied again after V415__user_id_family.sql, which altered
-- core.transaction.created_by and core.daily_tran.created_by.

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

-- Transaction reports (CORPT00C): the service requests a run and reads its
-- status; batch-service writes everything after that. The request and the one
-- change the service makes to a row, a definite trigger failure, go through
-- procedures: SQL Server has no column-level INSERT grant, and the request must
-- not set status. So there is no INSERT, UPDATE or DELETE on the table and
-- nothing on job_execution_id (data-model.md, "Report runs"). The procedures
-- and the view are owned by dbo like the table, so EXECUTE and SELECT on them
-- are enough (V417 adds batch.report_run_request). output_sha256 (V424) is read
-- by the download, which checks the served bytes against it; the job execution
-- context, which holds the intent's own copy, stays batch's.
GRANT SELECT ON OBJECT::batch.report_run (report_run_id, report_type, start_date, end_date, requested_by,
    requested_at, status, started_at, finished_at, failure_code, row_version, output_sha256) TO transaction_svc;
GRANT EXECUTE ON OBJECT::batch.report_run_request TO transaction_svc;
GRANT EXECUTE ON OBJECT::batch.report_run_trigger_failed TO transaction_svc;
GRANT SELECT ON OBJECT::batch.v_report_run_stalled TO transaction_svc;
GO

-- Transaction add (COTRN02C): the account and card checks, the type and
-- category checks (D-52) and BatchWindowGuard read; the insert of a waiting
-- online add and the replay check go through V417's dbo-owned procedures, so
-- there is no INSERT on core.daily_tran and no SELECT on its card_num,
-- raw_record, created_by or idempotency_key. The add's audit row uses the
-- ops.audit grant below.
GRANT SELECT ON OBJECT::core.card_xref TO transaction_svc;
GRANT SELECT ON OBJECT::ref.tran_type TO transaction_svc;
GRANT SELECT ON OBJECT::ref.tran_category TO transaction_svc;
GRANT SELECT ON OBJECT::ops.batch_window TO transaction_svc;
GRANT EXECUTE ON OBJECT::core.daily_tran_online_add TO transaction_svc;
GRANT EXECUTE ON OBJECT::core.daily_tran_key_lookup TO transaction_svc;
GO

-- Transaction view (COTRN01C) shows the card number masked to its last four,
-- which the service does from the clear value, and the ADMIN unmask returns it
-- in clear after writing its ops.audit row (D-09). The list reads no card
-- number. tran_pk is never exposed, so it stays ungranted.
GRANT SELECT ON OBJECT::core.[transaction] (card_num) TO transaction_svc;
GRANT UNMASK ON OBJECT::core.[transaction] (card_num) TO transaction_svc;
GRANT INSERT ON OBJECT::ops.audit TO transaction_svc;
GO

-- The database instance marker (V420): each report download reads it with the
-- run row and resolves the instance root from it (batch-execution-contract.md
-- section 4.10). Read only.
GRANT SELECT ON OBJECT::ops.database_instance TO transaction_svc;
GO
