# Data handling: PII, cardholder data and privacy

CardDemo models a credit card system, so its record layouts carry the same kinds of personal
and cardholder data a production system would. This page lists those fields, states what the
bundled data is, and sets the rules for displays, logs, reports and extracts. The last
sections are for anyone who deploys or modernizes CardDemo with real data.

## The bundled data is synthetic

Every record shipped in this repository is generated test data: `app/data/ASCII/`,
`app/data/EBCDIC/`, `app/app-authorization-ims-db2-mq/data/`, the in-stream records in
JCL such as `DUSRSECJ` and `ESDSRRDS`, and the data inside the `samples/m2/` runtime
archives. No record describes a real person, card or account. The sign-on passwords are
demo seed values; see [secrets.md](secrets.md).

Do not add real data to the repository, to issues or to pull requests. If you need a
reproduction case, edit the synthetic records.

## Inventory of sensitive fields

Classes used below:

- **PAN**: primary account number (card number).
- **SAD**: sensitive authentication data. In CardDemo, the CVV.
- **Direct PII**: identifies a person on its own (SSN, government ID, name with address).
- **PII**: personal data that identifies a person in combination with other data.
- **Credential**: a password.

### Base application copybooks (`app/cpy`)

| Copybook | Record / file | Field | Class |
| :------- | :------------ | :---- | :---- |
| `CVCUS01Y` | `CUSTOMER-RECORD`, customer master (`CUSTDATA`, CICS file `CUSTDAT`) | `CUST-SSN` | Direct PII |
| | | `CUST-GOVT-ISSUED-ID` | Direct PII |
| | | `CUST-DOB-YYYY-MM-DD` | PII |
| | | `CUST-FIRST-NAME`, `CUST-MIDDLE-NAME`, `CUST-LAST-NAME` | PII |
| | | `CUST-ADDR-LINE-1/2/3`, `CUST-ADDR-STATE-CD`, `CUST-ADDR-COUNTRY-CD`, `CUST-ADDR-ZIP` | PII |
| | | `CUST-PHONE-NUM-1`, `CUST-PHONE-NUM-2` | PII |
| | | `CUST-EFT-ACCOUNT-ID` | PII (bank account reference) |
| | | `CUST-FICO-CREDIT-SCORE` | PII (credit data) |
| `CUSTREC` | Same layout, used by `CBSTM03A` | Same fields; the date of birth is spelled `CUST-DOB-YYYYMMDD` | As above |
| `CVACT02Y` | `CARD-RECORD`, card master (`CARDDATA`, CICS file `CARDDAT`) | `CARD-NUM` | PAN |
| | | `CARD-CVV-CD` | SAD |
| | | `CARD-EMBOSSED-NAME` | PII |
| | | `CARD-EXPIRAION-DATE` (spelled this way in the source) | Cardholder data |
| `CVACT03Y` | `CARD-XREF-RECORD`, card cross reference (`CARDXREF`, `CCXREF`) | `XREF-CARD-NUM` | PAN |
| `CVTRA05Y` | `TRAN-RECORD`, transaction master (`TRANSACT`) | `TRAN-CARD-NUM` | PAN |
| `CVTRA06Y` | `DALYTRAN-RECORD`, daily transactions (`DALYTRAN`), also the first 350 bytes of each `DALYREJS` reject record | `DALYTRAN-CARD-NUM` | PAN |
| `CVACT01Y` | `ACCOUNT-RECORD`, account master | `ACCT-ADDR-ZIP` | PII (low risk on its own) |
| `CSUSR01Y` | `SEC-USER-DATA`, user security file (`USRSEC`) | `SEC-USR-PWD` (clear text) | Credential |
| | | `SEC-USR-FNAME`, `SEC-USR-LNAME` | PII (staff) |
| `CVEXPORT` | Export record written by `CBEXPORT`, read by `CBIMPORT` | `EXP-CUST-SSN`, `EXP-CUST-GOVT-ISSUED-ID`, `EXP-CUST-DOB-YYYY-MM-DD`, `EXP-CUST-FIRST/MIDDLE/LAST-NAME`, `EXP-CUST-ADDR-LINE`, `EXP-CUST-ADDR-*`, `EXP-CUST-PHONE-NUM`, `EXP-CUST-EFT-ACCOUNT-ID`, `EXP-CUST-FICO-CREDIT-SCORE` | Direct PII / PII |
| | | `EXP-CARD-NUM`, `EXP-XREF-CARD-NUM`, `EXP-TRAN-CARD-NUM` | PAN |
| | | `EXP-CARD-CVV-CD` | SAD |
| | | `EXP-CARD-EMBOSSED-NAME`, `EXP-CARD-EXPIRAION-DATE` | PII / cardholder data |

### Optional pending authorization module (`app/app-authorization-ims-db2-mq`)

| Source | Record | Field | Class |
| :----- | :----- | :---- | :---- |
| `cpy/CIPAUDTY.cpy` | IMS pending authorization detail segment | `PA-CARD-NUM` | PAN |
| | | `PA-CARD-EXPIRY-DATE` | Cardholder data |
| `cpy/CCPAURQY.cpy` | MQ authorization request | `PA-RQ-CARD-NUM` | PAN |
| | | `PA-RQ-CARD-EXPIRY-DATE` | Cardholder data |
| `cpy/CCPAURLY.cpy` | MQ authorization response | `PA-RL-CARD-NUM` | PAN |
| `ddl/AUTHFRDS.ddl` | Db2 table `CARDDEMO.AUTHFRDS` (fraud reports) | `CARD_NUM` (part of the primary key), `CARD_EXPIRY_DATE` | PAN / cardholder data |

## Where the sensitive data surfaces today

CardDemo shows data in clear because it is a demo. These are the places a real deployment
has to change.

**Online screens**

- Account View (CAVW, `COACTVWC`) and Account Update (CAUP, `COACTUPC`) show the full SSN,
  formatted `999-99-9999`, plus date of birth, government ID, address, phone numbers,
  EFT account ID and FICO score.
- Credit Card List (CCLI, `COCRDLIC`), Credit Card View (CCDL, `COCRDSLC`) and Credit Card
  Update (CCUP, `COCRDUPC`) show full card numbers. The CVV is read into working storage
  but not sent to any map.
- Update User (CU02, `COUSR02C`) sends the stored password to a non-display (`DRK`) field.

**Batch job output (SYSOUT)**

| Job | Program | What it writes to SYSOUT |
| :-- | :------ | :----------------------- |
| READCUST | `CBCUS01C` | `DISPLAY CUSTOMER-RECORD`: every customer field, SSN included |
| READCARD | `CBACT02C` | `DISPLAY CARD-RECORD`: card number and CVV |
| READXREF | `CBACT03C` | `DISPLAY CARD-XREF-RECORD`: card number |
| READACCT | `CBACT01C` | `DISPLAY ACCOUNT-RECORD` |
| (no shipped JCL) | `CBTRN01C` | `DISPLAY DALYTRAN-RECORD` and card numbers per transaction |
| TRANREPT | `CBTRN03C` | `DISPLAY TRAN-RECORD`, and `INVALID CARD NUMBER :` with the card number |

The READ* jobs exist to show that a file loaded correctly. Do not run them against real data.

**Reports, statements and extracts**

- `CREASTMT` (`CBSTM03A`) writes customer name and full address to
  `AWS.M2.CARDDEMO.STATEMNT.PS` and `AWS.M2.CARDDEMO.STATEMNT.HTML`.
- `TRANREPT` (`CBTRN03C`) writes `AWS.M2.CARDDEMO.TRANREPT(+1)`, a transaction report
  grouped by card.
- `CBEXPORT` writes `AWS.M2.CARDDEMO.EXPORT.DATA` with the full customer, card (CVV
  included), cross reference and transaction records in the `CVEXPORT` layout.
- `POSTTRAN` (`CBTRN02C`) copies each rejected daily transaction, card number included, to
  `AWS.M2.CARDDEMO.DALYREJS(+1)`.

## Rules for code and data changes

These rules apply to every change in this repository. They are written so that a deployment
with real data can follow them unchanged.

### Displays, logs and job output

1. Never DISPLAY, log or trace a CVV, a password, an SSN or a government ID. Not even in a
   debug path behind a flag.
2. Never DISPLAY a whole record or group item that contains one of the fields listed above
   (for example `DISPLAY CARD-RECORD`). Display keys and counts instead: account ID,
   customer ID, record counts, file status.
3. When a card number must appear in a message, mask it to the last four digits
   (`************1234`).
4. Error paths are the usual leak. In `9910-DISPLAY-IO-STATUS` style routines, show the file
   status and the key you used, not the record buffer.

The COBOL lint tool enforces these rules with the **`sensitive-display`** rule:

```sh
scripts/lint.sh                                                      # full gate; fails on new findings
python3 scripts/cobol_lint.py lint --rule sensitive-display          # only this rule
python3 scripts/cobol_lint.py lint --rule sensitive-display --no-baseline   # every finding, including known ones
```

The rule flags a DISPLAY of a sensitive field, or of a group item that contains one. Its
field list is the set of regular expressions in `cobol-lint.json` under
`rules.sensitive-display.field_patterns`. The patterns are matched against full upper-case
data names (88-levels are ignored) and cover every field in the inventory above. Findings
that already exist in the code, such as the DISPLAYs listed under "Batch job output", are
recorded in the lint baseline so they stay visible; any new finding fails the gate. If you add a copybook with a new kind
of sensitive field, add a pattern to `cobol-lint.json` in the same change.

### Screens

1. Show card numbers masked to the last four digits on list screens. Show the full number
   only where the user's task needs it and their role allows it.
2. Show SSN and government ID masked (`***-**-1234`). Accept full values on input only.
3. Never send a stored password or CVV to a map, even into a `DRK` field.

### Reports, statements and extracts

1. Statements and reports carry the masked card number, never the CVV.
2. Extracts that leave the system (`CBEXPORT`, FTP jobs such as `FTPJCL`, MQ messages from
   the optional modules) carry only the fields the receiver needs. Drop CVV and SSN unless
   the receiver has a documented need.
3. Protect output datasets with your security manager (for example a RACF dataset profile on
   `AWS.M2.CARDDEMO.**`), and send files only over encrypted transport (FTPS or SFTP).

### Test data

1. Use synthetic data only. Generate new records rather than copying production.
2. If production-derived data is ever unavoidable, mask it irreversibly before it reaches a
   test region: replace card numbers, SSNs, names, addresses, phone numbers and dates of birth,
   and blank the CVV.

## Retention and deletion

What CardDemo keeps today, from the JCL:

| Data | Retention mechanism | Source |
| :--- | :------------------ | :----- |
| Transaction backups `TRANSACT.BKUP`, daily extracts `TRANSACT.DALY`, reports `TRANREPT`, category balance backups `TCATBALF.BKUP`, system transactions `SYSTRAN`, combined transactions `TRANSACT.COMBINED` | GDG, `LIMIT(5) SCRATCH` as defined by DEFGDGB: the five newest generations are kept, older ones are deleted. The shipped catalog shows `TRANSACT.BKUP` and `TRANSACT.DALY` as `NOSCRATCH` instead, with 71 and 25 rolled-off generations still cataloged, so check each base with `LISTCAT` before relying on deletion. The alternative job REPTFILE defines `TRANREPT` with `LIMIT(10)` and no `SCRATCH` | `app/jcl/DEFGDGB.jcl`, `app/jcl/REPTFILE.jcl`, `app/catlg/LISTCAT.txt` |
| Daily rejects `DALYREJS` | GDG, `LIMIT(5) SCRATCH` | `app/jcl/DALYREJS.jcl` |
| Statements `STATEMNT.PS`, `STATEMNT.HTML`; statement work files `TRXFL.SEQ` and `TRXFL.VSAM.KSDS` (a copy of every transaction keyed by card number); the PDF `STATEMNT.PS.PDF` | Statements and work files: deleted and re-created on every `CREASTMT` run, so the last run's copies stay cataloged until the next run. PDF: written by `TXT2PDF1` and never deleted by any job; whether a rerun overwrites it depends on the TXT2PDF exec | `app/jcl/CREASTMT.JCL` (`DELDEF01`, `STEP030`), `app/jcl/TXT2PDF1.JCL` |
| Pending authorizations (IMS) | Deleted by `CBPAUP0J` once older than the expiry days given in its SYSIN | `app/app-authorization-ims-db2-mq/jcl/CBPAUP0J.jcl`, program `CBPAUP0C` |
| Master files (customer, account, card, cross reference, transactions, users) | Kept until reloaded; no purge job | |
| Fraud reports (`CARDDEMO.AUTHFRDS`) | Kept indefinitely; no purge job | |
| Export file `EXPORT.DATA`, with full customer PII, card numbers and CVVs | Deleted and defined again by `STEP01` of every `CBEXPORT` run; otherwise kept until deleted by hand | `app/jcl/CBEXPORT.jcl` |

There is no online or batch function that deletes a customer. Deleting a person's data
means removing records from every place that holds them, keyed by customer ID, account ID
and card number:

1. Customer master (`CUSTDATA`), keyed by `CUST-ID`.
2. Cross reference (`CARDXREF` and its AIX), which links `XREF-CUST-ID` to accounts and cards.
3. Card master (`CARDDATA` and `CARDAIX`), and the account master (`ACCTDATA`) if the account closes.
4. Transactions (`TRANSACT`), daily input (`DALYTRAN`), and every GDG generation listed above, including rolled-off generations of `NOSCRATCH` bases.
5. Category balances (`TCATBALF`), keyed by account.
6. With the optional modules: the IMS pending authorization database, `CARDDEMO.AUTHFRDS`,
   and any MQ queues still holding messages.
7. Statements, reports, exports and anything sent elsewhere by FTP or MQ.

Backups taken outside these datasets (volume dumps, snapshots) need their own expiry.

## GDPR, CCPA and PCI DSS considerations for real deployments

CardDemo does not process real personal data, so these laws do not apply to the sample
itself. They will apply to a modernized system built from it. This is not legal advice;
involve your privacy and compliance teams. Points that follow directly from CardDemo's
design:

- **PCI DSS: do not store the CVV.** `CARD-CVV-CD` is stored in the card master and copied
  into the export file. PCI DSS forbids storing card verification codes after
  authorization. Remove the field, or keep it out of every persistent store, in any real
  system. Card numbers that are stored must be rendered unreadable (for example by
  tokenization or strong encryption), and displays must be masked.
- **Data minimization (GDPR Art. 5(1)(c)).** Collect only what each function needs. SSN,
  government ID, date of birth and FICO score sit in the same record as the address used for
  statements. Consider splitting them out and limiting who can read them.
- **Right of access and portability (GDPR Art. 15 and 20, CCPA right to know).** You need a
  way to produce one person's data. `CBEXPORT` shows the record shapes involved; a
  per-customer extract would follow the same joins (customer to cross reference to cards,
  accounts and transactions).
- **Right to erasure (GDPR Art. 17, CCPA right to delete).** Plan the deletion path listed
  above, and decide which records you must keep for legal reasons (for example
  transaction history for financial record keeping) and for how long.
- **Retention limits (GDPR Art. 5(1)(e)).** Set explicit retention for master files,
  `AUTHFRDS` and exports, which CardDemo keeps indefinitely today.
- **Security of processing (GDPR Art. 32).** Encrypt data at rest and in transit, use
  role-based access in CICS and for datasets, and keep clear-text passwords out of the user
  file (see [secrets.md](secrets.md)).
- **Logs are personal data too.** SYSOUT, CICS logs and runtime logs in a modernized
  platform inherit whatever the programs DISPLAY. Keep the `sensitive-display` lint gate on,
  and set retention for job output in your output management and log services.
- **Breach notification.** GDPR requires notifying the supervisory authority within 72 hours
  of becoming aware of a breach; US state laws, which CCPA builds on, set their own rules.
  Know which datasets hold which fields (the inventory above) so you can assess scope quickly.
- **Cross-border transfers.** The MQ and FTP paths move data between systems. Record where
  each receiver runs.
