# Ledger checks and traceability

Tools behind `make -C modernization verify-ledger` and `make -C modernization trace`. Python 3.11 or newer, standard library only.

```sh
python3 modernization/verify/trace/verify_ledger.py            # what make verify-ledger runs
python3 modernization/verify/trace/verify_ledger.py --no-code  # the ledger against the spec only
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
| `code_switch` | yes | `true` when the code branches on the item through `FidelitySwitches`; `false` when the behaviour is decided but the code has no branch, or none yet. |
| `setting` | no | The chosen variant when the fix has more than one, for example `WRITES_BLOCKED` for the batch window. Only with `value = "fixed"`. |
| `decision` | yes | A decision of `docs/modernization/decisions/README.md` (`D-28`), or `none`. |
| `approved_by`, `approved_on` | yes | Who approved the value, and when. |
| `note` | no | Why, when the value is not the plain fix. |

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
