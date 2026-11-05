# CBSTM03B spec-only reimplementation: notes

## (a) How it was run on the sample

    python3 cbstm03b_from_spec.py --data sample --out out-sample

Result: 460 lines in `out-sample/calls.txt`, each 1006 bytes plus `\n` (`RC=`, 2 bytes, `|`, 1000 bytes); `out-sample/rc.txt` is `0\n`.

Plausibility checks (no expected output was available):
- `TRXFL.ps` holds 300 records; the 301 `TRNXFILE R` requests return `00` 300 times, then `10`. The 300 data areas equal the records sorted by their 32-byte key, each followed by 650 spaces. The sample file was already in key order.
- `XREFFILE R` returns 50 records, then `10`. After each one, `CUSTFILE K` (length 9) and `ACCTFILE K` (length 11) return `00` with the customer or account whose ID is in that cross-reference record.
- The `O` and `C` requests return `00` and leave the data area as it was (so the closes show the last account record).

Edge checks (`out-edge/`, run by `out-edge-test.py`): all the account-key examples in NUM-STIO-002 give the stated results; X'0000' for a no-I/O request before the first I/O; `35`, `47` and `42` for a missing file; `41` on a second open; `10` and then `46`; `23` on a missing key; an unknown or lower-case DD keeps the previous RC; reopening starts from the first record again.

## (b) Ambiguities and gaps, with the decisions taken

1. **How the driver stores the key length** (NUM-STIO-001, DEF-STIO-001). The task says the driver "sets" the key length from columns 10-13, but not whether it moves it as a number (stored as `000I` with an overpunched sign) or as the raw characters. I assumed a numeric move into `S9(4)`: 3 digits followed by `{` or `A`-`I`. This only matters for key lengths from 26 upward, where the key text runs into these bytes. A non-digit in columns 10-13 counts as 0 in that position.
2. **Key length outside 1-25** is not part of the contract (section 15). I implemented it anyway:
   - Length 0 gives an empty text, so the customer key is 9 spaces and the account key is `00000000000`, as in DEF-STIO-001.
   - Lengths from 26 to 1029 take bytes from the request area past the key: the key-length bytes, then the data area.
   - Above 1029 the text stops at the end of the 1040-byte area. The caller storage beyond it is unknown.
   - Negative lengths cannot occur, because the request line has 4 unsigned digits.
3. **Duplicate keys in a data file.** The spec says only that each file is an indexed file loaded from the records. I keep the first record with a given key, which assumes the loader rejects later duplicates with a write status of `22`.
4. **File length that is not a multiple of the record length.** I ignore the trailing partial record.
5. **Empty data file (0 bytes).** I treat it as an existing, empty indexed file: open returns `00` and the first read returns `10`. The spec does not cover this case; Q-STIO-004 asks the related question for z/OS.
6. **Record order.** "Read next" uses ascending unsigned byte order of the primary key (memcmp, as Berkeley DB compares keys). Keys are the first 32, 16, 9 and 11 bytes of the record.
7. **What "dataset missing" means.** The open returns `35` when `<data>/<file>.ps` is not a regular file. Every open re-reads the file, so a later reopen sees the same contents.
8. **Status after `46`.** ERR-STIO-001 says `46` repeats "any number of times". I keep returning `46` until the file is closed; a close followed by an open resets the position.
9. **`41` on a second open** leaves the file open and keeps the read position, including the end-of-file state. **`23`** does not affect the next keyed read.
10. **Requests file parsing.**
    - Lines are split on `\n`, and a trailing `\r` is removed.
    - The empty string after the final newline is not a request.
    - An empty line in the middle counts as a request of all spaces: an unknown DD, so a line is printed with the RC and data area unchanged.
    - Short lines are padded with spaces, and characters after column 38 are ignored.
11. **Initial request area.** All spaces, as the task says, so the first lines show RC `  ` for an unknown DD. The status fields start as X'0000' (NUM-STIO-003).
12. **White space in NUM-STIO-002** is exactly X'09'-X'0D' and X'20'. Bytes above X'7F' are treated as ordinary non-digits.

## (c) Contradictions between spec files

- None that change the behaviour.
- Minor: section 11 of CBSTM03B.md says the unit-test driver presets the RC to `..` and the data area to spaces on every request. The harness described in TASK.md keeps both from the previous call. I followed TASK.md, which matches the persistent-area behaviour that BR-STIO-002 and the equivalence contract describe.
- Minor: the entity catalogue gives `XREF-CUST-ID` and `CUST-ID` as `9(09)` digits, but BR-STIO-003 treats the customer key as plain text compared byte for byte. I followed BR-STIO-003; there is no conversion.
