# reference-service: architect rulings on the contract

Status: **approved with the rulings below**, 2026-11-11. The contract reviewed is the reference-service contract v1. It covers the units TTLS (COTRTLIC, transaction type list) and TTUP (COTRTUPC, transaction type maintenance) and the read-only `ref` reads of its section 7. TTBU (COBTUPDT) is out of scope (D-66).

The orchestrator approved every ruling on 2026-11-11 under the standing delegation. Coding may start once the orchestrator has recorded the DEF-TTUP-008 entry of section 2. The agent writes these rulings into the contract as v2 before the first unit merges. A ruling here overrides the contract where the two differ. The check against the specs, the decision catalog and switches.md found no code switch and no new switch: every item is built in its decided mode only, as the contract's section 1 says.

Everything in the contract that is not named below is approved as drafted. That covers the service settings, the roles and the absence of a sign-on bypass, the code normalisation apart from section 2, the description rules and the filter (D-65), the versions and `If-Match` (D-17), the vanished-row outcome, the batch window, rollback, the lock timeout, the audit, the check orders, the web behaviour, the scenarios and the item tables.

## 1. The lookup and category reads are not built now (question 1)

The four reads of the contract's section 7 have no consumer. Account update validates states, ZIP prefixes and area codes with the lists bundled in `platform/validation`, on the page and in the service. No screen lists categories, because category maintenance was not chosen (D-64). The refused delete already returns the type's categories in its problem.

- **Ruling.** `GET /api/reference/lookups/{states|state-zip-prefixes|area-codes}` and `GET /api/reference/transaction-categories` are **deferred until a consumer is named**. They are not built, tested or documented in the service's OpenAPI document. [api-security.md](api-security.md#reference-service) marks the two rows as deferred. The architecture keeps them as the intended shape for a later consumer.
- **Single source.** `platform/validation`'s bundled lists stay the only lists that validate input. The `ref` lookup tables (`V303`) stay as loaded reference data. A batch-service test already pins their content to the bundled lists, so the two cannot drift.
- **The contract's section 7 and its error `REFERENCE_READ_FAILED`** move to an appendix marked "deferred". Nothing else in the contract depends on them.

This is not a scope cut of a spec unit. No program spec has these reads. They were an architecture convenience with no user.

## 2. `.5` as a type code (question 2, DEF-TTUP-008)

The spec calls `.5` "a code the zero check should have refused" (DEF-TTUP-008, VAL-TTUP-003): NUMVAL gives 0.5, the zero check passes, and the legacy code stores `00`, which the list cannot filter on.

- **Ruling: approved as drafted.** A code whose integer part is 0 is refused as zero (`400 TYPE_CODE_ZERO`, `Tran Type code must not be zero.`). That includes `.5`, `0.`, `-0` and `00`. The other NUMVAL forms that NUM-TTUP-001 observed are kept: `-1` gives `01`, `+3` gives `03`, `1.` gives `01`. Refusing every sign and decimal point would change accepted inputs that store a valid code, which no decision asks for.
- **Scope.** Only the zero case changes. No switch is added, and no other numeric form changes its meaning.
- **Ledger.** DEF-TTUP-008 gets a `fixed` entry, with the note "only the zero case changes: an integer part of 0 is refused as zero; the other sign and decimal forms are kept per NUM-TTUP-001". The item has no catalog decision. The orchestrator records this as a minimal defect fix under the standing delegation: it removes a stored value that no screen can reach, and no valid input changes.
- **Tests.** `.5`, `0.`, `-0` and `00` give `TYPE_CODE_ZERO`. `-1`, `+3`, `1.`, ` 1` and `5 ` give `01`, `03`, `01`, `01` and `05`. They run on the path segment and on an add's `typeCode`.

## 3. The delete text names the categories (question 3, D-64)

D-64 decided "keep refusing, with a message naming the categories".

- **Ruling: approved as drafted.** The problem's `message` keeps the legacy lead text and names the first three category codes in ascending order, followed by ` +<n> more` when there are more: `Please delete associated child records first: 0001, 0002, 0003 +2 more`. The longest form is 74 characters, so it fits the 75-character message line. The problem's `categories` lists every category with its description, and both pages show that list under the message. Together they name the categories, which is what D-64 asks. Category descriptions do not fit in the message line.
- **Test.** A unit test of the text for 9999 categories (` +9996 more`) gives 74 characters.

## 4. Message after a delete from the list (question 4)

- **Ruling: approved.** After a successful delete from the list, the page is re-read without the row (DEF-TTLS-008 fixed per D-75), and the information line shows `Delete successful.`, TTUP's legacy text. The list's `HIGHLIGHTED row deleted.Hit Enter to continue` describes a highlighted row that is no longer on the page, so it would be false. No new text is invented.

## 5. One text per outcome on both pages (question 5)

- **Lock: approved.** Both pages show `Could not lock record for update` (ERR-TTUP-002) for `409 TYPE_LOCK_FAILED`. The list's `Deadlock. Someone else updating ?` named Db2's -911, which covered both a deadlock and a timeout. The target's outcome is a lock timeout (SQL Server error 1222), not a deadlock, so the list's text would be wrong. The list's text keeps no Db2 detail in either case (D-58).
- **Vanished row: approved.** Both pages show the list's `Record not found. Deleted by others ?` (ERR-TTLS-002) for `404 TRANSACTION_TYPE_DELETED`. TTUP has no legacy text for this outcome, because its update of a missing row inserted it (BR-TTUP-002), and the target splits create and update (D-17).
- **No change: approved.** Each page keeps its own full legacy text for `400 NO_CHANGE`. The API returns TTUP's `No change detected with respect to values fetched.`, and the list page shows `No change detected with respect to database values.` as its web variant (catalogue key `TT_LIST_NO_CHANGE`, web only).

## 6. Description column type (question 6)

The contract's "either works" is not accepted. The online write rule is not the only writer.

- **Ruling.** `ref.tran_type.description` and `ref.tran_category.description` are `NVARCHAR(50)`, as the [text repertoire inventory](data-model.md#inventory) already lists them in stage 1. The loader reads `trantype.txt` and `trancatg.txt`. Under the text policy (rules 1, 5 and 6), every input path keeps the full ISO-8859-1 range: 50 characters, any of U+0000 to U+00FF, and load then export reproduces the file byte for byte. `VARCHAR(50)` under the database's UTF-8 collation holds 50 bytes, so it refuses a 50-character description with an accented letter. Under `Latin1_General_100_BIN2` it is code page 1252, which stores U+0080 to U+009F as `?`. The sample holds only ASCII, but the promise is not limited to the sample.
- **Owner.** Data adds the migration that changes both columns from `VARCHAR(50)` to `NVARCHAR(50)`, at the version the orchestrator reserved for it: `V421` (after `V418` account update, `V419` statements and `V420` output root). The table row in [data-model.md](data-model.md#reference-identity-and-module-tables) now says `NVARCHAR(50)` too; it had contradicted the inventory. Backend changes nothing in the service. It binds strings as Unicode, the mssql-jdbc default, which is never turned off. `CHARINDEX(UPPER(:filter), UPPER(description))` works unchanged on `NVARCHAR`.
- **The online rule does not widen.** Writes accept only `A`-`Z`, `a`-`z`, `0`-`9` and space (D-65 keeps the character set). A loaded description outside that set is shown as stored. An update replaces it only with a valid value. The change test compares it as stored.
- **Tests.** The data round-trip fixture of [data-model.md](data-model.md#fixture-tests) covers both files, with all 256 byte values in the description and a 50-character accented description. A page and an API read of a loaded description with U+00E9 return it unchanged.

## 7. Feature flag (question 7)

- **Ruling.** The rule is the same as for authorization ([authorization-service.md](authorization-service.md), B7). `carddemo.identity.features.transactionTypes` stays `false` in every profile, and in the web mock, until reference-service's API and its two web pages have both merged. A backend merge alone does not enable it. The deferred reads of section 1 are not part of the condition. Enabling it is a later identity-service configuration change, owned by identity. `scripts/stack.py` already lists reference-service (port 28085), so infra has nothing to add. When the service is not built, it is not started, and the flag stays off.

## 8. Ledger entries (for the orchestrator to route)

The contract's section 9 is approved, with question 2 resolved: DEF-TTUP-008 is `fixed` with the note in section 2. The deferred reads of section 1 need no ledger entry, because they carry no spec item.
