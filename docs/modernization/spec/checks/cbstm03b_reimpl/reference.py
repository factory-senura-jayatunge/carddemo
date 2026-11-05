"""Run CBSTM03B (GnuCOBOL build) through stio_driver.cbl on one scenario directory.

A scenario directory holds requests.txt (one request per line: DD name in
columns 1-8, operation in column 9, key length in columns 10-13, key in
columns 14-38) and the files as fixed-length records without separators:
TRXFL.ps (350, TRNXFILE), CARDXREF.ps (50, XREFFILE), CUSTDATA.ps (500,
CUSTFILE), ACCTDATA.ps (300, ACCTFILE), in any record order. Each file is
loaded into an indexed file; a missing file means the DD names a dataset that
does not exist.

Writes OUT/calls.txt (one line per request: RC=xx|<1000 bytes>) and OUT/rc.txt.
"""
from __future__ import annotations

import subprocess
import sys
import tempfile
import tomllib
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[4]
sys.path.insert(0, str(REPO / "tools"))
import carddemo  # noqa: E402

FILES = {"TRNXFILE": ("TRXFL", 350), "XREFFILE": ("CARDXREF", 50),
         "CUSTFILE": ("CUSTDATA", 500), "ACCTFILE": ("ACCTDATA", 300)}


def compile_driver(exe: Path) -> None:
    manifest = tomllib.loads((REPO / "tools" / "batch-programs.toml").read_text())
    cobc_flags = list(manifest["toolchain"]["cobc_flags"])
    subprocess.run(["cobc", "-x", *cobc_flags, "-o", str(exe), str(HERE / "stio_driver.cbl")],
                   check=True)


def run(scenario: Path, out: Path) -> int:
    out.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as tmp:
        exe = Path(tmp) / "stiodrv"
        compile_driver(exe)
        work = carddemo.WorkDir.fresh(Path(tmp) / "work")
        env = work.base_env()
        for dd, (ds, lrecl) in FILES.items():
            src = scenario / f"{ds}.ps"
            if src.exists():
                work.load_ksds(ds, carddemo.read_fixed(src, lrecl))
                env[f"DD_{dd}"] = str(work.dataset_path(ds))
            else:
                env[f"DD_{dd}"] = str(Path(tmp) / f"missing-{ds}.ksds")
        stdin = (scenario / "requests.txt").read_bytes() + b"END\n"
        proc = subprocess.run([str(exe)], input=stdin, cwd=work.path, env=env,
                              capture_output=True, timeout=120)
        (out / "calls.txt").write_bytes(proc.stdout)
        (out / "stderr.txt").write_bytes(proc.stderr)
        (out / "rc.txt").write_text(f"{proc.returncode}\n")
        return proc.returncode


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    print("rc", run(Path(sys.argv[1]), Path(sys.argv[2])))
