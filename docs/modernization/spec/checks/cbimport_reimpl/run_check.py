"""Run the CBIMPORT equivalence check: GnuCOBOL build vs the spec-only reimplementation.

Usage: python3 run_check.py [--stress] [--keep DIR]
Needs `make build`. The sample scenario is the export file that CBEXPORT (the
GnuCOBOL build) writes from the sample data after POSTTRAN, as in the CBIMPORT
job. --stress runs CBEXPORT on its own stress data first and derives the
gen_stress.py scenarios (interleaved types, unknown types, large sequence
numbers, edge encodings, empty and missing export file) from that export,
with other clock settings. Exits 0 when every comparison passes.
"""
import argparse
import importlib.util
import subprocess
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
EXPORT_CHECK = HERE.parent / "cbexport_reimpl"
sys.path.insert(0, str(HERE.parent))
import harness  # noqa: E402


def load(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


reference = load("cbimport_reference", HERE / "reference.py")
compare = load("cbimport_compare", HERE / "compare.py").compare
export_reference = load("cbexport_reference", EXPORT_CHECK / "reference.py")
sys.path.insert(0, str(EXPORT_CHECK))
export_check = load("cbexport_run_check", EXPORT_CHECK / "run_check.py")

DEFAULT_CLOCK = "2022-07-06 12:00:00"
STRESS = [("stress", "2024-02-29 08:15:30"), ("empty", "2023-12-31 23:59:58"), ("missing", DEFAULT_CLOCK)]


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--stress", action="store_true", help="use the edge-case scenarios from gen_stress.py")
    ap.add_argument("--keep", help="work in DIR and keep it (default: a temporary directory)")
    args = ap.parse_args()
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(args.keep).resolve() if args.keep else Path(tmp)
        root.mkdir(parents=True, exist_ok=True)
        data = root / "data"
        export_check.sample_scenario(root / "export-in" / "sample", root / "posttran")
        if args.stress:
            subprocess.run([sys.executable, str(EXPORT_CHECK / "gen_stress.py"), str(root / "export-in" / "sample"),
                            str(root / "export-in")], check=True)
            export_reference.run(root / "export-in" / "stress", root / "export-out", DEFAULT_CLOCK)
            subprocess.run([sys.executable, str(HERE / "gen_stress.py"), str(root / "export-out" / "EXPORT.ps"),
                            str(data)], check=True)
            runs = STRESS
        else:
            export_reference.run(root / "export-in" / "sample", data / "sample", DEFAULT_CLOCK)
            runs = [("sample", DEFAULT_CLOCK)]
        rep = harness.Report()
        for name, clock in runs:
            reference.run(data / name, root / "ref" / name, clock)
            subprocess.run([sys.executable, str(HERE / "cbimport_from_spec.py"), "--data", str(data / name),
                            "--out", str(root / "new" / name), "--clock", clock], check=False)
            compare(root / "ref" / name, root / "new" / name, name, rep)
        return rep.finish()


if __name__ == "__main__":
    sys.exit(main())
