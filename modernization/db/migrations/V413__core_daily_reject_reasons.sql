-- Reject reasons (decisions D-20 and D-21). reason_cd stays the legacy reason,
-- the last failing check, which the 430-byte reject record carries
-- (BR-POST-003); reason_cds lists every failing check in check order. Only the
-- over-limit and expiry checks are evaluated together (CBTRN02C section 8), so
-- the one list with two codes is '0102,0103'. 0109 is the reject of a valid
-- record whose account update failed: the record is rolled back and rejected
-- (D-20) instead of posted without its account change (DEF-POST-003).
ALTER TABLE core.daily_reject DROP CONSTRAINT CK_daily_reject_reason;
ALTER TABLE core.daily_reject ADD reason_cds VARCHAR(9) COLLATE Latin1_General_100_BIN2 NULL;
GO

UPDATE core.daily_reject SET reason_cds = reason_cd;
ALTER TABLE core.daily_reject ALTER COLUMN reason_cds VARCHAR(9) COLLATE Latin1_General_100_BIN2 NOT NULL;
GO

ALTER TABLE core.daily_reject ADD
    CONSTRAINT CK_daily_reject_reason CHECK (reason_cd IN ('0100', '0101', '0102', '0103', '0109')),
    CONSTRAINT CK_daily_reject_reasons CHECK (reason_cds = reason_cd
        OR (reason_cd = '0103' AND reason_cds = '0102,0103'));
GO

-- As V411, with reason_cds.
CREATE OR ALTER VIEW core.v_daily_reject AS
SELECT r.cycle_id,
       c.business_date,
       r.seq,
       t.origin,
       t.created_by,
       r.reason_cd,
       r.reason_desc,
       r.reason_cds,
       CAST(STUFF(r.raw_record, 263, 12, N'XXXXXXXXXXXX') AS NCHAR(350)) AS raw_record_masked,
       r.created_at
FROM core.daily_reject AS r
JOIN batch.cycle AS c ON c.cycle_id = r.cycle_id
JOIN core.daily_tran AS t ON t.daily_pk = r.daily_pk;
GO
