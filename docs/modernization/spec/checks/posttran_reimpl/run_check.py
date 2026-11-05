"""Run the POSTTRAN equivalence check: GnuCOBOL build vs the spec-only reimplementation.

Usage: python3 run_check.py [--stress] [--keep DIR]
Needs `make build`. Exits 0 when every comparison passes.
"""
import argparse
import subprocess
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
import harness  # noqa: E402
from compare import compare  # noqa: E402

UNLOAD = ["TRANSACT", "ACCTDATA", "TCATBALF"]


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--stress", action="store_true", help="use the edge-case data from gen_stress.py")
    ap.add_argument("--keep", help="work in DIR and keep it (default: a temporary directory)")
    args = ap.parse_args()
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(args.keep).resolve() if args.keep else Path(tmp)
        root.mkdir(parents=True, exist_ok=True)
        data = harness.SAMPLE_DATA
        if args.stress:
            data = root / "data"
            subprocess.run([sys.executable, str(HERE / "gen_stress.py"), str(data)], check=True)
        harness.run_reference("POSTTRAN", data, root / "ref", UNLOAD)
        subprocess.run([sys.executable, str(HERE / "posttran_from_spec.py"),
                        "--data", str(data), "--out", str(root / "new")], check=False)
        return compare(root / "ref", root / "new")


if __name__ == "__main__":
    sys.exit(main())
