-- The lookup lists of CSLKPCDY that the account update edits use: 56 state
-- codes (VALID-US-STATE-CODE), 240 state and ZIP-prefix pairs
-- (VALID-US-STATE-ZIP-CD2-COMBO) and 410 general-purpose area codes
-- (VALID-GENERAL-PURP-CODE). They are exposed read-only so the Java and
-- TypeScript validators and the database agree. The pairs have no foreign key
-- to the states: the copybook lists pairs for AA, AE, AP, FM, MH and PW, which
-- are not in its state list, and the lists are kept as the copybook has them.
CREATE TABLE ref.us_state (
    state_cd  CHAR(2)  COLLATE Latin1_General_100_BIN2 NOT NULL,
    CONSTRAINT PK_us_state PRIMARY KEY (state_cd)
);
GO

CREATE TABLE ref.us_state_zip_prefix (
    state_cd    CHAR(2)  COLLATE Latin1_General_100_BIN2 NOT NULL,
    zip_prefix  CHAR(2)  COLLATE Latin1_General_100_BIN2 NOT NULL,
    CONSTRAINT PK_us_state_zip_prefix PRIMARY KEY (state_cd, zip_prefix),
    CONSTRAINT CK_us_state_zip_prefix_digits CHECK (zip_prefix LIKE '[0-9][0-9]')
);
GO

CREATE TABLE ref.nanp_area_code (
    area_code  CHAR(3)  COLLATE Latin1_General_100_BIN2 NOT NULL,
    CONSTRAINT PK_nanp_area_code PRIMARY KEY (area_code),
    CONSTRAINT CK_nanp_area_code_digits CHECK (area_code LIKE '[0-9][0-9][0-9]')
);
GO
