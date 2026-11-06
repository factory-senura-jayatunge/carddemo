-- What operations read instead of the DALYREJS data set: the record with the
-- card number (positions 263-278) masked to its last four digits. raw_record
-- has no dynamic data mask, because a mask on it would hide the whole record
-- in this view too; readers get SELECT on this view only (ownership chaining).
CREATE VIEW core.v_daily_reject AS
SELECT r.cycle_id,
       c.business_date,
       r.seq,
       t.origin,
       t.created_by,
       r.reason_cd,
       r.reason_desc,
       CAST(STUFF(r.raw_record, 263, 12, 'XXXXXXXXXXXX') AS CHAR(350)) AS raw_record_masked,
       r.created_at
FROM core.daily_reject AS r
JOIN batch.cycle AS c ON c.cycle_id = r.cycle_id
JOIN core.daily_tran AS t ON t.daily_pk = r.daily_pk;
GO
