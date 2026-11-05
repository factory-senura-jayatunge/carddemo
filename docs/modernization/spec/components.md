# CardDemo component map

System-level map of programs, entry points, data stores, CRUD and cross-component invariants. It started with the slice around the two pilot units, `COBIL00C` (bill payment) and `CBTRN02C` (POSTTRAN), and holds the proposals merged from areas a (sign-on and administration), c (transactions), d1 (core batch) and f (transaction type Db2 module and VSAM-MQ module). Sources: `docs/inventory.md`, `app/csd/CARDDEMO.CSD`, `app/jcl/`, `app/scheduler/`, `docs/runbooks/batch-cycle-restart.md`, the program and job specs and the `areas/` proposals. Components marked *(not yet decomposed)* are named from the inventory only; their behaviour will be specified when their own specs are merged.

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
| `COACTUPC`, `COACTVWC` | online | `CAUP`, `CAVW` | core | Account update and view; use `ACCTDAT` | *(not yet decomposed)* |
| `COCRDLIC`, `COCRDSLC`, `COCRDUPC` | online | `CCLI`, `CCDL`, `CCUP` | core | Card list, view and update | *(not yet decomposed)* |
| `CBTRN02C` | batch | job `POSTTRAN` step `STEP15` | core | Daily posting | [programs/CBTRN02C.md](programs/CBTRN02C.md) |
| `CBACT04C` | batch | job `INTCALC` step `STEP15` | core | Monthly interest from `TCATBALF`; updates `ACCTDATA`, writes `SYSTRAN(+1)` | [programs/CBACT04C.md](programs/CBACT04C.md) |
| `CBTRN03C` | batch | job `TRANREPT` step `STEP10R` | core | Transaction detail report | [programs/CBTRN03C.md](programs/CBTRN03C.md) |
| `CBTRN01C` | batch | `TRANVALD` (no JCL, no schedule) | core | Daily transaction validation that writes nothing | [programs/CBTRN01C.md](programs/CBTRN01C.md) |
| `CBACT01C`, `CBACT02C`, `CBACT03C`, `CBCUS01C` | batch | jobs `READACCT`, `READCARD`, `READXREF`, `READCUST` | core | File readers; READACCT also writes three extract files | [CBACT01C](programs/CBACT01C.md), [CBACT02C](programs/CBACT02C.md), [CBACT03C](programs/CBACT03C.md), [CBCUS01C](programs/CBCUS01C.md) |
| CLOSEFIL, OPENFIL | JCL (CEMT) | batch cycle steps 1 and 9 | core | Close and reopen the CICS files around the batch window | - |
| TRANBKP, COMBTRAN, TRANIDX | JCL (IDCAMS, SORT) | batch cycle steps 5, 6, 8 | core | Back up, rebuild and re-index `TRANSACT` | - |
| `CEE3ABD`, `CEEDAYS` | LE services (shims locally) | CALL | runtime | Abend for batch programs; date conversion for CSUTLDTC | - |
| `COTRTLIC` | online, Db2 | `CTLI` | transaction type (Db2) | Transaction type list, update and delete | [programs/COTRTLIC.md](programs/COTRTLIC.md) |
| `COTRTUPC` | online, Db2 | `CTTU` | transaction type (Db2) | Transaction type maintenance (add, update, delete) | [programs/COTRTUPC.md](programs/COTRTUPC.md) |
| `COBTUPDT` | batch, Db2 | job `MNTTRDB2` | transaction type (Db2) | Batch type maintenance from `INPFILE` | [programs/COBTUPDT.md](programs/COBTUPDT.md) |
| MNTTRDB2, TRANEXTR, CREADB21 | JCL | Control-M weekly (MNTTRDB2, TRANEXTR); CREADB21 by hand | transaction type (Db2) | Maintenance run, unload to the VSAM load files, schema and seed | [MNTTRDB2](jobs/MNTTRDB2.md), [TRANEXTR](jobs/TRANEXTR.md), [CREADB21](jobs/CREADB21.md) |
| `COACCT01` | online, MQ | `CDRA` (MQ trigger) | VSAM-MQ | Account inquiry over MQ | [programs/COACCT01.md](programs/COACCT01.md) |
| `CODATE01` | online, MQ | `CDRD` (MQ trigger) | VSAM-MQ | System date inquiry over MQ | [programs/CODATE01.md](programs/CODATE01.md) |
| CRDDEMOD, CRDDEMOM | CSD | CICS resource definitions | transaction type (Db2), VSAM-MQ | Transactions, programs, Db2 entry, MQ triggers of the two modules | [CRDDEMOD-CSD](jobs/CRDDEMOD-CSD.md), [CRDDEMOM-CSD](jobs/CRDDEMOM-CSD.md) |

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
| INV-006 | The online session identity is `CDEMO-USER-ID` and `CDEMO-USER-TYPE` in the commarea. Only sign-on is meant to set them, but the account and card programs (type `U`) and the Db2 transaction type programs (type `A`) also set them when started without a commarea; no program verifies them (no CICS sign-on, every transaction `RESSEC(NO)`). It does **not** hold today: a direct start of `CTLI` or `CTTU` followed by PF3 opens the admin menu without a sign-on. | COSGN00C, every online program, COTRTLIC, COTRTUPC, COADM01C | `app/cbl/COSGN00C.cbl:224-240`; `app/csd/CARDDEMO.CSD:385`; `app/cbl/COCRDLIC.cbl:315-325`; `app/app-transaction-type-db2/cbl/COTRTLIC.cbl:515-525`; DEF-SGN-006, DEF-SGN-012, DEF-ADMN-001, DEF-TTLS-017, DEF-TTUP-017 | PINV-a-signon-admin-01, PINV-f-06 |
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

## Candidate seams for the architecture step (observations, not decisions)

- **Payments** (bill payment) is a self-contained online use case with one screen, three data stores and no outbound calls other than navigation; it is the natural first slice for selective conversion. Its coupling points are INV-001, INV-002 and INV-004 (`COBIL00C.md` "Module extraction notes").
- **Posting** (POSTTRAN) is a classic read-validate-write chunk job: daily file in, three stores updated, rejects out. It maps onto a Spring Batch step with an item reader, a validating processor and composite writers; the transaction boundary is open question Q-POST-004.
- The **batch window** (INV-002) is an operational coupling that the target can remove only if account updates from payments and from posting are made safe to run concurrently.
- **Identity and users** (sign-on, menus, user administration) touch only `USRSEC` and the commarea. The target replaces the commarea identity with an authenticated session, which closes INV-006 and makes INV-007 and INV-009 design choices rather than data rules.
- **Reference data** (transaction types and categories) exists twice, in Db2 and in VSAM, kept in step by hand (INV-019). One owned store with the Db2 foreign key (INV-017) removes the copy.
- **MQ inquiries** (COACCT01, CODATE01) are request-reply services with fixed reply queues and no status field (INV-021); they map onto RabbitMQ request-reply or plain HTTP queries.
