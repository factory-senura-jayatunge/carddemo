# CardDemo natural-language specification

This folder holds the structured natural-language (NL) specification of CardDemo: what every COBOL program, JCL job and data store does today, written so that a business analyst can confirm it, an engineer can reimplement it from the text alone, and an agent can reason over the whole system. It is the input to the target architecture (React front end, Spring Boot services, SQL Server, Spring Batch with Airflow, RabbitMQ); it does not propose that architecture.

The spec describes the **as-is** behaviour at a given commit, defects included. Where the code is surprising, the spec says what the code does and adds a `DEF` item; it never corrects behaviour silently.

## Contents

| Path | What it is |
| :-- | :-- |
| [format.md](format.md) | The format: sections, front matter, item template, writing rules, machine-checked rules. Read this first if you write specs. |
| [programs/](programs/) | One spec per COBOL or assembler program, `programs/<PROGRAM-ID>.md`. |
| `jobs/` | One spec per JCL job, procedure, CSD group or schedule that carries logic, `jobs/<NAME>.md` (created by the first job spec). |
| [entities.md](entities.md) | Entity catalog: every record layout, file, table and segment, with keys and relationships. Specs refer to entities as `ENT-<NAME>`. |
| [components.md](components.md) | Component map: programs, entry points, data stores, CRUD matrix, cross-component invariants (`INV-NNN`), candidate seams. |
| [interactions.md](interactions.md) | Interaction view: online navigation, batch cycle, cross-module flows. |
| [areas/](areas/) | Proposals from the area teams for the three system artifacts above. |
| [evidence/](evidence/) | Results of independent reviews and spec-only reimplementations. |
| `tools/check_spec.py` | Checker for front matter, IDs, references, source citations and paragraph coverage. |

## How to read a program spec

1. **Summary** and **Rules at a glance** (sections 1-2) say what the program does in a few lines each.
2. **Actors, interfaces, data used and processing flow** (sections 3-6) give the screens, files, fields and the order of events, with a Mermaid diagram.
3. **Items** (sections 7-13) are the precise statements, each with an ID, the source lines it comes from, a fidelity and a confidence.
4. **Open questions** (section 14) are the decisions only a person can take.
5. **Equivalence contract** (section 15) says what a reimplementation must reproduce exactly.
6. **Paragraph coverage** (section 16) proves that no COBOL paragraph was skipped.

## Item IDs

Every statement is an item `<KIND>-<UNIT>-<NNN>`, for example `BR-POST-001` (business rule 1 of POSTTRAN):

| Kind | Meaning |
| :-- | :-- |
| `BR` | Business rule |
| `VAL` | Validation with a visible outcome |
| `FLOW` | Navigation, keys, state, batch control |
| `SE` | Side effect on data (read, write, lock) |
| `ERR` | Error and abend handling |
| `NUM` | Numeric, date or format detail |
| `DEF` | Defect or oddity in the as-is behaviour |
| `Q` | Open question |

`UNIT` is the spec's `unit_code` from its front matter (`POST` for CBTRN02C, `BILL` for COBIL00C). IDs are never reused or renumbered; a removed item is marked *withdrawn*. System-level IDs: `ENT-<NAME>` for entities, `INV-NNN` for cross-component invariants.

Each item carries:

- **Source:** `path:start-end` at the commit in `source_rev`; `:start-end` means the spec's primary source.
- **Fidelity:** *must-preserve* (changes data or what a user sees), *incidental* (platform artefact the target may replace) or *defect-decide* (a defect whose fix is a business decision).
- **Confidence:** *runtime-verified* (checked with the GnuCOBOL runner or a test), *reviewed* (checked against the source by an independent reviewer) or *platform-inferred* (depends on CICS, VSAM, IMS, DB2, MQ or z/OS behaviour that cannot run locally).

## Lifecycle

| Status | Meaning |
| :-- | :-- |
| `draft` | Written by the area agent; the checker passes. |
| `reviewed` | An independent reviewer checked the items against the source and every WRONG finding is fixed. |
| `verified` | Also reimplemented from the spec alone and matched against the GnuCOBOL runner on sample and unseen data (batch programs that run locally). |

The status is the `spec_status` field of the front matter; the evidence for `reviewed` and `verified` is in `evidence/`.

## Who edits what

- Area agents write `programs/*.md` and `jobs/*.md` for their area, and put proposed entity and interaction additions in `areas/<area>-entities.md` and `areas/<area>-interactions.md`.
- The spec lead owns `format.md`, `entities.md`, `components.md`, `interactions.md` and the checker, and merges the area proposals.
