#!/usr/bin/env python3
"""Run CBSTM03A on GnuCOBOL without its z/OS control-block prologue.

CBSTM03A starts by walking PSA -> TCB -> TIOT (app/cbl/CBSTM03A.CBL:266-291),
memory that exists only on z/OS. This script copies the program to a scratch
directory, turns exactly those lines into comment lines (so every other line
keeps its number), compiles the copy with the project flags, links it with the
built CBSTM03B and CEE3ABD modules, and runs it on a TRXFL file built the way
CREASTMT.JCL STEP010 builds it (SORT by card number + transaction ID,
OUTREC 1:263,16,17:1,262,279:279,50, padded with spaces to LRECL 350).

Usage:
  python3 run_harness.py OUTDIR                 # sample data after POSTTRAN
  python3 run_harness.py OUTDIR --data DIR      # DIR holds TRANSACT.ps, CARDXREF.ps,
                                                # CUSTDATA.ps, ACCTDATA.ps (fixed records)
Writes OUTDIR/STMTFILE, OUTDIR/HTMLFILE, OUTDIR/sysout.txt, OUTDIR/rc.txt.
The repository source is never modified.
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

PROLOGUE = range(266, 292)   # lines 266-291: SET ADDRESS OF PSA ... END-IF.


def harness_source() -> str:
    lines = (ROOT / "app/cbl/CBSTM03A.CBL").read_text().split("\n")
    for n in PROLOGUE:
        lines[n - 1] = "      *" + " " * 65
    return "\n".join(lines)


def trxfl(transact: list[bytes]) -> list[bytes]:
    ordered = sorted(transact, key=lambda r: (r[262:278], r[0:16]))
    return [(r[262:278] + r[0:262] + r[278:328]).ljust(350, b" ") for r in ordered]


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("outdir")
    ap.add_argument("--data", help="directory with TRANSACT.ps, CARDXREF.ps, CUSTDATA.ps, ACCTDATA.ps")
    args = ap.parse_args()
    out = Path(args.outdir).resolve()
    out.mkdir(parents=True, exist_ok=True)
    lay = carddemo.layout("release")
    manifest = carddemo.load_manifest()
    flags = list(manifest["toolchain"]["cobc_flags"])
    for d in manifest["toolchain"]["copybook_dirs"]:
        flags += ["-I", str(ROOT / d)]
    with tempfile.TemporaryDirectory() as tmp:
        src = Path(tmp) / "CBSTM03A.cbl"
        src.write_text(harness_source())
        exe = Path(tmp) / "CBSTM03A"
        proc = subprocess.run([carddemo.cobc_cmd(), "-x", *flags, "-o", str(exe), str(src)],
                              capture_output=True, text=True)
        if proc.returncode:
            print(proc.stdout + proc.stderr, file=sys.stderr)
            return 2
        work = carddemo.WorkDir.fresh(Path(tmp) / "work")
        if args.data:
            data = Path(args.data)
            for name in ("CARDXREF", "CUSTDATA", "ACCTDATA"):
                ds = work.datasets[name]
                work.load_ksds(name, carddemo.read_fixed(data / f"{name}.ps", ds.lrecl))
            transact = carddemo.read_fixed(data / "TRANSACT.ps", 350)
        else:
            res = work.run_job(carddemo.JOBS["POSTTRAN"])
            if res.rc not in (0, 4):
                print(res.sysout, file=sys.stderr)
                return 2
            transact = work.unload_ksds("TRANSACT")
        work.load_ksds("TRXFL", trxfl(transact))
        env = work.base_env()
        env.update({
            "DD_TRNXFILE": str(work.dataset_path("TRXFL")),
            "DD_XREFFILE": str(work.ensure_dataset("CARDXREF")),
            "DD_CUSTFILE": str(work.ensure_dataset("CUSTDATA")),
            "DD_ACCTFILE": str(work.ensure_dataset("ACCTDATA")),
            "DD_STMTFILE": str(out / "STMTFILE"),
            "DD_HTMLFILE": str(out / "HTMLFILE"),
        })
        run = subprocess.run([str(exe)], cwd=work.path, env=env, capture_output=True, text=True, timeout=120)
        (out / "sysout.txt").write_text(run.stdout + run.stderr)
        (out / "rc.txt").write_text(f"{run.returncode}\n")
        (out / "TRXFL.ps").write_bytes(b"".join(trxfl(transact)))
    print(f"rc={run.returncode} -> {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
