# Spec-only reimplementation checks

Each folder here holds one equivalence check for a batch program that runs locally (format.md, Scaling). A reimplementation is written from the spec, entities.md and format.md only, without opening the COBOL, JCL, tests or reference outputs. The check runs the GnuCOBOL build and the reimplementation on the same data and compares every output record, the return code and the sysout lines the spec names.

Run `make build` first. Then:

```sh
python3 docs/modernization/spec/checks/posttran_reimpl/run_check.py            # sample data
python3 docs/modernization/spec/checks/posttran_reimpl/run_check.py --stress   # edge-case data
```

Both exit 0 when every comparison passes. They work in a temporary directory unless `--keep DIR` is given.

`harness.py` holds the shared parts: `run_reference` runs a job from `tools/carddemo.py` on a chosen data directory and writes its sequential outputs (`<DDNAME>.txt`), unloaded KSDS files (`<DATASET>.after.txt`), `sysout.txt` and `rc.txt`; `Report` prints PASS/FAIL lines. A new check copies the `posttran_reimpl` layout:

| File | Contents |
| :-- | :-- |
| `<prog>_from_spec.py` | The reimplementation. Takes `--data DIR --out DIR` and writes the same file names as `run_reference`. Standard library only. |
| `gen_stress.py` | Builds an edge-case data directory from the sample data, aimed at the spec's business rules and defects. It may use `tests/oracle.py` for record layouts. |
| `compare.py` | `compare.py REF NEW`: which fields are masked (wall-clock timestamps) and which sysout patterns must match. |
| `run_check.py` | Runs reference, reimplementation and comparison; `--stress`, `--keep DIR`. |
| `notes.md` | The reimplementer's ambiguities, decisions and contradictions, which feed the spec's review. |

Results go in `evidence/<area>-review.md` with the counts and the commit they were run on.
