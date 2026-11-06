"""Compare the TRANREPT reimplementation's outputs (NEW) with the GnuCOBOL run (REF).

Usage: python3 compare.py REF NEW
Every report line, the whole SYSOUT, the abend line and the return code are
compared; nothing is masked.
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from d1_harness import compare_dirs  # noqa: E402


def compare(ref: Path, new: Path) -> int:
    return compare_dirs(ref, new, ["TRANREPT.txt"])


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    sys.exit(compare(Path(sys.argv[1]), Path(sys.argv[2])))
