# TRANREPT spec-only reimplementation notes

Implementation: `tranrept_from_spec.py` in this folder, written only from `programs/CBTRN03C.md`, `entities.md`, `areas/d1-core-batch-entities.md`, `format.md` and the ERR-POST-007 and NUM-POST-003 items of `programs/CBTRN02C.md`.

## Ambiguities and decisions

- **§4.3 end-of-file displays, sign of a negative zero.** The spec gives the GnuCOBOL layout (11 digits and a trailing sign) but not how a negative zero is shown. Decision: `TRAN-AMT` shows the stored overpunch sign (`}` gives `-`). `WS-PAGE-TOTAL` shows `-` only when a negative sum truncated to zero (NUM-TRPT-001/NUM-POST-003). A sum that is exactly zero is positive.
- **§4.3 / BR-TRPT-003, non-numeric `TRAN-AMT` bytes.** Not specified. Decision: a digit counts as itself. Any other byte in the 10 leading positions counts as its low nibble when that is 0-9, otherwise 0. The last byte is decoded as overpunch, or else by the same rule as positive.
- **BR-TRPT-004, blank first card number.** The corrected spec says the account column then comes from a cross-reference record that was never read, but not what that record holds. Decision: like `TRAN-RECORD` (FLOW-TRPT-001), the working-storage cross-reference record has no `VALUE` and starts as binary zeros, so the account column is 11 X'00' bytes until the first successful lookup.
- **NUM-TRPT-002, edited zero and negative zero.** A zero magnitude prints as 15 spaces with either pattern, including a negative zero from truncation.
- **NUM-TRPT-001, overflow.** The true sum is reduced modulo 10^11 cents and keeps the sign of the true sum (as NUM-POST-003). Adding a page total of exactly zero to the grand total leaves the grand total unchanged.
- **BR-TRPT-003 / NUM-TRPT-003, category code and account ID.** These are copied as text from `TRAN-CAT-CD` and `XREF-ACCT-ID`, with no numeric normalisation.
- **ERR-TRPT-004, category key.** The 6-character key is `TRAN-TYPE-CD` + `TRAN-CAT-CD` as text. It is used both for the lookup and in the message.
- **BR-TRPT-007, order of lookups.** The cross-reference lookup happens on a card change, after the account total block of the previous card. Then the type lookup, then the category lookup, all before any header or page break of that detail. So an abend on a lookup leaves the previous card's account total block in the report, but not the new card's header or page block.
- **ERR-TRPT-001, missing input files.** The spec does not say what a missing local file does. Decision: a missing `tranfile.txt`, `cardxref.txt`, `trantype.txt` or `trancatg.txt` behaves as an OPEN failure with status 35 (the open message, `FILE STATUS IS: NNNN0035`, abend). A missing `dateparm.txt` is an empty DATEPARM (input contract). The open order follows §6 step 1, so a missing TRANFILE gives an empty report file.
- **Input loading.** Lines longer than the record length are cut to it. Lines that are empty or only spaces are skipped. In a KSDS text file with a repeated key, the first record wins.
- **BR-TRPT-001.** The dates are taken as the raw 10-character slices, columns 1-10 and 12-21 of the record padded to 80 bytes, and printed as they are in `Reporting from ...` and in the name header.

## Contradictions

Both contradictions found in the first version are resolved by the spec correction:

- Resolved (§4.2): the column header `Amount` field was given as columns 99-114 (16 positions) with a 14-character value. The spec now says it is a 16-character field with `Amount` in columns 107-112. The output did not change.
- Resolved (FLOW-TRPT-001): the spec said the initial work record of an empty input (assumed to be spaces) was below any start date, which is false for a blank start date. The spec now says it starts as binary zeros locally, below any start date, even a blank one. The work record now starts as X'00', so an empty input always gives an empty report.

## Not implemented

- CLOSE failures (ERR-TRPT-001 close messages), DATEPARM and TRANFILE read errors other than end of file (ERR-TRPT-002/003), report write errors (ERR-TRPT-007), and lookup failures other than "not found" (DEF-TRPT-005). None of them can occur with text inputs loaded as described in the input contract. The messages are documented in the spec, but the conditions cannot be reproduced locally.
- Status codes starting with `9` or non-numeric (ERR-POST-007 second form). No path produces them here.
- z/OS behaviour: the end-of-file displays with the sign folded into the last digit, and deletion of `TRANREPT(+1)` on abend. The GnuCOBOL behaviour is reproduced instead.
