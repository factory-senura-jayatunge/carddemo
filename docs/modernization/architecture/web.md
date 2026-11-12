# React web app

## Stack

Vite, React and TypeScript in `modernization/web/`, built with Node 24 (pinned by `scripts/toolchain.sh`). React Router for routes, TanStack Query for server state, a client generated from each service's OpenAPI document, and forms whose validators come from `platform/validation` (the TypeScript mirror of the Java validators, generated from one rule table so the two cannot drift). The component library is decision D-WEB-01 (recommended: an accessible, themeable library such as Mantine, with the app's own tokens).

The screens are designed as modern web pages, not 24x80 copies: labelled fields, tables with real headers, inline field errors and dialogs. What carries over from the 3270 screens is behaviour: which fields exist, their order, validation order, message text, message colour, which fields are cleared and where the focus goes.

## Routes

| Route | Screen (spec) | Role (choice C1) | Notes |
| :-- | :-- | :-- | :-- |
| `/sign-on` | COSGN00C | public | Redirects to `/` or `/admin` by user type. |
| `/sign-on/change-password`, `/signed-off` | COSGN00C, D-04 | public | Forced password change at first sign-on; the goodbye page after Exit. |
| `/` | COMEN01C main menu | USER | 11 options. |
| `/admin` | COADM01C admin menu | ADMIN | 6 options, plus option 7 for the read-only account and card views (D-09). |
| `/accounts/view` | COACTVWC | USER, ADMIN | Account ID field, then the account, customer and card panel. ADMIN: read-only, with a Show control per masked field (D-09). |
| `/accounts/edit` | COACTUPC | USER | Fetch, edit, validate (Enter), save (F5), with a confirm step. |
| `/cards` | COCRDLIC | USER, ADMIN | 7 rows, account and card filters, row actions View and Update (the `S` and `U` selection codes); ADMIN sees View only. |
| `/cards/lookup`, `/cards/:cardId` | COCRDSLC | USER, ADMIN | Typed keys from the menu, or `cardId` from the list. ADMIN: Show control on the card number. |
| `/cards/:cardId/edit`, `/cards/edit` | COCRDUPC | USER | Keys protected when opened from the list. |
| `/transactions` | COTRN00C | USER, ADMIN (read-only) | 10 rows, start ID filter, row action View. |
| `/transactions/lookup` | COTRN01C, empty view | USER, ADMIN (read-only) | The ID prompt; Enter opens `/transactions/:tranId`. |
| `/transactions/:tranId` | COTRN01C | USER, ADMIN (read-only) | `ADMIN` gets a Show control on the card number ([transaction-service.md](transaction-service.md#access-and-the-card-number-reveal-d-09)). |
| `/transactions/new` | COTRN02C | USER | Validate, confirm `Y`/`N`, "copy last" (PF5). |
| `/reports/transactions` | CORPT00C | USER | Monthly, yearly or custom range; shows the run and a download link when done. |
| `/reports/transactions/:runId` | CORPT00C | USER, ADMIN | One run by its ID, the way an `ADMIN` opens a run, with no run list and no menu entry ([transaction-service.md](transaction-service.md#5-rpts-transaction-reports)): the same run panel as the request page, refreshed while the run is requested or running, and the download when it is ready. A `USER` sees only their own runs; any other ID answers as not found. Back goes to `/admin` for an `ADMIN` and to `/reports/transactions` for a `USER`. |
| `/bill-pay` | COBIL00C | USER | First slice. |
| `/authorizations`, `/authorizations/:authId` | COPAUS0C, COPAUS1C | USER | Shown when the authorization module is installed. |
| `/admin/lookup` | admin option 7 (D-09) | ADMIN | Links to account view, card list, card view, transaction list and the transaction view's ID prompt, read-only. |
| `/admin/users`, `/admin/users/new`, `/admin/users/:userKey/edit`, `/admin/users/:userKey/delete` | COUSR00C to COUSR03C | ADMIN | `:userKey` is the URL-safe key of the user ID, and the API calls use the key form ([identity-service.md](identity-service.md#5-user-ids-in-urls)). |
| `/admin/transaction-types`, `/admin/transaction-types/maintain` | COTRTLIC, COTRTUPC | ADMIN | Shown when the reference module is installed. |

## Menu mapping

| Main menu option (ENT-MAIN-MENU-OPTIONS) | Route | Admin menu option (ENT-ADMIN-MENU-OPTIONS) | Route |
| :-- | :-- | :-- | :-- |
| 1 Account View | `/accounts/view` | 1 User List (Security) | `/admin/users` |
| 2 Account Update | `/accounts/edit` | 2 User Add (Security) | `/admin/users/new` |
| 3 Credit Card List | `/cards` | 3 User Update (Security) | `/admin/users` then edit, or by ID |
| 4 Credit Card View | `/cards/lookup` | 4 User Delete (Security) | `/admin/users` then delete, or by ID |
| 5 Credit Card Update | `/cards/edit` | 5 Transaction Type List/Update (Db2) | `/admin/transaction-types` |
| 6 Transaction List | `/transactions` | 6 Transaction Type Maintenance (Db2) | `/admin/transaction-types/maintain` |
| 7 Transaction View | `/transactions/lookup`, then `/transactions/:tranId` | | |
| 8 Transaction Add | `/transactions/new` | | |
| 9 Transaction Reports | `/reports/transactions` | | |
| 10 Bill Payment | `/bill-pay` | | |
| 11 Pending Authorization View | `/authorizations` | 7 Account and Card View (D-09) | `/admin/lookup` |

The menu keeps the option numbers and texts, and the numeric option field: typing `10` and Enter still works (VAL-MENU-001, NUM-MENU-001), next to clickable entries. Every option opens its route above (BR-MENU-002), and no shipped option reaches `This option <name> is coming soon ...` (BR-MENU-005, dormant). Option 11 needs the authorization module: without it, the menu shows `This option Pending Authorization View is not installed...` (BR-MENU-004). Admin options 5 and 6 are hidden while the transaction type module is absent (D-70).

## Navigation

```mermaid
flowchart TD
    S["/sign-on"] -->|type A| A["/admin"]
    S -->|other types| M["/ (main menu)"]
    M --> AV["/accounts/view"] & AE["/accounts/edit"] & CL["/cards"] & CV["/cards/lookup"] & CE["/cards/edit"]
    M --> TL["/transactions"] & TV["/transactions/:tranId"] & TA["/transactions/new"] & RP["/reports/transactions"]
    M --> BP["/bill-pay"] & PA["/authorizations"]
    CL -->|View| CD["/cards/:cardId"]
    CL -->|Update| CU["/cards/:cardId/edit"]
    TL -->|View| TV
    PA -->|select| PD["/authorizations/:authId"]
    A --> LK["/admin/lookup"] --> AV & CL & CV & TL & TV
    A --> UL["/admin/users"] & UA["/admin/users/new"] & TT["/admin/transaction-types"] & TM["/admin/transaction-types/maintain"]
    UL -->|U| UU["/admin/users/:userKey/edit"]
    UL -->|D| UD["/admin/users/:userKey/delete"]
```

Back (PF3) returns to the screen that opened the current one, else to the menu; on a menu it signs off. That is the legacy "from program" rule (FLOW-BILL-004 and its siblings) expressed as router history. Returning to the card list starts at page 1 with no filter (Q-CRDL-003, legacy mode).

## Keys, messages and focus

| 3270 | Web |
| :-- | :-- |
| Enter | Primary button (Continue, Search, Validate); the Enter key in a form submits it |
| PF3 | Back button; Escape key |
| PF4 | Clear button |
| PF5 | Save, or Copy last on transaction add |
| PF7 / PF8 | Previous / Next page buttons; Page Up / Page Down in tables |
| PF10, PF12 | Confirm and Cancel in a confirmation dialog |
| Other keys | With the 3270 shortcuts on: `Invalid key pressed. Please see below...`, input kept. With them off: nothing. |

Optional keyboard shortcuts for F3, F4, F5, F7 and F8 can be switched on per user for operators used to the 3270 (D-WEB-03); with them on, any other function key shows the legacy invalid-key message, so VAL-BILL-006 and its siblings keep a test path.

The message area under each form shows the server's `message`: red for errors, green for success, neutral for information, as on the 3270. Focus moves to the server's `field`. The texts come from one message catalogue shared with the services, so a test can compare the exact legacy text.

Sign-on and forced password change checks follow [identity-service.md](identity-service.md#8-web-checks-on-the-sign-on-and-forced-password-change-pages-2026-11-11). Before submitting, the web runs the server's static rules, never stricter ones, and the server decides every submit. Neither page caps the length of the user ID or password inputs; the shared rules check the lengths in code points after the trailing pad is removed. The typed user ID is sent raw. A direct entry to the change page runs only the checks that do not depend on the mode.

## Session state

The commarea becomes three things:

| Commarea content (ENT-COMMAREA) | Web |
| :-- | :-- |
| User ID and type | The session token; `GET /api/auth/me` |
| From and to program, context flag | Router history and route state |
| Selected account, customer and card (`CDEMO-ACCT-ID`, `CDEMO-CUST-ID`, `CDEMO-CARD-NUM`) | A "current account" store in session storage, holding IDs only, never a PAN in clear (the card is held by `cardId`) |
| Paging state (first and last keys, page number) | Query parameters of the list route, except the card list: its filter can be a PAN, so `/cards` keeps paging state in the page and a reload shows page 1 |

The account view page never carries over a previous customer after a failed lookup (DEF-ACVW-018 fixed-only).

## Availability

During a batch window the API returns `503 BATCH_WINDOW`; the page shows the legacy message for the affected screen and a "try again after the batch" hint. Read-only pages keep working when the window mode allows reads ([batch.md](batch.md#batch-window)).

## Non-functional defaults

- **Browsers**: the current and previous major versions of Chrome, Edge, Firefox and Safari (D-NFR-07).
- **Accessibility**: WCAG 2.2 AA: labels on every field, keyboard operation for every action, focus management on errors, colour never the only signal (the message text says error or success) (D-NFR-08).
- **Tests**: Vitest for components and validators (with spec item IDs in test names), Playwright for the equivalence scenarios S1 to Sn of each screen.
