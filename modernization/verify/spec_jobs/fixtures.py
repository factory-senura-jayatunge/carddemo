"""Source-defined DML fixtures; usable only in the driver's private database."""
from __future__ import annotations

ACCOUNT_SENTINEL = "87361920451"
SENTINELS = (ACCOUNT_SENTINEL, "4987654321098765", "SpecSentinelName",
             "876543219", "SPEC-GOVT-ID-9361", "1973-02-19", "8736192045")

# All source sample account/customer IDs stay intact. A separate account makes
# the sentinel distinctive enough not to match a generated transaction ID.
PII_DML = """
INSERT INTO core.account(acct_id,active_status,curr_bal,credit_limit,cash_credit_limit,
 open_date,expiration_date,reissue_date,curr_cyc_credit,curr_cyc_debit,addr_zip,group_id,legacy_filler)
 SELECT TOP (1) '87361920451',active_status,curr_bal,credit_limit,cash_credit_limit,
 open_date,expiration_date,reissue_date,curr_cyc_credit,curr_cyc_debit,addr_zip,group_id,legacy_filler
 FROM core.account ORDER BY acct_id;
UPDATE core.customer SET first_name=N'SpecSentinelName', ssn='876543219',
 govt_issued_id=N'SPEC-GOVT-ID-9361', dob='1973-02-19', eft_account_id='8736192045'
 WHERE cust_id=(SELECT MIN(cust_id) FROM core.customer);
UPDATE core.card SET card_num=N'4987654321098765'
 WHERE card_num=(SELECT MIN(card_num) FROM core.card);
UPDATE core.card_xref SET card_num=N'4987654321098765'
 WHERE card_num=(SELECT MIN(card_num) FROM core.card_xref);
"""


def archive(cycle_id: int, previous_cycle: int) -> str:
    values = []
    origins = ("POSTED", "INTEREST", "ONLINE_ADD", "BILL_PAY")
    for i in range(1, 351):
        origin = origins[(i - 1) % 4]
        values.append(
            f"(N'{i:016d}',N'01',N'0001',N'SPEC',N'fixture',{i}.01,"
            f"N'000000001',N'fixture',N'city',N'00000',N'4987654321098765',"
            f"'2022-07-06T12:00:00','2022-07-06T12:00:00',N'                    ',"
            f"'{origin}',{cycle_id if i <= 300 else previous_cycle},{1 if i <= 300 else 0})")
    return (
        "DELETE FROM core.transaction;\n"
        "INSERT INTO core.transaction (tran_id,type_cd,cat_cd,source,description,amount,"
        "merchant_id,merchant_name,merchant_city,merchant_zip,card_num,orig_ts,proc_ts,"
        "legacy_filler,origin,cycle_id,in_master) VALUES\n" + ",\n".join(values) + ";\n"
        "INSERT INTO core.daily_tran (tran_id,type_cd,cat_cd,source,description,amount,"
        "merchant_id,merchant_name,merchant_city,merchant_zip,card_num,orig_ts,proc_ts,"
        "raw_record,origin,cycle_id,seq,created_by) VALUES "
        "(N'8000000000000001',N'01',N'0001',N'SPEC',N'waiting',1,N'000000001',N'fixture',"
        "N'city',N'00000',N'4987654321098765',N'2022-07-06 12:00:00.000000',"
        "N'2022-07-06 12:00:00.000000',REPLICATE(N' ',350),'ONLINE_ADD',NULL,NULL,N'USER0001'),"
        "(N'8000000000000002',N'01',N'0001',N'SPEC',N'waiting',2,N'000000001',N'fixture',"
        "N'city',N'00000',N'4987654321098765',N'2022-07-06 12:00:00.000000',"
        "N'2022-07-06 12:00:00.000000',REPLICATE(N' ',350),'ONLINE_ADD',NULL,NULL,N'USER0001');")


SIGNS_DML = """
WITH keys AS (SELECT *, ROW_NUMBER() OVER(ORDER BY acct_id,type_cd,cat_cd) n FROM core.tcat_bal)
UPDATE keys SET balance=CASE n WHEN 1 THEN -0.01 WHEN 2 THEN -12.34 WHEN 3 THEN 0
 WHEN 4 THEN 999999999.99 WHEN 5 THEN -999999999.99 ELSE balance END WHERE n<=5;
INSERT INTO core.tcat_bal(acct_id,type_cd,cat_cd,balance,legacy_filler)
 VALUES ('00000000001',N'99',N'9999',-1.23,REPLICATE(N' ',22)),
 ('00000000001',N'98',N'9998',2.34,REPLICATE(N' ',22));
"""


def purge_edges(year_end: bool = False) -> str:
    cutoff = "2022-12-28" if year_end else "2022-07-01"
    later = "2022-12-29" if year_end else "2022-07-02"
    future = "2023-01-03" if year_end else "2022-07-07"
    return f"""
UPDATE pauth.auth_detail SET processed_at='2022-06-01',auth_fraud=' ';
WITH rows AS (SELECT *,ROW_NUMBER() OVER(ORDER BY auth_id) n FROM pauth.auth_detail)
UPDATE rows SET
 card_num=N'4987654321098765',
 processed_at=CASE n WHEN 1 THEN '{cutoff}' WHEN 2 THEN '{later}'
 WHEN 3 THEN '{future}' ELSE '2022-06-01' END,
 auth_fraud=CASE n WHEN 4 THEN 'F' ELSE ' ' END,
 auth_resp_code=CASE n WHEN 7 THEN '05' ELSE '00' END,
 match_status=CASE n WHEN 6 THEN 'M' ELSE 'P' END,
 matched_tran_pk=CASE n WHEN 6 THEN 87361920451 ELSE NULL END,
 matched_tran_id=CASE n WHEN 6 THEN N'8736192045100001' ELSE NULL END,
 matched_cycle_id=CASE n WHEN 6 THEN 1 ELSE NULL END,
 matched_at=CASE n WHEN 6 THEN '2022-06-01' ELSE NULL END WHERE n<=8;
INSERT INTO pauth.auth_fraud(auth_id,card_num,auth_ts,auth_fraud,fraud_rpt_date,acct_id,cust_id,reported_by)
 SELECT TOP(1) d.auth_id,d.card_num,d.processed_at,'F','2022-06-01',d.acct_id,s.cust_id,N'USER0001'
 FROM pauth.auth_detail d JOIN pauth.auth_summary s ON s.acct_id=d.acct_id
 WHERE d.auth_fraud='F' ORDER BY d.auth_id;
INSERT INTO pauth.auth_summary(acct_id,cust_id,credit_limit,cash_limit,credit_balance,cash_balance,
 approved_auth_cnt,declined_auth_cnt,approved_auth_amt,declined_auth_amt)
 SELECT TOP(1) '87361920451',cust_id,credit_limit,cash_limit,5,0,1,1,3,4
 FROM pauth.auth_summary ORDER BY acct_id;
INSERT INTO pauth.processed_message(msg_key,key_kind,auth_id,reply_body,received_at) VALUES
 (HASHBYTES('SHA2_256','spec-old'),'M',NULL,REPLICATE('X',63),'{cutoff}'),
 (HASHBYTES('SHA2_256','spec-new'),'M',NULL,REPLICATE('X',63),'{later}');
INSERT INTO pauth.outbox(created_at,exchange_name,routing_key,content_type,expiration_ms,body,sent_at) VALUES
 ('{cutoff}','spec','spec','text/plain',1,'SpecSentinelName','{cutoff}'),
 ('{later}','spec','spec','text/plain',1,'SpecSentinelName','{later}'),
 ('{cutoff}','spec','spec','text/plain',1,'SpecSentinelName',NULL);
"""
