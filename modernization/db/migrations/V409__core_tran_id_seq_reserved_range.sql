-- Fixed-mode transaction IDs (D-59) come from a reserved range,
-- 9000000000000000 to 9999999999999999, that ingest keeps daily-file IDs out
-- of (TRAN_ID_RESERVED). An ID is in the range only when it is 16 digits from
-- 9000000000000000 up; any other legacy ID stays valid and is ignored here.
-- The sequence restarts at the start of the range, or above the highest ID a
-- table already holds in it or the sequence already issued, so no ID repeats.
-- A full range fails the migration instead of wrapping.
-- MAX is taken before the CAST, so only IDs that passed the digit test are converted.
DECLARE @highest BIGINT = (
    SELECT CAST(MAX(tran_id) AS BIGINT)
    FROM (SELECT tran_id FROM core.[transaction] UNION ALL SELECT tran_id FROM core.daily_tran) AS ids
    WHERE LEN(tran_id) = 16 AND tran_id NOT LIKE '%[^0-9]%' AND tran_id >= '9000000000000000');
DECLARE @issued BIGINT = (
    SELECT CAST(last_used_value AS BIGINT) FROM sys.sequences WHERE object_id = OBJECT_ID(N'core.tran_id_seq'));
DECLARE @next BIGINT = 9000000000000000;
IF @highest >= @next SET @next = @highest + 1;
IF @issued >= @next SET @next = @issued + 1;
IF @next > 9999999999999999
    THROW 50000, N'TRAN_ID_EXHAUSTED: the reserved transaction ID range ends at 9999999999999999 and is used up; core.tran_id_seq does not wrap', 1;
-- ALTER SEQUENCE takes no variables; @next is a BIGINT computed above.
DECLARE @alter NVARCHAR(300) = N'ALTER SEQUENCE core.tran_id_seq RESTART WITH ' + CAST(@next AS NVARCHAR(20))
    + N' MINVALUE 9000000000000000 MAXVALUE 9999999999999999 NO CYCLE;';
EXEC sp_executesql @alter;
GO
