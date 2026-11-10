# Decision brief: D-33 release of pending approvals "when matched"

Status: **open, waiting for the user.** Prepared by the architect, 2026-11-10. Nothing in this brief is built until the user chooses. The schema and the purge release proceed independently.

## What is decided and what is missing

D-33 (accepted) says: available credit = credit limit − posted balance − pending approvals, and approvals are released "when purged or matched". The purge release is designed ([data-model.md](data-model.md#pending-authorizations-pauth)). Matching is not, because no rule exists for deciding which posted transaction belongs to which authorization. Until an approval is released, its amount is counted twice: once in the posted balance and once as pending. With the 5-day purge default (D-34), that double count lasts up to 5 days.

This is an obligation from an accepted decision, not an exclusion. It stays open until the user picks one of the options below.

## Evidence from the spec

- Nothing in the legacy system matches. Match status `M` ("matched with a posted transaction") exists in ENT-AUTH-DETAIL, but only `P` and `D` are ever set (`entities.md`, ENT-AUTH-DETAIL; COPAUA0C §downstream). The module README promises "adjustment of available credit when unmatched authorizations are deleted", which the code never does (CBPAUP0C DEF-AUPG-003).
- **No shared identifier.** The authorization and the posted transaction share no key:

| Meaning | Authorization (ENT-AUTH-DETAIL) | Posting input and master (ENT-DAILY-TRAN, ENT-TRANSACTION) | Comparable? |
| :-- | :-- | :-- | :-- |
| Card | `PA-CARD-NUM X(16)` | `TRAN-CARD-NUM X(16)` | yes, exact |
| Amount | `PA-APPROVED-AMT S9(10)V99` (0 when declined) | `TRAN-AMT S9(09)V99` | yes, exact; a tip or partial amount differs |
| Time | `PA-AUTH-ORIG-DATE`/`-TIME` as sent (`YYMMDD`, `HHMMSS`), processing time | `TRAN-ORIG-TS X(26)` | as a date window only |
| Merchant ID | `PA-MERCHANT-ID X(15)` | `TRAN-MERCHANT-ID 9(09)` | only after normalising (different widths and types) |
| Merchant name, city, ZIP | `X(22)`, `X(13)`, `X(9)` | `X(50)`, `X(50)`, `X(10)` | only as prefixes |
| Requester's transaction ID | `PA-TRANSACTION-ID X(15)` | none | no |
| Authorization code | `PA-AUTH-ID-CODE X(6)` | none | no |
| Posting ID | none | `TRAN-ID X(16)` | no |

- Sample data: the 202 sample authorizations are dated 2023-10-19 to 2024-04-05. The 300 daily transactions are all dated 2022-06-10. No sample pair can match under any option, so the differential checks are unaffected whichever option is chosen. Tests need synthetic data.

## Options

| Option | What it does | Ambiguity and risk | Works on today's inputs |
| :-- | :-- | :-- | :-- |
| **1. Conservative automatic match** | After posting, each approved, still-pending (`P`), not fraud-flagged authorization is matched to a transaction posted in that cycle when all of the following hold: same card; approved amount = transaction amount; same merchant ID after removing leading zeros and spaces; transaction date from the authorization's processing date up to the purge expiry later. The pair must be the **only** candidate on both sides. Anything else stays pending until the purge. | A wrong pair needs two charges with the same card, amount, merchant and window, and one of the authorizations not yet posted. Releasing either hold then has the same effect on credit, so the money effect is nil. Tips, partial captures and split shipments never match and fall back to the purge. | yes |
| 2. Exact key only | The posting input gains an authorization reference (the authorization code, or the requester's transaction ID) in a new field. A transaction matches only the authorization with that exact reference. | None in the match itself. The new field changes the daily-file contract, and no producer of that file exists in this repository. Until a feed sends the field, nothing matches. | no: purge only until a feed changes |
| 3. Manual match | On the authorization detail screen, an `ADMIN` picks the posted transaction that settles a pending approval. The release is audited. | Operator effort and error. Unmatched approvals still wait for the purge. | yes, by hand |
| 4. Purge-only release | Amend D-33: approvals are released only by the purge. | The double count lasts up to the expiry, as described above. This changes an accepted decision, so it needs the user's explicit amendment. | yes |

**Recommendation: option 1, with option 2 added when a feed can carry the reference.** Option 1 is the only one that releases holds automatically on today's inputs. Requiring a unique pair on both sides means it never guesses between candidates. The purge remains the backstop for everything it leaves pending.

## How option 1 would be built (if chosen)

- **Where.** A step `matchAuthorizations` at the end of `postDailyTransactionsJob`, after `post`, fenced like every job step ([batch-execution-contract.md](batch-execution-contract.md)). It considers only the transactions that this cycle posted (`in_master = 1`, the cycle's `cycle_id`, origins `POSTED` and `ONLINE_ADD`).
- **Writes.** For each matched pair, in one transaction:
  - set `pauth.auth_detail.match_status = 'M'`, a new `matched_tran_id CHAR(16)` and a new `matched_cycle_id INT`;
  - subtract the approved amount from `auth_summary.credit_balance` with an atomic update, so concurrent online authorizations stay correct.
  - Approved counts and amounts are not changed. They are removed when the purge deletes the detail.
- **Purge interplay.** The purge subtracts `credit_balance` only for details still `P`. A matched detail is never released twice.
- **Cycle rerun.** `cycle-rerun` sets the cycle's matched details back to `P`, clears the two new columns and adds their amounts back to `credit_balance`, before posting again.
- **Grants.** `batch_svc` gets `UPDATE` on `auth_detail (match_status, matched_tran_id, matched_cycle_id)`. It already has `UPDATE` on `credit_balance`.
- **What users see.** The authorization summary list and the detail screen show `M` in the existing Match Status field (COPAUS0C BR-AUSM-003, COPAUS1C). Available credit for the next authorization rises by the matched amount. The detail API may also return `matchedTranId`. No new screen is needed.

## Test examples for option 1 (synthetic data, business date 2022-07-06, expiry 5 days)

| # | Pending authorization | Transaction posted in the cycle | Expected |
| :-- | :-- | :-- | :-- |
| 1 | card C1, 50.00 approved, merchant ID `000000000123456`, processed 2022-07-05 | card C1, 50.00, merchant ID `000123456`, original date 2022-07-06 | `M`; `credit_balance` −50.00; `matched_tran_id` set |
| 2 | two identical authorizations for 50.00, C1, the same merchant | one transaction for 50.00, C1, that merchant | none matched (ambiguous); both stay `P` |
| 3 | C1, 50.00 | C1, 57.50 (tip) | none matched |
| 4 | C1, 50.00, processed 2022-07-05 | C1, 50.00, original date 2022-07-04 (before the authorization) | none matched |
| 5 | C1, declined (`05`) | C1, 50.00 | never matched |
| 6 | C1, 50.00, fraud flag `F` | C1, 50.00 | not matched |
| 7 | the detail matched in example 1, later purged | - | purge deletes it without changing `credit_balance` again |
| 8 | the cycle of example 1 rerun with `cycle-rerun` | - | detail back to `P`, `credit_balance` +50.00, and the rerun matches it again exactly once |
| 9 | the 202 sample authorizations | the 300 sample daily transactions | no match; the posting results are unchanged |

## Question for the user

How are pending approvals released "when matched" (D-33)?

1. Conservative automatic match (recommended).
2. Exact authorization reference only, once a feed carries it.
3. Manual match by an `ADMIN`.
4. Amend D-33 to purge-only release.
