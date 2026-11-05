# Area a-signon-admin: entity proposals

Proposals from area `a-signon-admin` (COSGN00C, COMEN01C, COADM01C, COUSR00C-03C) for `../entities.md`. Source revision `b5e0139`. The program specs reference these IDs already.

## ENT-USER-SECURITY · User security record (sign-on credentials and user type)

- **Copybook:** `app/cpy/CSUSR01Y.cpy:17-23` (`SEC-USER-DATA`, 80 bytes).
- **Dataset:** `AWS.M2.CARDDEMO.USRSEC.VSAM.KSDS`, `KEYS(8,0)`, `RECORDSIZE(80,80)`, `REUSE`, `INDEXED`, `CISZ(8192)` (`app/jcl/DUSRSECJ.jcl:64-73`).
- **CICS file:** `USRSEC` (`app/csd/CARDDEMO.CSD:88-99`): `READ(YES) BROWSE(YES) ADD(YES) UPDATE(YES) DELETE(YES)`, `RECOVERY(NONE)` (no backout of online changes), `STRINGS(1)` (one concurrent request), `RECORDFORMAT(V)` although the cluster has fixed 80-byte records. Opened and closed around the batch cycle by `app/jcl/OPENFIL.jcl:30` and `app/jcl/CLOSEFIL.jcl:30`.
- **Primary key:** `SEC-USR-ID`, 8 bytes, unique. Key order is the EBCDIC byte order (lower case < upper case < digits).
- **Loaded by:** `app/jcl/DUSRSECJ.jcl`: IEBGENER copies 10 in-stream records to `AWS.M2.CARDDEMO.USRSEC.PS` (`:32-49`), IDCAMS deletes and defines the cluster (`:58-73`) and REPRO loads it (`:82-83`). Copies as ESDS and RRDS are made by `app/jcl/ESDSRRDS.jcl:63-116` (not used by any program in this area). No batch program reads the file.
- **Used by:** `COSGN00C` (read), `COUSR00C` (browse), `COUSR01C` (write), `COUSR02C` (read for update, rewrite), `COUSR03C` (read for update, delete). `COMEN01C` and `COADM01C` copy the layout but never use it.

| Field | Pos | Len | PIC | Type | Meaning |
| :-- | --: | --: | :-- | :-- | :-- |
| `SEC-USR-ID` | 1 | 8 | `X(08)` | text(8) | User ID, the key. Sign-on upper-cases the typed ID before the lookup; add, update and delete use it as typed |
| `SEC-USR-FNAME` | 9 | 20 | `X(20)` | text(20) | First name |
| `SEC-USR-LNAME` | 29 | 20 | `X(20)` | text(20) | Last name |
| `SEC-USR-PWD` | 49 | 8 | `X(08)` | text(8) | Password in **plain text**; compared with the upper-cased typed password at sign-on |
| `SEC-USR-TYPE` | 57 | 1 | `X(01)` | code(1) | `A` administrator (admin menu), anything else regular user (main menu); `U` by convention. Not validated on add or update |
| `SEC-USR-FILLER` | 58 | 23 | `X(23)` | text | Unused; written uninitialised by user add (DEF-USRA-004) |

Sample data (`app/jcl/DUSRSECJ.jcl:35-44`, public demo data): 10 users, `ADMIN001`-`ADMIN005` with type `A` and `USER0001`-`USER0005` with type `U`, all with password `PASSWORD`; names upper case (for example `ADMIN001` `MARGARET` `GOLD`, `USER0001` `LAWRENCE` `THOMAS`). The filler is spaces in the seed records.

Observations for the target:

- Credentials are case-insensitive in effect only when stored in upper case (BR-SGN-001, DEF-SGN-011); the maintenance programs do not enforce it (DEF-USRA-001, DEF-USRU-004).
- There is no audit data (created, changed, last sign-on), no status (locked, disabled), no password history and no attempt counter (DEF-SGN-002, Q-USRA-002, Q-USRD-003).
- User IDs are not referenced by any other file: transactions, accounts and cards carry no user ID.

## ENT-MAIN-MENU-OPTIONS · Main menu option table (compiled in)

- **Copybook:** `app/cpy/COMEN02Y.cpy:19-98` (`CARDDEMO-MAIN-MENU-OPTIONS`), working storage of `COMEN01C`; not a file.
- `CDEMO-MENU-OPT-COUNT` `9(02)` = 11 (`:21`); the data area has 11 entries of 46 bytes, redefined by a table of **12** occurrences (`:93-98`), so entry 12 overlays the storage after the table.

| Field | Pos (in entry) | Len | PIC | Type | Meaning |
| :-- | --: | --: | :-- | :-- | :-- |
| `CDEMO-MENU-OPT-NUM` | 1 | 2 | `9(02)` | digits(2) | Option number shown (`01`-`11`) |
| `CDEMO-MENU-OPT-NAME` | 3 | 35 | `X(35)` | text(35) | Option text |
| `CDEMO-MENU-OPT-PGMNAME` | 38 | 8 | `X(08)` | text(8) | Program reached by XCTL |
| `CDEMO-MENU-OPT-USRTYPE` | 46 | 1 | `X(01)` | code(1) | `A` = admin only, `U` = everyone; all 11 are `U` |

Content: 1 `Account View` `COACTVWC`, 2 `Account Update` `COACTUPC`, 3 `Credit Card List` `COCRDLIC`, 4 `Credit Card View` `COCRDSLC`, 5 `Credit Card Update` `COCRDUPC`, 6 `Transaction List` `COTRN00C`, 7 `Transaction View` `COTRN01C`, 8 `Transaction Add` `COTRN02C` (formerly `(Admin Only)`, commented out at `:69`), 9 `Transaction Reports` `CORPT00C`, 10 `Bill Payment` `COBIL00C`, 11 `Pending Authorization View` `COPAUS0C` (optional authorisation module). See COMEN01C BR-MENU-001.

## ENT-ADMIN-MENU-OPTIONS · Admin menu option table (compiled in)

- **Copybook:** `app/cpy/COADM02Y.cpy:19-59` (`CARDDEMO-ADMIN-MENU-OPTIONS`), working storage of `COADM01C`; not a file.
- `CDEMO-ADMIN-OPT-COUNT` `9(02)` = 6 (`:22`; the earlier value 4 is commented out at `:21`); 6 entries of 45 bytes redefined by a table of **9** occurrences (`:55-59`). There is no user-type column.

| Field | Pos (in entry) | Len | PIC | Type | Meaning |
| :-- | --: | --: | :-- | :-- | :-- |
| `CDEMO-ADMIN-OPT-NUM` | 1 | 2 | `9(02)` | digits(2) | Option number shown (`01`-`06`) |
| `CDEMO-ADMIN-OPT-NAME` | 3 | 35 | `X(35)` | text(35) | Option text |
| `CDEMO-ADMIN-OPT-PGMNAME` | 38 | 8 | `X(08)` | text(8) | Program reached by XCTL |

Content: 1 `User List (Security)` `COUSR00C`, 2 `User Add (Security)` `COUSR01C`, 3 `User Update (Security)` `COUSR02C`, 4 `User Delete (Security)` `COUSR03C`, 5 `Transaction Type List/Update (Db2)` `COTRTLIC`, 6 `Transaction Type Maintenance (Db2)` `COTRTUPC` (5 and 6: optional transaction-type module). See COADM01C BR-ADMN-001.

## ENT-COMMAREA-USER-ADMIN-EXT · User administration commarea extension

- **Defined in:** `app/cbl/COUSR00C.cbl:67-75` (`CDEMO-CU00-INFO`), `app/cbl/COUSR02C.cbl:50-58` (`CDEMO-CU02-INFO`), `app/cbl/COUSR03C.cbl:50-58` (`CDEMO-CU03-INFO`): three in-program copies with the same layout, appended to ENT-COMMAREA, so the user programs pass 194 bytes. `COUSR01C` has no extension (160 bytes).
- When a 160-byte commarea (from the admin menu) is copied into the 194-byte area, the extension is filled with spaces (group move; GnuCOBOL test). `COADM01C` truncates a 194-byte commarea to 160 bytes.

| Field | Pos | Len | PIC | Type | Meaning |
| :-- | --: | --: | :-- | :-- | :-- |
| `CDEMO-CUnn-USRID-FIRST` | 161 | 8 | `X(08)` | text(8) | User ID in row 1 of the list page shown (PF7 start key) |
| `CDEMO-CUnn-USRID-LAST` | 169 | 8 | `X(08)` | text(8) | User ID in row 10 of the list page shown (PF8 start key) |
| `CDEMO-CUnn-PAGE-NUM` | 177 | 8 | `9(08)` | digits(8) | List page number |
| `CDEMO-CUnn-NEXT-PAGE-FLG` | 185 | 1 | `X(01)` | flag | `Y` a next page exists, `N` not |
| `CDEMO-CUnn-USR-SEL-FLG` | 186 | 1 | `X(01)` | code(1) | Selection code typed in the list (`U`, `u`, `D`, `d`) |
| `CDEMO-CUnn-USR-SELECTED` | 187 | 8 | `X(08)` | text(8) | User ID selected in the list; read by update and delete on first entry |

## Corrections to existing entities

| Entity | Field or statement | Current text | Proposed text | Evidence (file:line) |
| :-- | :-- | :-- | :-- | :-- |
| ENT-COMMAREA | `CDEMO-USER-TYPE` meaning | `A` admin, `U` regular user | `A` admin, `U` regular user; set by sign-on from `SEC-USR-TYPE`, and also forced to `U` by the account and card programs and to `A` by the Db2 transaction-type programs (DEF-SGN-012); any value other than `A` is routed as a regular user | `app/cbl/COSGN00C.cbl:227-240`; `app/cbl/COCRDLIC.cbl:320`; `app/app-transaction-type-db2/cbl/COTRTLIC.cbl:520` |
| ENT-COMMAREA | `CDEMO-USER-ID` meaning | Signed-on user | Signed-on user, upper-cased by sign-on; the only identity in the online application (no CICS sign-on) | `app/cbl/COSGN00C.cbl:132-133, 226` |
| ENT-COMMAREA | extension sentence | programs may append their own extension | programs may append their own extension; a shorter commarea copied into a longer one leaves the extension filled with spaces, and the menus truncate longer ones to 160 bytes. User administration extension: ENT-COMMAREA-USER-ADMIN-EXT | `app/cbl/COUSR00C.cbl:114`; `app/cbl/COADM01C.cbl:90` |
| ENT-COMMAREA | (new) initialisation | - | Sign-on sets only the from-fields, user ID, user type and context; the other fields are passed uninitialised (COSGN00C DEF-SGN-007) | `app/cbl/COSGN00C.cbl:224-228` |

## Relationships

| From | To | Join fields | Cardinality | Evidence |
| :-- | :-- | :-- | :-- | :-- |
| ENT-COMMAREA | ENT-USER-SECURITY | `CDEMO-USER-ID` = `SEC-USR-ID` (upper case) | many sessions to 1 user; not enforced after sign-on | `app/cbl/COSGN00C.cbl:132-133, 215, 226` |
| ENT-COMMAREA-USER-ADMIN-EXT | ENT-USER-SECURITY | `CDEMO-CUnn-USR-SELECTED` = `SEC-USR-ID` | 0..1 to 1 | `app/cbl/COUSR00C.cbl:154`; `app/cbl/COUSR02C.cbl:99-103` |
| ENT-ADMIN-MENU-OPTIONS | programs | `CDEMO-ADMIN-OPT-PGMNAME` | 1 to 1 | `app/cpy/COADM02Y.cpy:26-53` |
| ENT-MAIN-MENU-OPTIONS | programs | `CDEMO-MENU-OPT-PGMNAME` | 1 to 1 | `app/cpy/COMEN02Y.cpy:25-90` |

## Relational mapping hint

| Entity | Candidate table | Primary key | Notes |
| :-- | :-- | :-- | :-- |
| ENT-USER-SECURITY | `app_user` | `user_id` CHAR(8) | hash the password (Q-SGN-001); constrain `user_type` to `A`/`U` (Q-USRA-001); a case-insensitive unique index if sign-on stays case-insensitive (Q-SGN-002); a binary collation if the EBCDIC list order must be kept (Q-USRL-002) |
