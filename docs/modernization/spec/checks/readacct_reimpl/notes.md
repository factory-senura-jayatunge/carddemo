# READACCT spec-only reimplementation notes

## Ambiguities and decisions

- BR-ACRD-001 / input contract: the spec does not say what happens when acctdata.txt holds two records with the same key. Decision: the keyed load keeps the first record and drops later duplicates (a KSDS load rejects duplicate keys). Keys are sorted by their latin-1 bytes.
- NUM-ACRD-001: the spec covers only overpunch characters and plain digits in the sign byte. Decision: any other sign byte is read as positive, with the low 4 bits of the byte (mod 10) as the digit. The spec also does not say what happens to non-digit bytes in the 11 leading digit positions. Decision: they are displayed and copied unchanged, and the zero test of BR-ACRD-005 uses the low 4 bits of each such byte.
- NUM-ACRD-001 / BR-ACRD-003: a "copy with the same picture" is implemented as: the 11 leading bytes are kept and the sign byte is re-encoded as an overpunch character (a plain digit becomes the positive overpunch; a negative zero stays `}`).
- NUM-ACRD-004: the plain-digit rewrite in the record area is applied to all five signed amounts (CURR-BAL, CREDIT-LIMIT, CASH-CREDIT-LIMIT, CURR-CYC-CREDIT, CURR-CYC-DEBIT), because all five are displayed by BR-ACRD-002. Only the record line (BR-ACRD-008) is affected; ACCTDATA.after.txt keeps the bytes as read.
- NUM-ACRD-002: for a non-zero negative cycle debit the sign nibble is D. A negative zero never reaches the packed field, because BR-ACRD-005 replaces it with 2525.00.
- BR-ACRD-006: slot 3 holds -1025.00 as zoned decimal, written as `00000010250}` (negative overpunch on the last digit 0).
- ERR-ACRD-001: a missing acctdata.txt is treated as an ACCTFILE open failure with status 35. The output prints `ERROR OPENING ACCTFILE`, then `FILE STATUS IS: NNNN0035`, then `ABENDING PROGRAM`, with RC 16. The spec does not give the status for this case; 35 is an assumption.
- DEF-ACRD-001: stderr.txt gets the three implicit-CLOSE warnings at normal end (VBRC-FILE, then ARRY-FILE, then OUT-FILE), as the spec describes. The checker ignores this content.

## Contradictions

- None found between CBACT01C.md, entities.md and d1-core-batch-entities.md for the fields this program uses.

## Not implemented

- ERR-ACRD-002 (read errors other than end of file), ERR-ACRD-003 (write errors) and ERR-ACRD-004 (close errors): a text-file input and in-memory outputs cannot produce these statuses, so they are not simulated. Only the open failure of a missing input (ERR-ACRD-001, ERR-ACRD-005, ERR-ACRD-006) is modelled.
- ERR-ACRD-001 for the three output files: output directory failures are not mapped to file statuses.
- NUM-ACRD-005: the VBRCFILE record header is not written, because the output contract asks for data bytes only.
