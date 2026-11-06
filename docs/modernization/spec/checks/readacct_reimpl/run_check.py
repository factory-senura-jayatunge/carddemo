"""Run the READACCT (CBACT01C) equivalence check: GnuCOBOL build vs the spec-only reimplementation.

Usage: python3 run_check.py [--stress] [--keep DIR]
Needs `make build`. Exits 0 when every comparison passes.
The three extract files are compared as hex, one record per line.
"""
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
sys.path.insert(0, str(HERE))
import d1_harness  # noqa: E402
from compare import compare  # noqa: E402

if __name__ == "__main__":
    sys.exit(d1_harness.run_check("READACCT", HERE, "readacct_from_spec.py", ("ACCTDATA",), compare,
                                  hex_outputs=True))
