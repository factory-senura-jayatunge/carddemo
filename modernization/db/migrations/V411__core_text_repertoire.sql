-- Text repertoire, stage 1 for core (data-model.md, "Text repertoire and
-- width"): the X(n) columns that an online or message writer fills, and the
-- byte carriers, hold any of the 256 characters U+0000 to U+00FF that a legacy
-- byte maps to. Under the UTF-8 database collation VARCHAR(n) holds n bytes,
-- not n characters, and CHAR or VARCHAR with Latin1_General_100_BIN2 is code
-- page 1252, which stores U+0080 to U+009F as '?'. NVARCHAR(n) and NCHAR(n)
-- hold n characters of the range. Every value the old types held converts
-- unchanged.
--
-- What depends on an altered column is dropped and recreated with the same
-- name and definition: the two account defaults, the mask on govt_issued_id
-- and core.v_daily_reject. No index, key or check names these columns, and
-- ALTER COLUMN drops the optimizer's own statistics on them. Grants survive
-- ALTER COLUMN and ALTER VIEW; R__grants_batch.sql names this migration, so
-- its grants are applied again after it.

ALTER TABLE core.account DROP CONSTRAINT DF_account_group_id;
ALTER TABLE core.account DROP CONSTRAINT DF_account_addr_zip;
GO
ALTER TABLE core.account ALTER COLUMN group_id NCHAR(10) COLLATE Latin1_General_100_BIN2 NOT NULL;
ALTER TABLE core.account ALTER COLUMN addr_zip NVARCHAR(10) NOT NULL;
ALTER TABLE core.account ALTER COLUMN legacy_filler NCHAR(178) COLLATE Latin1_General_100_BIN2 NULL;
GO
ALTER TABLE core.account ADD CONSTRAINT DF_account_group_id DEFAULT N'' FOR group_id;
ALTER TABLE core.account ADD CONSTRAINT DF_account_addr_zip DEFAULT N'' FOR addr_zip;
GO

ALTER TABLE core.customer ALTER COLUMN addr_line_1 NVARCHAR(50) NOT NULL;
ALTER TABLE core.customer ALTER COLUMN addr_line_2 NVARCHAR(50) NOT NULL;
ALTER TABLE core.customer ALTER COLUMN govt_issued_id DROP MASKED;
ALTER TABLE core.customer ALTER COLUMN govt_issued_id NVARCHAR(20) NOT NULL;
ALTER TABLE core.customer ALTER COLUMN govt_issued_id ADD MASKED WITH (FUNCTION = 'default()');
GO

ALTER TABLE core.card ALTER COLUMN legacy_filler NCHAR(59) COLLATE Latin1_General_100_BIN2 NULL;
GO

ALTER TABLE core.tcat_bal ALTER COLUMN legacy_filler NCHAR(22) COLLATE Latin1_General_100_BIN2 NULL;
GO

ALTER TABLE core.[transaction] ALTER COLUMN source NVARCHAR(10) NOT NULL;
ALTER TABLE core.[transaction] ALTER COLUMN description NVARCHAR(100) NOT NULL;
ALTER TABLE core.[transaction] ALTER COLUMN merchant_name NVARCHAR(50) NOT NULL;
ALTER TABLE core.[transaction] ALTER COLUMN merchant_city NVARCHAR(50) NOT NULL;
ALTER TABLE core.[transaction] ALTER COLUMN merchant_zip NVARCHAR(10) NOT NULL;
ALTER TABLE core.[transaction] ALTER COLUMN legacy_filler NCHAR(20) COLLATE Latin1_General_100_BIN2 NULL;
GO

ALTER TABLE core.daily_tran ALTER COLUMN source NVARCHAR(10) NOT NULL;
ALTER TABLE core.daily_tran ALTER COLUMN description NVARCHAR(100) NOT NULL;
ALTER TABLE core.daily_tran ALTER COLUMN merchant_name NVARCHAR(50) NOT NULL;
ALTER TABLE core.daily_tran ALTER COLUMN merchant_city NVARCHAR(50) NOT NULL;
ALTER TABLE core.daily_tran ALTER COLUMN merchant_zip NVARCHAR(10) NOT NULL;
ALTER TABLE core.daily_tran ALTER COLUMN raw_record NCHAR(350) COLLATE Latin1_General_100_BIN2 NOT NULL;
GO

ALTER TABLE core.daily_reject ALTER COLUMN raw_record NCHAR(350) COLLATE Latin1_General_100_BIN2 NOT NULL;
GO

-- As V403, with the record and its mask as NCHAR(350).
CREATE OR ALTER VIEW core.v_daily_reject AS
SELECT r.cycle_id,
       c.business_date,
       r.seq,
       t.origin,
       t.created_by,
       r.reason_cd,
       r.reason_desc,
       CAST(STUFF(r.raw_record, 263, 12, N'XXXXXXXXXXXX') AS NCHAR(350)) AS raw_record_masked,
       r.created_at
FROM core.daily_reject AS r
JOIN batch.cycle AS c ON c.cycle_id = r.cycle_id
JOIN core.daily_tran AS t ON t.daily_pk = r.daily_pk;
GO
