# Ledger checks and traceability

Tools behind `make -C modernization verify-ledger` and `make -C modernization trace`. Python 3.11 or newer, standard library only.

```sh
python3 modernization/verify/trace/verify_ledger.py            # what make verify-ledger runs
python3 modernization/verify/trace/verify_ledger.py --no-code  # the ledger against the spec only
python3 modernization/verify/trace/trace_matrix.py             # what make trace runs
python3 -m unittest discover -s modernization/verify/trace/tests
```

`tracelib/` holds the shared code: `spec.py` reads the item IDs, fidelity classes, units and scenarios through the spec checker's own loader (`docs/modernization/spec/tools/check_spec.py`), so an ID is defined here exactly when `make spec-check` accepts it; `ledger.py` loads and checks the three ledger files; `catalog.py` reads the decision IDs of the catalog and the architecture-dependent table; `code.py` finds the fidelity switches in the Java code and the `carddemo.fidelity.*` keys in the application configuration.

## The ledger files

`modernization/ledger/` records what the business decided for the spec items. Every file starts with `schema = 1`. Dates are `YYYY-MM-DD` strings (a TOML date is accepted too).

### fidelity.toml

One table per defect-decide item that keeps a legacy mode, and per decided question of a converted unit:

```toml
[switch."DEF-BILL-010"]
value = "fixed"          # legacy | fixed
code_switch = true       # false: decided, no branch in code
decision = "D-28"
approved_by = "user (decision catalog answers)"
approved_on = "2026-11-06"
```

| Field | Required | Meaning |
| :-- | :-- | :-- |
| `value` | yes | `legacy` (the COBOL behaviour) or `fixed` (the fix the item describes). `platform/fidelity` loads it into `carddemo.fidelity.<ID>`; a missing entry means `legacy`. |
| `code_switch` | yes | `true` when the code branches on the item through `FidelitySwitches`: exactly the items of `docs/modernization/architecture/switches.md` (targeted policy). `false` when the item is built and tested in its decided mode only. |
| `setting` | no | The chosen variant when the fix has more than one, for example `WRITES_BLOCKED` for the batch window. Only with `value = "fixed"`. |
| `decision` | yes | A decision of `docs/modernization/decisions/README.md` (`D-28`), or `none`. |
| `approved_by`, `approved_on` | yes | Who approved the value, and when. |
| `note` | no | Why, when the value is not the plain fix. `Covered by the switch <ID>` marks an item with no switch of its own whose tests are the switch's: `code_switch` must be `false`, `<ID>` must be a code switch, and trace requires passing tests in both modes that cite the item. |

### deviations.toml

One table per item that the target does not reproduce, so it has no legacy mode and no switch:

```toml
[deviation."DEF-BILL-001"]
kind = "architecture-dependent"
decision = "D-29"
target = "All or nothing: one database transaction."
approved_by = "user (decision catalog answers)"
approved_on = "2026-11-06"
```

| Field | Required | Meaning |
| :-- | :-- | :-- |
| `kind` | yes | `architecture-dependent` (the mechanism behind the behaviour is gone, including the platform items of the catalog's Appendix B), `security-c1` (access model C1), `incidental-ui` (a screen or commarea artefact), `decision` (a catalog decision changes it). |
| `decision` | yes | A catalog decision, or `none`. |
| `target` | yes | What the target does instead. |
| `legacy` | no | What the COBOL does, when the item title does not say it. |
| `group` | no | The shared reason when several items deviate for it (`DEV-C1`, `DEV-ARCH-08`, `DEV-PLATFORM`). |
| `scenarios` | no | Equivalence scenarios whose legacy result the target does not reproduce, as `"<spec> <scenario>"`, for example `"COSGN00C S10"`. |
| `source`, `note` | no | Where the reason is written down; anything else. |
| `approved_by`, `approved_on` | yes | Who accepted the deviation, and when. |

An item may have both a fidelity entry and a deviation (a decided question whose answer also changes a flow, such as Q-MENU-003), but an architecture-dependent item cannot be a code switch.

### scope.toml

One table per converted unit:

```toml
[unit.MENU]
spec = "docs/modernization/spec/programs/COMEN01C.md"
excluded = { "BR-MENU-002" = "options 1-9 and 11 not converted" }
```

| Field | Required | Meaning |
| :-- | :-- | :-- |
| `spec` | yes | The spec file of the unit. |
| `excluded` | no | Items of the unit that the conversion leaves out, each with the reason. |

## make verify-ledger

`verify_ledger.py` prints each problem, a summary line `ledger: ...`, and ends with `verify-ledger: OK` (exit 0) or `verify-ledger: FAILED (<n> problems)` (exit 1). It checks:

- **Spec.** Every fidelity entry names a defect-decide item or a question the spec defines; every deviation names an ID the spec defines (item, question, invariant or proposed invariant); every defect-decide item has a fidelity entry or a deviation; scenarios exist in their spec; every unit in `scope.toml` is the unit code of the spec it names, and every excluded ID belongs to that unit.
- **Approval.** Every entry and deviation has a `decision` that the catalog defines (or `none`), `approved_by` and a valid `approved_on`; no unknown fields.
- **Architecture.** Every item of the architecture-dependent table in `docs/modernization/architecture/decisions.md` is defined and has a deviation of kind `architecture-dependent`.
- **Code** (everything under `modernization/`, main sources only). Every switch the Java code declares (`FidelitySwitch.of("ID", ...)`, `new FidelitySwitch("ID", ...)`) or reads by literal ID (`.mode("ID")`, `.fixed("ID")`, `.isFixed("ID")`, `.isLegacy("ID")`) has a fidelity entry with `code_switch = true`. A `carddemo.fidelity.<ID>` key in the default profile of a main `application.yml`, `application.yaml` or `application.properties` must name an entry and equal its `value`, because an explicit property overrides the ledger. An entry with `code_switch = true` that no code declares yet is a warning, not a failure.

## make trace

`trace_matrix.py` (named so that it does not shadow Python's own `trace` module) reads the spec, the ledger and every test report under `modernization/`, writes `modernization/build/trace/matrix.md` and `matrix.json`, prints the gaps and ends with `trace: OK` (exit 0) or `trace: FAILED (<n> gaps)` (exit 1).

```sh
python3 modernization/verify/trace/trace_matrix.py                         # what make trace runs
python3 modernization/verify/trace/trace_matrix.py --unit BILL             # gate only Bill Pay (repeatable)
python3 modernization/verify/trace/trace_matrix.py --reports path/to/dir-or-file ... --out /tmp/trace
python3 modernization/verify/trace/trace_matrix.py --unit BILL --manifest build/test-billpay/manifest.json --out build/test-billpay/trace
```

`--unit` takes a unit of `scope.toml` (any other unit fails) and can be given more than once. Only the gaps of the named units count for the gate and the exit status: live code switches of other units (see Gate), and undefined IDs that neither look like an ID of the unit nor share a test with one of its items, are listed under "Gaps of units outside the gate" instead. An unreadable report is a gap whatever the selection, because it may hold the unit's results.

**Run manifest.** A runner that gates its own tests, such as `make test-billpay`, passes `--manifest FILE` (not combinable with `--reports`). The trace then reads only the report files the run wrote, so a report left on disk by an earlier, unrelated run (for example another browser's Playwright run) neither covers nor breaks the run's gate. Without a manifest, `make trace` still reads every report on disk, so such a report keeps the top-level gate red until a valid run replaces it.

```json
{"schema": 1, "run": "test-billpay", "started_ns": 1762795547123456789,
 "reports": [
  {"path": "services/billpay-service/target/spec-items", "required": true,
   "before": [{"file": "org.carddemo.billpay.BillPayApiTest.jsonl", "mtime_ns": 1762795001000000000, "size": 18234, "sha256": "…"}]},
  {"path": "web/playwright-report/e2e-mock-fixed.json", "required": true, "before": []},
  {"path": "platform/test-support/target/spec-items", "required": false, "before": []}]}
```

| Field | Meaning |
| :-- | :-- |
| `started_ns` | When the run began, `time.time_ns()`, taken before its first test step. |
| `base` | Optional; the directory that relative paths start from, relative to the repository root (default `modernization`). |
| `reports[].path` | A report file, or a directory searched like any report directory (it may itself be a `spec-items` or `test-results` directory). |
| `reports[].required` | `true` when the run must have produced it. |
| `reports[].before` | The report files already at the path when the run began: `file` (relative to a directory path, or the file's own name), `mtime_ns`, `size`, `sha256`. |

`tracelib/manifest.py`'s `begin(manifest_path, repo_root, run, [(path, required), ...])` takes `started_ns` and the `before` fingerprints and writes the file; a runner may also write the same JSON itself.

A file at a listed path is **current** when its modification time is at or after `started_ns`, compared in nanoseconds, and it differs from its `before` fingerprint. Only current files are read; the others are **stale**, not read, and listed in the matrix. The gate fails closed: an invalid or unreadable manifest ends with `trace: FAILED (the run manifest is not valid)`, and a required path that is missing, holds no current file, or whose current files carry no test result citing an item is a gap (`report: required report … is missing`, `… is stale`, `… holds no report file`, `… has no test result citing an item`). A failing test in a current report is a gap as always. Every other report file under `modernization/` is listed under "Reports outside this run (not read)" with its failed count, and trace prints a warning naming the ones with failing or unreadable results.

Limits: freshness trusts the file system's modification times and the runner's clock. A file system with coarse times (HFS+, FAT, some network mounts), or a kernel that stamps files from a coarse clock, can date a file the run wrote before `started_ns`; it then counts as stale and the gate fails, which errs on the safe side. A file server whose clock runs ahead of the runner's can make an older file look current; the `before` fingerprint still marks it stale while its content and time are unchanged. Output paths that are new for each invocation avoid both; where a path is fixed (Maven's `target/spec-items`), the fingerprint is what tells an old file from a new one.

**Reports.** Files under any `spec-items`, `test-results`, `playwright-report`, `surefire-reports` or `failsafe-reports` directory, including a `--reports` or manifest path that is itself such a directory (`node_modules`, `.git` and `var` are skipped):

| Format | Items | Mode |
| :-- | :-- | :-- |
| `*.jsonl` from `platform/fidelity` (`<module>/target/spec-items/<TestClass>.jsonl`): `{"class", "method", "displayName", "items", "status", "mode"}` | `items` | `mode` (`LEGACY`, `FIXED` or null) |
| JUnit XML: Maven Surefire, Vitest's `junit` reporter, `pytest --junitxml` | `@<ID>` tags in the test name, and `<property>` elements named `spec`, `spec_item`, `items`, `tag` or starting with `spec` (pytest `record_property("spec", "INV-052")`) | a `mode` property, an `@legacy`/`@fixed` tag, or `[LEGACY]`/`[FIXED]` in the name |
| Playwright JSON reporter | `@<ID>` tags in the suite and test titles, and the test's `tags` | an `@legacy`/`@fixed` tag |

A test counts as passed, failed or skipped; a skipped test proves nothing.

Only the tag forms above count. An ID written in brackets in a test name or title (`[FLOW-BILL-002] shows the header`) is not a citation; trace prints `warning: <n> tests carry bracketed IDs that are not counted` with the tests and the IDs, and lists them in the matrix, so a wrong tag form is visible. A mode written as a word (`BILL-S5 fixed:`) is not a mode either; use `@fixed`.

**Gate.** For every unit in `scope.toml`:

| Item | Needs |
| :-- | :-- |
| excluded in `scope.toml` | nothing |
| has a deviation | nothing (tests are listed if any cite it) |
| code switch (`code_switch = true`), item covered by a switch, or defect-decide with no fidelity entry | passing tests in legacy and in fixed mode |
| defect-decide with a fidelity entry | a passing test in the entry's mode (a test without a mode counts) |
| must-preserve, or no fidelity class in the spec | a passing test |
| incidental only, question, invariant | nothing |

In addition: a failing test that cites a required item is a gap; every item of the architecture-dependent table needs a deviation; every live code switch, and every item covered by one, needs both modes, whatever its unit; and any test that cites an ID the spec does not define is a gap, in or out of scope. An unreadable report is a gap too.

A code switch of a unit outside `scope.toml` (and an item covered by one) is **live** when the main code declares or reads the switch (the same scan as verify-ledger's Code check), when the switch's unit is in scope, or when any test cites the switch or the item. A live one gates `make trace` like an item in scope, because shipped code runs it in both modes; BR-TRNA-003 (ledger-core, read by Bill Pay) and DEF-ACUP-002 (batch data load) are live this way. One that is not live has no code to test yet: its gap is listed under "Planned code switches (not gated, not delivered)" in the matrix and counted on the console, and it gates as soon as it becomes live. A switch of a unit in scope gates whether or not the code declares it. If the scan finds no main Java at all, every switch is treated as live.

**Matrix.** `matrix.json` has the gate result (`ok`, the gated `units`, `gaps`, `ungated_gaps`, `planned` and the `declared_switches` the code scan found, or null when it found no Java), the units in scope, the tests with bracketed IDs (`uncounted_bracketed`), the report files, a status count per unit, and one record per spec item (and per question or invariant that the ledger or a test names): unit, kind, title, fidelity text and classes, ledger entry, deviation, exclusion, requirement, status (`covered`, `gap`, `deviation`, `excluded`, `not-required`, `out-of-scope`, `planned`) and the tests with their result and mode. `matrix.md` shows the gaps, the gaps outside the gate, the planned code switches, the tests with bracketed IDs, a table per unit in scope, the code switches, and the cited items of other units.
