"""Run the TRANREPT (CBTRN03C) equivalence check: GnuCOBOL build vs the spec-only reimplementation.

Usage: python3 run_check.py [--stress] [--keep DIR]
Needs `make build`. Exits 0 when every comparison passes.

Both sides read the same sorted TRANFILE (tranfile.txt) and DATEPARM record
(dateparm.txt). For the sample data they are made the way the local job makes
them: POSTTRAN on the sample day, then the REPRO and SORT stand-in.
"""
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
sys.path.insert(0, str(HERE))
import d1_harness  # noqa: E402
import harness  # noqa: E402
from compare import compare  # noqa: E402


def prepare(data: Path, root: Path) -> None:
    harness.run_reference("POSTTRAN", data, root / "pre", ["TRANSACT"])
    recs = [r.encode("latin-1") for r in harness.read_records(root / "pre" / "TRANSACT.after.txt")]
    start, end = harness.carddemo.REPORT_START.encode(), harness.carddemo.REPORT_END.encode()
    recs = sorted((r for r in recs if start <= r[304:314] <= end), key=lambda r: r[262:278])
    harness.write_records(data / "tranfile.txt", recs)
    (data / "dateparm.txt").write_text(harness.carddemo.DATEPARM + "\n")


if __name__ == "__main__":
    sys.exit(d1_harness.run_check("TRANREPT", HERE, "tranrept_from_spec.py", (), compare, prepare=prepare))
