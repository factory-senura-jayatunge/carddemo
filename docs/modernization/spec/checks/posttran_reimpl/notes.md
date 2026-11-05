# POSTTRAN (CBTRN02C) reimplementation from the NL spec: notes

Script: `posttran_from_spec.py` in this folder (Python 3, standard library only), written during the pilot from the CBTRN02C spec v3, entities.md and format.md.
Rerun: `python3 posttran_from_spec.py --out DIR` (`--data` defaults to `app/data/ASCII`), or the whole comparison with `run_check.py`. The script exits with the job return code (4 on this data). It prints a one-line count summary on stderr.

Inputs read: only `dailytran.txt`, `cardxref.txt`, `acctdata.txt`, `tcatbal.txt` from `app/data/ASCII`. No source, JCL, tests, tools or reference outputs were opened.

## (a) Counts

| Item | Value |
| :-- | --: |
| Processed | 300 |
| Posted | 262 |
| Rejected, total | 38 |
| Rejected 100 (invalid card) | 0 |
| Rejected 101 (account not found) | 0 |
| Rejected 102 (over limit) | 38 |
| Rejected 103 (after expiration) | 0 |
| New category balance records | 50 (all type `03` category `0001`; 100 in total after the run) |
| `TCATBAL record not found ...` lines | 50 |
| Return code | 4 |

Output files: `DALYREJS.txt` (38 x 430 bytes), `TRANSACT.after.txt` (262 x 350), `ACCTDATA.after.txt` (50 x 300), `TCATBALF.after.txt` (100 x 50), `sysout.txt` (54 lines), `rc.txt` (`4`). All with `\n` line endings, no `\r`. Duplicate keys in the input KSDS files: none.

These counts match the sample-day figures in spec section 15. That section states the expected result, so a reimplementer can check against it; the requester may want to keep that in mind when judging independence.

## (b) Ambiguities and gaps, with decisions

1. **`cardxref.txt` records are 36 bytes, not 50** (entities.md ENT-CARD-XREF says `RECORDSIZE(50 50)`, FILLER at 37-50). The spec does not say the sample file omits the FILLER. Decision: right-pad each line with spaces to 50 bytes. It does not affect any output because only bytes 1-36 are used.
2. **`tcatbal.txt` has mixed line endings (CRLF and LF).** The spec does not mention this; the task brief says to strip line endings. Stripping only `\r\n` or `\n` gives 50-byte records.
3. **Zoned-decimal encoding of results** (NUM-POST-001, format.md glossary). The docs describe decoding (`{`, `A`-`I` positive, `}`, `J`-`R` negative), but not how the program *writes* a result: whether zero is written as `{` or `}`, and whether a positive result always gets an overpunch rather than a plain digit. Decision: positive and zero values are written with `{`/`A`-`I`, negative values with `}`/`J`-`R`, and a zero result is always written as positive `{`.
4. **Negative zero on input** (NUM-POST-001 says "treated as zero"). It is not clear whether that means "treated as >= 0", so the amount goes to cycle credit (BR-POST-005), and whether a stored `-0` is re-encoded as `+0` on rewrite. Decision: -0 decodes to 0, so it counts as >= 0 (credit), and it is re-encoded as `{`. No sample amount is zero, so this decision is not exercised.
5. **Plain digit in the sign position, and invalid characters.** The spec does not say what happens when the last byte is a plain digit, or when a zoned field holds spaces or other non-digits. Decision: a plain digit is read as positive. Any other character stops the script with an error, which is not a modelled abend. The sample data does not hit this case.
6. **Which account fields are re-encoded on rewrite** (BR-POST-005, section 5 "rewritten unchanged"). Decision: splice only `ACCT-CURR-BAL` and the one cycle field that changed (credit or debit). Every other byte, including the cycle field that did not change, keeps its original bytes. With a normalised encoding this matters only for non-canonical input bytes, such as a stored `-0`.
7. **Category balance rewrite.** BR-POST-004 says "add and rewrite". Decision: only bytes 18-28 (`TRAN-CAT-BAL`) are re-encoded. Key and FILLER keep their read bytes.
8. **New category FILLER source** (DEF-POST-010). The spec says "the last category balance record read or written". Decision: track the FILLER of the last *successfully* read TCATBAL record, starting as 22 low-values (X'00'). A not-found read (status 23) does not change it, and writing a new record does not change it either, because the new record already carries the inherited FILLER. On the sample data every new record follows at least one successful read (the type `01` records), so all 50 new records get 22 zeros. The spec does not state that a failed read leaves the record area unchanged; I inferred it.
9. **Overflow truncation** (NUM-POST-003). The spec says "keeps only its low-order digits" but not what happens to the sign. Decision: magnitude modulo 10^digits, sign kept, and a truncated zero is written as positive. This is not reachable with the sample data.
10. **Processing timestamp** (NUM-POST-007). The time of day comes from the wall clock at each posting. The date is pinned to 2022-07-06. Hundredths are truncated from microseconds. Whether the clock is read before or after the category and account updates does not matter.
11. **Output sort order** (task brief: "sorted by key"). VSAM KSDS order on z/OS is EBCDIC collation. Decision: plain byte (ASCII) order. All keys are digits, so the two orders agree.
12. **Abend behaviour in the reimplementation** (ERR-POST-004, ERR-POST-008). Only the duplicate `DALYTRAN-ID` abend is modelled. The script prints `ERROR WRITING TO TRANSACTION FILE`, `FILE STATUS IS: NNNN0022`, `ABENDING PROGRAM` to sysout and `CEE3ABD: USER ABEND U0999` to stderr, and sets return code 16. The category and account updates for the failing record are kept (DEF-POST-008). The spec does not say whether the counts and end line are printed on abend. Decision: they are not, because the abend happens inside the loop. The spec also does not say what the output files contain after an abend. Decision: write the state reached so far. Open, read and close errors, and errors on the TCATBAL or account I/O, cannot occur in an in-memory model, so they are not modelled. ERR-POST-006 (reason 109) therefore never fires.
13. **Short or long daily records.** The spec says the reject record holds bytes 1-350 "exactly as read" but does not say what happens when a line is not 350 bytes. Decision: such a line stops the script with an error. All 300 sample lines are 350 bytes.
14. **Sysout "error lines of section 10"** (section 4.3). The spec does not say whether these go to SYSOUT or stderr. Decision: the program's DISPLAY lines go to sysout, and only the shim's `CEE3ABD:` line goes to stderr.
15. **Account key used for the category key.** NUM-POST-005 says `XREF-ACCT-ID` "as 11 digits". Decision: use the 11 bytes at cross-reference positions 26-36 unchanged, with no numeric reformatting.

## (c) Contradictions found

- No hard contradiction between the spec, entities.md and format.md.
- Minor mismatch: entities.md gives ENT-CARD-XREF as a 50-byte record, but the ASCII sample `cardxref.txt` has 36-byte lines (see b1).
- Minor wording tension: section 5 lists `DALYTRAN-PROC-TS` and FILLER as "ignored (replaced on posting)", but the reject record copies the full 350 bytes including them (section 4.2). These two statements are consistent once read carefully: the fields are ignored for posting, not for rejects.
- format.md's example IDs (`BR-POST-003 over-limit check`, `NUM-POST-001 timestamp layout`, `ERR-POST-001 I/O error abends`) do not match the CBTRN02C spec, where the over-limit check is BR-POST-001, BR-POST-003 is "last failing check", and the timestamp is NUM-POST-007. The examples are illustrative only, but a reader cross-referencing them would be misled.
