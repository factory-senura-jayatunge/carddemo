-- ENT-TRAN-TYPE (TRANTYPE, CVTRA03Y, 60 bytes) and ENT-TRAN-CATEGORY
-- (TRANCATG, CVTRA04Y, 60 bytes). One store replaces both the VSAM files and
-- the Db2 tables of the type maintenance module (INV-019).
CREATE TABLE ref.tran_type (
    type_cd      CHAR(2)      COLLATE Latin1_General_100_BIN2 NOT NULL,
    description  VARCHAR(50)  NOT NULL,
    row_version  ROWVERSION   NOT NULL,
    CONSTRAINT PK_tran_type PRIMARY KEY (type_cd)
);
GO

-- A type that still has categories cannot be deleted, as Db2's ON DELETE
-- RESTRICT refuses it (BR-TTLS-009).
CREATE TABLE ref.tran_category (
    type_cd      CHAR(2)      COLLATE Latin1_General_100_BIN2 NOT NULL,
    cat_cd       CHAR(4)      COLLATE Latin1_General_100_BIN2 NOT NULL,
    description  VARCHAR(50)  NOT NULL,
    row_version  ROWVERSION   NOT NULL,
    CONSTRAINT PK_tran_category PRIMARY KEY (type_cd, cat_cd),
    CONSTRAINT FK_tran_category_type FOREIGN KEY (type_cd) REFERENCES ref.tran_type (type_cd)
        ON DELETE NO ACTION,
    CONSTRAINT CK_tran_category_cat_cd CHECK (LEN(cat_cd) = 4 AND cat_cd NOT LIKE '%[^0-9]%')
);
GO
