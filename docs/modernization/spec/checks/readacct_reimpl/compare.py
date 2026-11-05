"""Compare the READACCT reimplementation's outputs (NEW) with the GnuCOBOL run (REF).

Usage: python3 compare.py REF NEW
OUTFILE, ARRYFILE and VBRCFILE (record bytes as hex, so the VB header is not
compared), the account master after the run, the whole SYSOUT, the abend line
and the return code. The libcob implicit-CLOSE warnings on stderr are not
compared.
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from d1_harness import compare_dirs  # noqa: E402


def compare(ref: Path, new: Path) -> int:
    return compare_dirs(ref, new, ["OUTFILE.hex", "ARRYFILE.hex", "VBRCFILE.hex", "ACCTDATA.after.txt"])


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    sys.exit(compare(Path(sys.argv[1]), Path(sys.argv[2])))
