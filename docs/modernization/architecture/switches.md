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
| ledger-core (TRNA, BILL, INTC) | BR-TRNA-003 | "highest + 1" under a lock, over master rows and waiting online adds | `core.tran_id_seq` (D-59) | S |
| account data load | DEF-ACUP-002 | `dataLoadJob` leaves `A000000000` at the ZIP position and `group_id` blank | group ID moved to `group_id` (D-11) | S: INTCALC picks its rates by group, so its legacy comparison needs the unmigrated data. Account update is fixed-only and never reads this switch. |
| INTCALC | DEF-JINTC-001 | job parameter `parm` taken as given (the JCL literal) | derived from `businessDate` (D-59) | B: IDs and timestamps |
| INTCALC | DEF-INTC-010 | IDs `parm` + 6-digit counter | IDs from `core.tran_id_seq` (D-59) | B; NUM-JINTC-001 is covered by this switch |
| INTCALC | DEF-INTC-012 | credit (negative) balances earn negative interest | positive balances only, truncation kept (D-25) | B |
| TRANREPT | DEF-TRPT-001 | stop at the first out-of-range record | skip it, always print totals (D-46) | B; FLOW-TRPT-001 is covered by this switch |
| TRANREPT | DEF-TRPT-004 | "Account Total" per card, card order | per account, account order (D-47) | B |
| PRTCATBL | DEF-JPCAT-001 | negative balances without a sign | signed (D-75) | B (oracle) |
| CREASTMT | DEF-STMT-001 | 51-card, 10-transaction table limit | no limit (D-75) | B; DEF-JSTMT-005 is covered by this switch |
| CREASTMT | DEF-STMT-002 | names, cities, ZIPs and descriptions truncated | full values (D-44) | B; BR-STMT-005 and BR-STMT-006 are covered by this switch |
| CREASTMT | DEF-STMT-003 | HTML lines cut at a double space or `*` | full values (D-44) | B; BR-STMT-008 is covered by this switch |
| CREASTMT | DEF-STMT-004 | balances of 1,000,000,000.00 or more lose the highest digit | full value (D-75) | B (stress data) |
| CREASTMT | DEF-STMT-005 | one statement per card, legacy content | one per account per period with the fields of D-43 | B: D-43 keeps "a parity mode only for the migration check" |
| CREASTMT | DEF-STMT-007 | text written into HTML unescaped | escaped (D-45) | B |
| inquiry (MQAC) | DEF-MQAC-002 | reply to the fixed reply queue | to `reply_to`, falling back to the fixed queue (D-73) | W |
| inquiry (MQDT) | DEF-MQDT-002 | reply to the fixed reply queue | to `reply_to`, falling back to the fixed queue (D-73) | W |
| inquiry (MQDT) | DEF-MQDT-003 | reply `message_id` and `correlation_id` copied from the request | `correlation_id` = request `message_id` (D-73) | W: a requester that matches on the copied IDs breaks without it |

"Covered by this switch" means the item's ledger entry has `code_switch = false`, names the switch in `note`, and the switch's tests cite both IDs.

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
| DEF-MQAC-007, ERR-MQAC-005 | negative acknowledgement and dead-lettering (D-74) | failure path |
| BR-AUPG-*, DEF-AUPG-*, BR-JAUPG-001, DEF-JAUPG-001 | as decided (D-33, D-34, D-10) | CBPAUP0C uses IMS, so it cannot run locally and there is no comparison |
| DEF-POST-003, DEF-POST-012, ERR-TVAL-003, ERR-JINTC-001, DEF-STMT-010, DEF-JSTMT-001, DEF-JPOST-003, DEF-JTBKP-001 | as decided | failure path or rerun protection |
| BR-JTRPT-001, DEF-JTRPT-001, SE-JTRPT-002 | one inclusive date range from the request; order by card, then transaction ID | the comparison passes the same dates to both sides, and the legacy order is already deterministic |
| DEF-JTRAN-005 | no seed record (D-59) | the comparison builds its own input. If `verify-batch` shows the seed record in a legacy output, batch raises it and it becomes an S switch. |
| CBEXPORT, CBIMPORT, CBSTM03B items | as decided | not converted (D-62) or absorbed into SQL access |

Every other entry in the ledger is fixed-only (or kept legacy) by the general rule above.
