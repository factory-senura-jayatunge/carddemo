"""Run the INTCALC (CBACT04C) equivalence check: GnuCOBOL build vs the spec-only reimplementation.

Usage: python3 run_check.py [--stress] [--keep DIR]
Needs `make build`. Exits 0 when every comparison passes.

For the sample data the account master and category balances are taken after
the sample POSTTRAN day (as in the batch cycle), so the balances are not zero.
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
    harness.run_reference("POSTTRAN", data, root / "pre", ["ACCTDATA", "TCATBALF"])
    for name, file in (("ACCTDATA", "acctdata.txt"), ("TCATBALF", "tcatbal.txt")):
        (data / file).write_bytes((root / "pre" / f"{name}.after.txt").read_bytes())


if __name__ == "__main__":
    sys.exit(d1_harness.run_check("INTCALC", HERE, "intcalc_from_spec.py", ("ACCTDATA",), compare, prepare=prepare))
