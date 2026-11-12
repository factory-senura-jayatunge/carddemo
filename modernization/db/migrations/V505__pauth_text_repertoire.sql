-- Text repertoire of the authorization tables (data-model.md, Text repertoire
-- and width). The request's text fields are stored as received, and a request
-- may carry any of the 256 characters U+0000 to U+00FF. Under the UTF-8
-- database collation a CHAR(n) or VARCHAR(n) holds n bytes, and U+0080 up takes
-- two, so a full-width value of such characters failed the insert. NCHAR(n)
-- and NVARCHAR(n) hold n characters. Widths, nullability and collations are
-- unchanged: the text columns keep the database collation, the reply carriers
-- keep Latin1_General_100_BIN2, whose code-point order is ISO-8859-1 byte
-- order. The stored data is ASCII, so the conversion keeps every value.
-- auth_fraud.pos_entry_mode is SMALLINT and the codes the service sets stay
-- CHAR. The two user-ID columns become NCHAR(8) BIN2 like every column that
-- holds a user ID, because a user ID is stored as typed (BR-SGN-001).

-- The pending-approval index includes merchant_id.
DROP INDEX IX_auth_detail_pending ON pauth.auth_detail;
GO

ALTER TABLE pauth.auth_detail ALTER COLUMN orig_date              NCHAR(6)      NOT NULL;
ALTER TABLE pauth.auth_detail ALTER COLUMN orig_time              NCHAR(6)      NOT NULL;
ALTER TABLE pauth.auth_detail ALTER COLUMN auth_type              NCHAR(4)      NOT NULL;
ALTER TABLE pauth.auth_detail ALTER COLUMN card_expiry_date       NCHAR(4)      NOT NULL;
ALTER TABLE pauth.auth_detail ALTER COLUMN message_type           NCHAR(6)      NOT NULL;
ALTER TABLE pauth.auth_detail ALTER COLUMN message_source         NCHAR(6)      NOT NULL;
ALTER TABLE pauth.auth_detail ALTER COLUMN processing_code        NCHAR(6)      NOT NULL;
ALTER TABLE pauth.auth_detail ALTER COLUMN merchant_category_code NCHAR(4)      NOT NULL;
ALTER TABLE pauth.auth_detail ALTER COLUMN acqr_country_code      NCHAR(3)      NOT NULL;
ALTER TABLE pauth.auth_detail ALTER COLUMN pos_entry_mode         NCHAR(2)      NOT NULL;
ALTER TABLE pauth.auth_detail ALTER COLUMN merchant_id            NCHAR(15)     NOT NULL;
ALTER TABLE pauth.auth_detail ALTER COLUMN merchant_name          NVARCHAR(22)  NOT NULL;
ALTER TABLE pauth.auth_detail ALTER COLUMN merchant_city          NVARCHAR(13)  NOT NULL;
ALTER TABLE pauth.auth_detail ALTER COLUMN merchant_state         NCHAR(2)      NOT NULL;
ALTER TABLE pauth.auth_detail ALTER COLUMN merchant_zip           NVARCHAR(9)   NOT NULL;
ALTER TABLE pauth.auth_detail ALTER COLUMN transaction_id         NCHAR(15)     NOT NULL;
ALTER TABLE pauth.auth_detail ALTER COLUMN fraud_changed_by       NCHAR(8)      COLLATE Latin1_General_100_BIN2 NULL;
GO

CREATE INDEX IX_auth_detail_pending ON pauth.auth_detail (card_num, approved_amt)
    INCLUDE (merchant_id, processed_at) WHERE match_status = 'P';
GO

ALTER TABLE pauth.auth_fraud ALTER COLUMN auth_type              NCHAR(4)      NULL;
ALTER TABLE pauth.auth_fraud ALTER COLUMN card_expiry_date       NCHAR(4)      NULL;
ALTER TABLE pauth.auth_fraud ALTER COLUMN message_type           NCHAR(6)      NULL;
ALTER TABLE pauth.auth_fraud ALTER COLUMN message_source         NCHAR(6)      NULL;
ALTER TABLE pauth.auth_fraud ALTER COLUMN processing_code        NCHAR(6)      NULL;
ALTER TABLE pauth.auth_fraud ALTER COLUMN merchant_category_code NCHAR(4)      NULL;
ALTER TABLE pauth.auth_fraud ALTER COLUMN acqr_country_code      NCHAR(3)      NULL;
ALTER TABLE pauth.auth_fraud ALTER COLUMN merchant_id            NCHAR(15)     NULL;
ALTER TABLE pauth.auth_fraud ALTER COLUMN merchant_name          NVARCHAR(22)  NULL;
ALTER TABLE pauth.auth_fraud ALTER COLUMN merchant_city          NCHAR(13)     NULL;
ALTER TABLE pauth.auth_fraud ALTER COLUMN merchant_state         NCHAR(2)      NULL;
ALTER TABLE pauth.auth_fraud ALTER COLUMN merchant_zip           NCHAR(9)      NULL;
ALTER TABLE pauth.auth_fraud ALTER COLUMN transaction_id         NCHAR(15)     NULL;
ALTER TABLE pauth.auth_fraud ALTER COLUMN reported_by            NCHAR(8)      COLLATE Latin1_General_100_BIN2 NOT NULL;
GO

-- The reply repeats the request's card value and transaction ID. The mask is
-- dropped for the type change and added back with the same function; the
-- UNMASK grants survive.
ALTER TABLE pauth.processed_message ALTER COLUMN reply_body DROP MASKED;
ALTER TABLE pauth.processed_message ALTER COLUMN reply_body NCHAR(63) COLLATE Latin1_General_100_BIN2 NOT NULL;
ALTER TABLE pauth.processed_message ALTER COLUMN reply_body ADD MASKED WITH (FUNCTION = 'default()');
GO

ALTER TABLE pauth.outbox ALTER COLUMN body DROP MASKED;
ALTER TABLE pauth.outbox ALTER COLUMN body NVARCHAR(1000) COLLATE Latin1_General_100_BIN2 NOT NULL;
ALTER TABLE pauth.outbox ALTER COLUMN body ADD MASKED WITH (FUNCTION = 'default()');
GO
