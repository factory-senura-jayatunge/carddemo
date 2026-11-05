# CBIMPORT reimplementation from spec: notes

Program: `cbimport_from_spec.py` (Python 3, standard library only).
Run: `python3 cbimport_from_spec.py --data SCENARIO_DIR --out OUT_DIR --clock "YYYY-MM-DD HH:MM:SS"`.
Self-test: `python3 scratch/selftest.py` (prints `FAILS []` when every check passes).

## (a) How it was run and what was checked

1. Sample: `python3 cbimport_from_spec.py --data sample --out scratch/out_sample --clock "2022-07-06 12:00:00"`.
   - stdout: start line, `Import Date: 2022-07-06`, `Import Time: 12:00:00`, the two validation lines, `Import completed`, and counts 462 read, 50 C, 50 A, 50 X, 262 T, 50 D, 0 errors, 0 unknown. RC 0, empty stderr. These match the sample-day figures in the spec's equivalence contract (section 15).
   - File sizes: CUSTOUT 25000 (50 x 500), ACCTOUT 15000 (50 x 300), XREFOUT 2500 (50 x 50), TRNXOUT 91700 (262 x 350), CARDOUT 7500 (50 x 150), ERROUT 0.
   - Content: every FILLER area (BR-IMPT-004) is spaces, no output holds X'00', customer IDs are `000000001`, `000000002`, ..., every TRAN-AMT is 10 digits followed by an overpunch character, and CVV 747 (X'02EB') comes out as `747`. Account 1 decodes to the values in the ENT-ACCOUNT sample: limit `00000020200{` (2,020.00), cash limit `00000010200{` (1,020.00), dates 2014-11-20 and 2025-05-20, and cycle debit -70.77 shown as `00000000707P`. I could not compare against the source VSAM files because I have no copy of them.
2. Unit checks of the conversions against every example given in NUM-IMPT-001, 002, 003, 004 and 006 (truncation, negative zero, X'D' and the other sign nibbles, invalid half-bytes, the ignored leading nibble). All pass.
3. Edge scenario (`scratch/edge/EXPORT.ps`, clock 2024-02-29 08:15:30): records in shuffled order with keys 0, 5, 7, 10,000,000, 12,345,678, 99,999,999, 2,147,483,648 and 4,294,967,295; unknown types `c`, X'00', X'FF', space and `9`; non-blank header, branch and region bytes. Checked:
   - records are processed in ascending key order;
   - error sequence numbers are `0000005`, `0000000`, `2345678`, `9999999` and `7483648`;
   - each error record is 132 bytes and matches the layout of section 4.3, with bytes 88-132 all spaces;
   - the counters are 8 read, 5 errors and 5 unknown;
   - a customer record that was re-keyed to 4,294,967,295 is byte-identical to its sample output.
4. Empty `EXPORT.ps`: all six outputs exist and are empty, all 8 counters are `000000000`, RC 0.
5. Missing `EXPORT.ps`: no output files are created. stdout is the start line, `ERROR: Cannot open EXPORT-INPUT, Status: 35` and `CBIMPORT: ABENDING PROGRAM`. stderr is `CEE3ABD: USER ABEND U0000`. RC 16.
6. Output that cannot be opened (I made `ERROUT.ps` a directory): the five earlier outputs exist and are empty, stdout shows `ERROR: Cannot open ERROR-OUTPUT, Status: 30` then the abend line, RC 16.
7. Running through the command line gives byte-identical files to the in-process run.

## (b) Ambiguities and gaps, with decisions

- **Export load (BR-IMPT-002, NUM-IMPT-012).** I sort records by bytes 28-31 compared as unsigned bytes. This is offset 27, the program's key, not the `KEYS(4 28)` of the JCL (DEF-IMPT-004).
- **Duplicate keys.** The spec says a KSDS cannot hold two records with the same key, but not what the loader does when the input has duplicates. Decision: keep the first record in file order and drop the later ones.
- **`EXPORT.ps` length not a multiple of 500.** Not specified. Decision: pad the short last record with spaces to 500 bytes.
- **`EXPORT.ps` exists but cannot be read, or is a directory; `--data` directory missing.** Only "missing" is specified (ERR-IMPT-007, status 35). Decision: all of these are treated as missing, so the run prints status 35 and abends.
- **Output open failure status (ERR-IMPT-001).** I use `30`, as the spec observed for an output path that cannot be created.
- **Write failure status (ERR-IMPT-003, ERR-IMPT-004).** These cannot happen in a local run and the spec gives no status value. Decision: `30` on an OSError. A failed normalized write abends without incrementing its counter. A failed ERROUT write prints the message, continues, and still increments the error count (DEF-IMPT-002).
- **Clock (NUM-IMPT-008, NUM-IMPT-010, DEF-IMPT-010).** Date and time come from `--clock`. The hundredths come from the real wall clock. The UTC offset is the process time zone's offset at the real current time (`tm_gmtoff`), formatted `+hhmm` or `-hhmm`. The six date/time reads and the one read per error record all use the same pinned value, so DEF-IMPT-010 has no visible effect. The `00000` form for an unknown offset is never produced.
- **stderr (ERR-IMPT-005).** Only `CEE3ABD: USER ABEND U0000` is written. The incidental `libcob: warning: implicit CLOSE ...` lines are left out, because the task compares only the shim line.
- **Packed conversion (NUM-IMPT-003, 004, 006).**
  - The sign nibble X'D' means negative and every other value means positive, including X'B'.
  - In the 7-byte fields the leading nibble is ignored.
  - A digit nibble of X'A'-X'F' becomes chr(0x30 + nibble). When it is the last digit of a signed output, the digit-0 overpunch character (`{` or `}`) is written instead.
  - FICO is unsigned, so an invalid last nibble stays chr(0x30 + nibble), as in the example X'12AC' giving `12:`.
- **Signed binary (NUM-IMPT-002).** The output keeps the low 12 digits of |I| and the sign of I, so a negative value whose kept digits are all zero is written as negative zero.
- **Counters (NUM-IMPT-011).** They are printed modulo 10^9. This cannot be reached in practice.
- **Close (ERR-IMPT-006, SE-IMPT-008).** Close errors are ignored.
- **Abend output files.** `stdout.txt`, `stderr.txt` and `rc.txt` are always written, including on an abend.
- **Type dispatch (BR-IMPT-001).** The type byte is compared exactly against `C`, `A`, `X`, `T` and `D`. Any other byte is copied unchanged into the error record, including non-ASCII bytes.

## (c) Contradictions between the spec files

- **Number of counters.** Section 1 of CBIMPORT.md says the program prints "nine counters". Sections 4.4 and 5 and FLOW-IMPT-004 list eight counter lines (section 5 also names eight counters). I followed section 4.4: eight counter lines after `Import completed`. Nine is probably counting the `Import completed` line.
- **Key position.** The program and spec use offset 27. The `KEYS(4 28)` cluster definition quoted in DEF-IMPT-004 and in ENT-EXPORT-RECORD uses offset 28. The task and the local build follow the program, so I used offset 27.
- **Packed pad nibble.** ENT-EXPORT-RECORD defines `packed(p,s)` with a pad nibble of `0` when p is even. NUM-IMPT-003 says the nibble is ignored even when it is non-zero. These do not really conflict, because one describes the writer and the other the reader. I followed NUM-IMPT-003.
- **Sign convention.** The `packed` type note in ENT-EXPORT-RECORD names only `C`, `D` and `F`. NUM-IMPT-003 defines the behaviour for every nibble value. I followed NUM-IMPT-003.
- **How the program is run.** The `local_run` line in the front matter says POSTTRAN and CBEXPORT run first. The task loads `EXPORT.ps` directly. I followed the task, so the program only reads `EXPORT.ps`.
