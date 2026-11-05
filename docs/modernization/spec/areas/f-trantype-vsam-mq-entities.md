# Area f-trantype-vsam-mq: entity proposals

Proposals for `../entities.md` from the transaction type (Db2) module (`app/app-transaction-type-db2`) and the VSAM-MQ module (`app/app-vsam-mq`). Positions are 1-based bytes. Db2 tables are described column by column; their host-variable layouts come from the DCLGEN members.

## ENT-DB2-TRAN-TYPE · Db2 table CARDDEMO.TRANSACTION_TYPE

- **Source:** `app/app-transaction-type-db2/ctl/DB2CREAT.ctl:35-50` (the DDL that CREADB21 runs), `app/app-transaction-type-db2/ddl/TRNTYPE.ddl:1-4`, `app/app-transaction-type-db2/ddl/XTRNTYPE.ddl`, DCLGEN `app/app-transaction-type-db2/dcl/DCLTRTYP.dcl:28-46`.
- **Store:** table `CARDDEMO.TRANSACTION_TYPE` in table space `CARDDEMO.CARDSPC1` (`SEGSIZE 4`, `LOCKSIZE TABLE`, `CCSID EBCDIC`, `DB2CREAT.ctl:24-31`).
- **Key:** primary key `TR_TYPE`, unique index `CARDDEMO.XTRAN_TYPE` (`TR_TYPE ASC`, `DB2CREAT.ctl:44-50`).
- **Access:** `GRANT DELETE, INSERT, SELECT, UPDATE ... TO PUBLIC` (`DB2CREAT.ctl:57-59`).
- **Used by:** COTRTLIC (select, update, delete), COTRTUPC (select, insert, update, delete), COBTUPDT (insert, update, delete), TRANEXTR STEP40 (unload with DSNTIAUL), CREADB21 RUNTEP2 (seed insert).

| Field | Pos | Len | PIC | Type | Meaning |
| :-- | :-- | --: | :-- | :-- | :-- |
| TR_TYPE (`DCL-TR-TYPE`) | 1-2 | 2 | X(2) | CHAR(2) NOT NULL | Transaction type code. The online programs store two digits with a leading zero; the batch maintenance program stores whatever two characters it gets. |
| TR_DESCRIPTION (`DCL-TR-DESCRIPTION-LEN`) | 3-4 | 2 | S9(4) COMP | VARCHAR length | Length of the description in host-variable form |
| TR_DESCRIPTION (`DCL-TR-DESCRIPTION-TEXT`) | 5-54 | 50 | X(50) | VARCHAR(50) NOT NULL | Description. COBTUPDT, COTRTLIC and COTRTUPC pass length 50 (the online programs left-trim the text, `app/app-transaction-type-db2/cbl/COTRTLIC.cbl:1845-1848`, `app/app-transaction-type-db2/cbl/COTRTUPC.cbl:1538-1542`), so rows they write are 50 long; CREADB21 seed rows keep the literal length. |

Sample observations: the seed `app/app-transaction-type-db2/ctl/DB2LTTYP.ctl:17-23` inserts 7 rows `01`-`07` in upper case (`PURCHASE`, `PAYMENT`, `CREDIT`, `AUTHORIZATION`, `REFUND`, `REVERAL` [sic], `ADJUSTMENT`), the same codes as the VSAM file ENT-TRAN-TYPE.

## ENT-DB2-TRAN-TYPE-CAT · Db2 table CARDDEMO.TRANSACTION_TYPE_CATEGORY

- **Source:** `app/app-transaction-type-db2/ctl/DB2CREAT.ctl:61-105`, `app/app-transaction-type-db2/ddl/TRNTYCAT.ddl:1-7`, `app/app-transaction-type-db2/ddl/XTRNTYCAT.ddl`, DCLGEN `app/app-transaction-type-db2/dcl/DCLTRCAT.dcl:28-51`.
- **Store:** table `CARDDEMO.TRANSACTION_TYPE_CATEGORY` in table space `CARDDEMO.CARDSTTC` (`LOCKSIZE TABLE`, `CCSID EBCDIC`).
- **Key:** primary key (`TRC_TYPE_CODE`, `TRC_TYPE_CATEGORY`), unique index `CARDDEMO.X_TRAN_TYPE_CATG` (`DB2CREAT.ctl:85-92`).
- **Foreign key:** `TRC_TYPE_CODE` references ENT-DB2-TRAN-TYPE `TR_TYPE`, `ON DELETE RESTRICT` (`DB2CREAT.ctl:96-99`): a type that still has categories cannot be deleted (SQLCODE -532).
- **Used by:** no program reads or writes it directly. COTRTLIC, COTRTUPC and COBTUPDT meet it through the foreign key on delete; TRANEXTR STEP50 unloads it; CREADB21 LDTCCAT seeds it.

| Field | Pos | Len | PIC | Type | Meaning |
| :-- | :-- | --: | :-- | :-- | :-- |
| TRC_TYPE_CODE (`DCL-TRC-TYPE-CODE`) | 1-2 | 2 | X(2) | CHAR(2) NOT NULL | Transaction type code (foreign key) |
| TRC_TYPE_CATEGORY (`DCL-TRC-TYPE-CATEGORY`) | 3-6 | 4 | X(4) | CHAR(4) NOT NULL | Category code, 4 digits in the seed data |
| TRC_CAT_DATA (`DCL-TRC-CAT-DATA-LEN`) | 7-8 | 2 | S9(4) COMP | VARCHAR length | Length of the category description |
| TRC_CAT_DATA (`DCL-TRC-CAT-DATA-TEXT`) | 9-58 | 50 | X(50) | VARCHAR(50) NOT NULL | Category description |

Sample observations: the seed `app/app-transaction-type-db2/ctl/DB2LTCAT.ctl:20-37` inserts 18 categories in upper case, for the same (type, category) pairs as the VSAM file ENT-TRAN-CATEGORY.

## ENT-TTYP-MAINT-INPUT · Transaction type maintenance input record (MNTTRDB2 INPFILE)

- **Source:** `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:31-46, 71-77`; layout comment `app/app-transaction-type-db2/jcl/MNTTRDB2.jcl:8-18`.
- **Store:** sequential file, DD `INPFILE`, `RECORDING MODE F`, 53-byte records (the JCL allocates `DSN=INPFILE,DISP=SHR`, a placeholder name).
- **Key:** none; processed in file order.

| Field | Pos | Len | PIC | Type | Meaning |
| :-- | :-- | --: | :-- | :-- | :-- |
| INPUT-REC-TYPE | 1 | 1 | X(1) | alphanumeric | Action: `A` add, `U` update, `D` delete, `*` comment; anything else is an error. Upper case only. |
| INPUT-REC-NUMBER | 2-3 | 2 | X(2) | alphanumeric | Transaction type code, used as is (the JCL comment says "numeric", the program does not check it) |
| INPUT-REC-DESC | 4-53 | 50 | X(50) | alphanumeric | Description for add and update, stored as is with trailing spaces; ignored for delete and comment |

## ENT-MQ-ACCT-REQUEST · Account inquiry request message (CDRA)

- **Source:** COACCT01 `REQUEST-MSG-COPY`, `app/app-vsam-mq/cbl/COACCT01.cbl:109-112`; received through `MQ-BUFFER` (`app/app-vsam-mq/cbl/COACCT01.cbl:50, 369, 373`).
- **Store:** the MQ queue whose trigger starts transaction CDRA (name not in the repository, Q-MQAC-001). Message up to 1000 bytes; the data length is not used (DEF-MQAC-006).

| Field | Pos | Len | PIC | Type | Meaning |
| :-- | :-- | --: | :-- | :-- | :-- |
| WS-FUNC | 1-4 | 4 | X(4) | alphanumeric | Function code; only `INQA` (upper case) is accepted (`COACCT01.cbl:393`) |
| WS-KEY | 5-15 | 11 | 9(11) | unsigned zoned decimal | Account ID with leading zeros; must be greater than zero (`COACCT01.cbl:393-394`); not checked for numeric content |
| WS-FILLER | 16-1000 | 985 | X(985) | alphanumeric | Ignored |

## ENT-MQ-ACCT-REPLY · Account inquiry reply message (CARD.DEMO.REPLY.ACCT)

- **Source:** COACCT01 `WS-ACCT-RESPONSE` (`app/app-vsam-mq/cbl/COACCT01.cbl:130-169`), moved into the 1000-byte `REPLY-MESSAGE` (`app/app-vsam-mq/cbl/COACCT01.cbl:426`), put at `app/app-vsam-mq/cbl/COACCT01.cbl:467-468`.
- **Store:** queue `CARD.DEMO.REPLY.ACCT`, 1000 bytes, format `MQFMT_STRING`. MsgId and CorrelId are the request's values (`app/app-vsam-mq/cbl/COACCT01.cbl:469-470`, DEF-MQAC-003).

Found-account body:

| Field | Pos | Len | PIC | Type | Meaning |
| :-- | :-- | --: | :-- | :-- | :-- |
| WS-ACCT-LBL | 1-13 | 13 | X(13) | literal | `ACCOUNT ID : ` |
| WS-ACCT-ID | 14-24 | 11 | 9(11) | zoned | ENT-ACCOUNT `ACCT-ID` |
| WS-STATUS-LBL | 25-41 | 17 | X(17) | literal | `ACCOUNT STATUS : ` |
| WS-ACCT-ACTIVE-STATUS | 42 | 1 | X(1) | alphanumeric | `ACCT-ACTIVE-STATUS` |
| WS-CURR-BAL-LBL | 43-52 | 10 | X(10) | literal | `BALANCE : ` |
| WS-ACCT-CURR-BAL | 53-64 | 12 | S9(10)V99 | zoned, trailing overpunch sign, 2 implied decimals | `ACCT-CURR-BAL` |
| WS-CRDT-LMT-LBL | 65-79 | 15 | X(15) | literal | `CREDIT LIMIT : ` |
| WS-ACCT-CREDIT-LIMIT | 80-91 | 12 | S9(10)V99 | zoned overpunch | `ACCT-CREDIT-LIMIT` |
| WS-CASH-LIMIT-LBL | 92-104 | 13 | X(13) | literal | `CASH LIMIT : ` |
| WS-ACCT-CASH-CREDIT-LIMIT | 105-116 | 12 | S9(10)V99 | zoned overpunch | `ACCT-CASH-CREDIT-LIMIT` |
| WS-OPEN-DATE-LBL | 117-128 | 12 | X(12) | literal | `OPEN DATE : ` |
| WS-ACCT-OPEN-DATE | 129-138 | 10 | X(10) | alphanumeric | `ACCT-OPEN-DATE` |
| WS-EXPR-DATE-LBL | 139-150 | 12 | X(12) | literal | `EXPR DATE : ` |
| WS-ACCT-EXPIRAION-DATE | 151-160 | 10 | X(10) | alphanumeric | `ACCT-EXPIRAION-DATE` |
| WS-REISSUE-DT-LBL | 161-172 | 12 | X(12) | literal | `REIS DATE : ` |
| WS-ACCT-REISSUE-DATE | 173-182 | 10 | X(10) | alphanumeric | `ACCT-REISSUE-DATE` |
| WS-CURR-CYC-CREDIT-LBL | 183-195 | 13 | X(13) | literal | `CREDIT BAL : ` |
| WS-ACCT-CURR-CYC-CREDIT | 196-207 | 12 | S9(10)V99 | zoned overpunch | `ACCT-CURR-CYC-CREDIT` |
| WS-CURR-CYC-DEBIT-LBL | 208-219 | 12 | X(12) | literal | `DEBIT BAL : ` |
| WS-ACCT-CURR-CYC-DEBIT | 220-231 | 12 | S9(10)V99 | zoned overpunch | `ACCT-CURR-CYC-DEBIT` |
| WS-ACCT-GRP-LBL | 232-242 | 11 | X(11) | literal | `GROUP ID : ` |
| WS-ACCT-GROUP-ID | 243-252 | 10 | X(10) | alphanumeric | `ACCT-GROUP-ID` |
| (padding) | 253-1000 | 748 | - | spaces | |

Other bodies: not found (`COACCT01.cbl:429-434`) is `INVALID REQUEST PARAMETERS ` + `ACCT ID : ` + the 11-digit key, 48 bytes, then spaces; invalid request (`app/app-vsam-mq/cbl/COACCT01.cbl:449-455`) is the same 48 bytes + `FUNCTION : ` + the 4-byte function, 63 bytes, then spaces. There is no status field (DEF-MQAC-014).

## ENT-MQ-DATE-REPLY · System date reply message (CARD.DEMO.REPLY.DATE)

- **Source:** CODATE01 `REPLY-MESSAGE` (`app/app-vsam-mq/cbl/CODATE01.cbl:340, 355-360`), fields at `app/app-vsam-mq/cbl/CODATE01.cbl:37-38`, put at `app/app-vsam-mq/cbl/CODATE01.cbl:371-372`.
- **Store:** queue `CARD.DEMO.REPLY.DATE`, 1000 bytes, `MQFMT_STRING`. MsgId and CorrelId are the request's values (`app/app-vsam-mq/cbl/CODATE01.cbl:373-374`).

| Field | Pos | Len | PIC | Type | Meaning |
| :-- | :-- | --: | :-- | :-- | :-- |
| (literal) | 1-14 | 14 | - | literal | `SYSTEM DATE : ` |
| WS-MMDDYYYY | 15-24 | 10 | X(10) | alphanumeric | CICS FORMATTIME `MMDDYYYY` with `-`: `MM-DD-YYYY` |
| (literal) | 25-38 | 14 | - | literal | `SYSTEM TIME : ` |
| WS-TIME | 39-46 | 8 | X(8) | alphanumeric | CICS FORMATTIME `TIME` with `:`: `HH:MM:SS`, 24-hour, region local time |
| (padding) | 47-1000 | 954 | - | spaces | |

## ENT-MQ-ERROR-MSG · Error message (CARD.DEMO.ERROR)

- **Source:** `MQ-ERR-DISPLAY`, `app/app-vsam-mq/cbl/COACCT01.cbl:58-67` and `app/app-vsam-mq/cbl/CODATE01.cbl:58-67`; moved into the 1000-byte `ERROR-MESSAGE` and put at `app/app-vsam-mq/cbl/COACCT01.cbl:505-507` and `app/app-vsam-mq/cbl/CODATE01.cbl:409-411`.
- **Store:** queue `CARD.DEMO.ERROR`, 1000 bytes, `MQFMT_STRING`. MsgId and CorrelId are not set; they are whatever the shared message descriptor holds.

| Field | Pos | Len | PIC | Type | Meaning |
| :-- | :-- | --: | :-- | :-- | :-- |
| MQ-ERROR-PARA | 1-25 | 25 | X(25) | alphanumeric | `CICS RETREIVE` (COACCT01) or `CICS RETRIEVE` (CODATE01) on the RETRIEVE path; otherwise spaces |
| FILLER | 26-27 | 2 | X(2) | spaces | |
| MQ-APPL-RETURN-MESSAGE | 28-52 | 25 | X(25) | alphanumeric | `RESP: nnnnnnnn00000000END`, `INP MQOPEN ERR`, `OUT MQOPEN ERR`, `INP MQGET ERR:`, `ERROR WHILE READING ACCTF` (COACCT01 only), `MQPUT ERR`, `MQCLOSE ERR` |
| FILLER | 53-54 | 2 | X(2) | spaces | |
| MQ-APPL-CONDITION-CODE | 55-56 | 2 | 9(2) | unsigned zoned | MQ completion code; for the COACCT01 file error, the CICS RESP truncated to 2 digits (RESP mod 100) |
| FILLER | 57-58 | 2 | X(2) | spaces | |
| MQ-APPL-REASON-CODE | 59-63 | 5 | 9(5) | unsigned zoned | MQ reason code; for the COACCT01 file error, RESP2 mod 100000 |
| FILLER | 64-65 | 2 | X(2) | spaces | |
| MQ-APPL-QUEUE-NAME | 66-113 | 48 | X(48) | alphanumeric | Queue concerned (input queue, `CARD.DEMO.REPLY.*`, `CARD.DEMO.ERROR`), or spaces on the RETRIEVE path |
| (padding) | 114-1000 | 887 | - | spaces | |

## Corrections to `entities.md`

| Entity | Field or statement | Current text | Proposed text | Evidence (file:line) |
| :-- | :-- | :-- | :-- | :-- |
| ENT-TRAN-TYPE and ENT-TRAN-CATEGORY | layout | `TRAN-TYPE` X(2), `TRAN-TYPE-DESC` X(50); key + `TRAN-CAT-TYPE-DESC` X(50) | Add the FILLERs: type record FILLER X(8) (bytes 53-60, `00000000` in the sample and in TRANEXTR output), category record FILLER X(4) (bytes 57-60, `0000`); both records 60 bytes | `app/cpy/CVTRA03Y.cpy:4-7`, `app/cpy/CVTRA04Y.cpy:4-9`, `app/app-transaction-type-db2/jcl/TRANEXTR.jcl:76-81, 106-111` |
| ENT-TRAN-TYPE and ENT-TRAN-CATEGORY | source | sample data only | Add: in the Db2 module the files are produced from ENT-DB2-TRAN-TYPE and ENT-DB2-TRAN-TYPE-CAT by TRANEXTR and loaded into VSAM by the TRANTYPE and TRANCATG jobs (`KEYS(2 0)` and `KEYS(6 0)`, `RECORDSIZE(60 60)`) | `app/jcl/TRANTYPE.jcl:36-61`, `app/jcl/TRANCATG.jcl:36-61` |
| ENT-TRAN-TYPE and ENT-TRAN-CATEGORY | descriptions | mixed case (Purchase, Reversal, ...) | Note that the Db2 seed is upper case with `06` = `REVERAL`; after a TRANEXTR + reload the VSAM text changes accordingly | `app/app-transaction-type-db2/ctl/DB2LTTYP.ctl:17-23` |

## Relationships

| From | To | Join fields | Cardinality | Evidence |
| :-- | :-- | :-- | :-- | :-- |
| ENT-DB2-TRAN-TYPE-CAT | ENT-DB2-TRAN-TYPE | `TRC_TYPE_CODE` = `TR_TYPE` (foreign key, ON DELETE RESTRICT) | many to one | `app/app-transaction-type-db2/ctl/DB2CREAT.ctl:96-99` |
| ENT-TTYP-MAINT-INPUT | ENT-DB2-TRAN-TYPE | `INPUT-REC-NUMBER` = `TR_TYPE` | each record names at most one row | `app/app-transaction-type-db2/cbl/COBTUPDT.cbl:145, 174, 203` |
| ENT-DB2-TRAN-TYPE | ENT-TRAN-TYPE | `TR_TYPE` = `TRAN-TYPE` (copy made by TRANEXTR, then REPRO) | one to one after a refresh | `app/app-transaction-type-db2/jcl/TRANEXTR.jcl:76-84` |
| ENT-DB2-TRAN-TYPE-CAT | ENT-TRAN-CATEGORY | (`TRC_TYPE_CODE`, `TRC_TYPE_CATEGORY`) = (`TRAN-TYPE-CD`, `TRAN-CAT-CD`) | one to one after a refresh | `app/app-transaction-type-db2/jcl/TRANEXTR.jcl:106-115` |
| ENT-MQ-ACCT-REQUEST | ENT-ACCOUNT | `WS-KEY` = `ACCT-ID` | zero or one | `app/app-vsam-mq/cbl/COACCT01.cbl:393-404` |
| ENT-MQ-ACCT-REPLY | ENT-ACCOUNT | fields copied from the account read | copy of one record | `app/app-vsam-mq/cbl/COACCT01.cbl:408-425` |
