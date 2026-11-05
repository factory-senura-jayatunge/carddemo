#!/usr/bin/env python3
"""Run the built CBEXPORT (GnuCOBOL) on one scenario directory.

The scenario directory holds the five input datasets as fixed-length record
files: CUSTDATA.ps (500), ACCTDATA.ps (300), CARDXREF.ps (50), TRANSACT.ps (350),
CARDDATA.ps (150), in any record order. A missing file means the DD points at
a dataset that does not exist. Each file is loaded into an indexed file the
way IDCAMS REPRO would, then CBEXPORT runs with the DD names of
app/jcl/CBEXPORT.jcl.

Usage: python3 run_cobol.py SCENARIO OUTDIR [--clock "YYYY-MM-DD HH:MM:SS"]
Writes OUTDIR/EXPORT.ps (the export KSDS unloaded in key order, only if the
program created it), OUTDIR/stdout.txt, OUTDIR/stderr.txt, OUTDIR/rc.txt.
"""
from __future__ import annotations

import argparse
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[5]
sys.path.insert(0, str(ROOT / "tools"))
import carddemo  # noqa: E402

INPUTS = {"CUSTFILE": "CUSTDATA", "ACCTFILE": "ACCTDATA", "XREFFILE": "CARDXREF",
          "TRANSACT": "TRANSACT", "CARDFILE": "CARDDATA"}


def run(scenario: Path, out: Path, clock: str) -> int:
    out.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as tmp:
        work = carddemo.WorkDir.fresh(Path(tmp) / "work")
        env = work.base_env()
        env["COB_CURRENT_DATE"] = carddemo.business_date_setting(clock)
        for dd, ds in INPUTS.items():
            src = scenario / f"{ds}.ps"
            if src.exists():
                work.load_ksds(ds, carddemo.read_fixed(src, work.datasets[ds].lrecl))
            env[f"DD_{dd}"] = str(work.dataset_path(ds))
        exp = work.dataset_path("EXPORT")
        env["DD_EXPFILE"] = str(exp)
        proc = subprocess.run([str(work.layout.bin / "CBEXPORT")], cwd=work.path, env=env,
                              capture_output=True, timeout=120)
        (out / "stdout.txt").write_bytes(proc.stdout)
        (out / "stderr.txt").write_bytes(proc.stderr)
        (out / "rc.txt").write_text(f"{proc.returncode}\n")
        (out / "EXPORT.ps").unlink(missing_ok=True)
        if exp.exists():
            (out / "EXPORT.ps").write_bytes(b"".join(work.unload_ksds("EXPORT")))
        return proc.returncode


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("scenario")
    ap.add_argument("outdir")
    ap.add_argument("--clock", default="2022-07-06 12:00:00")
    a = ap.parse_args()
    rc = run(Path(a.scenario), Path(a.outdir), a.clock)
    print(f"CBEXPORT rc={rc} -> {a.outdir}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
