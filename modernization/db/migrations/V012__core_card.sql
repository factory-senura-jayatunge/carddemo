-- ENT-CARD (CARDDATA, CVACT02Y, 150 bytes). No foreign key on acct_id: card
-- update in legacy mode may move a card to any account (DEF-CRDU-001).
CREATE TABLE core.card (
    card_num         CHAR(16)     COLLATE Latin1_General_100_BIN2 NOT NULL,
    card_id          BIGINT IDENTITY(1,1) NOT NULL,
    acct_id          CHAR(11)     COLLATE Latin1_General_100_BIN2 NOT NULL,
    cvv_cd           CHAR(3)      MASKED WITH (FUNCTION = 'default()') NOT NULL,
    embossed_name    VARCHAR(50)  NOT NULL,
    -- Text: card update keeps whatever day it read (DEF-CRDU-009).
    expiration_date  CHAR(10)     NOT NULL,
    active_status    CHAR(1)      NOT NULL,
    legacy_filler    CHAR(59)     NULL,
    row_version      ROWVERSION   NOT NULL,
    CONSTRAINT PK_card PRIMARY KEY (card_num),
    CONSTRAINT UQ_card_card_id UNIQUE (card_id),
    CONSTRAINT CK_card_expiration_date CHECK (expiration_date LIKE '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]')
);
GO
-- Replaces CARDAIX, without its 8-per-account limit.
CREATE INDEX IX_card_acct ON core.card (acct_id, card_num);
GO
