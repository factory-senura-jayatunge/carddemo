-- ENT-CUSTOMER (CUSTDATA, CVCUS01Y, 500 bytes).
CREATE TABLE core.customer (
    cust_id              CHAR(9)      COLLATE Latin1_General_100_BIN2 NOT NULL,
    first_name           VARCHAR(25)  NOT NULL,
    middle_name          VARCHAR(25)  NOT NULL,
    last_name            VARCHAR(25)  NOT NULL,
    addr_line_1          VARCHAR(50)  NOT NULL,
    addr_line_2          VARCHAR(50)  NOT NULL,
    addr_line_3          VARCHAR(50)  NOT NULL,
    state_cd             CHAR(2)      NOT NULL,
    country_cd           CHAR(3)      NOT NULL,
    zip                  VARCHAR(10)  NOT NULL,
    phone_1              VARCHAR(15)  NOT NULL,
    phone_2              VARCHAR(15)  NOT NULL,
    ssn                  CHAR(9)      MASKED WITH (FUNCTION = 'partial(0,"XXXXX",4)') NOT NULL,
    govt_issued_id       VARCHAR(20)  MASKED WITH (FUNCTION = 'default()') NOT NULL,
    dob                  DATE         MASKED WITH (FUNCTION = 'default()') NULL,
    eft_account_id       CHAR(10)     MASKED WITH (FUNCTION = 'default()') NOT NULL,
    pri_card_holder_ind  CHAR(1)      NOT NULL,
    fico_score           SMALLINT     NOT NULL,
    row_version          ROWVERSION   NOT NULL,
    CONSTRAINT PK_customer PRIMARY KEY (cust_id),
    CONSTRAINT CK_customer_cust_id CHECK (LEN(cust_id) = 9 AND cust_id NOT LIKE '%[^0-9]%')
);
