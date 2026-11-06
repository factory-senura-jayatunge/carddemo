# CardDemo component map

System-level map of programs, entry points, data stores, CRUD and cross-component invariants. It started with the slice around the two pilot units, `COBIL00C` (bill payment) and `CBTRN02C` (POSTTRAN), and holds the proposals merged from areas a (sign-on and administration), b (accounts and cards), c (transactions), d1 (core batch), d2 (batch utilities), e (authorization module, IMS, Db2 and MQ) and f (transaction type Db2 module and VSAM-MQ module). Sources: `docs/inventory.md`, `app/csd/CARDDEMO.CSD`, `app/jcl/`, `app/scheduler/`, `docs/runbooks/batch-cycle-restart.md`, the program and job specs and the `areas/` proposals. Components marked *(not yet decomposed)* are named from the inventory only; their behaviour will be specified when their own specs are merged.

## Component map

| Component | Kind | Entry point | Module | Role | Spec |
| :-- | :-- | :-- | :-- | :-- | :-- |
| `COSGN00C` | online | `CC00` | core | Sign-on: checks the user against `USRSEC`, then admin menu for type `A`, main menu otherwise | [programs/COSGN00C.md](programs/COSGN00C.md) |
| `COMEN01C` | online | `CM00` | core | Main menu, 11 options (ENT-MAIN-MENU-OPTIONS) | [programs/COMEN01C.md](programs/COMEN01C.md) |
| `COADM01C` | online | `CA00` | core | Admin menu, 6 options (ENT-ADMIN-MENU-OPTIONS) | [programs/COADM01C.md](programs/COADM01C.md) |
| `COUSR00C` | online | `CU00` | core | User list; hands the selected user to update or delete | [programs/COUSR00C.md](programs/COUSR00C.md) |
| `COUSR01C` | online | `CU01` | core | User add | [programs/COUSR01C.md](programs/COUSR01C.md) |
| `COUSR02C` | online | `CU02` | core | User update | [programs/COUSR02C.md](programs/COUSR02C.md) |
| `COUSR03C` | online | `CU03` | core | User delete | [programs/COUSR03C.md](programs/COUSR03C.md) |
| `COBIL00C` | online | `CB00`, map `COBIL00`/`COBIL0A` | core | Bill payment | [programs/COBIL00C.md](programs/COBIL00C.md) |
| `COTRN00C` | online | `CT00` | core | Transaction list; hands the selected transaction to the view | [programs/COTRN00C.md](programs/COTRN00C.md) |
| `COTRN01C` | online | `CT01` | core | Transaction view | [programs/COTRN01C.md](programs/COTRN01C.md) |
| `COTRN02C` | online | `CT02` | core | Transaction add; creates `TRAN-ID` = highest + 1 | [programs/COTRN02C.md](programs/COTRN02C.md) |
| `CORPT00C` | online | `CR00` | core | Report request; submits the TRANREPT job through TDQ `JOBS` | [programs/CORPT00C.md](programs/CORPT00C.md) |
| `CSUTLDTC` | callable | CALL | core | Date check through the LE service CEEDAYS, for COTRN02C, CORPT00C and COACTUPC | [programs/CSUTLDTC.md](programs/CSUTLDTC.md) |
| `COACTVWC` | online | `CAVW` | core | Account view: account, customer and card of one account, found through `CXACAIX` | [programs/COACTVWC.md](programs/COACTVWC.md) |
| `COACTUPC` | online | `CAUP` | core | Account update: edits and rewrites the account and its customer, with a field comparison under lock | [programs/COACTUPC.md](programs/COACTUPC.md) |
| `COCRDLIC` | online | `CCLI` | core | Card list, 7 cards a page, filter by account or card; hands the selected card to view or update | [programs/COCRDLIC.md](programs/COCRDLIC.md) |
| `COCRDSLC` | online | `CCDL` | core | Card view by card number | [programs/COCRDSLC.md](programs/COCRDSLC.md) |
| `COCRDUPC` | online | `CCUP` | core | Card update: embossed name, expiry year and month, status | [programs/COCRDUPC.md](programs/COCRDUPC.md) |
| `CBTRN02C` | batch | job `POSTTRAN` step `STEP15` | core | Daily posting | [programs/CBTRN02C.md](programs/CBTRN02C.md) |
| `CBACT04C` | batch | job `INTCALC` step `STEP15` | core | Monthly interest from `TCATBALF`; updates `ACCTDATA`, writes `SYSTRAN(+1)` | [programs/CBACT04C.md](programs/CBACT04C.md) |
| `CBTRN03C` | batch | job `TRANREPT` step `STEP10R` | core | Transaction detail report | [programs/CBTRN03C.md](programs/CBTRN03C.md) |
| `CBTRN01C` | batch | `TRANVALD` (no JCL, no schedule) | core | Daily transaction validation that writes nothing | [programs/CBTRN01C.md](programs/CBTRN01C.md) |
| `CBACT01C`, `CBACT02C`, `CBACT03C`, `CBCUS01C` | batch | jobs `READACCT`, `READCARD`, `READXREF`, `READCUST` | core | File readers; READACCT also writes three extract files | [CBACT01C](programs/CBACT01C.md), [CBACT02C](programs/CBACT02C.md), [CBACT03C](programs/CBACT03C.md), [CBCUS01C](programs/CBCUS01C.md) |
| `CBSTM03A` | batch | job `CREASTMT` step `STEP040` | core | Account statements (text and HTML) for every card in the cross-reference | [programs/CBSTM03A.md](programs/CBSTM03A.md) |
| `CBSTM03B` | batch subprogram | CALL from CBSTM03A | core | File access for the statement program (four DDs, one request area) | [programs/CBSTM03B.md](programs/CBSTM03B.md) |
| `CBEXPORT` | batch | job `CBEXPORT` step `STEP02` | core | Branch migration export: five master files into one multi-record file | [programs/CBEXPORT.md](programs/CBEXPORT.md) |
| `CBIMPORT` | batch | job `CBIMPORT` step `STEP01` | core | Branch migration import: the export file back into five sequential files and an error file | [programs/CBIMPORT.md](programs/CBIMPORT.md) |
| `COBSWAIT` | batch | job `WAITSTEP` step `WAIT` | core | Waits for the interval on its SYSIN card | [programs/COBSWAIT.md](programs/COBSWAIT.md) |
| `MVSWAIT` | assembler subroutine (shim locally) | CALL from COBSWAIT | core | `STIMER WAIT` for a binary interval in hundredths of a second | [programs/MVSWAIT.md](programs/MVSWAIT.md) |
| `COBDATFT` | assembler subroutine (shim locally) | CALL from CBACT01C (job `READACCT`) | core | Date format conversion in the caller's parameter block | [programs/COBDATFT.md](programs/COBDATFT.md) |
| CREASTMT | JCL (IDCAMS, SORT, IEFBR14, program) | CA-7, after CLOSEFIL | core | Rebuilds the TRXFL work file, deletes the previous statements, runs CBSTM03A | - |
| CBEXPORT, CBIMPORT | JCL (IDCAMS, program) | not scheduled | core | Run the export and import programs | - |
| WAITSTEP | JCL (program) | CA-7 and Control-M, between jobs | core | Runs COBSWAIT | - |
| TXT2PDF1 | JCL (TSO REXX) | CA-7, after CREASTMT | core | Converts `STATEMNT.PS` to PDF | - |
| CLOSEFIL, OPENFIL | JCL (CEMT) | batch cycle steps 1 and 9 | core | Close and reopen the CICS files around the batch window | - |
| TRANBKP, COMBTRAN, TRANIDX | JCL (IDCAMS, SORT) | batch cycle steps 5, 6, 8 | core | Back up, rebuild and re-index `TRANSACT` | - |
| `CEE3ABD`, `CEEDAYS` | LE services (shims locally) | CALL | runtime | Abend for batch programs (CBEXPORT, CBIMPORT and CBSTM03A call it without arguments, so their abend code is not set); date conversion for CSUTLDTC | - |
| `COTRTLIC` | online, Db2 | `CTLI` | transaction type (Db2) | Transaction type list, update and delete | [programs/COTRTLIC.md](programs/COTRTLIC.md) |
| `COTRTUPC` | online, Db2 | `CTTU` | transaction type (Db2) | Transaction type maintenance (add, update, delete) | [programs/COTRTUPC.md](programs/COTRTUPC.md) |
| `COBTUPDT` | batch, Db2 | job `MNTTRDB2` | transaction type (Db2) | Batch type maintenance from `INPFILE` | [programs/COBTUPDT.md](programs/COBTUPDT.md) |
| MNTTRDB2, TRANEXTR, CREADB21 | JCL | Control-M weekly (MNTTRDB2, TRANEXTR); CREADB21 by hand | transaction type (Db2) | Maintenance run, unload to the VSAM load files, schema and seed | [MNTTRDB2](jobs/MNTTRDB2.md), [TRANEXTR](jobs/TRANEXTR.md), [CREADB21](jobs/CREADB21.md) |
| `COACCT01` | online, MQ | `CDRA` (MQ trigger) | VSAM-MQ | Account inquiry over MQ | [programs/COACCT01.md](programs/COACCT01.md) |
| `CODATE01` | online, MQ | `CDRD` (MQ trigger) | VSAM-MQ | System date inquiry over MQ | [programs/CODATE01.md](programs/CODATE01.md) |
| CRDDEMOD, CRDDEMOM | CSD | CICS resource definitions | transaction type (Db2), VSAM-MQ | Transactions, programs, Db2 entry, MQ triggers of the two modules | [CRDDEMOD-CSD](jobs/CRDDEMOD-CSD.md), [CRDDEMOM-CSD](jobs/CRDDEMOM-CSD.md) |
| `COPAUA0C` | online, IMS, MQ | `CP00` (MQ trigger) | authorization (IMS, Db2, MQ) | Authorization decision: reads requests from MQ, approves when the amount fits the available credit, records the summary and detail in IMS, replies on MQ | [programs/COPAUA0C.md](programs/COPAUA0C.md) |
| `COPAUS0C` | online, IMS | `CPVS`, main menu option 11 | authorization (IMS, Db2, MQ) | Pending authorization summary of one account, five authorizations a page; hands the selected one to the detail screen | [programs/COPAUS0C.md](programs/COPAUS0C.md) |
| `COPAUS1C` | online, IMS | `CPVD` | authorization (IMS, Db2, MQ) | Authorization detail; PF5 marks fraud or removes the mark (IMS detail and, through COPAUS2C, Db2), committed together | [programs/COPAUS1C.md](programs/COPAUS1C.md) |
| `COPAUS2C` | online, Db2 | LINK from COPAUS1C | authorization (IMS, Db2, MQ) | Inserts or updates the fraud row in `CARDDEMO.AUTHFRDS`; does not commit | [programs/COPAUS2C.md](programs/COPAUS2C.md) |
| `CBPAUP0C` | batch, IMS BMP | job `CBPAUP0J` | authorization (IMS, Db2, MQ) | Purge of expired authorization details and of summaries with no approved authorization left | [programs/CBPAUP0C.md](programs/CBPAUP0C.md) |
| `PAUDBUNL`, `PAUDBLOD`, `DBUNLDGS` | batch, IMS | jobs `UNLDPADB` (DL/I), `LOADPADB` (BMP), `UNLDGSAM` (DL/I) | authorization (IMS, Db2, MQ) | Unload of `DBPAUTP0` to sequential files, reload from them, unload to GSAM files | [PAUDBUNL](programs/PAUDBUNL.md), [PAUDBLOD](programs/PAUDBLOD.md), [DBUNLDGS](programs/DBUNLDGS.md) |
| CBPAUP0J, DBPAUTP0, LOADPADB, UNLDGSAM, UNLDPADB | JCL | CA-7 between CLOSEFIL and POSTTRAN (CBPAUP0J); the others on demand | authorization (IMS, Db2, MQ) | Purge run; HD unload utility `DFSURGU0`; reload; GSAM unload; sequential unload | [CBPAUP0J](jobs/CBPAUP0J.md), [DBPAUTP0](jobs/DBPAUTP0.md), [LOADPADB](jobs/LOADPADB.md), [UNLDGSAM](jobs/UNLDGSAM.md), [UNLDPADB](jobs/UNLDPADB.md) |
| CRDDEMO2 | CSD | CICS resource definitions | authorization (IMS, Db2, MQ) | Mapsets, the four programs, transactions `CP00`, `CPVS`, `CPVD`, Db2 entry for plan `AWS01PLN` | [CRDDEMO2-CSD](jobs/CRDDEMO2-CSD.md) |

## Data stores

Online programs name a store by its CICS file name and batch programs by DD name; the entity entries list the DD names by program where they differ.

| Store | Entity | Physical | Online name | Batch DD | Key |
| :-- | :-- | :-- | :-- | :-- | :-- |
| Accounts | ENT-ACCOUNT | `ACCTDATA.VSAM.KSDS` | `ACCTDAT` | `ACCTFILE` | `ACCT-ID` |
| Card cross-reference | ENT-CARD-XREF | `CARDXREF.VSAM.KSDS` + AIX on account | `CCXREF`, `CXACAIX` | `XREFFILE`, `CARDXREF` (CBTRN03C), `XREFFIL1` (path) | card; AIX account (non-unique) |
| Customers | ENT-CUSTOMER | `CUSTDATA.VSAM.KSDS` | `CUSTDAT` | `CUSTFILE` | `CUST-ID` |
| Cards | ENT-CARD | `CARDDATA.VSAM.KSDS` + AIX on account | `CARDDAT`, `CARDAIX` | `CARDFILE` | card; AIX account (non-unique) |
| Transactions | ENT-TRANSACTION | `TRANSACT.VSAM.KSDS` + AIX on `TRAN-PROC-TS` | `TRANSACT` | `TRANFILE`, `TRANSACT` (by program) | `TRAN-ID` |
| Interest transactions | ENT-TRANSACTION | `SYSTRAN(+1)` GDG, FB 350 | - | `TRANSACT` (CBACT04C) | - |
| Report extract | ENT-TRANSACTION | `TRANSACT.DALY(+1)`, sorted by card | - | `TRANFILE` (CBTRN03C) | - |
| Category balances | ENT-TCAT-BAL | `TCATBALF.VSAM.KSDS` | - | `TCATBALF` | account + type + category |
| Daily transactions | ENT-DAILY-TRAN | `DALYTRAN.PS` | - | `DALYTRAN` | - |
| Statement work file | ENT-STMT-TRAN | `TRXFL.SEQ`, then `TRXFL.VSAM.KSDS` | - | `TRNXFILE` | card + transaction ID (32 bytes) |
| Text statements | - (80-byte print lines) | `STATEMNT.PS` | - | `STMTFILE` | - |
| HTML statements | - (100-byte HTML lines) | `STATEMNT.HTML` | - | `HTMLFILE` | - |
| Export file | ENT-EXPORT-RECORD | `EXPORT.DATA` (KSDS) | - | `EXPFILE` | sequence number (offset 27 in the programs, `KEYS(4 28)` in IDCAMS) |
| Import outputs | ENT-CUSTOMER, ENT-ACCOUNT, ENT-CARD-XREF, ENT-TRANSACTION, ENT-CARD | `CUSTDATA.IMPORT`, `ACCTDATA.IMPORT`, `CARDXREF.IMPORT`, `TRANSACT.IMPORT`, `CARDDATA.IMPORT` | - | `CUSTOUT`, `ACCTOUT`, `XREFOUT`, `TRNXOUT`, `CARDOUT` | - |
| Import errors | ENT-IMPORT-ERROR | `IMPORT.ERRORS` | - | `ERROUT` | - |
| Daily rejects | ENT-DAILY-REJECT | `DALYREJS(+1)` GDG | - | `DALYREJS` | - |
| Transaction types, categories | ENT-TRAN-TYPE, ENT-TRAN-CATEGORY | `TRANTYPE.VSAM.KSDS`, `TRANCATG.VSAM.KSDS` | - | `TRANTYPE`, `TRANCATG` | type; type + category |
| Disclosure groups | ENT-DISC-GROUP | `DISCGRP.VSAM.KSDS` | - | `DISCGRP` | group + type + category |
| Users | ENT-USER-SECURITY | `USRSEC.VSAM.KSDS` | `USRSEC` | - | `SEC-USR-ID` |
| Report date range | ENT-DATE-PARM | `DATEPARM` or in-stream | - | `DATEPARM` | - |
| Transaction report | ENT-TRAN-REPORT | `TRANREPT(+1)`, FB 133 | - | `TRANREPT` | - |
| Account extracts | ENT-ACCT-EXTRACT | `ACCTDATA.PSCOMP`, `.ARRYPS`, `.VBPS` | - | `OUTFILE`, `ARRYFILE`, `VBRCFILE` | - |
| Report job stream | ENT-REPORT-JOB | TDQ to the JES internal reader | `JOBS` | - | - |
| Db2 transaction types | ENT-DB2-TRAN-TYPE | `CARDDEMO.TRANSACTION_TYPE` | plan `CARDDEMO` | plan `CARDDEMO` | `TR_TYPE` |
| Db2 type categories | ENT-DB2-TRAN-TYPE-CAT | `CARDDEMO.TRANSACTION_TYPE_CATEGORY` | (foreign key only) | (unload only) | type + category |
| Type maintenance input | ENT-TTYP-MAINT-INPUT | sequential, 53 bytes | - | `INPFILE` | - |
| MQ request queues | ENT-MQ-ACCT-REQUEST | triggered queues for `CDRA` and `CDRD` (names not in the repository) | MQ | - | - |
| MQ reply and error queues | ENT-MQ-ACCT-REPLY, ENT-MQ-DATE-REPLY, ENT-MQ-ERROR-MSG | `CARD.DEMO.REPLY.ACCT`, `CARD.DEMO.REPLY.DATE`, `CARD.DEMO.ERROR` | MQ | - | - |
| Pending authorizations | ENT-AUTH-SUMMARY, ENT-AUTH-DETAIL | IMS HIDAM `DBPAUTP0` (`OEM.IMS.IMSP.PAUTHDB`) + index `DBPAUTX0` (`PAUTHDBX`) | PSB `PSBPAUTB` | `DDPAUTP0`, `DDPAUTX0` (DL/I batch); none (BMP) | summary `ACCNTID` (packed account ID); detail `PAUT9CTS` (8 bytes) under its summary |
| Fraud reports | ENT-AUTH-FRAUD | Db2 table `CARDDEMO.AUTHFRDS`, index `XAUTHFRD` | plan `AWS01PLN` | - | `CARD_NUM`, `AUTH_TS` |
| Authorization requests and replies | ENT-AUTH-REQUEST, ENT-AUTH-RESPONSE | MQ queues `AWS.M2.CARDDEMO.PAUTH.REQUEST`, `.REPLY` | - | - | correlation ID |
| Authorization error log | ENT-AUTH-ERROR-LOG | CICS TD queue `CSSL` | `CSSL` | - | - |
| Database unloads | ENT-AUTH-ROOT-FILE, ENT-AUTH-CHILD-FILE, ENT-AUTH-CHILD-GSAM, ENT-AUTH-HD-UNLOAD | `PAUTDB.ROOT/CHILD.FILEO`, `PAUTDB.ROOT/CHILD.GSAM`, `IMSDATA.DBPAUTP0` | - | `OUTFIL1/2`, `INFILE1/2`, `PASFILOP`, `PADFILOP`, `DFSURGU1` | - |

## CRUD matrix (pilot units, from their specs)

R = read, RU = read for update, C = create, U = update, D = delete, B = browse.

| Program | Accounts | Card xref | Transactions | Category balances | Daily transactions | Daily rejects |
| :-- | :-- | :-- | :-- | :-- | :-- | :-- |
| `COBIL00C` | RU, U (`ACCT-CURR-BAL`) | R (by account, AIX) | B (highest ID), C | - | - | - |
| `CBTRN02C` | R, U (`ACCT-CURR-BAL`, cycle credit/debit) | R (by card) | C (file opened for output) | R, C, U | R | C |

## CRUD by program (merged from the areas)
| Program | Store | Access | Spec item | Area |
| :-- | :-- | :-- | :-- | :-- |
| COSGN00C | USRSEC | R | SE-SGN-001 | a |
| COUSR00C | USRSEC | B | SE-USRL-001, SE-USRL-002 | a |
| COUSR01C | USRSEC | C | SE-USRA-001 | a |
| COUSR02C | USRSEC | RU, U | SE-USRU-001, SE-USRU-002 | a |
| COUSR03C | USRSEC | RU, D | SE-USRD-001, SE-USRD-002 | a |
| COMEN01C | (none) | - | §9 of COMEN01C | a |
| COADM01C | (none) | - | §9 of COADM01C | a |
| COACTVWC | CXACAIX (card xref by account) | R | SE-ACVW-001 | b |
| COACTVWC | ACCTDAT | R | SE-ACVW-002 | b |
| COACTVWC | CUSTDAT | R | SE-ACVW-003 | b |
| COACTUPC | CXACAIX (card xref by account) | R | SE-ACUP-001 | b |
| COACTUPC | ACCTDAT | R; RU; U (rewrite from `ACCT-UPDATE-RECORD`) | SE-ACUP-002, SE-ACUP-004, SE-ACUP-006 | b |
| COACTUPC | CUSTDAT | R; RU; U (rewrite from `CUST-UPDATE-RECORD`) | SE-ACUP-003, SE-ACUP-005, SE-ACUP-007 | b |
| COCRDLIC | CARDDAT | B (forward and backward) | SE-CRDL-001, SE-CRDL-002 | b |
| COCRDSLC | CARDDAT | R | SE-CRDV-001 | b |
| COCRDUPC | CARDDAT | R; RU; U | SE-CRDU-001, SE-CRDU-002, SE-CRDU-003 | b |
| COTRN00C | TRANSACT | B | SE-TRNL-001, SE-TRNL-002 | c |
| COTRN01C | TRANSACT | RU (read with update lock, never rewritten) | SE-TRNV-001 | c |
| COTRN02C | CXACAIX (card xref by account) | R | SE-TRNA-003 | c |
| COTRN02C | CCXREF (card xref by card) | R | SE-TRNA-003 | c |
| COTRN02C | TRANSACT | B (highest ID), C | SE-TRNA-001, SE-TRNA-002 | c |
| CORPT00C | TDQ JOBS | C (write only) | SE-RPTS-001 | c |
| CORPT00C (through job `TRNRPT00`) | TRANSACT | R (batch, while CICS has it open) | BR-RPTS-006 | c |
| CSUTLDTC | none | - | FLOW-DTCK-001 | c |
| CBACT01C | ACCTDATA | B | BR-ACRD-001 | d1 |
| CBACT01C | ACCTDATA.PSCOMP (OUTFILE) | C | BR-ACRD-003 | d1 |
| CBACT01C | ACCTDATA.ARRYPS (ARRYFILE) | C | BR-ACRD-006 | d1 |
| CBACT01C | ACCTDATA.VBPS (VBRCFILE) | C | BR-ACRD-007 | d1 |
| CBACT02C | CARDDATA | B | BR-CDRD-001 | d1 |
| CBACT03C | CARDXREF | B | BR-XRRD-001 | d1 |
| CBCUS01C | CUSTDATA | B | BR-CURD-001 | d1 |
| CBACT04C | TCATBALF | B | BR-INTC-008 | d1 |
| CBACT04C | CARDXREF (AIX by account) | R | BR-INTC-007 | d1 |
| CBACT04C | ACCTDATA | RU | BR-INTC-006 | d1 |
| CBACT04C | DISCGRP | R | BR-INTC-002 | d1 |
| CBACT04C | SYSTRAN | C | BR-INTC-004 | d1 |
| CBTRN01C | DALYTRAN | B | BR-TVAL-001 | d1 |
| CBTRN01C | CARDXREF | R | BR-TVAL-002 | d1 |
| CBTRN01C | ACCTDATA | R | BR-TVAL-003 | d1 |
| CBTRN01C | CUSTDATA, CARDDATA, TRANSACT | opened and closed only | DEF-TVAL-003 | d1 |
| CBTRN03C | TRANSACT.DALY (sorted copy of TRANSACT) | B | BR-TRPT-002 | d1 |
| CBTRN03C | CARDXREF | R | BR-TRPT-007 | d1 |
| CBTRN03C | TRANTYPE | R | BR-TRPT-007 | d1 |
| CBTRN03C | TRANCATG | R | BR-TRPT-007 | d1 |
| CBTRN03C | DATEPARM | B (first record) | BR-TRPT-001 | d1 |
| CBTRN03C | TRANREPT | C | SE-TRPT-001 | d1 |
| COTRTLIC | CARDDEMO.TRANSACTION_TYPE (ENT-DB2-TRAN-TYPE) | B, R, U, D | SE-TTLS-002 .. SE-TTLS-006 | f |
| COTRTUPC | CARDDEMO.TRANSACTION_TYPE (ENT-DB2-TRAN-TYPE) | R, U, C, D | SE-TTUP-001 .. SE-TTUP-004 | f |
| COBTUPDT | CARDDEMO.TRANSACTION_TYPE (ENT-DB2-TRAN-TYPE) | C, U, D | SE-TTBU-001 .. SE-TTBU-003 | f |
| COBTUPDT | INPFILE (ENT-TTYP-MAINT-INPUT) | B | FLOW-TTBU-002 | f |
| TRANEXTR (DSNTIAUL) | CARDDEMO.TRANSACTION_TYPE, CARDDEMO.TRANSACTION_TYPE_CATEGORY | B | SE-JTREX-004, SE-JTREX-005 | f |
| TRANEXTR | `TRANTYPE.PS`, `TRANCATG.PS` (ENT-TRAN-TYPE, ENT-TRAN-CATEGORY) | B, D, C | SE-JTREX-001 .. SE-JTREX-005 | f |
| CREADB21 (DSNTIAD, DSNTEP4) | both tables (ENT-DB2-TRAN-TYPE, ENT-DB2-TRAN-TYPE-CAT) | C (schema and seed rows) | SE-JCRDB-002 .. SE-JCRDB-004 | f |
| COACCT01 | ACCTDAT (ENT-ACCOUNT) | R | SE-MQAC-002 | f |
| COACCT01 | input queue (ENT-MQ-ACCT-REQUEST) | D (destructive get) | SE-MQAC-001 | f |
| COACCT01 | `CARD.DEMO.REPLY.ACCT` (ENT-MQ-ACCT-REPLY), `CARD.DEMO.ERROR` (ENT-MQ-ERROR-MSG) | C | SE-MQAC-003, SE-MQAC-004 | f |
| CODATE01 | input queue | D (destructive get) | SE-MQDT-001 | f |
| CODATE01 | `CARD.DEMO.REPLY.DATE` (ENT-MQ-DATE-REPLY), `CARD.DEMO.ERROR` (ENT-MQ-ERROR-MSG) | C | SE-MQDT-002, SE-MQDT-003 | f |
| CBSTM03A (through CBSTM03B) | TRXFL (ENT-STMT-TRAN) | B (sequential, whole file into a table) | FLOW-STMT-003, SE-STIO-001 | d2 |
| CBSTM03A (through CBSTM03B) | CARDXREF (ENT-CARD-XREF) | B (sequential) | FLOW-STMT-004, SE-STIO-002 | d2 |
| CBSTM03A (through CBSTM03B) | CUSTDATA (ENT-CUSTOMER) | R (by `CUST-ID`) | BR-STMT-004, SE-STIO-003 | d2 |
| CBSTM03A (through CBSTM03B) | ACCTDATA (ENT-ACCOUNT) | R (by `ACCT-ID`) | BR-STMT-004, SE-STIO-004 | d2 |
| CBSTM03A | STATEMNT.PS (text statements) | C | SE-STMT-001 | d2 |
| CBSTM03A | STATEMNT.HTML | C | SE-STMT-002 | d2 |
| CREASTMT DELDEF01, STEP010, STEP020 | TRANSACT; TRXFL.SEQ; TRXFL.VSAM.KSDS | TRANSACT B (sort input); TRXFL.SEQ D, C, then R; TRXFL KSDS D, C | CBSTM03A §3 (Earlier steps of the same job) | d2 |
| CREASTMT STEP030 | STATEMNT.PS, STATEMNT.HTML | D | CBSTM03A §3 (Earlier steps of the same job) | d2 |
| TXT2PDF1 | STATEMNT.PS; STATEMNT.PS.PDF | R; C | CBSTM03A §4 (Downstream) | d2 |
| CBEXPORT | CUSTDATA, ACCTDATA, CARDXREF, TRANSACT, CARDDATA | B (sequential, key order) | SE-EXPT-002 | d2 |
| CBEXPORT job STEP01 | EXPORT.DATA | D, then define | DEF-EXPT-002 | d2 |
| CBEXPORT | EXPORT.DATA (ENT-EXPORT-RECORD) | C | SE-EXPT-001 | d2 |
| CBIMPORT | EXPORT.DATA (ENT-EXPORT-RECORD) | B (sequential, key order) | FLOW-IMPT-003 | d2 |
| CBIMPORT | CUSTDATA.IMPORT, ACCTDATA.IMPORT, CARDXREF.IMPORT, TRANSACT.IMPORT, CARDDATA.IMPORT | C | SE-IMPT-002..006 | d2 |
| CBIMPORT | IMPORT.ERRORS (ENT-IMPORT-ERROR) | C | SE-IMPT-007 | d2 |
| COBSWAIT | SYSIN control card (`WAITSTEP.jcl` in-stream) | R | BR-WAIT-001 | d2 |
| COPAUA0C | MQ request queue (ENT-AUTH-REQUEST) | D (destructive get) | SE-AUTH-001 | e |
| COPAUA0C | MQ reply-to queue (ENT-AUTH-RESPONSE) | C | SE-AUTH-002 | e |
| COPAUA0C | `CCXREF` (ENT-CARD-XREF) | R | BR-AUTH-003 | e |
| COPAUA0C | `ACCTDAT` (ENT-ACCOUNT) | R | BR-AUTH-004 | e |
| COPAUA0C | `CUSTDAT` (ENT-CUSTOMER) | R | ERR-AUTH-006 | e |
| COPAUA0C | `DBPAUTP0` `PAUTSUM0` (ENT-AUTH-SUMMARY) | RU, C, U | BR-AUTH-009, SE-AUTH-003 | e |
| COPAUA0C | `DBPAUTP0` `PAUTDTL1` (ENT-AUTH-DETAIL) | C | BR-AUTH-010, SE-AUTH-004 | e |
| COPAUA0C | TD queue `CSSL` (ENT-AUTH-ERROR-LOG) | C | SE-AUTH-006 | e |
| COPAUS0C | `CXACAIX` (ENT-CARD-XREF) | R | BR-AUSM-001 | e |
| COPAUS0C | `ACCTDAT` (ENT-ACCOUNT) | R | BR-AUSM-001 | e |
| COPAUS0C | `CUSTDAT` (ENT-CUSTOMER) | R | BR-AUSM-001 | e |
| COPAUS0C | `DBPAUTP0` `PAUTSUM0` | R | BR-AUSM-002 | e |
| COPAUS0C | `DBPAUTP0` `PAUTDTL1` | B | BR-AUSM-003..006 | e |
| COPAUS1C | `DBPAUTP0` `PAUTSUM0` | R | BR-AUDT-001 | e |
| COPAUS1C | `DBPAUTP0` `PAUTDTL1` | R, B, U (`PA-AUTH-FRAUD`, `PA-FRAUD-RPT-DATE`) | BR-AUDT-001, BR-AUDT-006, SE-AUDT-001 | e |
| COPAUS2C | Db2 `CARDDEMO.AUTHFRDS` (ENT-AUTH-FRAUD) | C, U | SE-AUFR-001, SE-AUFR-002 | e |
| CBPAUP0C | `DBPAUTP0` `PAUTSUM0` | B, D | FLOW-AUPG-002, SE-AUPG-002 | e |
| CBPAUP0C | `DBPAUTP0` `PAUTDTL1` | B, D | FLOW-AUPG-002, SE-AUPG-001 | e |
| PAUDBUNL | `DBPAUTP0` `PAUTSUM0`, `PAUTDTL1` | B | FLOW-PDUL-001 | e |
| PAUDBUNL | `PAUTDB.ROOT.FILEO` (ENT-AUTH-ROOT-FILE) | C | SE-PDUL-001 | e |
| PAUDBUNL | `PAUTDB.CHILD.FILEO` (ENT-AUTH-CHILD-FILE) | C | SE-PDUL-002 | e |
| PAUDBLOD | `PAUTDB.ROOT.FILEO`, `PAUTDB.CHILD.FILEO` | R | FLOW-PDLD-001 | e |
| PAUDBLOD | `DBPAUTP0` `PAUTSUM0` | C, R | SE-PDLD-001, BR-PDLD-002 | e |
| PAUDBLOD | `DBPAUTP0` `PAUTDTL1` | C | SE-PDLD-002 | e |
| DBUNLDGS | `DBPAUTP0` `PAUTSUM0`, `PAUTDTL1` | B | FLOW-DBUL-001 | e |
| DBUNLDGS | `PAUTDB.ROOT.GSAM` (ENT-AUTH-ROOT-FILE) | C | SE-DBUL-001 | e |
| DBUNLDGS | `PAUTDB.CHILD.GSAM` (ENT-AUTH-CHILD-GSAM) | C | SE-DBUL-002 | e |
| DFSURGU0 (job DBPAUTP0) | `DBPAUTP0` `PAUTSUM0`, `PAUTDTL1` | B | SE-JDBPA-002 | e |
| DFSURGU0 (job DBPAUTP0) | `IMSDATA.DBPAUTP0` (ENT-AUTH-HD-UNLOAD) | D (previous, by IEFBR14), C | SE-JDBPA-001, SE-JDBPA-002 | e |
| IEFBR14 (job UNLDPADB, step `STEP0`) | `PAUTDB.ROOT.FILEO`, `PAUTDB.CHILD.FILEO` | D (previous) | SE-JULPA-001 | e |

No account or card program accesses `CARDAIX` or `CCXREF` at run time: card view reads `CARDAIX` only in a paragraph that is never performed (SE-CRDV-002), and the edits of account update read no store (BR-ACUP-104).

The interaction diagrams and edges are in [interactions.md](interactions.md).

## Cross-component invariants

"Proposed as" names the area proposal an invariant came from; the `areas/` file keeps the original wording.

| ID | Invariant | Components | Evidence | Proposed as |
| :-- | :-- | :-- | :-- | :-- |
| INV-001 | `TRAN-ID` is unique in `TRANSACT`. Online writers compute "highest + 1"; POSTTRAN takes IDs from the daily file; INTCALC builds IDs from a date PARM and a counter. Nothing coordinates them; a collision makes online writes fail and POSTTRAN abend. Two transaction adds at the same time collide with `Tran ID already exist...`. | COBIL00C, COTRN02C, CBTRN02C, CBACT04C | `COBIL00C.md` BR-BILL-004, DEF-BILL-003; `CBTRN02C.md` ERR-POST-004; COTRN02C BR-TRNA-003, DEF-TRNA-003; `docs/runbooks/batch-cycle-restart.md:37-39` | - |
| INV-002 | Online and batch never run on the same files at the same time: CLOSEFIL closes `TRANSACT`, `CCXREF`, `ACCTDAT`, `CXACAIX` and `USRSEC` before POSTTRAN, and OPENFIL reopens them. Sign-on and user administration are therefore unavailable during the batch cycle. | CICS programs, batch cycle, COSGN00C, COUSR00C-03C | `app/jcl/CLOSEFIL.jcl:26-30`; `app/jcl/OPENFIL.jcl:30`; `docs/runbooks/batch-cycle-restart.md:13`; ERR-SGN-001 | PINV-a-signon-admin-03 |
| INV-003 | `TRANSACT` is empty when POSTTRAN starts (opened for output). | TRANBKP, CBTRN02C | `CBTRN02C.md` §3 | - |
| INV-004 | Posting keeps three stores in step for each transaction: category balance, account, transaction master. Bill payment updates only the account balance and the transaction master. Transaction add changes only the transaction master; its transactions never reach the account or the category balances. | CBTRN02C, COBIL00C, COTRN02C | `CBTRN02C.md` §9, `COBIL00C.md` DEF-BILL-010; COTRN02C BR-TRNA-007, DEF-TRNA-001 | PINV-c-05 (transaction add) |
| INV-005 | Amount sign: POSTTRAN treats positive amounts as increasing the balance; bill payment stores a positive amount for a payment that decreases it. | CBTRN02C, COBIL00C | `COBIL00C.md` DEF-BILL-010 | - |
| INV-006 | The online session identity is `CDEMO-USER-ID` and `CDEMO-USER-TYPE` in the commarea. Only sign-on is meant to set them, and the menus are meant to be reached only after sign-on. It does **not** hold today, and no program verifies the identity (no CICS sign-on, every transaction `RESSEC(NO)`). The five account and card programs treat a start without a commarea (transaction code typed at a terminal) as a first run with a blank user ID; only the card list also sets type `U` there. On PF3 each transfers to the main menu because the commarea names no caller, and the menu accepts it, so `CAVW`, `CAUP`, `CCLI`, `CCDL` or `CCUP` followed by PF3 opens the main menu without a password. They overwrite the user type with `U` on every transfer of control and never test the user or the user type before they show or change data. The Db2 transaction type programs set type `A` when started without a commarea, so `CTLI` or `CTTU` followed by PF3 opens the admin menu. The pending authorization screens open the same path without setting any type: `CPVS` started without a commarea initialises an empty one and shows its screen, `CPVD` started that way transfers to `CPVS`, and PF3 on `CPVS` transfers to the main menu with the user ID and the user type still blank (DEF-AUSM-012, DEF-AUDT-009). The main menu refuses an admin-only option only when the type is `U`, so a blank type would pass even an option marked `A`; today all 11 main-menu options are marked `U`, and a blank type reaches every one of them. Neither screen tests the user or the user type before it shows or changes data (DEF-AUSM-007, DEF-AUDT-006). Any authorisation in the target must be added, not preserved. | COSGN00C, COMEN01C, COADM01C, COACTVWC, COACTUPC, COCRDLIC, COCRDSLC, COCRDUPC, COTRTLIC, COTRTUPC, COPAUS0C, COPAUS1C, every online program | `app/cbl/COSGN00C.cbl:224-240`; `app/csd/CARDDEMO.CSD:385`; `app/cbl/COMEN01C.cbl:82-91, 136-143`; `app/cpy/COMEN02Y.cpy:29-90`; `app/cbl/COCRDLIC.cbl:315-325, 384-405`; `app/cbl/COACTVWC.cbl:282-286, 324-352`; `app/cbl/COACTUPC.cbl:880-886, 927-959`; `app/cbl/COCRDSLC.cbl:268-272, 305-334`; `app/cbl/COCRDUPC.cbl:435-476`; `app/app-transaction-type-db2/cbl/COTRTLIC.cbl:515-525`; `app/app-authorization-ims-db2-mq/cbl/COPAUS0C.cbl:190-198, 235-237, 665-677`; `app/app-authorization-ims-db2-mq/cbl/COPAUS1C.cbl:165-169`; DEF-SGN-006, DEF-SGN-012, DEF-MENU-003, DEF-ADMN-001, DEF-ACVW-009, DEF-ACUP-001, DEF-CRDL-003, DEF-CRDV-002, DEF-CRDU-006, DEF-TTLS-017, DEF-TTUP-017, DEF-AUSM-007, DEF-AUSM-012, DEF-AUDT-006, DEF-AUDT-009 | PINV-a-signon-admin-01, PINV-f-06, PINV-b-accounts-cards-01, PINV-b-accounts-cards-22 |
| INV-007 | A user can sign on only if the stored `SEC-USR-ID` and `SEC-USR-PWD` contain no lower-case letters, because sign-on upper-cases the typed values; user add and update do not enforce it. | COSGN00C, COUSR01C, COUSR02C | `app/cbl/COSGN00C.cbl:132-136`; `app/cbl/COUSR01C.cbl:154-158`; `app/cbl/COUSR02C.cbl:227-230`; DEF-SGN-011 | PINV-a-signon-admin-02 |
| INV-008 | Programs that hand data over through a commarea extension keep the same extension layout: the transaction list, view and add (and bill payment) at 161-218, with the selected transaction ID at 203-218; the user list, update and delete at 161-194, with the selected user at 187-194. Changing one program's extension breaks the hand-over. | COTRN00C, COTRN01C, COTRN02C, COBIL00C, COUSR00C, COUSR02C, COUSR03C | `app/cbl/COTRN00C.cbl:62-70, 186-195`; `app/cbl/COTRN01C.cbl:53-61, 99-109`; `app/cbl/COTRN02C.cbl:72-80`; `app/cbl/COUSR00C.cbl:67-75`; `app/cbl/COUSR02C.cbl:50-58`; `app/cbl/COUSR03C.cbl:50-58` | PINV-c-01, PINV-a-signon-admin-04 |
| INV-009 | At least one user of type `A` must exist for the admin menu to be reachable; nothing enforces it, and only a batch reload of `USRSEC` restores access. | COSGN00C, COUSR02C, COUSR03C | `app/cbl/COSGN00C.cbl:230-240`; DEF-USRU-006, DEF-USRD-004 | PINV-a-signon-admin-05 |
| INV-010 | The first 10 characters of `TRAN-PROC-TS` are a `YYYY-MM-DD` date for every writer (POSTTRAN, INTCALC, bill payment, transaction add); the report job selects on them as text, and the list and view show them. A writer that stores another format drops out of reports. | CBTRN02C, CBACT04C, COBIL00C, COTRN02C, CORPT00C, CBTRN03C | `app/cbl/COTRN02C.cbl:369-381, 465`; `app/cbl/CBACT04C.cbl:499-500`; `app/proc/TRANREPT.prc:40-46`; `app/cbl/CBTRN03C.cbl:173-174`; ENT-TRANSACTION timestamp layouts | PINV-c-02 |
| INV-011 | The job stream written by the report request matches the procedure TRANREPT: step names `STEP05R` and `STEP10R`, DD names `SYMNAMES` and `DATEPARM`, symbol names and positions `TRAN-CARD-NUM,263,16,ZD` and `TRAN-PROC-DT,305,10,CH`, and the date pair `start end` read by `CBTRN03C`. Nothing checks this at run time. | CORPT00C, TRANREPT (CBTRN03C) | `app/cbl/CORPT00C.cbl:93-125`; `app/proc/TRANREPT.prc:35-72`; `app/cbl/CBTRN03C.cbl:122-125` | PINV-c-03 |
| INV-012 | The SORT date range of TRANREPT equals its DATEPARM range; a wider SORT range truncates the report silently. The report request writes both from the same two dates; the stored TRANREPT JCL sets them separately. | TRANREPT JCL, CORPT00C, CBTRN03C | CBTRN03C DEF-TRPT-001, DEF-TRPT-002 | PINV-d1-04 |
| INV-013 | Callers of the date check rely on severity `0000` meaning "valid" and on message number `2513` (date before 1582-10-15) being distinguishable; two of them treat 2513 as valid. | CSUTLDTC, COTRN02C, CORPT00C, COACTUPC | `app/cbl/COTRN02C.cbl:397-427`; `app/cbl/CORPT00C.cbl:396-426`; `app/cpy/CSUTLDPY.cpy:298-316` | PINV-c-04 |
| INV-014 | Every account that has a category balance in `TCATBALF` has an account record and at least one card in `CARDXREF`; otherwise INTCALC abends. | INTCALC, POSTTRAN | CBACT04C ERR-INTC-003, ERR-INTC-004 | PINV-d1-01 |
| INV-015 | `DISCGRP` holds a `DEFAULT` record for every transaction type and category that occurs in `TCATBALF`; otherwise INTCALC abends on the first account without a specific rate. | INTCALC, DISCGRP load | CBACT04C ERR-INTC-005 | PINV-d1-02 |
| INV-016 | Every card, transaction type and category in the transaction master exists in `CARDXREF`, `TRANTYPE` and `TRANCATG`; otherwise TRANREPT abends. Transaction add and the type maintenance programs do not check this. | TRANREPT, POSTTRAN, COTRN02C, COTRTLIC, COTRTUPC, COBTUPDT | CBTRN03C BR-TRPT-007, ERR-TRPT-004 | PINV-d1-03 |
| INV-017 | A transaction type that has categories cannot be deleted by any path (online list, online update, batch). | COTRTLIC, COTRTUPC, COBTUPDT, Db2 | `app/app-transaction-type-db2/ctl/DB2CREAT.ctl:96-99`; BR-TTLS-009, BR-TTUP-004, BR-TTBU-004 | PINV-f-01 |
| INV-018 | Every online type change is committed at once (SYNCPOINT after each successful statement); a batch run is one unit of work, except that a -911 rolls back the changes made so far and processing continues in a new one. | COTRTLIC, COTRTUPC, COBTUPDT | SE-TTLS-005, SE-TTUP-002, SE-TTBU-004 | PINV-f-02 |
| INV-019 | The Db2 tables and the VSAM files ENT-TRAN-TYPE and ENT-TRAN-CATEGORY hold the same codes only after TRANEXTR and then the base TRANTYPE and TRANCATG jobs have run; nothing enforces it. It does **not** hold today for the descriptions (the seed text differs). | TRANEXTR, TRANTYPE, TRANCATG, every reader of the VSAM files | DEF-JTREX-001, DEF-JCRDB-005 | PINV-f-03 |
| INV-020 | Online-written type codes are two digits from `01` to `99`; batch-written codes may be any two characters, so the invariant holds only if the MNTTRDB2 input is clean. | COTRTUPC, COTRTLIC, COBTUPDT | BR-TTUP-001, DEF-TTBU-003 | PINV-f-04 |
| INV-021 | Each MQ request produces at most one reply, on a fixed reply queue, with the request's MsgId and CorrelId; a request whose processing fails produces an error record and no reply. | COACCT01, CODATE01 | BR-MQAC-005, DEF-MQAC-007 | PINV-f-05 |
| INV-022 | The statement request area is declared twice, in CBSTM03A working storage and CBSTM03B linkage, and the two declarations stay byte-identical (1040 bytes, ENT-STMT-REQUEST). | CBSTM03A, CBSTM03B | `app/cbl/CBSTM03A.CBL:71-83`, `app/cbl/CBSTM03B.CBL:100-112` | PINV-d2-01 |
| INV-023 | Export and import agree on the 500-byte layout (CVEXPORT, copied by both) and on the key: each program declares its own FD overlay with a 4-byte key at offset 27, the bytes of `EXPORT-SEQUENCE-NUM`. The IDCAMS definition `KEYS(4 28)` does not match. | CBEXPORT, CBIMPORT, CBEXPORT job | `app/cpy/CVEXPORT.cpy:9-16`, `app/cbl/CBEXPORT.cbl:65-69, 89-101`, `app/cbl/CBIMPORT.cbl:37-41, 76-84, 118`, `app/jcl/CBEXPORT.jcl:32`; DEF-EXPT-002, DEF-IMPT-004 | PINV-d2-02 |
| INV-024 | A CBEXPORT run followed by CBIMPORT reproduces the five input files byte for byte (observed on the sample day after POSTTRAN), provided no field holds a value its export encoding cannot carry. | CBEXPORT, CBIMPORT | CBEXPORT BR-EXPT-004..008, CBIMPORT BR-IMPT-005..009, `tests/integration/test_export_import.py:11-36` | PINV-d2-03 |
| INV-025 | TRXFL is rebuilt from `TRANSACT` (CREASTMT DELDEF01, STEP010-020) before CBSTM03A runs; a stale TRXFL gives statements that do not match the transaction master. | CREASTMT, CBSTM03A | `app/jcl/CREASTMT.JCL:22-62` | PINV-d2-04 |
| INV-026 | Between the account and card programs only the 160-byte base commarea travels; a program's extension (paging state, old and new card values) lives only in the area it returns to itself, so every return to the card list starts on page 1 and a call into card update starts a new update. | COACTVWC, COCRDLIC, COCRDSLC, COCRDUPC | `app/cbl/COCRDLIC.cbl:402-405, 538-541, 566-569`; `app/cbl/COCRDUPC.cbl:473-476` | PINV-b-accounts-cards-02 |
| INV-027 | A program that sends the user to card view or card update for a selected card sets `CDEMO-FROM-PROGRAM` = `COCRDLIC`, `CDEMO-LAST-MAPSET` = `COCRDLI`, context 0 and the selected `CDEMO-ACCT-ID` and `CDEMO-CARD-NUM`. Card view needs all of them; card update needs `CDEMO-FROM-PROGRAM` and the two keys. Otherwise the target shows an empty search screen. | COCRDLIC, COCRDSLC, COCRDUPC | `app/cbl/COCRDLIC.cbl:517-534`; `app/cbl/COCRDSLC.cbl:339-348, 505-512`; `app/cbl/COCRDUPC.cbl:482-491` | PINV-b-accounts-cards-03 |
| INV-028 | Callers pass `CDEMO-PGM-CONTEXT` = 0 on XCTL; card view, account view and card update set 1 before their own RETURN TRANSID, and card view ends the conversation on any other value. The card list keeps 0 throughout and recognises its re-entry by `CDEMO-FROM-PROGRAM`. | COMEN01C, COACTVWC, COCRDLIC, COCRDSLC, COCRDUPC | `app/cbl/COMEN01C.cbl:183`; `app/cbl/COCRDLIC.cbl:523, 604-608`; `app/cbl/COCRDSLC.cbl:373-380` | PINV-b-accounts-cards-04 |
| INV-029 | The card master (`CARD-NUM`) and the cross-reference (`XREF-CARD-NUM`) hold the same set of card numbers, so a card the list shows (from the card master) is also known to the programs that look cards up through the cross-reference. Nothing enforces it. | CARDDAT, CCXREF, COCRDLIC, COCRDSLC, COCRDUPC, COACTVWC | `app/data/ASCII/carddata.txt:1-50`; `app/data/ASCII/cardxref.txt:1-50` | PINV-b-accounts-cards-05 |
| INV-030 | For every card, `CARD-ACCT-ID` equals `XREF-ACCT-ID` of the cross-reference record with the same card number. Card update violates it on the menu path (DEF-CRDU-001, Q-CRDU-001); the target must keep the account read-only in card update or change both stores together. | COCRDUPC, COCRDLIC, COACTVWC, CBACT02C, CBEXPORT, CBIMPORT | `app/cbl/COCRDUPC.cbl:1463`; `app/cpy/CVACT03Y.cpy:4-8` | PINV-b-accounts-cards-06 |
| INV-031 | An account can be shown or updated online only if it has exactly one cross-reference record; the customer shown and updated is that record's customer. A load or card maintenance that gives an account zero or several cards makes it unviewable in account view and not updatable in account update (DEF-ACVW-003, DEF-ACVW-004, DEF-ACUP-012). INTCALC has the same single-card assumption (CBACT04C DEF-INTC-007), and so does the pending authorization summary (COPAUS0C DEF-AUSM-010). | COACTVWC, COACTUPC, COPAUS0C, CXACAIX, data loads | `app/cbl/COACTVWC.cbl:691-699, 727-768`; `app/cbl/COACTUPC.cbl:3654-3696` | PINV-b-accounts-cards-07 |
| INV-032 | Every `XREF-CUST-ID` exists in `CUSTDAT` and every `XREF-ACCT-ID` in `ACCTDAT`; otherwise account view shows a partial screen with an error (DEF-ACVW-002). | COACTVWC, CXACAIX, ACCTDAT, CUSTDAT | `app/cbl/COACTVWC.cbl:704-715, 789-807, 839-857` | PINV-b-accounts-cards-08 |
| INV-033 | `CDEMO-ACCT-ID` holds the last account accepted by account view or the last card-list filter or selection (0 after invalid input); the card list shows it in its account filter only on its own re-entries (not when entered from the menu, and it clears the whole commarea on a PF3 return from card view or card update, `app/cbl/COCRDLIC.cbl:458-482`), and card view and card update take it only when entered from the list. `CDEMO-CUST-ID` and `CDEMO-CARD-NUM` can belong to an earlier account after a failed lookup in account view (DEF-ACVW-018); after a successful fetch in account update the commarea names one consistent account, customer and card. A replacement of any of these screens must keep these meanings or replace them together. | COACTVWC, COACTUPC, COMEN01C, COCRDLIC, COCRDSLC, COCRDUPC | `app/cbl/COACTUPC.cbl:3805-3811`; `app/cbl/COACTVWC.cbl:660, 675, 678, 739-740`; `app/cbl/COCRDLIC.cbl:839-853`; `app/cbl/COCRDSLC.cbl:339-342`; `app/cbl/COCRDUPC.cbl:482-490` | PINV-b-accounts-cards-09 |
| INV-034 | `CARD-NUM` is the unique primary key of the card master, and the card list's paging (start at or after a key, read ahead one card for "next page", read backwards for the previous page) depends on it being unique and ordered. | CARDDAT, COCRDLIC | `app/jcl/CARDFILE.jcl:50-58`; `app/cbl/COCRDLIC.cbl:1129-1136, 1191-1214` | PINV-b-accounts-cards-10 |
| INV-035 | While the CARDFILE job reloads the card master, `CARDDAT` and `CARDAIX` are closed in CICS, and card view, the card list and card update report a file error in that window. `CARDDAT` is not among the files the batch cycle closes (INV-002). | CARDFILE job, COCRDLIC, COCRDSLC, COCRDUPC | `app/jcl/CARDFILE.jcl:26-27, 122-123`; `app/cbl/COCRDSLC.cbl:762-771` | PINV-b-accounts-cards-11 |
| INV-036 | A card update changes only the fields the user can edit (name, expiry year and month, status); `CARD-CVV-CD` and the record FILLER keep their stored values. It does **not** hold today (DEF-CRDU-002, DEF-CRDU-003, Q-CRDU-002). | COCRDUPC | `app/cbl/COCRDUPC.cbl:314-321, 1461-1465` | PINV-b-accounts-cards-12 |
| INV-037 | A card is rewritten only if CVV, upper-cased name, expiry year, month and day, and status read again with a lock still equal the values read when the details were shown (optimistic concurrency, BR-CRDU-005). | COCRDUPC | `app/cbl/COCRDUPC.cbl:1499-1517` | PINV-b-accounts-cards-13 |
| INV-038 | Customer and account data loaded by batch satisfies the edits of the online account update (state list, state and ZIP prefix, area-code list, FICO 300-850, SSN part 1 rule, letters-only names and city); otherwise the account cannot be updated online at all. The sample data violates it for all 50 customers (DEF-ACUP-112). | CUSTFILE load, CBIMPORT, COACTUPC | `app/cbl/COACTUPC.cbl:1470-1675`; `app/data/ASCII/custdata.txt:1-50` | PINV-b-accounts-cards-14 |
| INV-039 | Every program that writes the account record uses the 300-byte `CVACT01Y` layout, so `ACCT-ADDR-ZIP` (103) and `ACCT-GROUP-ID` (113) keep their positions. It does **not** hold today: account update writes the group ID at 103 and FILLER content it does not set from 113, which the `CVACT01Y` readers (account view, account update itself, INTCALC, which falls back to the `DEFAULT` group when the group is not found) then take as the group ID (DEF-ACUP-002, Q-ENT-001, Q-ACUP-001). | COACTUPC, CBACT04C, CBTRN02C, COBIL00C, COACTVWC, ACCTDAT | `app/cbl/COACTUPC.cbl:418-433, 4002, 4066-4068`; `app/cpy/CVACT01Y.cpy:4-17`; `app/cbl/CBACT04C.cbl:210, 438` | PINV-b-accounts-cards-15 |
| INV-040 | Callers of CSUTLDTC pass date and format fields of the length CSUTLDTC declares (10 bytes each, result 80), or tell it the real length; the callers that use `CSUTLDWY` and `CSUTLDPY` pass 8-character fields (DEF-ACUP-105, CSUTLDTC DEF-DTCK-003). | COACTUPC (through `CSUTLDPY` and `CSUTLDWY`), CSUTLDTC, COTRN02C, CORPT00C | `app/cpy/CSUTLDPY.cpy:293-296`; `app/cpy/CSUTLDWY.cpy:4-36`; `app/cbl/CSUTLDTC.cbl:84-88` | PINV-b-accounts-cards-16 |
| INV-041 | The state code list and the state and ZIP prefix list in `CSLKPCDY` agree: every state in the prefix list is in the state list. It does **not** hold today: `AA`, `AE`, `AP`, `FM`, `MH` and `PW` are only in the prefix list. | COACTUPC (users of `CSLKPCDY`) | `app/cpy/CSLKPCDY.cpy:1013-1069, 1073-1313` | PINV-b-accounts-cards-17 |
| INV-042 | An online account update does not overwrite a balance, limit, cycle total, status or date changed by POSTTRAN, INTCALC or a bill payment after the fetch. Account update enforces it by comparing those account fields under lock before the rewrite (BR-ACUP-005); a target must keep an equivalent check (version or field comparison). | COACTUPC, COBIL00C, CBTRN02C, CBACT04C | `app/cbl/COACTUPC.cbl:4115-4145`; `app/cbl/COBIL00C.cbl:379`; `app/cbl/CBTRN02C.cbl:555`; `app/cbl/CBACT04C.cbl:357` | PINV-b-accounts-cards-18 |
| INV-043 | The account rewrite and the customer rewrite of one save happen together or not at all. It does **not** hold today: the account is rewritten first and both files are `RECOVERY(NONE)`, so a failed customer rewrite leaves the account changed (SE-ACUP-007, DEF-ACUP-005). | COACTUPC, ACCTDAT, CUSTDAT, CSD file definitions | `app/cbl/COACTUPC.cbl:4065-4103`; `app/csd/CARDDEMO.CSD:9, 59` | PINV-b-accounts-cards-19 |
| INV-044 | Writers of `CUST-ADDR-ZIP` keep all 10 characters. Account update keeps only the first 5 (DEF-ACUP-003). | COACTUPC, CBIMPORT, ENT-CUSTOMER | `app/cbl/COACTUPC.cbl:4025`; `app/cpy-bms/COACTUP.CPY:246` | PINV-b-accounts-cards-20 |
| INV-045 | Dates in `ACCTDAT` and `CUSTDAT` are stored as `YYYY-MM-DD`; account update rebuilds them in that form from year, month and day. | COACTUPC, ENT-ACCOUNT, ENT-CUSTOMER | `app/cbl/COACTUPC.cbl:3976-4000, 4047-4052` | PINV-b-accounts-cards-21 |
| INV-046 | A pending authorization detail sits under the summary of the account that owns its card (card → `CCXREF` → account ID → summary). Held as-is for placement: an unknown card writes nothing to IMS (`app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl:463-465`), and for a missing account the detail still goes under the card's own `XREF-ACCT-ID` (`app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl:619-623, 911-919`). The exceptions are in the decision and the totals: a stale summary flag or account record from an earlier request (DEF-AUTH-002, DEF-AUTH-003). | COPAUA0C, COPAUS0C, COPAUS1C | COPAUA0C BR-AUTH-003, DEF-AUTH-002, DEF-AUTH-003 | PINV-e-authorization-01 |
| INV-047 | A summary's approved and declined counts and amounts equal the totals of the details under it, and available credit is derived from them. Only COPAUA0C maintains the totals; the purge deletes details and keeps the stored totals, the reload merges instead of restoring (existing summaries keep their current totals, purged details come back), and the screens show the stored totals. Not held as-is. | COPAUA0C, CBPAUP0C, PAUDBLOD, COPAUS0C | COPAUA0C BR-AUTH-009, DEF-AUTH-005; CBPAUP0C DEF-AUPG-003; LOADPADB DEF-JLDPA-002; COPAUS0C BR-AUSM-002 | PINV-e-authorization-02 |
| INV-048 | The fraud flag on a detail and its `AUTHFRDS` row change in one CICS unit of work: COPAUS1C replaces the detail only after COPAUS2C reports success, then syncpoints, and rolls back otherwise. A purge later deletes the detail but never the fraud row. | COPAUS1C, COPAUS2C, CBPAUP0C | COPAUS1C SE-AUDT-001, SE-AUDT-002; CBPAUP0C DEF-AUPG-009 | PINV-e-authorization-03 |
| INV-049 | There is no batch window for `DBPAUTP0`: the online programs, the purge (BMP) and the reload (BMP) run concurrently through the IMS control region, which serializes their calls; the unloads (DL/I batch, DBRC off) read the data sets directly and are not protected. The base window INV-002 covers the VSAM files only, and CLOSEFIL closes `CCXREF`, `ACCTDAT` and `CXACAIX` but not `CUSTDAT`, so COPAUA0C requests fail with a critical file error and COPAUS0C cannot read the cross-reference during the base batch window. | COPAUA0C, COPAUS0C, COPAUS1C, CBPAUP0C, PAUDBLOD, PAUDBUNL, DBUNLDGS | CBPAUP0J §3; INV-002; UNLDGSAM DEF-JULGS-002; `app/jcl/CLOSEFIL.jcl:26-30`; COPAUA0C ERR-AUTH-005 | PINV-e-authorization-04 |
| INV-050 | The sequential unload and the reload are a matched pair: PAUDBUNL writes 100-byte summaries and 206-byte details with the packed parent key, which is exactly what PAUDBLOD reads, provided the parent key survives in the record area after each `WRITE` (DEF-PDUL-005). The GSAM unload (DBUNLDGS) drops the parent key and the HD unload (DFSURGU0) uses the IMS format; neither can be loaded by PAUDBLOD. | PAUDBUNL, PAUDBLOD, DBUNLDGS, DFSURGU0 | PAUDBUNL BR-PDUL-003, NUM-PDUL-001, DEF-PDUL-005; PAUDBLOD BR-PDLD-002; DBUNLDGS DEF-DBUL-001; DBPAUTP0 SE-JDBPA-002 | PINV-e-authorization-05 |
| INV-051 | Every authorization request gets exactly one reply carrying the request's correlation ID. As-is the reply buffer is never reset, so later replies of a task carry earlier ones (DEF-AUTH-001), and a request whose reply put fails is lost after the get (DEF-AUTH-008). | COPAUA0C, the external requester | COPAUA0C SE-AUTH-001, SE-AUTH-002, DEF-AUTH-001, DEF-AUTH-008 | PINV-e-authorization-06 |

## Candidate seams for the architecture step (observations, not decisions)

- **Payments** (bill payment) is a self-contained online use case with one screen, three data stores and no outbound calls other than navigation; it is the natural first slice for selective conversion. Its coupling points are INV-001, INV-002 and INV-004 (`COBIL00C.md` "Module extraction notes").
- **Posting** (POSTTRAN) is a classic read-validate-write chunk job: daily file in, three stores updated, rejects out. It maps onto a Spring Batch step with an item reader, a validating processor and composite writers; the transaction boundary is open question Q-POST-004.
- The **batch window** (INV-002) is an operational coupling that the target can remove only if account updates from payments and from posting are made safe to run concurrently.
- **Accounts and cards** (account view and update, card list, view and update) touch `ACCTDAT`, `CUSTDAT`, `CARDDAT` and `CXACAIX` and hand over only the base commarea (INV-026). Before they move, the account update layout must be fixed (INV-039), the save must become one transaction across account and customer (INV-043), and the field comparisons under lock (INV-037, INV-042) map onto version columns. The card-to-account link is held twice (INV-030); the target keeps one owner.
- **Identity and users** (sign-on, menus, user administration) touch only `USRSEC` and the commarea. The target replaces the commarea identity with an authenticated session, which closes INV-006 and makes INV-007 and INV-009 design choices rather than data rules.
- **Reference data** (transaction types and categories) exists twice, in Db2 and in VSAM, kept in step by hand (INV-019). One owned store with the Db2 foreign key (INV-017) removes the copy.
- **Branch migration** (CBEXPORT, CBIMPORT) is a self-contained file round trip that nothing schedules and nothing downstream reads (CBIMPORT Q-IMPT-004); the target can replace it with a database export or drop it.
- **Authorizations** (module e) are a message-driven decision service (COPAUA0C: one request in, one reply out, read-only use of the cross-reference, account and customer) with two screens and a fraud slice (COPAUS0C, COPAUS1C, COPAUS2C) around a store the module owns, the IMS database `DBPAUTP0`. They map onto a RabbitMQ request-reply consumer in Spring Boot and two SQL Server tables (summary by account, detail by account and timestamp). The reply must carry the correlation ID (INV-051); the summary totals must be derived from the details or kept in step by every writer, including the purge and the reload (INV-047); the fraud flag and the fraud row stay one transaction (INV-048); and the module's only coupling to the base batch window is the CA-7 position of the purge between CLOSEFIL and POSTTRAN and the files CLOSEFIL closes (INV-049). The unload and reload jobs (INV-050) become a database export, or are dropped.
- **MQ inquiries** (COACCT01, CODATE01) are request-reply services with fixed reply queues and no status field (INV-021); they map onto RabbitMQ request-reply or plain HTTP queries.
