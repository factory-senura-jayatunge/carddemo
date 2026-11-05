# Area proposals and unit codes

The per-program specs are written by area agents in parallel. The system artifacts (`../entities.md`, `../components.md`, `../interactions.md`) are edited only by the spec lead, so areas never conflict on them. An area proposes its additions in this folder, and the spec lead merges them.

## Areas

| Area | Scope | Owns these spec files |
| :-- | :-- | :-- |
| `a-signon-admin` | Sign-on, menus, users, admin | `programs/` for COSGN00C, COMEN01C, COADM01C, COUSR00C-03C |
| `b-accounts-cards` | Account and card view, update, list | `programs/` for COACTVWC, COACTUPC, COCRDLIC, COCRDSLC, COCRDUPC |
| `c-transactions` | Transaction list, view, add; report request; date check | `programs/` for COTRN00C-02C, CORPT00C, CSUTLDTC |
| `d1-core-batch` | Core batch: account, card, xref and customer readers, interest, validation, report | `programs/` for CBACT01C-04C, CBCUS01C, CBTRN01C, CBTRN03C |
| `d2-batch-utilities` | Statements, export and import, wait utility, assembler | `programs/` for CBSTM03A, CBSTM03B, CBEXPORT, CBIMPORT, COBSWAIT, COBDATFT, MVSWAIT |
| `e-authorization` | Authorization module (CICS, IMS, DB2, MQ) | `programs/` for COPAUA0C, COPAUS0C-2C, CBPAUP0C, DBUNLDGS, PAUDBLOD, PAUDBUNL; `jobs/` for the module's JCL and CSD |
| `f-trantype-vsam-mq` | Transaction type (DB2) and VSAM-MQ modules | `programs/` for COTRTLIC, COTRTUPC, COBTUPDT, COACCT01, CODATE01; `jobs/` for the modules' JCL and CSD |
| `g-jobs-ops` | Base JCL, procedures, CSD, schedules | `jobs/` for `app/jcl`, `app/proc`, `app/csd/CARDDEMO.CSD`, `app/scheduler`, with `app/ctl` and `app/catlg` as sources |
| spec lead | Pilot programs and system artifacts | `programs/COBIL00C.md`, `programs/CBTRN02C.md`, `format.md`, `entities.md`, `components.md`, `interactions.md`, `tools/` |

Rules across areas:

- An area writes only its own spec files and its two proposal files, `areas/<area>-entities.md` and `areas/<area>-interactions.md`.
- A call into another area's program is described at the interface only (what is passed, what comes back), with a reference to that program's items. CSUTLDTC is specified once, by `c-transactions`; COBIL00C and CBTRN02C are already specified.
- A shared copybook is described once, in `entities.md`. Entities already there are final; reference them as `ENT-<NAME>` and propose corrections, never a second copy.
- CORPT00C submits the TRANREPT job: `c-transactions` specifies the submission, `g-jobs-ops` specifies the job.

## Unit codes

Every spec's `unit_code` is reserved here so item IDs never collide. Job specs in `jobs/` use `J` plus up to 5 characters chosen by the area (for example `JPOST` for POSTTRAN); the checker rejects duplicates.

| Program | Unit code | Area | | Program | Unit code | Area |
| :-- | :-- | :-- | :-- | :-- | :-- | :-- |
| COSGN00C | `SGN` | a | | CBACT01C | `ACRD` | d1 |
| COMEN01C | `MENU` | a | | CBACT02C | `CDRD` | d1 |
| COADM01C | `ADMN` | a | | CBACT03C | `XRRD` | d1 |
| COUSR00C | `USRL` | a | | CBACT04C | `INTC` | d1 |
| COUSR01C | `USRA` | a | | CBCUS01C | `CURD` | d1 |
| COUSR02C | `USRU` | a | | CBTRN01C | `TVAL` | d1 |
| COUSR03C | `USRD` | a | | CBTRN02C | `POST` | lead (done) |
| COACTVWC | `ACVW` | b | | CBTRN03C | `TRPT` | d1 |
| COACTUPC | `ACUP` | b | | CBSTM03A | `STMT` | d2 |
| COCRDLIC | `CRDL` | b | | CBSTM03B | `STIO` | d2 |
| COCRDSLC | `CRDV` | b | | CBEXPORT | `EXPT` | d2 |
| COCRDUPC | `CRDU` | b | | CBIMPORT | `IMPT` | d2 |
| COTRN00C | `TRNL` | c | | COBSWAIT | `WAIT` | d2 |
| COTRN01C | `TRNV` | c | | COBDATFT | `DTFT` | d2 |
| COTRN02C | `TRNA` | c | | MVSWAIT | `MWAT` | d2 |
| CORPT00C | `RPTS` | c | | COPAUA0C | `AUTH` | e |
| CSUTLDTC | `DTCK` | c | | COPAUS0C | `AUSM` | e |
| COBIL00C | `BILL` | lead (done) | | COPAUS1C | `AUDT` | e |
| COTRTLIC | `TTLS` | f | | COPAUS2C | `AUFR` | e |
| COTRTUPC | `TTUP` | f | | CBPAUP0C | `AUPG` | e |
| COBTUPDT | `TTBU` | f | | DBUNLDGS | `DBUL` | e |
| COACCT01 | `MQAC` | f | | PAUDBLOD | `PDLD` | e |
| CODATE01 | `MQDT` | f | | PAUDBUNL | `PDUL` | e |

## Entity proposals: `areas/<area>-entities.md`

Use the layout of `../entities.md`:

- **New entities:** one `## ENT-<NAME> · <title>` section each, with the copybook or DDL or DBD source, the dataset, table or segment, keys, the field table (`Field | Pos | Len | PIC | Type | Meaning`) and sample-data observations. Use an ID that is not yet in `entities.md`; the spec lead keeps it when merging, so specs can reference it right away.
- **Corrections to existing entities:** a table `Entity | Field or statement | Current text | Proposed text | Evidence (file:line)`.
- **Relationships:** a table `From | To | Join fields | Cardinality | Evidence`.
- **Open questions:** rows `| Q-<UNIT>-NNN | ... |` belong in the owning program spec, not here.

## Interaction proposals: `areas/<area>-interactions.md`

- **Edges:** a table `From | To | Mechanism | Data passed | Source (file:line) | Spec item`, where the mechanism is one of `XCTL`, `LINK`, `CALL`, `START`, `RETURN TRANSID`, `TDQ`, `MQ`, `JCL step`, `scheduler`, `file`, `DB2`, `IMS`.
- **CRUD rows** for `../components.md`: `Program | Store | Access (R, RU, C, U, D, B) | Spec item`.
- **Invariant proposals:** rows `| PINV-<area>-NN | Invariant | Components | Evidence |`. The spec lead assigns the final `INV-NNN`; specs reference an invariant only after it is merged into `components.md`.
- **Mermaid** fragments are welcome; the spec lead merges them into the system diagrams.

## Merge process

1. The area merges `origin/modernization`, then commits its specs and proposals on its own branch. Before it reports: `python3 docs/modernization/spec/tools/check_spec.py --program <NAME>` passes for each of its specs; an independent reviewer subagent has checked the items against the source and its verdicts and error rate are in `evidence/<area>-review.md`; areas `d1` and `d2` also record their spec-only reimplementations against the GnuCOBOL runner in `evidence/` (see `../format.md`, "Evidence and verification").
2. The spec lead merges the proposals into the system artifacts, resolves overlaps between areas, and records each merge at the top of the proposal file (`Merged in <commit>`), so the file stays as the area's record.
3. Cross-area consistency reviews compare each area's interface statements with the other side's spec and file the differences as corrections.
