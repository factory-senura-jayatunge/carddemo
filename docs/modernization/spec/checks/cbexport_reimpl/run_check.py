"""Run the CBEXPORT equivalence check: GnuCOBOL build vs the spec-only reimplementation.

Usage: python3 run_check.py [--stress] [--keep DIR]
Needs `make build`. The sample scenario is the sample data as CBEXPORT sees
it in the CBEXPORT job: POSTTRAN runs first on app/data/ASCII and the five
input files are unloaded from it. --stress derives the gen_stress.py
scenarios (edge values, empty inputs, missing inputs) from that sample and
runs them with other clock settings. Exits 0 when every comparison passes.
"""
import argparse
import subprocess
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
sys.path.insert(0, str(HERE))
import harness  # noqa: E402
import reference  # noqa: E402
from compare import compare  # noqa: E402

carddemo = harness.carddemo
DEFAULT_CLOCK = "2022-07-06 12:00:00"
STRESS = [("stress", "2023-12-31 23:59:58"), ("empty", "2024-02-29 08:15:30"),
          ("missing-card", DEFAULT_CLOCK), ("missing-cust", DEFAULT_CLOCK)]


def sample_scenario(scen: Path, work_dir: Path) -> None:
    """The five CBEXPORT inputs after POSTTRAN on the sample data, as fixed-length files."""
    old = carddemo.os.environ.get("CARDDEMO_DATA_DIR")
    carddemo.os.environ["CARDDEMO_DATA_DIR"] = str(harness.SAMPLE_DATA)
    try:
        work = carddemo.WorkDir.fresh(work_dir)
        res = work.run_chain(carddemo.find_job("POSTTRAN"))
        if not carddemo.step_ok(res[-1], carddemo.find_job("POSTTRAN")):
            raise SystemExit(f"POSTTRAN failed: rc={res[-1].rc}")
        scen.mkdir(parents=True, exist_ok=True)
        for ds in reference.INPUTS.values():
            work.ensure_dataset(ds)
            (scen / f"{ds}.ps").write_bytes(b"".join(work.unload_ksds(ds)))
    finally:
        if old is None:
            carddemo.os.environ.pop("CARDDEMO_DATA_DIR", None)
        else:
            carddemo.os.environ["CARDDEMO_DATA_DIR"] = old


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--stress", action="store_true", help="use the edge-case scenarios from gen_stress.py")
    ap.add_argument("--keep", help="work in DIR and keep it (default: a temporary directory)")
    args = ap.parse_args()
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(args.keep).resolve() if args.keep else Path(tmp)
        root.mkdir(parents=True, exist_ok=True)
        data = root / "data"
        sample_scenario(data / "sample", root / "posttran")
        runs = [("sample", DEFAULT_CLOCK)]
        if args.stress:
            subprocess.run([sys.executable, str(HERE / "gen_stress.py"), str(data / "sample"), str(data)],
                           check=True)
            runs = STRESS
        rep = harness.Report()
        for name, clock in runs:
            reference.run(data / name, root / "ref" / name, clock)
            subprocess.run([sys.executable, str(HERE / "cbexport_from_spec.py"), "--data", str(data / name),
                            "--out", str(root / "new" / name), "--clock", clock], check=False)
            compare(root / "ref" / name, root / "new" / name, name, rep)
        return rep.finish()


if __name__ == "__main__":
    sys.exit(main())
