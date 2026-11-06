-- Transaction IDs in fixed mode of BR-TRNA-003 (D-59): 16 digits, never reused,
-- no wrap (NUM-BILL-005). The load restarts it above the highest loaded ID.
CREATE SEQUENCE core.tran_id_seq AS BIGINT
    START WITH 1 INCREMENT BY 1
    MINVALUE 1 MAXVALUE 9999999999999999
    NO CYCLE CACHE 50;
GO
