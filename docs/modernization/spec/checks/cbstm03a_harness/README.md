# CBSTM03A harness

Not an equivalence check: CBSTM03A does not run unmodified off z/OS (its prologue walks the PSA, TCB and TIOT control blocks from address 0), so there is no spec-only reimplementation and `verify-spec` does not run this folder. The harness runs the program with only that prologue turned into comments, so the statement logic can be observed.

```sh
make build
python3 docs/modernization/spec/checks/cbstm03a_harness/run_harness.py OUTDIR              # sample data after POSTTRAN
python3 docs/modernization/spec/checks/cbstm03a_harness/run_harness.py OUTDIR --data DIR   # TRANSACT.ps, CARDXREF.ps, CUSTDATA.ps, ACCTDATA.ps
```

It copies `app/cbl/CBSTM03A.CBL` to a scratch directory, comments out lines 266-291 (every other line keeps its number), compiles the copy with the project flags, links it with the built CBSTM03B and CEE3ABD modules, builds TRXFL as `app/jcl/CREASTMT.JCL` STEP010 does, and runs it. It writes `OUTDIR/STMTFILE` (80-byte lines), `OUTDIR/HTMLFILE` (100-byte lines), `OUTDIR/sysout.txt` and `OUTDIR/rc.txt`. The repository source is never changed. The CBSTM03A spec (`programs/CBSTM03A.md`) marks the items verified this way.
