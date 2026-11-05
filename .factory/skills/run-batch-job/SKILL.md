---
name: run-batch-job
description: >-
  Use when running or inspecting one CardDemo batch job locally (POSTTRAN, INTCALC, TRANREPT, CBEXPORT, CBIMPORT, READ*): counts, return code, outputs, rejects. NOT for CICS, DB2, IMS or MQ.
metadata:
  version: '1.0.0'
---

# Run a batch job

The local runner (`tools/carddemo.py`) emulates the JCL: each job has the DD names from its JCL, loads the ASCII sample data from `app/data/ASCII` into a fresh scratch directory, and runs the compiled program.

## Steps

1. Check the toolchain. It must print `OK`:

   ```sh
   make check-toolchain
   ```

2. Pick the job. `make jobs` lists every runnable job with its program, JCL and DD mapping. You can name a job (`POSTTRAN`) or its program (`CBTRN02C`).

   ```sh
   make jobs
   ```

3. Run it. `make run-batch` builds first (incremental), clears `build/run/<JOB>`, loads the data and runs the job. Jobs that need another job's output run it first: TRANREPT and CBEXPORT run POSTTRAN, and CBIMPORT runs CBEXPORT.

   ```sh
   make run-batch PROGRAM=POSTTRAN
   ```

   To keep a run apart from the default directory, call the runner directly. `--workdir` must be empty or a directory the runner created:

   ```sh
   python3 tools/carddemo.py run POSTTRAN --workdir /tmp/posttran-run
   ```

4. Read the result. For each step the runner prints the SYSOUT, then `RETURN-CODE n (max accepted m)`, then one line per output DD with its record count and file name. The last line is `RESULT OK` or `RESULT FAILED`, and the command exits non-zero on failure. On the sample data POSTTRAN prints:

   ```text
   TRANSACTIONS PROCESSED :000000300
   TRANSACTIONS REJECTED  :000000038
   ---- POSTTRAN / CBTRN02C RETURN-CODE 4 (max accepted 4) in 0.07s ----
     TRANFILE     262 records  TRANSACT.ksds
     DALYREJS      38 records  POSTTRAN.DALYREJS.ps
     reject reason 0102 OVERLIMIT TRANSACTION: 38
   RESULT OK: CBTRN02C RETURN-CODE=4
   ```

   RETURN-CODE 4 is expected for POSTTRAN whenever a transaction is rejected.

5. Inspect the files in `build/run/<JOB>/`. `.ps` files are fixed-length records with no newlines, with the LRECL that `make jobs` shows (DALYREJS is 430: the 350-byte transaction, a 4-byte reason code and a 76-byte description). `.ksds` files are Berkeley DB indexed files; unload them through the runner. From the repository root:

   ```sh
   python3 - <<'EOF'
   import sys
   from pathlib import Path
   sys.path.insert(0, "tools")
   import carddemo

   work = carddemo.WorkDir(Path("build/run/POSTTRAN").resolve())
   rejects = carddemo.read_fixed(work.path / "POSTTRAN.DALYREJS.ps", 430)
   print(len(rejects), "rejects")
   for rec in rejects[:3]:
       print(rec[:16].decode(), rec[350:354].decode(), rec[354:].decode().strip())
   trans = work.unload_ksds("TRANSACT")
   print(len(trans), "posted transactions, first id", trans[0][:16].decode())
   EOF
   ```

   Pass an absolute path to `WorkDir` (hence `.resolve()`); with a relative path the unload fails with FILE STATUS 35.

## Variations

- Business date: the programs see 2022-07-06 unless you set `CARDDEMO_BUSINESS_DATE`, for example `CARDDEMO_BUSINESS_DATE=2022-07-18 make run-batch PROGRAM=POSTTRAN`. The tests assume the default.
- Other input data: point `CARDDEMO_DATA_DIR` at a directory with the same file names as `app/data/ASCII`. Edit synthetic records only; never use production data (see `docs/security/data-handling.md`).
- Record layouts: the copybook named in each program's `COPY` statements (for example `app/cpy/CVTRA05Y.cpy` for TRANSACT, `app/cpy/CVTRA06Y.cpy` for DALYTRAN) gives the field offsets.

## What does not run

- `make run-batch PROGRAM=CBSTM03A` stops with `CBSTM03A builds but cannot run locally` because it reads z/OS control blocks.
- CICS programs and the DB2, IMS and MQ modules are not built; `make list` shows each with the reason.
- The mainframe JCL itself is never executed. Steps such as IDCAMS, SORT or SDSF are not run, except where the runner reproduces their effect (TRANREPT's REPRO and SORT pre-step).
