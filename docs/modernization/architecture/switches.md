# Fidelity switches

This is the authoritative list of code switches for the build. The ledger (`modernization/ledger/fidelity.toml`) sets `code_switch = true` for exactly the items below; every other entry has `code_switch = false`.

## Policy

The user chose the **targeted** switch policy on 2026-11-06. A legacy/fixed switch exists only where the architecture needs a real alternative. Every other accepted fix is built once, in its decided mode, with `code_switch = false`, and is tested in that mode. An item with no ledger entry (not a decision item) keeps the COBOL behaviour, also with no switch, for example NUM-BILL-002, DEF-BILL-002, DEF-INTC-007 and DEF-MQAC-005.

An item gets a switch only if at least one of these holds:

| Code | Reason |
| :-- | :-- |
| **B** | Batch parity: the job is compared with a COBOL reference (`verify-batch` against the GnuCOBOL program or `checks/cbstm03a_harness`, or the PRTCATBL oracle), and the fix changes an exported record or a report line of a normal run. Legacy mode keeps the byte-for-byte comparison working. |
| **S** | A shared rule that a B job uses, or data that a B job needs in legacy form. |
| **W** | A wire contract that existing external requesters can observe. Legacy mode keeps them working until each one has moved. |

Failure-path fixes (abends, error handling, rerun protection) are fixed-only even in batch, because a normal differential run never takes those paths. Online fixes are fixed-only, because the CICS programs cannot run locally, so no comparison needs their legacy behaviour.

A test of a switched item runs in both modes. A test of any other item runs in the decided mode only. Either way the test cites the item ID.

## Switches

| Unit | Switch | Legacy | Fixed (decided) | Reason |
| :-- | :-- | :-- | :-- | :-- |
| BILL, SGN (built) | DEF-BILL-010, DEF-BILL-014, DEF-SGN-002, DEF-SGN-003, DEF-SGN-005, DEF-SGN-011 | as in [billpay-slice.md](billpay-slice.md#6-fidelity-switches-touched) | same | Built before the policy and kept, so no rework. |
| ledger-core (TRNA, BILL, INTC) | BR-TRNA-003 | "highest + 1" under a lock, over master rows and waiting online adds | `core.tran_id_seq` (D-59), from `9000000000000000` up; reserved daily-file IDs fail the claim ([batch.md](batch.md#transaction-ids-from-the-file-and-from-the-services)) | S |
| account data load | DEF-ACUP-002 | `dataLoadJob` stores every record as read in `CVACT01Y` (the sample keeps `A000000000` at the ZIP position and a blank `group_id`) and ignores every layout manifest | records whose layout a manifest declares as `ACUP_UPDATE` (the built-in entry covers the ASCII sample only) move 103-112 to `group_id` (D-11); every other record as read; per-row state-based export ([data-model.md](data-model.md#filler-and-layout-quirks)) | S: INTCALC picks its rates by group, so its legacy comparison needs the unmigrated data. Account update is fixed-only, writes `group_id` only and never reads this switch. |
| INTCALC | DEF-JINTC-001 | `parm` from the property `carddemo.batch.interest.legacy-parm` (default `2022071800`, the JCL literal), used only as the ID prefix when DEF-INTC-010 is legacy; with DEF-INTC-010 fixed it has no effect. `verify-batch` passes the same value that the GnuCOBOL runner uses, and a test checks that the two are equal (recorded 2026-11-10) | `batch.cycle.interest_parm`, derived from `businessDate` as `yyyyMMdd00` (D-59) | B: IDs |
| INTCALC | DEF-INTC-010 | IDs `parm` + 6-digit counter | IDs from `core.tran_id_seq` (D-59) | B; NUM-JINTC-001 is covered by this switch |
| INTCALC | DEF-INTC-012 | credit (negative) balances earn negative interest | positive balances only, truncation kept (D-25) | B |
| TRANREPT | DEF-TRPT-001 | stop at the first out-of-range record | skip it, always print totals (D-46) | B; FLOW-TRPT-001 is covered by this switch |
| TRANREPT | DEF-TRPT-004 | "Account Total" per card, card order | per account, account order (D-47) | B |
| PRTCATBL | DEF-JPCAT-001 | negative balances without a sign | signed (D-75) | B (oracle) |
| CREASTMT | DEF-STMT-005 | one statement per card, legacy content, in the legacy 80-byte text and 100-byte HTML records: the 51-card, 10-transaction table limit, failing before any output on overflow (DEF-STMT-001); names, cities, ZIPs and descriptions truncated (DEF-STMT-002); HTML lines cut at a double space or `*` (DEF-STMT-003); balances of 1,000,000,000.00 or more lose the highest digit (DEF-STMT-004); text written into HTML unescaped (DEF-STMT-007) | one PDF per account per period with the fields of D-43: no limit (D-75), full values (D-44, D-75), no HTML (D-45) | B: D-43 keeps "a parity mode only for the migration check". DEF-STMT-001, DEF-STMT-002, DEF-STMT-003, DEF-STMT-004, DEF-STMT-007, BR-STMT-005, BR-STMT-006, BR-STMT-008 and DEF-JSTMT-005 are covered by this switch (amended 2026-11-11, below) |
| inquiry (MQAC) | DEF-MQAC-002 | reply to the fixed reply queue, `message_id` and `correlation_id` copied from the request | to `reply_to`, falling back to the fixed queue; `correlation_id` = request `message_id` (D-73). Also covers BR-MQAC-005 and the correlation part of DEF-MQAC-003 ([inquiry-service.md](inquiry-service.md#3-account-correlation-q1)) | W |
| inquiry (MQDT) | DEF-MQDT-002 | reply to the fixed reply queue | to `reply_to`, falling back to the fixed queue (D-73) | W |
| inquiry (MQDT) | DEF-MQDT-003 | reply `message_id` and `correlation_id` copied from the request | `correlation_id` = request `message_id` (D-73) | W: a requester that matches on the copied IDs breaks without it |

"Covered by this switch" means the item's ledger entry has `code_switch = false`, names the switch in `note`, and the switch's tests cite both IDs.

**Amendment, 2026-11-11 (decided by the orchestrator under the user's standing delegation, on the architect's proposal A).** DEF-STMT-001, DEF-STMT-002, DEF-STMT-003, DEF-STMT-004 and DEF-STMT-007 were switches of their own until this date. They are now covered by DEF-STMT-005, so the list has **18 code switches instead of 23** (CREASTMT: 1 instead of 6), and the covered items of CREASTMT are those nine. The reason is coupling: the decided behaviour of all five belongs to the fixed renderer that DEF-STMT-005 selects, and no decision, document or consumer requires a mixed setting, so a separate selector for each would be an alternative the architecture does not need. Format capacity is a limit for DEF-STMT-002 and DEF-STMT-007 only (a description keeps 49 of its 100 characters in the text record; full names and escaped fields can exceed the 100-byte HTML record). The fixed values of DEF-STMT-001, DEF-STMT-003 and DEF-STMT-004 would fit the legacy records, as the analysis's table shows. No mixed setting is offered. Nothing is lost: each of the five keeps its legacy behaviour in legacy mode and its decided behaviour in fixed mode, each is still tested in both modes through DEF-STMT-005, and no code or configuration key had declared any of the five. The analysis is in [batch.md](batch.md#build-rulings-statement-and-transaction-report-2026-11-11).

The reply body format of the inquiry and authorization listeners (legacy text or JSON, D-72, D-MSG-02) is chosen per message by `content_type`. It is a content option, not a switch.

## Fixed-only although an earlier document named a switch

| Item | Decided behaviour | Why no switch |
| :-- | :-- | :-- |
| DEF-ACUP-003 | ZIP+4 kept (D-14) | online |
| DEF-ACVW-018 | no carry-over of the previous card and customer (D-75) | online |
| DEF-CRDU-001 | card update never changes the account (D-13) | online; the legacy behaviour moves cards between accounts |
| DEF-CRDU-002 | the CVV is never written (D-12) | online; the CVV is dropped (D-DATA-04) |
| DEF-DTCK-004 | transaction add and reports treat CEE2513 as invalid (D-52) | the defect is in the online callers; `LegacyDateCheck` itself keeps CEEDAYS's results and is checked against the GnuCOBOL CSUTLDTC with no switch |
| DEF-TRNA-001 | online adds feed the posting input (D-27) | online; transaction-service never inserts `core.transaction` |
| DEF-AUTH-006, DEF-AUTH-008, FLOW-AUTH-003, DEF-AUTH-015 | duplicate detection, outbox, correlation fallback (D-32, D-35, D-31) | requesters see the same wire contract; only delivery guarantees improve |
| DEF-MQAC-007, ERR-MQAC-005 | retry by `basic.reject`, then on the sixth delivery an error record, an error reply and dead-lettering (D-74, [inquiry-service.md](inquiry-service.md#1-failed-requests-error-records-and-the-error-reply-q3-d-74)) | failure path |
| BR-AUPG-*, DEF-AUPG-*, BR-JAUPG-001, DEF-JAUPG-001 | as decided (D-33, D-34, D-10) | CBPAUP0C uses IMS, so it cannot run locally and there is no comparison |
| DEF-POST-003, DEF-POST-012, ERR-TVAL-003, ERR-JINTC-001, DEF-STMT-010, DEF-JSTMT-001, DEF-JPOST-003, DEF-JTBKP-001 | as decided | failure path or rerun protection |
| BR-JTRPT-001, DEF-JTRPT-001, SE-JTRPT-002 | one inclusive date range from the request; order by card, then transaction ID | the comparison passes the same dates to both sides, and the legacy order is already deterministic |
| DEF-JTRAN-005 | no seed record (D-59) | the comparison builds its own input. If `verify-batch` shows the seed record in a legacy output, batch raises it and it becomes an S switch. |
| CBEXPORT, CBIMPORT, CBSTM03B items | as decided | CBEXPORT and CBIMPORT are built fixed-only as D-62's replacement, with the differences listed in [batch.md](batch.md#export-and-import-ruled-2026-11-11); CBSTM03B is absorbed into SQL access |

Every other entry in the ledger is fixed-only (or kept legacy) by the general rule above.
