-- Collation of the fraud copies (data-model.md, Fraud copy collations). A
-- fraud row copies its detail's columns exactly, but V501 left auth_resp_code,
-- auth_resp_reason and match_status on the database collation while
-- pauth.auth_detail has them in Latin1_General_100_BIN2. A comparison across
-- the two tables failed with error 468 or 451, and a filter on the copy
-- ignored case and U+0000 where the detail does not. Types, widths and
-- nullability are unchanged. No index, constraint, default or column grant
-- names the three columns.
--
-- The two CHAR columns change code page from UTF-8 to 1252. Their values are
-- copies of 1252 detail values, so they convert exactly; a stored value that
-- would not come back unchanged stops the migration before anything changes.
-- Trailing spaces do not count, as in any CHAR comparison: a one-character
-- value that filled both UTF-8 bytes is padded to the width afterwards.

IF EXISTS (
    SELECT 1 FROM pauth.auth_fraud
    WHERE CONVERT(NVARCHAR(2), auth_resp_code COLLATE Latin1_General_100_BIN2) COLLATE Latin1_General_100_BIN2
              <> CONVERT(NVARCHAR(2), auth_resp_code) COLLATE Latin1_General_100_BIN2
       OR CONVERT(NVARCHAR(1), match_status COLLATE Latin1_General_100_BIN2) COLLATE Latin1_General_100_BIN2
              <> CONVERT(NVARCHAR(1), match_status) COLLATE Latin1_General_100_BIN2)
    THROW 50509, N'V509: a stored pauth.auth_fraud auth_resp_code or match_status does not convert to code page 1252 unchanged; nothing was changed', 1;
GO

-- A column's collation cannot change while statistics cover it, and a live
-- database creates them automatically. They are rebuilt on demand.
DECLARE @drop NVARCHAR(MAX);
SELECT @drop = STRING_AGG(CONVERT(NVARCHAR(MAX), N'DROP STATISTICS pauth.auth_fraud.' + QUOTENAME(s.name) + N';'), N' ')
FROM sys.stats AS s
WHERE s.object_id = OBJECT_ID(N'pauth.auth_fraud')
  AND (s.auto_created = 1 OR s.user_created = 1)
  AND EXISTS (
      SELECT 1 FROM sys.stats_columns AS sc
      JOIN sys.columns AS c ON c.object_id = sc.object_id AND c.column_id = sc.column_id
      WHERE sc.object_id = s.object_id AND sc.stats_id = s.stats_id
        AND c.name IN (N'auth_resp_code', N'auth_resp_reason', N'match_status'));
IF @drop IS NOT NULL
    EXEC sys.sp_executesql @drop;
GO

ALTER TABLE pauth.auth_fraud ALTER COLUMN auth_resp_code   CHAR(2)  COLLATE Latin1_General_100_BIN2 NULL;
ALTER TABLE pauth.auth_fraud ALTER COLUMN auth_resp_reason NCHAR(4) COLLATE Latin1_General_100_BIN2 NULL;
ALTER TABLE pauth.auth_fraud ALTER COLUMN match_status     CHAR(1)  COLLATE Latin1_General_100_BIN2 NULL;
GO
