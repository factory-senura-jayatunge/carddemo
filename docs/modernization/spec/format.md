# CardDemo natural-language specification format (v1.0)

This document defines the structured natural-language (NL) form that every CardDemo program is converted into before any target architecture is proposed. It was piloted on two programs, `COBIL00C` (online bill payment, [programs/COBIL00C.md](programs/COBIL00C.md)) and `CBTRN02C` (POSTTRAN, daily transaction posting, [programs/CBTRN02C.md](programs/CBTRN02C.md)); use them as worked examples. The pilot's self-check is in [evidence/nl-pilot-check.md](evidence/nl-pilot-check.md).

## Goals

The format has to serve three readers at once:

1. **A business analyst** reads the summary, the rules at a glance and the screen and message sections, and can confirm or challenge what the system does, without COBOL.
2. **An engineer** reimplements the program from the spec alone and gets the same outputs: same records, same messages, same numbers, same edge-case behaviour.
3. **An agent** reasons over many specs at once (entities, components, interactions) to propose the target architecture, and needs stable IDs, typed sections and machine-readable front matter to do that.

Two properties make it trustworthy for a benchmark: **every statement is traceable** to `file:line` ranges in the source, and **nothing is silently dropped**: a coverage table maps every COBOL paragraph to the spec items that describe it.

## Files

All paths are under `docs/modernization/spec/`.

| Artifact | Path | Content | Owner |
| :-- | :-- | :-- | :-- |
| Overview | `README.md` | What the spec is, how to read it, lifecycle, IDs. | spec lead |
| Format (this file) | `format.md` | Rules for writing specs. | spec lead |
| Program spec | `programs/<PROGRAM-ID>.md` | One per COBOL or assembler program, named after its `PROGRAM-ID` (or CSECT). | area agent |
| Job spec | `jobs/<NAME>.md` | One per JCL job, cataloged procedure, CSD group or schedule that carries logic. Same layout; the coverage section lists job steps instead of paragraphs. | area agent |
| Entity catalog | `entities.md` | Every record layout, file, table, segment, key and relationship, written once and referenced by ID from the specs. | spec lead |
| Component map | `components.md` | Programs, transactions, jobs and data stores, CRUD matrix, invariants, candidate seams. | spec lead |
| Interaction view | `interactions.md` | Who calls whom: online navigation, batch cycle, cross-module flows, Mermaid diagrams. | spec lead |
| Area proposals | `areas/<area>-entities.md`, `areas/<area>-interactions.md` | Additions an area proposes for the three system artifacts; the spec lead merges them. | area agent |
| Evidence | `evidence/*.md` | Self-checks: reviewer sampling, spec-only reimplementations, runner comparisons. | whoever ran the check |
| Checker | `tools/check_spec.py` | Front matter, IDs, references, source citations and paragraph coverage. | spec lead |

Only the spec lead edits `entities.md`, `components.md` and `interactions.md`, so parallel areas never conflict on them.
## Program spec layout

Sections appear in this order. A section that does not apply stays, with "None." and a one-line reason, so a reader can tell "not applicable" from "forgotten".

| # | Section | Audience | What goes in it |
| --: | :-- | :-- | :-- |
| 0 | YAML front matter | agents | Identity, sources with hashes, interfaces (see below). |
| 1 | Summary | all | One paragraph: what it does, for whom, when. |
| 2 | Rules at a glance | analyst | Table of every BR/VAL item with a one-line plain statement. |
| 3 | Actors and triggers | all | Who starts it and how (transaction ID, menu option, JCL step, scheduler), and the preconditions. |
| 4 | Interfaces | engineer | Screens with every field (position, length, attributes, direction), files and records with access mode and key, return codes, console output. |
| 5 | Data used | engineer | Fields read and written per entity, by reference to `entities.md` (`ENT-ACCOUNT.ACCT-CURR-BAL`), plus program-local working data that affects behaviour. |
| 6 | Processing flow | all | Numbered steps in plain language with a Mermaid diagram: screen states and keys for online programs, control flow for batch. |
| 7 | Business rules | all | `BR-` items. |
| 8 | Validations and messages | all | `VAL-` items, a decision table where there are several branches, and a message catalog with every user-facing text quoted exactly. |
| 9 | Side effects | engineer | `SE-` items: every read, write, rewrite, delete, lock and browse, with key, order and what happens on failure. |
| 10 | Error handling | engineer | `ERR-` items: every response code / file status branch, abends, return codes. |
| 11 | Interactions | architect | Inbound and outbound calls, XCTL/LINK, shared data with other components, invariants shared with them. |
| 12 | Numeric, date and format details | engineer | `NUM-` items: precision, sign, rounding and truncation, edit patterns, timestamp formats, padding. |
| 13 | Known defects and oddities | all | `DEF-` items, each with its consequence and a status: *open*, or *fixed in branch* with the commit. |
| 14 | Open questions | analyst | `Q-` items that only a person (business owner, mainframe team) can answer. |
| 15 | Equivalence contract | engineer, tester | What a reimplementation must reproduce exactly, what may differ (wall-clock timestamps, console text), and how to check it. |
| 16 | Paragraph coverage | auditor | Every paragraph, its line range, and the items that cover it. |

Online programs also get **"Module extraction notes"** (section 11) when they are candidates for selective conversion: everything that crosses the module boundary, so the module can be rebuilt alone and coexist with the rest.

### Front matter

```yaml
id: COBIL00C                  # PROGRAM-ID
title: Online bill payment
kind: online                  # online | batch | subprogram | utility | job | proc | csd | schedule
module: core                  # core | auth-ims-db2-mq | trantype-db2 | vsam-mq
unit_code: BILL               # 3-6 characters used in item IDs, unique
source_rev: b5e0139           # git commit the spec was written against
sources:                      # every file the behaviour depends on; the first is the primary source
  - {path: app/cbl/COBIL00C.cbl, sha256: b5c46039eb8f, role: program}
entry_points: [{type: cics-transaction, id: CB00}]
mapsets: [COBIL00]
copybooks: [COCOM01Y, CVACT01Y]
data_stores:                  # logical name, entity, access
  - {name: ACCTDAT, entity: ENT-ACCOUNT, access: [read-update, rewrite]}
calls: []                     # CALL / LINK targets
transfers_to: [COSGN00C]      # XCTL targets
invoked_by: [COMEN01C]
spec_status: draft            # draft | reviewed | verified
```

The `sha256` prefixes let the checker flag a spec as stale when its source changes (rule 1 of "Machine-checked rules").

## Item IDs and the item template

Every statement a reimplementer depends on is an *item* with a stable ID `<KIND>-<UNIT>-<NNN>`:

| Kind | Meaning | Example |
| :-- | :-- | :-- |
| `BR` | Business rule: a decision or calculation the business would recognise | `BR-POST-001` over-limit check |
| `VAL` | Validation with a user- or operator-visible outcome | `VAL-BILL-001` account ID required |
| `FLOW` | Navigation, key handling, pseudo-conversational state, batch control | `FLOW-BILL-002` PF3 returns |
| `SE` | Side effect on data | `SE-POST-002` account rewrite |
| `ERR` | Error and abend handling | `ERR-POST-008` abend with U0999 |
| `NUM` | Numeric, date or format detail that matters for equivalence | `NUM-POST-007` processing timestamp layout |
| `DEF` | Defect or oddity in the as-is behaviour | `DEF-BILL-001` payment continues after errors |
| `Q` | Open question | `Q-BILL-001` |

*Change from the suggested structure:* the brief proposed a single `BR-` series. Typed prefixes keep the same pattern but let agents and testers select "all validations" or "all side effects" across 40 programs, and they make the coverage table say what kind of behaviour each paragraph holds. IDs are never reused or renumbered; a removed item is marked *withdrawn*.

Each item uses the same small template:

```markdown
#### BR-POST-001 · Over-limit check (reason 102)
- **When:** <condition, in plain language, naming fields as Entity.FIELD>
- **Then:** <outcome>
- **Otherwise:** <outcome, if any>
- **Source:** `app/cbl/CBTRN02C.cbl:403-413`
- **Fidelity:** must-preserve | incidental | defect-decide
- **Confidence:** runtime-verified | reviewed | platform-inferred
```

- **Fidelity** tells the architect what to do with the item. *must-preserve*: it changes data or what a user sees. *incidental*: an artefact of the platform (CICS log lines, file status display formats) that the target may replace. *defect-decide*: a `DEF` whose fix or preservation is a business decision.
- **Confidence** separates what was proven from what is inferred. *runtime-verified*: checked against the GnuCOBOL runner or a test. *reviewed*: checked against the source by an independent reviewer. *platform-inferred*: depends on CICS, VSAM or z/OS semantics that cannot be run locally (for example `DUPKEY` on a non-unique alternate index), so it is a claim about the platform, flagged for the mainframe team.

## Writing rules

1. **Describe the as-is behaviour, not the intent.** If the code does something surprising, the rule says what the code does, and a `DEF` item says why it is surprising. Never "correct" behaviour silently in the spec; the architecture phase decides.
2. **Plain language first, names second.** "The account's current balance (`ENT-ACCOUNT.ACCT-CURR-BAL`)". Business names come from `entities.md`.
3. **Quote every user-facing text exactly**, in code format, including trailing dots, double spaces and capitalisation. Give field lengths so truncation is visible.
4. **Order matters; say it.** Side effects are listed in execution order, and the spec states what has already happened when a later step fails (no rollback in VSAM batch).
5. **Exact numbers.** Give precision as `decimal(p,s)` and the PICTURE; say whether a computation rounds or truncates, what happens on overflow, how the sign is stored and how a value is edited for display.
6. **Decision tables** replace nested prose whenever there are three or more branches (key handling, confirm values, file response codes).
7. **Cite source lines** for every item: `path:start-end` on the commit in `source_rev`. A citation points at the code that produces the behaviour, not at a comment.
8. **Copybooks and records are defined once**, in `entities.md`. Program specs list only the fields they read or write and what they do with them.
9. **No COBOL in the rules.** COBOL names appear as identifiers in code format, never as statements. Pseudocode is allowed in the processing flow when it is clearer than prose.
10. **Security-relevant observations** (missing authorisation, PII in logs or files) go in `DEF` with the tag `[security]`.

## Glossary for readers new to the mainframe

| Term | Meaning in these specs |
| :-- | :-- |
| CICS transaction | A 4-character code (`CB00`) that starts an online program on a terminal. |
| Pseudo-conversational | The program ends after each screen and is started again when the user presses a key; state survives only in the *commarea*. |
| Commarea | The block of data passed between screens and programs (`ENT-COMMAREA`). |
| BMS map | The screen layout; fields have a position, a length and attributes (protected, unprotected, colour). |
| AID key | The key the user pressed: Enter, PF3, PF4 and so on. |
| XCTL | Transfer control to another program, passing the commarea, without returning. |
| VSAM KSDS | An indexed file with a unique primary key; an *AIX* (alternate index) gives a second, possibly non-unique key. |
| Zoned decimal / overpunch | Numbers stored as digit characters with the sign folded into the last character (`{`, `A`-`I` positive; `}`, `J`-`R` negative in the ASCII sample data). |
| File status | Two-character result of a batch file operation: `00` OK, `10` end of file, `23` record not found, `22` duplicate key, `35` file missing. |
| RESP | The CICS equivalent: `NORMAL`, `NOTFND`, `DUPREC`, `DUPKEY`, `ENDFILE`. |
| U0999 | User abend 999, raised through `CEE3ABD`; the job step ends abnormally. |

## Evidence and verification

A spec moves from *draft* to *reviewed* when an independent reviewer has checked its items against the source (all items, or a sample of at least half of them chosen by the reviewer) and every WRONG finding is fixed. It moves to *verified* when, in addition, the behaviour that can run locally has been reimplemented from the spec alone and the outputs match the GnuCOBOL runner, on the sample data and on a data set the reimplementer has not seen. Record each check in `evidence/` and name the evidence file in the spec's equivalence contract. The pilot results are in [evidence/nl-pilot-check.md](evidence/nl-pilot-check.md).

## Machine-checked rules

`python3 docs/modernization/spec/tools/check_spec.py` checks every spec under `programs/` and `jobs/` and exits non-zero on any error. The rules:

1. **Front matter** is a YAML block between `---` lines at the top of the file, using only `key: value`, flow lists `[a, b]`, flow maps `{k: v}` and `- ` list items. Required keys: `id`, `title`, `kind`, `module`, `unit_code`, `source_rev`, `sources`, `spec_status`.
   - `id` equals the file name without `.md`.
   - `kind`: `online`, `batch`, `subprogram`, `utility` (programs) or `job`, `proc`, `csd`, `schedule` (jobs).
   - `module`: `core`, `auth-ims-db2-mq`, `trantype-db2`, `vsam-mq`.
   - `unit_code`: 3 to 6 capital letters or digits, starting with a letter, unique across all specs.
   - `spec_status`: `draft`, `reviewed`, `verified`.
   - `sources`: each entry has `path` (repository-relative, must exist) and `role`; `sha256` is the first 12 hex digits of the file's SHA-256 and must match the file as it is now (a mismatch means the spec is stale). An entry without `sha256` is a warning; use `lines: "..."` for a shared file such as the CSD.
   - **The first source is the primary source**: a program spec's COBOL program, a job spec's JCL member. Short citations and paragraph coverage refer to it.
2. **Items** are level-4 headings `#### <KIND>-<UNIT>-<NNN> · <title>`, with `KIND` one of `BR VAL FLOW SE ERR NUM DEF`, `UNIT` equal to `unit_code` and `NNN` three digits. Open questions are table rows `| Q-<UNIT>-<NNN> | ... |`. IDs are unique.
3. **References** to items (`BR-POST-001`, ranges such as `DEF-BILL-011..014`), entities (`ENT-ACCOUNT`) and invariants (`INV-001`) must resolve: items to any spec, entities to an `## ENT-<NAME> · ...` heading in `entities.md` or a proposal in `areas/`, invariants to `components.md`.
4. **Source citations** are code spans of the form `` `path:lines` `` or `` `:lines` `` (primary source), where `lines` is `N`, `N-M` or a comma-separated list of those. `path` is repository-relative or a file name that is unique in the repository. Every line must exist in the file and `N <= M`. Each `BR`, `VAL`, `FLOW`, `SE`, `ERR` item has a `**Source:**` line (a warning otherwise).
5. **Coverage** is section 16, a table whose rows are `` | `<PARAGRAPH>` | <start>-<end> | <items> | ``, plus free rows whose first cell is in parentheses, such as `(data division)`. For a COBOL primary source every paragraph and section of the PROCEDURE DIVISION has exactly one row; `start` is the line of its label, `end` is its last code line before the next label (comments and blank lines excluded); the items cell is not empty and every ID in it resolves. A row for a name that is not a paragraph is an error. For a JCL primary source the rows are the job steps (`//NAME EXEC`), with the same rules.

`--require-all` also fails when an in-scope source (every COBOL program under `app/`, every JCL member and procedure) has no spec; `--program NAME` limits the run to some specs; `--json FILE` writes the coverage report.

## Scaling to the full inventory

- One spec per COBOL program (44 under `app/`, base application and the three optional modules) and per JCL job or procedure that carries logic; copybooks, files, tables and segments go into `entities.md` as programs are processed.
- Online programs reuse the pilot's screen section; batch programs reuse the pilot's control-flow, side-effect and equivalence sections.
- Equivalence checks: every batch program that runs locally gets a spec-only reimplementation compared with the runner, as in the pilot (`checks/`). Online programs get scenario tables (inputs, key, expected screen and data) that become acceptance tests for the React and Spring Boot rewrite.
