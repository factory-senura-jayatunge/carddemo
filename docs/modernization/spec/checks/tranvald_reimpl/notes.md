# TRANVALD spec-only reimplementation notes

## Ambiguities and decisions

- ERR-TVAL-001 (open failures): the spec does not say which conditions make an open fail locally. Decision: a missing input text file is treated as OPEN status `35` (format.md glossary: `35` file missing), and the program prints the file's open message, `FILE STATUS IS: NNNN0035` and `ABENDING PROGRAM`, then abends (rc 16). Files are checked in the open order of section 6. TRANFILE has no text file and always opens.
- ERR-TVAL-001 (close failures), ERR-TVAL-002 (read errors other than `10`), ERR-TVAL-003 (lookup failures other than `23`): the spec says these are not reachable locally; with text inputs there is no way to provoke them. Decision: not modelled; the status-display routine (ERR-TVAL-004) is implemented for the open path only.
- BR-TVAL-002 key: the lookup key is the raw 16 characters of `DALYTRAN-CARD-NUM` (positions 263-278), compared byte for byte with `XREF-CARD-NUM`. A blank card number is just a key of spaces; it is not found unless the cross-reference has a blank key.
- BR-TVAL-003 / NUM-TVAL-001 key and display: the spec says `XREF-ACCT-ID` is `PIC 9(11)` and is displayed as 11 digits. Decision: the 11 bytes at positions 26-36 are displayed and used as the account key exactly as stored (no numeric normalisation), so non-digit content would be shown as is. `XREF-CUST-ID` likewise (positions 17-25).
- Input lines longer than the record length: the contract only covers short lines. Decision: truncate to the record length.
- Duplicate keys in an indexed file: the contract says they do not occur; the first record is kept if they do.
- BR-TVAL-001 record line: displayed as the full 350 characters after padding, so trailing spaces are kept.

## Contradictions

- None found between CBTRN01C.md, entities.md and the input contract. The header comment oddity (DEF-TVAL-007) has no effect on output.

## Not implemented

- Close failures (ERR-TVAL-001, including DEF-TVAL-001), daily-file read errors (ERR-TVAL-002) and other lookup failures (ERR-TVAL-003 / DEF-TVAL-005): not reachable with the local text-file inputs, so there is no condition to trigger them.
- CUSTFILE and CARDFILE contents are not loaded (DEF-TVAL-003: opened only); only their presence is checked for the open.
