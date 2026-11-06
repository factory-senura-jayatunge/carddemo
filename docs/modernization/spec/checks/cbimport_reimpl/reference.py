#!/usr/bin/env python3
"""Run the built CBIMPORT (GnuCOBOL) on one scenario directory.

The scenario directory holds EXPORT.ps, the export dataset as 500-byte
fixed-length records in any order (it is loaded into an indexed file keyed on
bytes 28-31, the binary sequence number, as the local build defines it). A
missing EXPORT.ps means the EXPFILE DD points at a dataset that does not exist.

Usage: python3 run_cobol.py SCENARIO OUTDIR [--clock "YYYY-MM-DD HH:MM:SS"]
Writes OUTDIR/{CUSTOUT,ACCTOUT,XREFOUT,TRNXOUT,CARDOUT,ERROUT}.ps (each only if
the program created it), OUTDIR/stdout.txt, OUTDIR/stderr.txt, OUTDIR/rc.txt.
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

OUTPUTS = ("CUSTOUT", "ACCTOUT", "XREFOUT", "TRNXOUT", "CARDOUT", "ERROUT")


def run(scenario: Path, out: Path, clock: str) -> int:
    out.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as tmp:
        work = carddemo.WorkDir.fresh(Path(tmp) / "work")
        env = work.base_env()
        env["COB_CURRENT_DATE"] = carddemo.business_date_setting(clock)
        src = scenario / "EXPORT.ps"
        if src.exists():
            work.load_ksds("EXPORT", carddemo.read_fixed(src, 500))
        env["DD_EXPFILE"] = str(work.dataset_path("EXPORT"))
        for dd in OUTPUTS:
            env[f"DD_{dd}"] = str(work.path / f"{dd}.ps")
        proc = subprocess.run([str(work.layout.bin / "CBIMPORT")], cwd=work.path, env=env,
                              capture_output=True, timeout=120)
        (out / "stdout.txt").write_bytes(proc.stdout)
        (out / "stderr.txt").write_bytes(proc.stderr)
        (out / "rc.txt").write_text(f"{proc.returncode}\n")
        for dd in OUTPUTS:
            target = out / f"{dd}.ps"
            target.unlink(missing_ok=True)
            produced = work.path / f"{dd}.ps"
            if produced.exists():
                target.write_bytes(produced.read_bytes())
        return proc.returncode


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("scenario")
    ap.add_argument("outdir")
    ap.add_argument("--clock", default="2022-07-06 12:00:00")
    a = ap.parse_args()
    rc = run(Path(a.scenario), Path(a.outdir), a.clock)
    print(f"CBIMPORT rc={rc} -> {a.outdir}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
