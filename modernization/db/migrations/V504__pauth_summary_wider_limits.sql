-- Limits wider than the summary (authorization-service question 12). Account
-- update accepts limits of up to 10 integer digits, as core.account holds them,
-- and the listener copies them into the summary, so the IMS domain S9(9)V99
-- would fail that write and send the request to the dead-letter queue with no
-- reply. The limits take core.account's DECIMAL(12,2); the pending balances,
-- which add up approvals, take the running totals' DECIMAL(15,2). Stored
-- values fit both, so no row changes.
-- auth_detail.fraud_changed_by and auth_fraud.reported_at already exist (V500,
-- V501).
ALTER TABLE pauth.auth_summary ALTER COLUMN credit_limit DECIMAL(12,2) NOT NULL;
ALTER TABLE pauth.auth_summary ALTER COLUMN cash_limit DECIMAL(12,2) NOT NULL;
ALTER TABLE pauth.auth_summary ALTER COLUMN credit_balance DECIMAL(15,2) NOT NULL;
ALTER TABLE pauth.auth_summary ALTER COLUMN cash_balance DECIMAL(15,2) NOT NULL;
GO
