# D-33 matching: releasing pending approvals "when matched"

Status: **decided 2026-11-10**. The user chose option 1, conservative automatic matching. This document is the authoritative rule. [data-model.md](data-model.md#pending-authorizations-pauth), [batch.md](batch.md) and [batch-execution-contract.md](batch-execution-contract.md) refer to it.

## Decision

D-33 (accepted): available credit = credit limit − posted balance − pending approvals. A pending approval is released when it is purged or when it is matched. A match is made only when exactly one pending approval and exactly one posted transaction agree on card, amount, merchant and date window. Nothing is guessed. An approval that is not matched stays pending until the purge (D-34), which releases it.

No legacy behaviour is reproduced. Match status `M` exists in ENT-AUTH-DETAIL, but no legacy program sets it (COPAUA0C, CBPAUP0C DEF-AUPG-003). This is a fixed-only rule with no switch ([switches.md](switches.md)).

## Evidence (why the rule has this shape)

The authorization and the posted transaction share no key:

| Meaning | Authorization (ENT-AUTH-DETAIL) | Posted transaction (ENT-TRANSACTION) | Used |
| :-- | :-- | :-- | :-- |
| Card | `PA-CARD-NUM X(16)` | `TRAN-CARD-NUM X(16)` | exact |
| Amount | `PA-APPROVED-AMT S9(10)V99` | `TRAN-AMT S9(09)V99` | exact |
| Merchant | `PA-MERCHANT-ID X(15)` | `TRAN-MERCHANT-ID 9(09)` | after normalising |
| Date | processing time (`processed_at`) | `TRAN-ORIG-TS` | date window |
| Requester's transaction ID, authorization code, posting ID | only on one side | | no |

None of the sample authorizations (dated 2023-10-19 to 2024-04-05) can match the sample daily transactions (all dated 2022-06-10). The differential checks are therefore unaffected, and the tests use synthetic data.

## The rule

Each cycle evaluates the **candidates** below. All of the conditions must hold.

**Authorization side.** These conditions apply to a `pauth.auth_detail` row:
- `auth_resp_code = '00'` (approved). Declined rows never match.
- `match_status = 'P'`.
- `auth_fraud <> 'F'`. A fraud-flagged row never matches. `R` (fraud removed) and blank do.
- `approved_amt > 0`.

**Transaction side.** These conditions apply to a `core.transaction` row:
- It was posted by this cycle: `cycle_id` = the cycle, `in_master = 1`, `origin IN ('POSTED', 'ONLINE_ADD')`. Bill payments, interest and loaded rows never match.
- `amount > 0`.
- It is not already matched.

**Pair conditions:**
1. **Card.** `auth_detail.card_num = transaction.card_num`, all 16 characters.
2. **Amount.** `approved_amt = amount`, exactly, as `DECIMAL`. There is no tolerance, so tips, partial captures and split shipments do not match.
3. **Merchant.** `N(auth.merchant_id) = N(transaction.merchant_id)`, where both sides are valid. `N` is applied to the stored text:
   - Remove leading and trailing spaces.
   - The result is **invalid** if it is empty or contains any character other than `0`-`9`. This includes an embedded space, a letter, a sign and a decimal point.
   - Remove leading zeros. The result is **invalid** if nothing is left, which means the value was all zeros.
   - The result is **invalid** if it is longer than 9 digits, because a transaction merchant ID has 9 digits.
   - A row whose merchant ID is invalid is not a candidate at all. It is counted as `merchantInvalid`. Invalid values are never treated as equal to each other or to anything else.
4. **Date window.** Let `Da` = `CAST(auth_detail.processed_at AS DATE)` and `Dt` = `CAST(transaction.orig_ts AS DATE)`. The pair qualifies when `Da <= Dt <= DATEADD(day, expiryDays, Da)`. Both ends are inclusive. `expiryDays` is the purge expiry (D-34, default 5). Both dates are compared as stored, with no time-zone conversion. A transaction dated before its authorization never matches.

**Uniqueness on both sides.** A pair `(a, t)` is matched only if `a` appears in exactly one candidate pair and `t` appears in exactly one candidate pair. Two approvals for one transaction, one approval for two transactions, and any larger group all stay unmatched.

**Snapshot.** Uniqueness is evaluated once, on the candidates visible when the cycle's candidate step runs. An authorization that commits later is not a candidate for this cycle's transactions. In the worst case, such a late authorization would have made an accepted pair ambiguous. Both holds then have the same card, amount and merchant, so releasing either one has the same effect on available credit. The late one is released by the purge.

**A transaction that stays unmatched is not reconsidered.** Each transaction is evaluated only in the cycle that posts it.

## What a match does

The changes below run in one fenced transaction per pair ([batch-execution-contract.md](batch-execution-contract.md#43-fence)):

1. `UPDATE pauth.auth_detail SET match_status = 'M', matched_tran_pk, matched_tran_id, matched_cycle_id, matched_at = SYSUTCDATETIME() WHERE auth_id = @a AND match_status = 'P' AND auth_fraud <> 'F'`. If this updates 0 rows, the row changed since the snapshot (marked as fraud, or already matched). The pair is skipped and counted as `changed`.
2. `UPDATE pauth.auth_summary SET credit_balance = credit_balance - @approved_amt WHERE acct_id = @acct AND credit_balance >= @approved_amt`. If this updates 0 rows, the summary does not hold the amount. The whole transaction rolls back, and the pair is counted as `inconsistent` and logged with `auth_id` only.
3. The approved count and amount are not changed. They are removed when the purge deletes the detail.

A detail that is marked as fraud after its match stays `M`: its transaction has posted, so the hold is no longer pending.

## Schema and grants

| Owner | Migration | Change |
| :-- | :-- | :-- |
| backend (pauth range) | `V503__pauth_auth_match.sql` | `pauth.auth_detail` gains `matched_tran_pk BIGINT NULL`, `matched_tran_id CHAR(16)` BIN2 `NULL` (for display) and `matched_cycle_id INT NULL`, with no FK, because retention may delete the transaction, and `matched_at DATETIME2(3) NULL`. It gains the check `(match_status = 'M') = (matched_tran_pk IS NOT NULL)`, written as two `CHECK` clauses. It gains `UX_auth_detail_matched_tran (matched_tran_pk) WHERE matched_tran_pk IS NOT NULL`, so a transaction can settle only one approval, and the candidate index `IX_auth_detail_pending (card_num, approved_amt) INCLUDE (merchant_id, processed_at) WHERE match_status = 'P'`. `matched_tran_pk` is the stable key, because `tran_id` can repeat across cycles in legacy ID mode. |
| data (batch range) | `V408__batch_auth_match_pair.sql` | `batch.auth_match_pair (cycle_id, attempt, auth_id, tran_pk)`, PK on all four. It holds the matched pairs of one cycle attempt, written by the candidate step. |
| data | `R__grants_batch.sql` | `batch_svc`: `UPDATE` on `pauth.auth_detail (match_status, matched_tran_pk, matched_tran_id, matched_cycle_id, matched_at)`. `UPDATE (credit_balance)` on the summary is already granted. No `UNMASK` is needed: card numbers are compared inside SQL and never leave the database in this step. |
| backend | `authorization-service` | The fraud-marking update and every summary update compare `row_version` and retry on conflict, or use atomic increments. A concurrent release is therefore never lost. The detail API returns `matchStatus` (`M` shown in the existing Match Status field, COPAUS0C BR-AUSM-003 and COPAUS1C) and a nullable `matchedTranId`. No screen is added. |

## Batch steps

`postDailyTransactionsJob` gains two steps after `post` and before `exportRejects`:

- `stageAuthorizationMatches` (tasklet, one transaction). It deletes the rows of `batch.auth_match_pair` for this cycle and attempt, evaluates the rule above in SQL, and inserts the unique pairs. It records the counts `candidatePairs`, `matched`, `ambiguous` and `merchantInvalid` in the step context. On a restart Spring Batch does not rerun a completed step, so the snapshot stays fixed for the attempt.
- `applyAuthorizationMatches` (chunk step over `batch.auth_match_pair`, ordered by `auth_id`). It applies each pair as described above. A restart resumes after the last committed chunk. A pair already applied fails predicate 1 and counts as `changed`, so no release is made twice.

`expiryDays` becomes a non-identifying parameter of `postDailyTransactionsJob`, taken from the same setting as the purge.

**Purge** (`purgePendingAuthorizationsJob`): a purged approval subtracts `credit_balance` only if it is still `P`. A purged `M` row subtracts only the approved count and amount. Unmatched approvals are released by the purge, as before.

**Cycle rerun** (`cycle-rerun`): this runs before the cycle's transactions are deleted. For each detail with `matched_cycle_id` = the cycle, it adds `approved_amt` back to `credit_balance`, sets `match_status` back to `P` and clears the four match columns. It then deletes the cycle's `batch.auth_match_pair` rows. A detail that was purged in the meantime is gone and is not restored.

## Tests

The tests use synthetic data, business date 2022-07-06 and `expiryDays` 5. They are written against SQL Server, and the step tests cover both the candidate step and the apply step.

| # | Case | Expected |
| :-- | :-- | :-- |
| 1 | Approval C1, 50.00, merchant `000000000123456`, processed 2022-07-05. Transaction C1, 50.00, merchant `000123456`, `orig_ts` 2022-07-06. | `M`, `credit_balance` −50.00, `matched_tran_pk` set. |
| 2 | Window boundaries: `Dt = Da`; `Dt = Da + 5`. | Both match. |
| 3 | Window boundaries: `Dt = Da + 6`; `Dt = Da − 1`. | Neither matches. |
| 4 | Two identical approvals, one transaction. | None matched. Both stay `P`, counted `ambiguous`. |
| 5 | One approval, two identical transactions. | None matched. |
| 6 | 2 × 2 identical approvals and transactions. | None matched. |
| 7 | Tip: approval 50.00, transaction 57.50. | None matched. |
| 8 | Split shipment: approval 50.00, transactions 30.00 and 20.00. | None matched. |
| 9 | Merchant IDs `ABC123`, `12 34`, `-123`, `12.3`, all spaces, all zeros, 10 significant digits, on either side. | Never matched, counted `merchantInvalid`. Two rows that are both invalid do not match each other. |
| 10 | Declined approval; fraud-flagged `F` approval. | Never matched. An `R` approval matches. |
| 11 | Bill payment, interest row, negative amount (refund). | Never matched. |
| 12 | Online add posted by the cycle. | Matched like a file transaction. |
| 13 | Fraud marked as `F` between the candidate step and the apply step. | Not matched (`changed`), no release. |
| 14 | An online approval on the same account commits while the apply step releases. | Final `credit_balance` = start + new approval − released amount, exactly. |
| 15 | Kill batch-service in the middle of the apply step, then relaunch. | Each pair is released exactly once. The totals equal an uninterrupted run. |
| 16 | `credit_balance` smaller than the approved amount. | Rolled back, `inconsistent`, nothing changed. |
| 17 | The detail matched in case 1 is purged later. | Deleted without changing `credit_balance` again. An unmatched approval that is purged releases its amount. |
| 18 | `cycle-rerun` of the cycle in case 1. | Detail back to `P`, `credit_balance` +50.00. The rerun matches it again exactly once. |
| 19 | An authorization committed after the candidate step. | Not considered in this cycle. It stays `P`. |
| 20 | The 202 sample authorizations and the 300 sample daily transactions. | No match. Posting results are unchanged. |
