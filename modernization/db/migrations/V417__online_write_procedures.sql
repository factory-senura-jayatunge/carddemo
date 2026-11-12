-- The writes transaction-service makes to core.daily_tran and batch.report_run,
-- and its replay check, as procedures (data-model.md, "Writers that must not
-- set some columns"; transaction-service.md sections 13 and 14). SQL Server
-- has no column list for INSERT in GRANT (error 1020), so a table grant would
-- let the service set status, origin, cycle_id or seq. Each procedure is owned
-- by dbo like the tables it uses, so ownership chaining lets a login with
-- EXECUTE only do exactly what the procedure does; there is no EXECUTE AS, so
-- the caller's application locks and permissions stay its own. They run in the
-- caller's transaction and catch no error: a constraint or index violation
-- reaches the caller with its number and the index or constraint name, and the
-- procedure then returns no result set. An invalid argument raises an error
-- that names the argument, never its value.
--
-- The parameters have the column types of the text repertoire's last stage
-- (NCHAR, NVARCHAR), so V414 needs no change here. Until V414, tran_id,
-- type_cd, card_num, orig_ts and proc_ts are CHAR columns and the values are
-- narrowed on insert; the add's own checks keep those values in ASCII.

-- RPTS (CORPT00C): one REQUESTED row. status, requested_at, the run columns and
-- job_execution_id come from the table's defaults; CK_report_run_type and
-- CK_report_run_dates check the arguments. Returns (report_run_id, requested_at).
CREATE PROCEDURE batch.report_run_request
    @report_type  VARCHAR(7),
    @start_date   DATE,
    @end_date     DATE,
    @requested_by NCHAR(8)
AS
BEGIN
    SET NOCOUNT ON;
    DECLARE @r TABLE (report_run_id BIGINT NOT NULL, requested_at DATETIME2(3) NOT NULL);
    INSERT INTO batch.report_run (report_type, start_date, end_date, requested_by)
    OUTPUT inserted.report_run_id, inserted.requested_at INTO @r
    VALUES (@report_type, @start_date, @end_date, @requested_by);
    -- Without XACT_ABORT a failed INSERT ends only its statement; the caller already has the error.
    IF @@ERROR <> 0 RETURN;
    SELECT report_run_id, requested_at FROM @r;
END;
GO

-- TRNA (COTRN02C), D-27: one waiting online add, origin ONLINE_ADD with
-- cycle_id and seq NULL, so only a cycle's claim gives it a place in posting.
-- The caller holds 'carddemo.batch_window' for its transaction
-- (BatchWindowGuard) and no window is open, so the add cannot race a claim.
-- The raw record carries the same fields as the columns at the CVTRA06Y
-- positions, because a reject returns the record and posting reads the
-- columns; equality ignores trailing spaces, as in the padded record. The
-- digit fields are all digits, as the add's padding leaves them.
-- UX_daily_waiting refuses a second waiting row with the same ID and
-- UX_daily_idempotency a second row with the same user and key, as error 2601.
-- Returns (daily_pk).
CREATE PROCEDURE core.daily_tran_online_add
    @created_by      NCHAR(8),
    @tran_id         NCHAR(16),
    @type_cd         NCHAR(2),
    @cat_cd          CHAR(4),
    @source          NVARCHAR(10),
    @description     NVARCHAR(100),
    @amount          DECIMAL(11,2),
    @merchant_id     CHAR(9),
    @merchant_name   NVARCHAR(50),
    @merchant_city   NVARCHAR(50),
    @merchant_zip    NVARCHAR(10),
    @card_num        NCHAR(16),
    @orig_ts         NCHAR(26),
    @proc_ts         NCHAR(26),
    @raw_record      NCHAR(350),
    @idempotency_key UNIQUEIDENTIFIER
AS
BEGIN
    SET NOCOUNT ON;
    IF @created_by IS NULL OR @created_by = N'' OR @idempotency_key IS NULL OR @amount IS NULL
    BEGIN
        THROW 50417, N'core.daily_tran_online_add: @created_by, @idempotency_key and @amount are required', 1;
    END;
    IF @tran_id IS NULL OR @tran_id COLLATE Latin1_General_100_BIN2 LIKE N'%[^0-9]%'
       OR @type_cd IS NULL OR @type_cd COLLATE Latin1_General_100_BIN2 LIKE N'%[^0-9]%'
       OR @cat_cd IS NULL OR @cat_cd COLLATE Latin1_General_100_BIN2 LIKE '%[^0-9]%'
       OR @merchant_id IS NULL OR @merchant_id COLLATE Latin1_General_100_BIN2 LIKE '%[^0-9]%'
       OR @card_num IS NULL OR @card_num COLLATE Latin1_General_100_BIN2 LIKE N'%[^0-9]%'
    BEGIN
        THROW 50417, N'core.daily_tran_online_add: @tran_id, @type_cd, @cat_cd, @merchant_id and @card_num must be digits over their whole width', 1;
    END;
    IF @orig_ts IS NULL OR @orig_ts COLLATE Latin1_General_100_BIN2 LIKE N'%[^ -~]%'
       OR @proc_ts IS NULL OR @proc_ts COLLATE Latin1_General_100_BIN2 LIKE N'%[^ -~]%'
    BEGIN
        THROW 50417, N'core.daily_tran_online_add: @orig_ts and @proc_ts must be printable ASCII', 1;
    END;
    IF @raw_record IS NULL OR @source IS NULL OR @description IS NULL OR @merchant_name IS NULL
       OR @merchant_city IS NULL OR @merchant_zip IS NULL
       OR SUBSTRING(@raw_record, 1, 16) COLLATE Latin1_General_100_BIN2 <> @tran_id
       OR SUBSTRING(@raw_record, 17, 2) COLLATE Latin1_General_100_BIN2 <> @type_cd
       OR SUBSTRING(@raw_record, 19, 4) COLLATE Latin1_General_100_BIN2 <> @cat_cd
       OR SUBSTRING(@raw_record, 23, 10) COLLATE Latin1_General_100_BIN2 <> @source
       OR SUBSTRING(@raw_record, 33, 100) COLLATE Latin1_General_100_BIN2 <> @description
       OR SUBSTRING(@raw_record, 144, 9) COLLATE Latin1_General_100_BIN2 <> @merchant_id
       OR SUBSTRING(@raw_record, 153, 50) COLLATE Latin1_General_100_BIN2 <> @merchant_name
       OR SUBSTRING(@raw_record, 203, 50) COLLATE Latin1_General_100_BIN2 <> @merchant_city
       OR SUBSTRING(@raw_record, 253, 10) COLLATE Latin1_General_100_BIN2 <> @merchant_zip
       OR SUBSTRING(@raw_record, 263, 16) COLLATE Latin1_General_100_BIN2 <> @card_num
       OR SUBSTRING(@raw_record, 279, 26) COLLATE Latin1_General_100_BIN2 <> @orig_ts
       OR SUBSTRING(@raw_record, 305, 26) COLLATE Latin1_General_100_BIN2 <> @proc_ts
    BEGIN
        THROW 50417, N'core.daily_tran_online_add: @raw_record must carry the other arguments at their record positions', 1;
    END;
    -- APPLOCK_MODE with the Transaction owner fails outside a transaction instead of answering NoLock.
    IF @@TRANCOUNT = 0
    BEGIN
        THROW 50418, N'core.daily_tran_online_add: the caller''s transaction does not hold carddemo.batch_window', 1;
    END;
    IF APPLOCK_MODE(N'public', N'carddemo.batch_window', N'Transaction') = N'NoLock'
    BEGIN
        THROW 50418, N'core.daily_tran_online_add: the caller''s transaction does not hold carddemo.batch_window', 1;
    END;
    IF EXISTS (SELECT 1 FROM ops.batch_window WHERE closed_at IS NULL)
    BEGIN
        THROW 50419, N'core.daily_tran_online_add: a batch window is open', 1;
    END;
    DECLARE @r TABLE (daily_pk BIGINT NOT NULL);
    INSERT INTO core.daily_tran (origin, created_by, idempotency_key, tran_id, type_cd, cat_cd, source, description,
        amount, merchant_id, merchant_name, merchant_city, merchant_zip, card_num, orig_ts, proc_ts, raw_record)
    OUTPUT inserted.daily_pk INTO @r
    VALUES ('ONLINE_ADD', @created_by, @idempotency_key, @tran_id, @type_cd, @cat_cd, @source, @description,
        @amount, @merchant_id, @merchant_name, @merchant_city, @merchant_zip, @card_num, @orig_ts, @proc_ts,
        @raw_record);
    IF @@ERROR <> 0 RETURN;
    SELECT daily_pk FROM @r;
END;
GO

-- TRNA's replay check (contract v7 ruling 1): the caller's row with this key,
-- found through UX_daily_idempotency whether or not a cycle has claimed it,
-- and whether every argument equals its stored column, card number included.
-- proc_ts and raw_record are not compared, so a retry on a later day with the
-- same form replays. Text compares exactly (BIN2), ignoring trailing spaces.
-- The comparison sits in a WHERE clause: dynamic data masking masks any
-- expression over card_num in a select list for a caller without UNMASK, but
-- not a predicate. Returns (found, tran_id, same_body); tran_id and same_body
-- are NULL when nothing is found.
CREATE PROCEDURE core.daily_tran_key_lookup
    @created_by      NCHAR(8),
    @idempotency_key UNIQUEIDENTIFIER,
    @type_cd         NCHAR(2),
    @cat_cd          CHAR(4),
    @source          NVARCHAR(10),
    @description     NVARCHAR(100),
    @amount          DECIMAL(11,2),
    @merchant_id     CHAR(9),
    @merchant_name   NVARCHAR(50),
    @merchant_city   NVARCHAR(50),
    @merchant_zip    NVARCHAR(10),
    @card_num        NCHAR(16),
    @orig_ts         NCHAR(26)
AS
BEGIN
    SET NOCOUNT ON;
    DECLARE @daily_pk BIGINT, @tran_id NCHAR(16), @same_body BIT;
    SELECT @daily_pk = daily_pk, @tran_id = tran_id
    FROM core.daily_tran
    WHERE created_by = @created_by AND idempotency_key = @idempotency_key;
    IF @daily_pk IS NOT NULL
    BEGIN
        SET @same_body = CASE WHEN EXISTS (
            SELECT 1 FROM core.daily_tran
            WHERE daily_pk = @daily_pk
              AND type_cd COLLATE Latin1_General_100_BIN2 = @type_cd
              AND cat_cd COLLATE Latin1_General_100_BIN2 = @cat_cd
              AND source COLLATE Latin1_General_100_BIN2 = @source
              AND description COLLATE Latin1_General_100_BIN2 = @description
              AND amount = @amount
              AND merchant_id COLLATE Latin1_General_100_BIN2 = @merchant_id
              AND merchant_name COLLATE Latin1_General_100_BIN2 = @merchant_name
              AND merchant_city COLLATE Latin1_General_100_BIN2 = @merchant_city
              AND merchant_zip COLLATE Latin1_General_100_BIN2 = @merchant_zip
              AND card_num COLLATE Latin1_General_100_BIN2 = @card_num
              AND orig_ts COLLATE Latin1_General_100_BIN2 = @orig_ts) THEN 1 ELSE 0 END;
    END;
    SELECT CAST(CASE WHEN @daily_pk IS NULL THEN 0 ELSE 1 END AS BIT) AS found, @tran_id AS tran_id,
           @same_body AS same_body;
END;
GO
