-- DEF-ACUP-002 (D-11, ruling v4): where positions 103-122 of an account came
-- from. legacy_zip_group keeps the 20 characters of the file as read, nothing
-- trimmed, and zip_group_origin says how the load stored them: R as read
-- (CVACT01Y), M moved from the ZIP position to group_id (ACUP_UPDATE, declared
-- by a layout manifest). An export writes legacy_zip_group back while addr_zip
-- and group_id still hold what the load derived from it, and the CVACT01Y
-- layout otherwise. Both NULL: the row was not loaded from a file.
ALTER TABLE core.account ADD legacy_zip_group NCHAR(20) COLLATE Latin1_General_100_BIN2 NULL,
    zip_group_origin CHAR(1) COLLATE Latin1_General_100_BIN2 NULL;
GO

ALTER TABLE core.account ADD CONSTRAINT CK_account_zip_group_origin CHECK (zip_group_origin IN ('R', 'M'));
ALTER TABLE core.account ADD CONSTRAINT CK_account_zip_group_pair CHECK (
    (legacy_zip_group IS NULL AND zip_group_origin IS NULL)
    OR (legacy_zip_group IS NOT NULL AND zip_group_origin IS NOT NULL));
GO
