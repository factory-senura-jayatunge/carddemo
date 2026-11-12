-- Text repertoire of the two authorization codes (data-model.md, Text
-- repertoire and width). V505 left auth_id_code and auth_resp_reason as CHAR
-- because the service sets them, but the pending-authorization load stores
-- PA-AUTH-ID-CODE and PA-AUTH-RESP-REASON of the unloaded segment as they are,
-- and like the request fields they may carry any of the 256 characters U+0000
-- to U+00FF. Under the UTF-8 database collation a CHAR(n) holds n bytes, so a
-- full-width code of characters from U+0080 up failed the insert. NCHAR(n)
-- holds n characters. Widths, nullability and collations are unchanged: on the
-- detail auth_id_code keeps the database collation and auth_resp_reason keeps
-- Latin1_General_100_BIN2; on the fraud copy both keep the database collation,
-- as V501 created them. No index or constraint names the four columns, and
-- the column grant UPDATE (auth_id_code) to authorization_svc survives the
-- type change. A stored value of n UTF-8 bytes has at most n characters, so
-- the conversion keeps every value.

ALTER TABLE pauth.auth_detail ALTER COLUMN auth_id_code     NCHAR(6)      NOT NULL;
ALTER TABLE pauth.auth_detail ALTER COLUMN auth_resp_reason NCHAR(4)      COLLATE Latin1_General_100_BIN2 NOT NULL;
GO

ALTER TABLE pauth.auth_fraud ALTER COLUMN auth_id_code      NCHAR(6)      NULL;
ALTER TABLE pauth.auth_fraud ALTER COLUMN auth_resp_reason  NCHAR(4)      NULL;
GO
