-- Text repertoire for the transaction type and category descriptions
-- (data-model.md, "Text repertoire and width", stage 1; reference-service.md,
-- question 6). trantype.txt and trancatg.txt carry 50 characters of
-- ISO-8859-1 in each description, and every input path keeps the whole range
-- U+0000 to U+00FF. Under the UTF-8 database collation VARCHAR(50) holds 50
-- bytes, not 50 characters, so it refuses a full description that has an
-- accented letter. NVARCHAR(50) holds 50 characters of the range. Every value
-- the old type held converts unchanged.
--
-- No index, key, check, default, view or procedure names these columns, and
-- the grants on both tables are table-level, which ALTER COLUMN keeps. The
-- collation stays the database default, as V301 left it.

ALTER TABLE ref.tran_type ALTER COLUMN description NVARCHAR(50) NOT NULL;
ALTER TABLE ref.tran_category ALTER COLUMN description NVARCHAR(50) NOT NULL;
GO
