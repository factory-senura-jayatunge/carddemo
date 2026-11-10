-- ENT-DISC-GROUP (DISCGRP, CVTRA02Y, 50 bytes): the annual interest rate per
-- account group, type and category. Interest falls back to the DEFAULT group
-- for an account whose group has no row (INTCALC), so DEFAULT must exist; the
-- load and the reload check that. No foreign key to the categories or to
-- core.account.group_id: the legacy files have none, and a reload of one
-- table must not depend on the others.
CREATE TABLE ref.disclosure_group (
    group_id     CHAR(10)      COLLATE Latin1_General_100_BIN2 NOT NULL,
    type_cd      CHAR(2)       COLLATE Latin1_General_100_BIN2 NOT NULL,
    cat_cd       CHAR(4)       COLLATE Latin1_General_100_BIN2 NOT NULL,
    -- DIS-INT-RATE S9(04)V99: annual percent (INTCALC divides by 1200).
    int_rate     DECIMAL(6,2)  NOT NULL,
    row_version  ROWVERSION    NOT NULL,
    CONSTRAINT PK_disclosure_group PRIMARY KEY (group_id, type_cd, cat_cd),
    CONSTRAINT CK_disclosure_group_cat_cd CHECK (LEN(cat_cd) = 4 AND cat_cd NOT LIKE '%[^0-9]%')
);
GO
