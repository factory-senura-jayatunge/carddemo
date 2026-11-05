"""Run the INTCALC (CBACT04C) equivalence check: GnuCOBOL build vs the spec-only reimplementation.

Usage: python3 run_check.py [--stress] [--keep DIR]
Needs `make build`. Exits 0 when every comparison passes.
"""
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
sys.path.insert(0, str(HERE))
import d1_harness  # noqa: E402
from compare import compare  # noqa: E402

if __name__ == "__main__":
    sys.exit(d1_harness.run_check("INTCALC", HERE, "intcalc_from_spec.py", ("ACCTDATA",), compare))
