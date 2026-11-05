"""Compare the TRANVALD reimplementation's outputs (NEW) with the GnuCOBOL run (REF).

Usage: python3 compare.py REF NEW
The program writes no file, so the comparison is the whole SYSOUT, the abend
line and the return code.
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from d1_harness import compare_dirs  # noqa: E402


def compare(ref: Path, new: Path) -> int:
    return compare_dirs(ref, new, [])


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    sys.exit(compare(Path(sys.argv[1]), Path(sys.argv[2])))
