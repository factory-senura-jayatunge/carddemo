---
name: add-batch-test
description: >-
  Use when adding a test for a changed CardDemo batch program, a bug regression or a coverage drop, keeping paragraph coverage above the minimum. NOT for CICS, DB2, IMS or MQ.
metadata:
  version: '1.0.0'
---

# Add a batch test

Tests live in `tests/unit/` (subroutines through COBOL drivers in `tests/unit/cobol/`, plus tool tests) and `tests/integration/` (whole jobs). `tests/run_tests.py` runs them in parallel in random order, measures paragraph coverage from a `-ftrace` build, and fails below the minimum in `tests/test-config.toml` (85%).

## Steps

1. Find what is missing. Run the full suite and read the per-program coverage:

   ```sh
   make coverage
   python3 -c "import json; c = json.load(open('build/test-results/coverage.json')); print({p: v['missed'] for p, v in c['programs'].items() if v['missed']})"
   ```

   Each `missed` entry is a paragraph no test reached. CBSTM03A always shows 0% because it cannot run locally.

2. Find the existing test for the job: `grep -rl '"CBIMPORT"' tests/integration`. Add a method there, or a new `tests/integration/test_<job>.py`. Naming is enforced by the runner: files `test_*.py`, classes ending in `Test`, methods starting with `test_`.

3. Write the test on `tests.support.CardDemoTestCase`. Every test gets a private scratch directory. The helpers:
   - `self.run_job("POSTTRAN")` runs a job and the jobs it depends on; it returns `(work, results)`.
   - `work = self.new_work()`, then `work.load_ksds(dataset, records)` or `carddemo.write_fixed(work.dataset_path(dataset), records, lrecl)` to replace an input, then `self.run_job(name, work)`. `work.run_job(carddemo.JOBS[name])` runs one job without its prerequisites.
   - `self.assert_rc(result, rc)`, `result.sysout`, and `result.outputs[dd]` as `(path, record_count)`. Read outputs with `carddemo.read_fixed(path, lrecl)` and indexed files with `work.unload_ksds(dataset)`.
   - `tests/oracle.py` decodes and builds records from the copybook layouts (`oracle.set_fields`, `oracle.build_record`, `oracle.records_of`) and models the business rules (`oracle.expected_posting`).

   Derive expected values from the rule or the copybook, never by pasting what the program printed.

   Worked example: a test that reaches CBIMPORT's 2700-PROCESS-UNKNOWN-RECORD and 2750-WRITE-ERROR. An export record (layout `app/cpy/CVEXPORT.cpy`: type X(1), timestamp X(26), sequence 9(9) COMP, data) with an unknown type must go to ERROUT, laid out as `WS-ERROR-RECORD` in `app/cbl/CBIMPORT.cbl`:

   ```python
   from tests.support import CardDemoTestCase, carddemo


   class ImportUnknownRecordTest(CardDemoTestCase):
       def test_unknown_record_type_goes_to_errout(self):
           work, results = self.run_job("CBEXPORT")
           self.assert_rc(results[-1], 0)
           exported = work.unload_ksds("EXPORT")
           seq = max(int.from_bytes(r[27:31], "big") for r in exported) + 1
           unknown = b"Z" + b" " * 26 + seq.to_bytes(4, "big") + b" " * 469
           work.load_ksds("EXPORT", exported + [unknown])

           res = work.run_job(carddemo.JOBS["CBIMPORT"])
           self.assert_rc(res, 0)
           path, count = res.outputs["ERROUT"]
           self.assertEqual(count, 1)
           err = carddemo.read_fixed(path, 132)[0].decode()
           self.assertEqual(err[26:37], f"|Z|{seq:07d}|")
           self.assertEqual(err[37:87].rstrip(), "Unknown record type encountered")
   ```

4. Run only the new test. Filter with `test-integration` or `test-unit`; they never apply the coverage minimum, so the run passes or fails on the selected tests alone:

   ```sh
   make test-integration TEST_ARGS="-k unknown_record"
   ```

5. Run the whole suite and confirm the paragraphs you targeted are no longer in `missed` and the total did not drop:

   ```sh
   make coverage
   ```

6. Finish with `make check`; it must end with `make check: OK`.

## New programs and jobs

- A COBOL program that should build locally needs a `[[program]]` entry in `tools/batch-programs.toml`; one that cannot needs an `[[exclude]]` entry with the reason. `tests/unit/test_manifest.py` fails otherwise. Coverage is measured over every `[[program]]`.
- A new runnable job needs a `Job(...)` entry in `JOBS` in `tools/carddemo.py`, with the DD names from its JCL; check it with `make jobs`.
- If the program set changes, regenerate `docs/inventory.md` with `python3 scripts/gen_inventory.py`.

## Flaky or order-dependent tests

The seed is printed on every run; repeat a run with `CARDDEMO_TEST_SEED=<seed> make test`. A test that passes only on retry is reported as flaky. Fix it rather than raising the retries.
