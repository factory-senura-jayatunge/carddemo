# INTCALC spec-only reimplementation notes

Implementation: `intcalc_from_spec.py` in this folder, written from `programs/CBACT04C.md`, `entities.md`, `areas/d1-core-batch-entities.md` and `format.md` (plus ERR-POST-007 and NUM-POST-001/003/007 in `programs/CBTRN02C.md`, which CBACT04C refers to). No source, test or runner file was read.

## Ambiguities and decisions

- **ERR-INTC-001, open failures.** The spec does not say which local conditions make an OPEN fail. Decision: a missing input text file (`tcatbal.txt`, `cardxref.txt`, `discgrp.txt`, `acctdata.txt`) is an open failure with status `35`, checked in the open order TCATBALF, XREFFILE, DISCGRP, ACCTFILE. TRANSACT never fails to open. Close failures are never produced.
- **ERR-INTC-001, XREFFILE open message.** The message is followed directly by the status (`...FILE35`). Decision: that line is then followed by the usual `FILE STATUS IS: NNNN0035` line and `ABENDING PROGRAM`, as for the other files; the spec does not say whether the status line is also printed.
- **§4.3, record display (DEF-INTC-011).** "The 50-byte record as stored". Decision: the record padded with spaces to 50 bytes, trailing spaces kept.
- **NUM-INTC-005, timestamps.** The spec reads the clock once per transaction; the task contract asks for one moment of the run. Decision: one clock reading at the start of the run, reused for both fields of every transaction; the date is pinned to 2022-07-06 and the hundredths come from the microseconds (truncated).
- **NUM-INTC-001/003, stored signs.** A computed zero is stored as `{`. For an overflow whose low-order digits are all zero, the sign of the true result is kept (as NUM-POST-003 describes for POSTTRAN): positive gives `{`, negative gives `}`. The spec only shows the positive case for this program.
- **NUM-INTC-003, total interest.** `WS-TOTAL-INT` is wrapped to 11 digits after each addition (no size error), before it is added to `ACCT-CURR-BAL`, which is then wrapped to 12 digits.
- **NUM-INTC-001, reading zoned digits.** The spec does not cover non-digit characters in the numeric positions. Decision: the low 4 bits of each byte are taken as the digit; a plain digit in the sign byte is positive (input contract).
- **BR-INTC-006, cycle fields.** Both cycle fields are written as `00000000000{`, whatever they held; every other byte of the account record is unchanged.
- **BR-INTC-007, several cards.** Locally the first card in ascending card-number order (status `00`); the z/OS status `02` abend (DEF-INTC-007) is not reproduced.
- **Input contract, duplicate keys.** The spec does not say what happens when a KSDS text file holds two records with one key. Decision: the first record in file order is kept. Lines longer than the record length are cut to the record length.
- **NUM-INTC-004, PARM.** First line of `parm.txt` without CR/LF, first 10 characters, padded with spaces when shorter; an empty line gives 10 spaces. `2022071800` when the file is absent.
- **ERR-INTC-003/004, missing account or cross-reference.** Status `23` is printed as `FILE STATUS IS: NNNN0023`. The order is: `ACCOUNT NOT FOUND: ` + the 11-character account ID from the balance record, the error line, the status line, `ABENDING PROGRAM`. The account check runs before the cross-reference check, so a balance whose account and cross-reference are both missing only reports the account.
- **ERR-INTC-009, abend outputs.** On an abend, `TRANSACT.txt` holds the transactions written before it, `ACCTDATA.after.txt` holds the accounts rewritten before it (DEF-INTC-009), `rc.txt` is 16, and `stderr.txt` holds `CEE3ABD: USER ABEND U0999`. When `acctdata.txt` is missing, `ACCTDATA.after.txt` is written empty.

## Contradictions

- NUM-INTC-005 says the clock is read once per transaction. The task contract asks for timestamps from one moment of the run. The equivalence contract (§15) only requires the layout, the run date and equality of the two fields, so both readings satisfy it; one reading per run was chosen.
- The runbook behaviour quoted in DEF-INTC-001 (last account not updated) is described as fixed. The implementation follows the fixed behaviour (§6 step 3).

## Not implemented

- I/O failures other than "file missing" on open and "record not found" on random reads: read, write, rewrite and close errors (ERR-INTC-002, ERR-INTC-006, ERR-INTC-007, the close messages of ERR-INTC-001). Text files cannot produce them locally. The messages are listed in the spec, and the status display routine (ERR-INTC-008, including the non-numeric form) is implemented.
- z/OS-only behaviour: status `02` on the alternate-key read (DEF-INTC-007), deletion of `SYSTRAN(+1)` on abend (DEF-INTC-009) and storage contents after a short PARM (DEF-INTC-010). The local GnuCOBOL behaviour is reproduced instead.
- Fees (DEF-INTC-004): the paragraph is empty in the program, so nothing is computed.
