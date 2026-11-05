# CBEXPORT reimplementation from spec: notes

## (a) How it was run and what was checked

Program: `cbexport_from_spec.py` (Python 3, standard library only).

    python3 cbexport_from_spec.py --data sample --out scratch/out_sample --clock "2022-07-06 12:00:00"

Sample result: 20 SYSOUT lines exactly as in CBEXPORT section 4.3, counts 50/50/50/262/50, total 462;
`EXPORT.ps` is 231,000 bytes (462 x 500); `rc.txt` is `0`; `stderr.txt` is empty. This matches the
sample-day figures stated in section 4.3 and section 15. Spot checks on the output: record 1 header is
`C`, `2022-07-06 12:00:00.00`, X'00000000', X'00000001', `0001`, `NORTH` (as in the ENT-EXPORT-RECORD
sample observation); record 462 has sequence X'000001CE'; account 1 balance `00000012881{` becomes
X'0000000128810C'; cycle debit `00000000707P` becomes the two's complement of 7077 cents.

Edge checks (`scratch/test_edges.py`, all pass):
- Every worked example in NUM-EXPT-002..004 and NUM-EXPT-006 (packed with pad nibble, 11-digit packed
  without pad, negative zero kept as `D` in packed and turned into 0 in binary, plain-digit sign byte,
  FICO `F` sign, `99Z` CVV = X'03E8', `99999999Z` customer = X'3B9ACA00', all-space amounts = zero,
  sign bytes `X` and `Z`).
- Records shuffled in every input file: output byte-identical to the sorted sample (BR-EXPT-001).
- Non-blank input FILLER: output unchanged (BR-EXPT-009).
- All five inputs empty (0-byte files): empty `EXPORT.ps` created, all counts 0, rc 0.
- Each input missing in turn: three SYSOUT lines (start, `ERROR: Cannot open <NAME>, Status: 35`,
  `CBEXPORT: ABENDING PROGRAM`), stderr `CEE3ABD: USER ABEND U0000`, rc 16, no `EXPORT.ps`.
- Card key with letters (`ABCD000000000001`): sorted after all-digit keys (NUM-EXPT-008).
- Another clock: timestamp and the two date/time lines follow it.

## (b) Ambiguities and gaps, with the decision taken

1. Missing input (ERR-EXPT-001, TASK.md): status `35` for any absent input. EXPFILE is opened only after
   the five inputs, so when an input is missing the export file is never created and `EXPORT.ps` is not
   written. When all five inputs exist, `EXPORT.ps` is always written, possibly empty.
2. An empty (0-byte) dataset is treated as present: it is an empty KSDS, the open succeeds and the first
   read returns end of file (BR-EXPT-001 edge case).
3. Duplicate primary keys in an input dataset: the spec does not say how the loader handles them.
   Decision: keep the first occurrence in file order, drop later ones (as a keyed WRITE would get
   status 22).
4. Dataset size not a multiple of the record length: not covered. Decision: pad the short last record
   with spaces to the full length.
5. Key collation (NUM-EXPT-008): plain byte comparison of the key bytes (Python bytes ordering), so
   low-values, spaces, digits, letters and bytes above X'7F' sort by their byte value.
6. Non-digit bytes in numeric fields (NUM-EXPT-006): implemented literally. Unsigned fields: every byte
   gives its low 4 bits. Signed fields: every byte except the last gives its low 4 bits; the last byte
   follows the overpunch table of NUM-EXPT-003 (`{`, `A`-`I`, `}`, `J`-`R`, `0`-`9`), and any other byte
   is positive with its low nibble as the digit if 0-9, else 0. Packed targets store the nibbles as is
   (values A-F possible); binary targets accumulate value x 10 + nibble. The spec does not say what
   happens for bytes above X'7F' or ASCII-style sign bytes (`p`-`y`) in the sign position; they fall
   under the "anything else" rule.
7. Binary overflow: the spec says none can happen; even with the largest nibble (15) in every position
   the accumulated values fit, but the code reduces modulo 2^(8 x size) for safety.
8. Timestamp bytes 24-27 (NUM-EXPT-001, DEF-EXPT-003): X'00000000' as observed locally and required by
   section 15.
9. Clock (NUM-EXPT-001, DEF-EXPT-004): the `--clock` value is taken as both clock reads, hundredths 00.
   The string is assumed well formed; date = characters 1-10, time = characters 12-19.
10. Read and write failures (ERR-EXPT-002, ERR-EXPT-003) cannot occur with the harness's inputs (keys are
    strictly ascending, no read error can be provoked), so they are not modelled beyond the messages
    being unreachable.
11. Counter overflow (NUM-EXPT-007, DEF-EXPT-014): counters wrap modulo 10^9; the resulting key-sequence
    write failure is not modelled (needs 10^9 records).
12. Standard error: only the CEE3ABD line is written; the libcob `implicit CLOSE` warnings
    (ERR-EXPT-001, ERR-EXPT-004) are not reproduced, since TASK.md compares only the CEE3ABD line.

## (c) Contradictions or tensions between the spec files

- No hard contradiction found between CBEXPORT.md and d2-batch-utilities-entities.md: positions, lengths,
  encodings and FILLER sizes (134, 352, 427, 140, 373) agree.
- entities.md gives the batch DD of ENT-TRANSACTION as `TRANFILE` (POSTTRAN); CBEXPORT uses DD
  `TRANSACT` for the same dataset. Different jobs, not a conflict, but the catalog lists only one DD.
- ENT-EXPORT-RECORD calls the key position "position 28 (offset 27)" while the IDCAMS definition says
  `KEYS(4 28)`; both files flag this as DEF-EXPT-002. The local behaviour (offset 27) was used.
- CBEXPORT section 1 says "ends with return code 0"; section 4.4 and ERR-EXPT-004 give 16 locally on
  abend. Read as: 0 for a normal run.
- format.md glossary describes U0999 for CEE3ABD abends; CBEXPORT passes no code and the local shim
  prints U0000 (DEF-EXPT-005). The program spec was followed.
- d2-batch-utilities-entities.md points to `programs/CBEXPORT.md`; the file supplied here is
  `spec/CBEXPORT.md` (path difference only).
