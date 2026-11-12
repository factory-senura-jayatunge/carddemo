# account-service: architect rulings on the contract

Status: **approved with the rulings below**, 2026-11-10. The contract under review is the backend's account-service contract v4. It covers five units: ACVW (COACTVWC), ACUP (COACTUPC), CRDL (COCRDLIC), CRDV (COCRDSLC) and CRDU (COCRDUPC).

Coding of all five units may start now. Backend writes these rulings into the contract as v5 before the first unit merges. A ruling here overrides the contract where the two differ. The item coverage was checked unit by unit against the program specs, the ledger and the decision catalog: every spec item ID is in the contract's tables, and no table lists an ID the spec lacks.

## 1. Rulings for every unit

### Access: ADMIN reads, USER writes (user decision on D-09)

D-09 says an `ADMIN` may unmask, and it affects the React components. Under C1 an `ADMIN` uses only the admin menu, so the contract as drafted had no screen on which anyone could reveal a value. The user ruled on 2026-11-10:

- `viewAccount`, `listCards`, `lookupCard` and `viewCard` accept `USER` and `ADMIN`.
- `fetchAccountForUpdate`, `validateAccountUpdate`, `updateAccount`, `lookupCardForUpdate`, `fetchCardForUpdate`, `validateCardUpdate` and `updateCard` stay `USER` only. An `ADMIN` token gets `403 ACCESS_DENIED`.
- `unmaskAccount` and `unmaskCard` stay `ADMIN` only. A `USER` token gets `403 ACCESS_DENIED`.
- The admin menu gets option 7, `Account and Card View`, routed to `/admin/lookup`. That page links to account view, card list and card view ([web.md](web.md#routes)). For an `ADMIN`, those pages are read-only: the card list shows only the View action, and every masked field has a Show control that calls the unmask endpoint for that field. A `USER` sees no Show control.
- A `USER` on account update replaces SSN, date of birth, government ID and EFT account without seeing the stored value. The masked fields are write-only (contract ACUP question 5, approved).

### Audit

- Only an unmask writes an audit row for a read. A masked view writes none. "Every view is audited" in D-09 means every reveal: the option it accepted says "log the reveal", and D-SEC-05 and D-NFR-10 say "every unmasked view". The DEV-C1 target text in `deviations.toml` is corrected to match (section 7).
- Each save writes one `ops.audit` row with `action = 'UPDATE'` and the changed field names, never the values (D-56).
- Every `ops.audit` row has its time (`occurred_at`, database time). `ops.audit` comes from the platform foundation migration `V004` (billpay-slice.md, Flyway ranges). account-service depends on it and adds no migration. The contract's §4 must describe it that way.
- When the audit insert fails, the unmask answers `500 UNMASK_AUDIT_FAILED` everywhere. CRDV S22, which expects `CARD_LOOKUP_FAILED`, is corrected.

### Messages (D-58 and the read-error texts)

D-58's fix is "plain messages; log the technical detail", and its question names both response codes and file names. The not-found and read-error texts therefore name no file. These texts replace the contract's proposals:

| Case | Text | Severity |
| :-- | :-- | :-- |
| Account not found (ACVW, ACUP) | `Account <id> not found.` | error |
| Customer named by the cross-reference not found | `Customer <custId> not found.` | error |
| Account with no card (ACVW, D-61) | `No card is linked to this account.` | information |
| Account read failure | `Error reading account data` | error |
| Customer read failure | `Error reading customer data` | error |
| Cross-reference read failure | `Error reading card cross-reference data` | error |
| Card read failure (CRDL, CRDV, CRDU) | `Error reading card data` | error |

The other new texts of the contract are approved as one catalogue addition:

- the unmask texts;
- the ACUP range, read-only, length, Zip+4 and phone-prefix texts, including `Account Group must not be longer than 10 characters.` and `Last Name must not be longer than 25 characters.`;
- the CRDU expiry and length texts;
- the key texts `Account number must be a non zero 11 digit number` and `Card number must be a non zero 16 digit number`, which card view and card update share;
- `Paging position is no longer valid. Start again from page 1` and `Page size must be between 1 and 7`;
- the new date texts in section 3.

### Saves

- **Batch window.** Each save runs `BatchWindowGuard.requireWritesAllowed()` inside its transaction, after the request checks and before the first `UPDLOCK` read. The ACUP save steps are reordered to match §1 of the contract.
- **Lock timeout.** Each save waits up to 5 seconds for a row lock, then answers the unit's lock-failed code. This is one setting for the service (approved).
- **Record changed keeps the edits in both update units.** D-17 keeps the user's edits for review, and D-68 ("a failed save keeps the entered values") answers Q-CRDU-007, which the contract had marked as having no decision. A `409 RECORD_CHANGED` from card update therefore returns `current`, and the page shows the current values with the user's edits kept for review, as in account update. BR-CRDU-005 keeps its observable parts: nothing is written, and the current values and the message are shown. The ledger gets a `fixed` entry for it under D-68 (section 7).
- **Version checked at validate.** Both `validate` endpoints check `version` and answer `409 RECORD_CHANGED` on a stale form, as well as the save (approved).
- **Invalid keys.** With the 3270 shortcuts on, every key except Enter and the keys the screen names shows `Invalid key pressed. Please see below...`. "Every key other than F3, F5, F12" also excludes Enter.

### Deviations and the ledger

A "deviation" label in an item table needs a `deviations.toml` entry. Without one, switches.md applies and the item keeps the COBOL behaviour. Section 7 lists the entries the ledger owner adds. An entry whose item keeps an outcome of its own gets `retained` and `requires_test = true`, so the kept part stays tested ([transaction-service.md](transaction-service.md#9-partial-deviations-what-stays-tested)). Where the spec already classes an item as incidental, the table says "incidental" and needs no entry: ERR-CRDL-004, ERR-CRDU-003, NUM-ACUP-007, NUM-ACUP-008, NUM-CRDL-006, NUM-CRDU-005 and ERR-CRDV-001. Those rows cite the spec's incidental class, not D-58, which lists only ACVW items. ERR-CRDL-001 is split: its error texts are incidental in the spec, but what the list shows after a read failure is not text, so it gets an entry ([section 4](#4-crdl-card-list)).

### Citations to correct (no behaviour change)

- BR-ACUP-004: "fixed per D-75".
- BR-ACUP-001, FLOW-ACUP-010, SE-ACUP-001 to SE-ACUP-003: the account is read first, carried by DEF-ACUP-012 under D-61.
- BR-ACUP-110: carried by DEF-ACUP-112 under D-49.
- DEF-ACUP-013: re-validation covers changed fields only, under D-49.
- Q-ACUP-004 and Q-CRDU-005: D-03 only.
- Q-ACUP-103: D-08 only.
- Q-ACUP-107: D-50, with non-US addresses deferred by D-50.
- References to "00-common": §1 and §2 of the contract.

## 2. ACVW (account view)

- **A missing customer stops the view (Q-ACVW-004, VAL-ACVW-005).** Appendix A of the catalog lists Q-ACVW-004 under D-75, and D-75 is decided as fix all. When the cross-reference names a customer that does not exist, the response is the not-found problem only, with no account panel. The ledger has no entry for VAL-ACVW-005, so it gets a `fixed` entry under D-75 (section 7). The contract's open question 2 is answered this way.
- **Customer read failure:** `500` with only the message (question 5, confirmed under the same D-75 item).
- **Account with no card (D-61).** This is a valid state, not an error. The account panel is shown with `No card is linked to this account.` as information, and the customer and card panels stay empty.
- **Unknown account:** the account is read first (D-61), so the account not-found text applies (question 6, confirmed).
- **BR-ACVW-005.** After `ACCOUNT_NOT_FOUND`, the typed account ID stays in the field, as legacy does. Only the customer and card panels clear (DEF-ACVW-018).
- **ERR-ACVW-004, ERR-ACVW-003, FLOW-ACVW-008:** these get architecture-dependent deviation entries (section 7). The session survives an unexpected `500`.

## 3. ACUP (account update)

- **D-49 changed-fields-only validation and the FormRules extensions** `validate(..., changedFields)`, `notAbove` and `nanpPrefix`: approved. Backend adds them to `platform/validation` with the TypeScript mirror and `rule-vectors.json` before the unit is built.
- **Dates (D-50; VAL-ACUP-005 and DEF-ACUP-120 are `fixed` in the ledger; Q-ACUP-104 and Q-ACUP-108 belong to D-50).** The contract's proposal, which kept the 1900-2099 window and added no cross-date rules, is not approved. `fixed` means the fix the item describes, and DEF-ACUP-120 names four rules. These rules apply:
  - Every year is 4 digits and not before 1900. The upper limit 2099 is dropped, so `Expiry Date` and `Reissue Date` may be after 2099. Years before 1900 keep the legacy text.
  - `Date of Birth` may be today, but not after today. There is no minimum age.
  - `Open Date` must not be after today: `Open Date:cannot be in the future`.
  - `Expiry Date` must be after `Open Date`: `Expiry Date:must be after Open Date`.
  - `Reissue Date` must be on or after `Open Date` and on or before `Expiry Date`: `Reissue Date:must be between Open Date and Expiry Date`.
  - Each cross-date rule runs after that date's own field checks, at the position of the later field in the edit order. It runs when either date changed, and an unchanged date is taken from the stored values (D-49).
- **Names, city, address and phones (question 3):** approved for the first release. Country stays read-only, and non-US customers are deferred (D-50). Y/N and state values are upper-cased before the change comparison because they are stored upper case; every other field is compared exactly after trimming.
- **Limits (question 4):** approved. Each limit is 0 or more, has at most the column's 10 integer digits, and the cash limit is at most the credit limit. There is no business maximum. The new edits keep the contract's positions.
- **Group ID (question 6):** free text up to 10 characters, as in the legacy screen. An unknown group falls back to `DEFAULT` rates in interest (D-11).
- **Any change to the account row blocks the save (question 7):** accepted.
- **Account with no card (D-61).** The account fields can be updated. The customer fields are absent, the version is `"<account rv>"`, and a change to a customer field gets `<label> cannot be changed on this screen.` The contract's `404 XREF_NOT_FOUND` is replaced by this behaviour. An account with several cards takes the customer of the lowest card number ([data-model.md](data-model.md)).
- **DEF-ACUP-107:** the contract states the behaviour for short phone parts as well as for dates.
- **FLOW-ACUP-011 and ERR-ACUP-006:** these get architecture-dependent entries.
- **The contract v5 table for the five conditional items is confirmed (2026-11-10).** Four are preserved and need no ledger entry. Each keeps its observable outcome, and the quirks inside them belong to items that have their own entries.
  - **BR-ACUP-010** (must-preserve, session contract): a successful fetch sets the current-account store to the account, its customer and its card, a failed fetch clears it, and a save does not refresh it. The next screen therefore opens on the same account and card, as the commarea made it do. The store holds IDs only, and each screen reads names and status itself (D-ARCH-12). "One of the account's cards" is the lowest card number (D-61). Tests: the store's Vitest and Playwright S4.
  - **FLOW-ACUP-003** (must-preserve; the spec allows rejecting unsupported keys, Q-ACUP-009): Enter and F3 are always accepted, Save (F5) only on the confirmation step and Cancel (F12) only once an account is shown, through the web key table (D-ARCH-12, web.md). Any other key gives the invalid-key message (D-69); "treated as Enter with no message" is DEF-ACUP-010, already `fixed` under D-75. Tests: Vitest and Playwright S17.
  - **FLOW-ACUP-004** (must-preserve): each row becomes the page state and the endpoint it calls (leave, fresh search, restart after a save attempt, edit and decide). DEF-ACUP-015 (any key after a failed save only shows the empty search) keeps its own classification. Tests: Playwright S2, S10 and S11.
  - **FLOW-ACUP-009** (must-preserve): each row's outcome comes from the endpoints (fetch, validate, save and their answers). Row 1's "state `S` although the re-read failed" is DEF-ACUP-019 (`fixed`, D-75), row 3's restored-values case is DEF-ACUP-122, row 4's save outcomes are ERR-ACUP-004 with D-17 and D-68, and row 6 is unreachable in the spec. Tests: API S7, S10 and S13 to S16.
  - **ERR-ACUP-005** gets an architecture-dependent entry, next to FLOW-ACUP-011 and ERR-ACUP-006: "No commarea state byte exists (D-ARCH-12). An unknown state cannot reach the decision step, so `UNEXPECTED DATA SCENARIO` is never shown." Its must-preserve part, that the conversation ends with an error, has no path left to apply to.
- **Grants:** `core.account.addr_zip` is out of the `UPDATE` grant (question 9, approved).

## 4. CRDL (card list)

- **Selection codes (question 1):** approved. With the 3270 shortcuts on, each row has a one-character `Sel` field and VAL-CRDL-004 and VAL-CRDL-005 apply as in legacy. With the shortcuts off, the row buttons are used and those checks cannot fire.
- **Codes are checked on paging too (D-55, VAL-CRDL-007).** D-55's fix includes "show invalid-code errors", and VAL-CRDL-004, VAL-CRDL-005 and VAL-CRDL-007 have no ledger entry. Next and Previous therefore run the code checks first: two selections or an invalid code gives its message and no page move. Valid marks are cleared when the page moves, which is the other part of D-55. The contract's "codes are not checked and are cleared" is replaced.
- **Lower-case `s` and `u` are accepted (Q-CRDL-007).** D-55 is decided as fix, and its items include Q-CRDL-007, whose question is exactly this. The codes are upper-cased before the check. The contract's question 4 is answered this way, and the ledger gets entries for VAL-CRDL-005 and Q-CRDL-007 (section 7).
- **No paging state in the URL (question 2):** accepted as a card-list exception, recorded in [web.md](web.md#session-state). The filter can be a PAN, and a cursor without its filter means nothing.
- **Stale cursor (question 5):** approved as `400 INVALID_CURSOR`, after which the client asks for page 1.
- **Unknown keys show the invalid-key message (VAL-CRDL-006, D-69):** this gets a `fixed` entry under D-69.
- **Notices:** severity `information` (question 7). DEF-CRDL-015 classes the colour as incidental.
- **End-of-list notices.** A `start` read with no rows gives `NO RECORDS FOUND FOR THIS SEARCH CONDITION.`. An `after` or `before` read with no rows gives `NO MORE RECORDS TO SHOW` or `NO PREVIOUS PAGES TO DISPLAY`.
- **Read failure (ERR-CRDL-001):** no rows and the card read-error text, and the page number does not move. A set-based read does not deliver part of a page, so "the rows read so far are shown, with the page number already changed" is architecture-dependent and gets an entry (section 7). The end-of-list handling, the must-preserve part, is kept as in the notices above. The error texts are incidental in the spec, so the text needs no D-58 entry. The DEF-CRDL-009 replacement on page 1 keeps its own classification.
- **FLOW-CRDL-005 and FLOW-CRDL-012:** these get entries. FLOW-CRDL-012 cites D-ARCH-12. FLOW-CRDL-017 is must-preserve in the spec, and the table is corrected to say so.

## 5. CRDV (card view)

- **The card-to-account check uses `core.card_xref` (question 4).** D-DATA-02 makes the cross-reference the owner of the link, and `card.acct_id` follows it. The lookup joins `core.card_xref` on `card_num` and compares `x.acct_id`. That is one extra primary-key read.
- **Key texts (question 2):** approved, and card update uses the same texts. The card list keeps its `FILTER,IF SUPPLIED` texts.
- **VAL-CRDV-008.** A blank or invalid key field is red and shows `*`, as in account view and legacy.
- **ERR-CRDV-002, ERR-CRDV-003, and FLOW-CRDV-003, FLOW-CRDV-008, FLOW-CRDV-011, FLOW-CRDV-012 and FLOW-CRDV-014:** these get architecture-dependent entries (section 7).

## 6. CRDU (card update)

- **Expiry under D-51 (question 1):** approved as proposed.
  - The current month is valid.
  - The rule runs only when the month or year changed, so an expired card can still be renamed or deactivated.
  - The stored day is kept, and clamped to the last day of the new month.
  - The texts `Card expiry date cannot be in the past` and `Card name must not be longer than 50 characters.` are approved.
- **`monthYearNotPast` in `platform/validation` (question 2):** approved.
- **Record changed (question 3):** the edits are kept, as section 1 rules.
- **After a save (D-68, Q-CRDU-007).**
  - A lock failure or a rewrite failure keeps the entered values, and the user may save again.
  - Leaving the form with unsaved changes asks for confirmation.
  - After a successful save, the next action returns to the list or to a new search (BR-CRDU-009).
- **One shared key form (question 4):** confirmed, including `0` as an invalid number (Q-CRDU-006 is GnuCOBOL-only, class c).
- **Lower-case `y`/`n` (VAL-CRDU-006, Q-CRDU-008, D-51)** and **unknown keys (VAL-CRDU-011, D-69):** these get `fixed` entries.
- **F12** is Cancel only in the confirmation dialog ([web.md](web.md#keys-messages-and-focus)). It is not a shortcut of its own.
- **FLOW-CRDU-002 and FLOW-CRDU-019:** these get entries. ERR-CRDU-003 is incidental.

## 7. Ledger changes requested

The ledger owner makes these changes, routed by the orchestrator.

- **DEV-C1 target text** (INV-006 and every member of the DEV-C1 group): "every view is audited (D-09)" becomes "every unmasked view is audited (D-09, D-SEC-05); ADMIN reads the account and card views read-only and may unmask".
- **New `fixed` entries:**
  - VAL-ACVW-005 and Q-ACVW-004 (D-75)
  - Q-ACUP-104 and Q-ACUP-108 (D-50)
  - VAL-CRDL-005 and Q-CRDL-007 (D-55)
  - VAL-CRDL-006, Q-CRDL-009 and VAL-CRDU-011 (D-69)
  - VAL-CRDU-006 and Q-CRDU-008 (D-51)
  - BR-CRDU-005 and Q-CRDU-007 (D-68)
- **New architecture-dependent deviation entries:**
  - ERR-ACVW-003, ERR-ACVW-004 and FLOW-ACVW-008
  - FLOW-ACUP-011 and ERR-ACUP-006
  - ERR-CRDV-002, ERR-CRDV-003, FLOW-CRDV-003, FLOW-CRDV-008, FLOW-CRDV-011, FLOW-CRDV-012 and FLOW-CRDV-014
  - FLOW-CRDU-002 and FLOW-CRDU-019
  - FLOW-CRDL-005, FLOW-CRDL-012 and ERR-CRDL-001 (after a read failure: no rows, page number unchanged)
  - ERR-ACUP-005 (no state byte, [section 3](#3-acup-account-update))
- **No entry, preserved:** BR-ACUP-010, FLOW-ACUP-003, FLOW-ACUP-004 and FLOW-ACUP-009, with the tests named in section 3.

## 8. Rulings on contract v5 (2026-11-10)

The v5 contract folds in sections 1 to 7. Its remaining questions are answered here. None of them holds up any unit.

- **ACVW question 1, account field on the no-card view.** Normal colour. The case is an information message, not an error (D-61), so no field is marked.
- **ACUP question 1, cross-date entries.** Entry kind `dateOrder` (a date with `min` and/or `max` dates, `exclusive` for strict order) is approved, and so are the codes and texts. 4a is `EXPIRY_NOT_AFTER_OPEN`: expiry strictly after open. 7a is `REISSUE_NOT_BETWEEN`: open ≤ reissue ≤ expiry. `field` is the year part of the checked date, and its three parts are in `invalidFields`.
- **ACUP question 2, a stored NULL date.** A cross-date rule compares only against the dates that have a value:
  - A bound that is NULL (stored and unchanged) is left out of the comparison. For example, 7a with a NULL open date still checks reissue ≤ expiry.
  - When the checked date is NULL, or every bound is NULL, the rule does not run.
  - A changed date can never be NULL here, because its own field checks reject empty parts before any cross-date rule runs.
  - The case is tested with rule vectors: 4a with NULL open; 7a with NULL open, NULL expiry, and both NULL.
- **CRDL question 1, `U` for an `ADMIN`.** It is an invalid code: `INVALID ACTION CODE`, as VAL-CRDL-005. An `ADMIN` list offers only View.
- **CRDV question 1, where `*` shows.** Confirmed. A blank key field shows `*`. An invalid key field keeps its typed value (DEF-CRDV-008), is red, and has the `*` beside it.
- **CRDU question 1, F12 in the Confirm state.** Confirmed. The Confirm state shows `F5=Save F12=Cancel` (FLOW-CRDU-016), so it counts as a confirmation step and F12 cancels there. In Search, Details and Errors F12 gives the invalid-key text.
- **Cross-unit question 1, severity.** The wire value `info` is the rulings' "information". `Changes committed to database` is `success`, shown green as web.md shows success. `Changes validated.Press F5 to save` stays `info`, because it asks for the next step. Every other notice keeps the severity v5 gives it.
- **Cross-unit question 2, catalogue keys.** Accepted as built, including the variant keys that keep Bill Pay's texts unchanged.

## 9. Whole and partial deviations (2026-11-10)

The ledger's partial mechanism ([transaction-service.md](transaction-service.md#9-partial-deviations-what-stays-tested)) needs each architecture-dependent and `security-c1` entry of the five account units classed as whole or partial before the units are gated, whatever the item's fidelity class. A whole entry exempts the item: what it must preserve is carried by other items that stay required. A partial entry gets `retained` (the sentences below) and `requires_test = true`. The entries' `target` texts stay as they are. No row needs a user decision.

| Item | Class | Retained, or the items that carry the must-preserve part | Tests |
| :-- | :-- | :-- | :-- |
| ERR-ACVW-004, ERR-CRDV-002, ERR-ACUP-006 | partial | An unexpected failure ends the request with the platform's `500` problem, which has the correlation ID and no technical detail, and the page shows the error page. | One test per unit through the platform error handler, with a forced exception |
| FLOW-ACVW-008 | whole | The context that survives between screens is the selected account and card. BR-ACVW-005 carries it, and has no entry, so it stays required. | - |
| FLOW-ACUP-011 | whole | BR-ACUP-010 carries the context between screens, FLOW-ACUP-004 the state within the screen, and ERR-ACUP-004 (`fixed`, D-16, D-17, D-68) the save outcomes. All three stay required. | - |
| FLOW-CRDV-008 | whole | BR-CRDV-003 and FLOW-CRDV-005 carry the entry from the card list, with its account and card and its protected keys. Both have no entry, so they stay required. | - |
| FLOW-CRDU-019 | whole | FLOW-CRDU-005 carries the entry from the card list, and BR-CRDU-009 the return to the list or a new search after a save ([section 6](#6-crdu-card-update)). Both stay required. | - |
| ERR-ACVW-003, ERR-CRDV-003, FLOW-CRDV-014 | whole | No path is left: no client-held context exists, so an unknown context cannot arrive (D-ARCH-12), as for ERR-ACUP-005. The `404 CARD_NOT_FOUND` that the CRDV `target` texts name is VAL-CRDV-007's outcome, tested under that item. | - |
| ERR-CRDL-001 | partial | Already in the ledger ([section 4](#4-crdl-card-list)): the three end-of-list notices, and no next page at the end of the list. | The CRDL API end-of-list scenarios |
| FLOW-CRDL-005, FLOW-CRDV-012, FLOW-CRDU-002 | whole | No key code is mapped, and PF13 to PF24 do not exist. Which keys act on each screen is carried by VAL-CRDL-006 and VAL-CRDU-011 (`fixed`, D-69) and by FLOW-CRDV-002 (no entry; its other-key outcome is DEF-CRDV-005, `fixed` under D-69). All three stay required. With the 3270 shortcuts on, every other key, PA3 included, shows the invalid-key message instead of acting as Enter. | - |
| FLOW-CRDL-012 | whole | No pseudo-conversational return is left (D-ARCH-12). What carries over between list requests is carried by BR-CRDL-005 and BR-CRDL-006 (paging from the page's first and last keys), FLOW-CRDL-011 (refresh with the current filters, page number unchanged) and FLOW-CRDL-003 (arrival from another page shows page 1 and loses the filter). None of them has an entry, so they stay required. The keys stay in the page, not in the URL ([web.md](web.md#session-state)). | - |
| SE-CRDL-003 | whole (`security-c1`) | The must-preserve part, that the selected row's account and card are passed and not the filter values, is carried by BR-CRDL-007, FLOW-CRDL-009 and FLOW-CRDL-010 on the list side, and BR-CRDV-003 and FLOW-CRDU-005 on the receiving side. All five stay required. BR-CRDL-007's test types a different valid filter in the same request as the selection, and the selected row's card opens. The filter values passed back on Back are FLOW-CRDL-007's, which stays required. The user type and the clearing are DEF-CRDL-004 and FLOW-CRDL-008, below. | - |
| FLOW-CRDV-003 | whole | Each row's outcome is carried by the item it names: row 1 by FLOW-CRDV-004, row 2 by FLOW-CRDV-005 and BR-CRDV-003, row 3 by FLOW-CRDV-006, row 4 by FLOW-CRDV-007. All stay required. Row 5 is FLOW-CRDV-014, which has no path left (above). | - |
| FLOW-CRDV-011 | whole | No map is sent. What the screen shows is carried by FLOW-CRDV-009 (titles), FLOW-CRDV-010 (protection, red, `*` and focus) and BR-CRDV-002 (the card details), all required. Context 1 has no counterpart (D-ARCH-12): re-entry is FLOW-CRDV-007. | - |
| FLOW-CRDL-001, DEF-ACVW-009 | partial (`security-c1`) | A request without a session reaches sign-on: `401 UNAUTHENTICATED` from the API, and the web opens the sign-on page. This is the same retained text as FLOW-TRNL-001. | CRDL S20 (API and Playwright); ACVW S1 and S17 |
| DEF-CRDL-003 | whole (`security-c1`) | Nothing of the defect is kept. The sign-on requirement is FLOW-CRDL-001's retained part (CRDL S20). | - |
| FLOW-CRDL-008 | partial (`security-c1`) | Back from card view or card update shows the list at page 1 of all cards with empty filters, and the session's user ID and role are unchanged. | CRDL S21 |
| DEF-CRDL-005 | whole (`security-c1`) | Nothing of the defect is kept: no navigation clears the session. "Session unchanged after Back" is FLOW-CRDL-008's retained part. | - |
| DEF-CRDL-004, DEF-CRDV-004, DEF-CRDU-007, DEF-ACVW-011, DEF-ACUP-009 | whole (`security-c1`) | Nothing of the defect is kept: no page sets the role, which comes from the session on every request. Role from the session is tested through FLOW-CRDL-008 (partial, CRDL S21) and BR-MENU-003 (partial, [billpay-slice.md](billpay-slice.md#whole-and-partial-deviations-2026-11-10)). The role scenarios ACUP S29, CRDU S27, ACVW S20 and CRDV S21 are further evidence. | - |
| DEF-CRDV-003 | whole (`security-c1`) | Nothing of the defect is kept. The session data comes from the token, and a start from the menu shows the empty search form, which is FLOW-CRDV-006 (required, CRDV S2). | - |
| DEF-ACUP-013 | partial (`security-c1`) | The account and customer keys come from the path and the stored cross-reference, never from the body. A change that names a field the screen does not edit gets `400 FIELD_NOT_EDITABLE` and writes nothing. The save runs the checks of the changed fields again (D-49), so a value the validate step would reject is rejected on save and nothing is written. | ACUP S31 and S43, and one new API test: a save with a changed field that fails its check, sent without a validate call, gets the field's `400` and writes nothing |
| DEF-ACUP-105 | whole | No LE call is left (DEV-PLATFORM). Whether a date is valid is carried by VAL-ACUP-005 to VAL-ACUP-010 (VAL-ACUP-005 `fixed` under D-50, the others with no entry), which stay required. | - |

**Sign-on in every account unit.** Each unit has a gated test for a request without a session. ACVW and CRDL test it through DEF-ACVW-009 and FLOW-CRDL-001 (partial, above). ACUP S1, CRDV S1 and S19, and CRDU S2 cite DEF-ACUP-001, DEF-CRDV-002 and DEF-CRDU-006. Those three items are `legacy` under D-03 for the ownership half (staff may open any account), have no deviation entry, and so stay required. Their sign-on half follows D-01.

**Ledger changes.** The ledger owner adds `retained` (the sentences above) and `requires_test = true` to FLOW-CRDL-001, DEF-ACVW-009, FLOW-CRDL-008 and DEF-ACUP-013. ERR-CRDL-001 already has both. No `target` text changes. Every other entry in the table stays a whole entry.

**Where the atomic save is tested.** ERR-ACUP-006's retained part says nothing about writes. Legacy backs out nothing on an abend, because the files are `RECOVERY(NONE)` (DEF-ACUP-005). "Both or neither" is the D-16 target of DEF-ACUP-005 and of row 5 of ERR-ACUP-004, which is `fixed` under D-16 and so stays required. The ERR-ACUP-004 row 5 test covers two cases: the customer update fails, and an unexpected exception is thrown after the account update. In both, neither row changes and no audit row is written. The test cites ERR-ACUP-004 and DEF-ACUP-005. DEF-ACUP-005 keeps no legacy outcome, so it stays a whole entry.

## 10. Message length on every API text (2026-11-10)

The build found that `INVALID_UNMASK_FIELD`, rendered for the account unmask, is 89 characters. Its test had been left out of the length check because only an API client can reach it: the Show control always sends one valid field.

**Ruling: the 75-character limit applies to every message text that an account-service endpoint returns, after its placeholders are filled in.** It is not limited to the texts a page is known to show. There is no exception for API-only texts.

- **NUM-ACVW-007 (must-preserve)** says a message never exceeds 75 characters, because the work field that builds it holds 75. The target has one message area per page, and it shows the server's `message` for any problem ([web.md](web.md#keys-messages-and-focus)). The account view page is the caller of `unmaskAccount` for an `ADMIN`. A text the page could receive therefore counts, whatever path makes it reachable today. A stale client or a changed field list would show it.
- **The approved contract** already says it: "Every text is at most 75 characters" for the whole catalogue addition, with no carve-out. The rulings approved the unmask texts as part of that addition ([section 1](#messages-d-58-and-the-read-error-texts)). "Only an API client can reach it" was never a criterion, and it is not proved for every caller. NUM-ACUP-103 is the same rule for account update.
- **The check.** The catalogue unit test renders each text with the longest value that any caller passes for each placeholder, and fails above 75. It does not skip a text because no page is known to show it, and the minimum is not lowered. A text is never cut to fit. Legacy cut a long text at 75 (NUM-ACVW-007), but the field list must stay whole, so the text has to fit.

**New text for `INVALID_UNMASK_FIELD`:** `Unmask one or more of: {fields}`.

- `{fields}` is unchanged: every allowed field name of the endpoint, comma-separated, none left out.
- **Order (pinned after checking the backend source at `0d4b67b`).** Each endpoint passes its allowed fields to `Unmask.requireFields` as a `List`, in the contract's order, and the message names them in that order. For the account endpoint that is `ssn, dob, govtIssuedId, eftAccountId, cardNum` (`AccountViewService.UNMASKABLE`). The `Set` overload, which sorts, stays for callers that pass a set; no service endpoint uses it. Card and transaction unmask pass `List.of("cardNum")`. The earlier wording here, "sorted", described the platform class before the `List` overload was added and is replaced. The order of the requested fields is a separate thing and stays sorted: `requireFields` returns them sorted without duplicates, so the response's `fields` and the audit row's field names (`cardNum,dob,eftAccountId,govtIssuedId,ssn`, ACVW S19) are sorted whatever order the request used. Only the message follows the contract order (ACVW S20).
- The worst case is the account endpoint: `Unmask one or more of: ssn, dob, govtIssuedId, eftAccountId, cardNum`, 68 characters. Card and transaction unmask: `Unmask one or more of: cardNum`, 30 characters.
- Only the text changes, in `MessageCatalog` and the generated `web/src/messages/` group files together. The code, the `400` status, the field `fields`, both `requireFields` overloads and their callers stay as they are at `0d4b67b`. The tests assert the new text with each caller's own list and order: the account API test (contract order), the card and transaction unmask tests (`cardNum`), and `PiiTest` (sorted for the `Set` overload, given order for the `List` overload). The contract's examples keep their field order; only the words before the list change. The OpenAPI descriptions already list the fields in contract order and need no change.

**Grants (noted, consistent).** account-service's login reads `core.card` without `cvv_cd`, and gets no `UNMASK` on it (D-DATA-04, [data-model.md](data-model.md#ownership-and-grants)). On `ops.audit` it has `INSERT` only, as every service does for audit inserts. Nothing in the design changes.

## 11. Message length across the catalogue (2026-11-10)

The all-text check from [section 10](#10-message-length-on-every-api-text-2026-11-10) found three more texts over a limit. This section bounds which limit applies to which text, and fixes the texts that break it.

**Which limit applies.** Each catalogue entry is checked against the units whose endpoints can return it. Its limit is the smallest message length of those units, as their specs give it: the program's message work field when that is shorter than the screen field, otherwise the screen field.

| Units | Limit | Spec |
| :-- | --: | :-- |
| ACVW, ACUP, CRDL, CRDV, CRDU | 75 | NUM-ACVW-007, NUM-ACUP-103, and the 75-character work fields in the CRDL, CRDV and CRDU specs |
| BILL | 78 | COBIL00C: 80-character work field, of which 78 reach `ERRMSG` |
| TRNA | 78 | COTRN02C: `ERRMSG` is 78 characters |

A platform text that account-service can return, such as the `500` problem text or `UNAUTHENTICATED`, has the 75 limit. For a unit not in the table, the check uses the length its spec gives. If the spec gives none, backend asks before setting one. The account limit is not relaxed, and no text is cut to fit.

**Texts changed.**

| Key | Code | Was | Now |
| :-- | :-- | :-- | :-- |
| `ACCOUNT_UPDATE_BATCH_WINDOW` | `503 BATCH_WINDOW` | `Account update is not available while the batch cycle runs. Try again later...` (78) | `Account update is not available during the batch cycle. Try again later...` (74) |

`CARD_UPDATE_BATCH_WINDOW` is exactly 75 and stays as published. Status, code, field, `Retry-After` and the page behaviour are unchanged. ACUP S32 asserts the new text.

**Bill Pay's text stays.** `BATCH_WINDOW` (`Bill payment is not available while the batch cycle runs. Try again later...`, 76) is returned only by billpay-service. Bill Pay's limit is 78, so the text fits and stays as published in [billpay-slice.md](billpay-slice.md) and [api-security.md](api-security.md). TRNA's text breaks its 78 limit and is ruled in [transaction-service.md](transaction-service.md#10-message-length-2026-11-10).

**`FIELD_NOT_EDITABLE` is bounded, and never echoes input.** The contract text `<label> cannot be changed on this screen.`, with "the name as sent when it is unknown", has no bound and reflects untrusted input. That is replaced as follows:

- **Known name.** The name is a field of the account update screen: a form field, or one of the read-only `acctId`, `custId`, `countryCd` and the three balances. For an account with no card, the customer fields also count as known. The response is `400 FIELD_NOT_EDITABLE`, `<label> cannot be changed on this screen.`, field = that name, as today. `<label>` comes from the service's fixed label table, never from the request. The longest label in the contract is 20 characters (`Government Issued Id`), which gives 54. The check renders every label in the table, and fails if a label added later breaks 75.
- **Unknown name.** Any other name, of any length or content, gets the same `400 FIELD_NOT_EDITABLE` with a new catalogue entry, `FIELD_NOT_EDITABLE_UNKNOWN`: `This field cannot be changed on this screen.` (44 characters, no parameter). Its field is `changes`, the request member, so no part of the name is returned. Nothing is written.
- **No new length check.** No maximum length is added for names in `changes`. A long or hostile name still gets `FIELD_NOT_EDITABLE`, never a different error that would hide it. The request size limit of the platform is unchanged.
- **Logs.** The service logs the code and the correlation ID, not the name that was sent.
- **Tests.**
  - ACUP S31 keeps its read-only names with their labels.
  - `cvv` is not a field of this screen, so it moves to the unknown case: `This field cannot be changed on this screen.`, field `changes`.
  - New cases: a 10,000-character name, and a name holding markup and a fake SSN. Each gets the generic text and field `changes`. Neither the body nor the log contains any part of the name, and nothing is written.
  - S48 (`ficoScore` on an account with no card) keeps `FICO Score cannot be changed on this screen.`.
- **Account update only.** `FIELD_NOT_EDITABLE` and `FIELD_NOT_EDITABLE_UNKNOWN` belong to account update, whose `changes` is a map of names. Card update's `changes` is a fixed object with four members (`CardChanges`: `embossedName`, `activeStatus`, `expiryMonth`, `expiryYear`), and its contract has no `FIELD_NOT_EDITABLE`. That stays as built. Any other member in a card update body is ignored. It is not an error, not echoed, not logged, and not written: the save writes only the four fields, with the account taken from the cross-reference (D-13) and the CVV untouched (DEF-CRDU-002). No new validation is added to card update. If CRDU has no test with extra members yet, one is added: a `PUT` with `acctId`, `cardNum` and `cvvCd` beside the four fields changes only the four, and the response and the log contain none of the extra values.

## 12. Character set and column width in account update (2026-11-10)

The build found that account update can accept a value that the database then refuses or changes. FormRules counts `maxLength` in Java characters, but the free-text columns are `VARCHAR(n)` under the UTF-8 collation, which holds `n` bytes. A government ID of 20 × `É` (40 bytes) failed with SQL error 2628 and a `500`, and the logged driver message contained the value. `core.account.group_id` is `CHAR(10)` with a code-page-1252 collation, so a group ID in Chinese was saved as `?` with no error.

**Ruling: the four free-text fields accept every character the legacy field can hold, up to the approved length, and the database stores it unchanged. A character the legacy field cannot hold is refused with a message.** The general policy and the column inventory for every service are in [data-model.md](data-model.md#text-repertoire-and-width). For account update this means:

- **Fields.** `groupId` (10), `addrLine1` (50), `addrLine2` (50) and `govtIssuedId` (20) are the free-text fields (BR-ACUP-108, VAL-ACUP-022). They accept any character from U+0000 to U+00FF, which is exactly the set of 256 single-byte characters that the legacy field holds, control characters and NUL included. No printable-only rule is added. The lengths are unchanged and count characters; each of these characters is one UTF-16 unit and one legacy byte.
- **Refused.** A character above U+00FF (for example `中`, `€` or an emoji) gives `400 UNSUPPORTED_CHARACTER`, `<label> contains a character that is not supported.`, field = that field, for example `Government Issued Id contains a character that is not supported.` (64 characters). The check (`latin1`) runs after `required` and before `maxLength`, in the shared rules, so the page shows the same text without a call. Nothing is written. The text, the log and the audit never contain the value.
- **Other fields.** Names, city and state accept ASCII letters only, and ZIP, phones, SSN, EFT account and the primary card holder flag have their own rules. Those rules are unchanged and refuse such characters online, as today. The columns behind them must still hold any legacy value the loaders bring; they become Unicode in stage 2 (`V414`, proposed), and the view shows such a value as stored.
- **Normalisation is unchanged.** The save and the change check keep the approved trim (contract §6, BR-ACUP-102 and BR-ACUP-105 per D-50): Java whitespace is removed at both ends, then `*` followed by spaces, or spaces only, is `''`. A tab, line feed or U+001C to U+001F at either end is therefore removed as today; inside a value it is kept. NUL, U+0085 and U+00A0 are not Java whitespace and are kept anywhere. `FormRules` is not changed. `latin1` checks the value after this trim, so a character above U+00FF that the trim removes is not refused.
- **Columns** (migration `V411`, owned by data and confirmed, agreed with backend): `addr_line_1` and `addr_line_2` become `NVARCHAR(50)`, `govt_issued_id` becomes `NVARCHAR(20)` with the same mask and `UNMASK` grant, `group_id` becomes `NCHAR(10)` BIN2 with default `N''`, and `addr_zip` becomes `NVARCHAR(10)` with default `N''`, because the loader stores any Latin-1 value there as read. Correction (2026-11-11, R4): account update writes `group_id` only, in every mode, and never `addr_zip`. The DEF-ACUP-002 switch lives in the account load only ([data-model.md](data-model.md#filler-and-layout-quirks)).
- **Group ID.** `NCHAR(10)` keeps today's blank padding, so readers that trim `CHAR` values keep working. The service trims trailing spaces on read, before it shows the value or compares it for "no changes". Until stage 2 makes `ref.disclosure_group.group_id` `NCHAR(10)` too, the interest join compares the two in `NCHAR`; `=` ignores trailing spaces under every collation, so a group ID matches its disclosure group as today. A group ID that no disclosure group has gets the `DEFAULT` rates (D-11). No code casts `group_id` to `CHAR` or `VARCHAR`.
- **Logs.** Database errors are logged with the SQL error number, the state and the correlation ID, never the driver message or a value (data-model rule 9). After `V411` a validated request cannot reach 2628, but the rule stays.

**Ledger.** No class changes. Every value a 3270 terminal could enter is accepted with the same length. The new refusal covers only characters the legacy field cannot hold. The ledger owner adds that note to BR-ACUP-108 and VAL-ACUP-022.

**Tests.**

- Account update API:
  - `govtIssuedId` of 20 × `É`, and a 20-character value that holds U+00A0, U+0009, U+0085, U+0000 and `ÿ`, each saves and reads back unchanged through the unmask. 21 × `É` gets `Government Issued Id must not be longer than 20 characters.`
  - `govtIssuedId` with `中`, with `€`, and with `😀` each gets `400 UNSUPPORTED_CHARACTER`, `Government Issued Id contains a character that is not supported.`, field `govtIssuedId`. Nothing is written, and neither the body nor the log contains the value.
  - `addrLine1` and `addrLine2` of 50 × `ÿ` save and read back unchanged; 51 get the length text; `中` gets the new text with that field.
  - `groupId` of 10 × `É`, and `ÀÉ` followed by U+0085, save and read back unchanged in `group_id`, with no `?`, and `addr_zip` is unchanged; interest for the account uses `DEFAULT`. `中` gets the new text, field `groupId`.
  - A value with spaces and a tab at both ends is saved without them, as today; a tab, NUL or U+0085 inside the value is kept; a value ending in U+0085 or U+00A0 keeps it.
  - None of these cases gets a `500`.
- Schema test of `V411`: types, lengths, collations, defaults and the mask as above; `govt_issued_id` is masked for a login without `UNMASK` and clear for account-service's login.
- Rules test: the page's `latin1` check gives the same text as the server for the same values.
