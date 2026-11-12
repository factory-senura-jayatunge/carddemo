-- Duplicate detection (DEF-AUTH-006) and the reply outbox (D-35) of the
-- authorization listener's fixed mode. The purge deletes processed_message
-- rows and sent outbox rows older than its expiry (D-34), so a duplicate that
-- arrives after the purge is handled as new.

-- One row per handled request. msg_key is the SHA-256 of 'M|' + message ID, or
-- of 'K|' + card + transaction ID + request date and time when the message ID
-- is empty, so the key holds no card number.
CREATE TABLE pauth.processed_message (
    msg_key         BINARY(32)    NOT NULL,
    key_kind        CHAR(1)       NOT NULL,
    auth_id         BIGINT        NULL,
    -- The 63-character reply, resent unchanged to a duplicate. It holds the card number.
    reply_body      CHAR(63)      COLLATE Latin1_General_100_BIN2 MASKED WITH (FUNCTION = 'default()') NOT NULL,
    received_at     DATETIME2(3)  NOT NULL CONSTRAINT DF_processed_message_received DEFAULT SYSUTCDATETIME(),
    CONSTRAINT PK_processed_message PRIMARY KEY (msg_key),
    CONSTRAINT CK_processed_message_kind CHECK (key_kind IN ('M', 'K'))
);
GO
CREATE INDEX IX_processed_message_received ON pauth.processed_message (received_at);
GO

-- Replies committed with the decision. The publisher sends them with
-- publisher confirms; only then is the request acknowledged.
CREATE TABLE pauth.outbox (
    outbox_id       BIGINT IDENTITY(1,1) NOT NULL,
    created_at      DATETIME2(3)  NOT NULL CONSTRAINT DF_outbox_created DEFAULT SYSUTCDATETIME(),
    exchange_name   VARCHAR(255)  NOT NULL,
    routing_key     VARCHAR(255)  NOT NULL,
    correlation_id  VARCHAR(255)  NULL,
    content_type    VARCHAR(40)   NOT NULL,
    expiration_ms   INT           NOT NULL,
    body            VARCHAR(1000) COLLATE Latin1_General_100_BIN2 MASKED WITH (FUNCTION = 'default()') NOT NULL,
    attempts        INT           NOT NULL CONSTRAINT DF_outbox_attempts DEFAULT 0,
    sent_at         DATETIME2(3)  NULL,
    CONSTRAINT PK_outbox PRIMARY KEY (outbox_id)
);
GO
CREATE INDEX IX_outbox_unsent ON pauth.outbox (outbox_id) WHERE sent_at IS NULL;
GO
