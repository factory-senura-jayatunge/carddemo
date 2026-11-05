"""Compile waitprob.cbl and show what COBSWAIT would pass to MVSWAIT for each SYSIN value.

Usage: python3 run_probe.py
Needs cobc. Prints one line per input and exits 1 if a value differs from the
table below, which the COBSWAIT spec quotes (GnuCOBOL 3.2, project flags).
"""
import subprocess
import sys
import tempfile
import tomllib
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[4]
EXPECTED = [("00003600", 3600), ("50      ", 50), ("    0050", 50), ("ABCDEFGH", 0),
            ("-0000050", 50), ("0000005A", 0), ("00000.50", 0), ("        ", 0), ("", 0)]


def main() -> int:
    flags = tomllib.loads((REPO / "tools" / "batch-programs.toml").read_text())["toolchain"]["cobc_flags"]
    ok = True
    with tempfile.TemporaryDirectory() as tmp:
        exe = Path(tmp) / "waitprob"
        subprocess.run(["cobc", "-x", *flags, "-o", str(exe), str(HERE / "waitprob.cbl")], check=True)
        for value, want in EXPECTED:
            out = subprocess.run([str(exe)], input=(value + "\n").encode(), capture_output=True).stdout.decode()
            got = int(out.rsplit("TIME=", 1)[1])
            ok &= got == want
            print(f"{'PASS' if got == want else 'FAIL'} SYSIN {value!r:>12} -> {got} (expected {want})")
    print("OVERALL", "PASS" if ok else "FAIL")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
