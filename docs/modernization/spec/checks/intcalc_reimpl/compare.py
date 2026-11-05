"""Compare the INTCALC reimplementation's outputs (NEW) with the GnuCOBOL run (REF).

Usage: python3 compare.py REF NEW
TRAN-ORIG-TS and TRAN-PROC-TS (bytes 279-330) come from the wall clock, so only
their layout and their equality are checked; every other byte is compared.
"""
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from d1_harness import compare_dirs  # noqa: E402

TS = re.compile(r"\d{4}-\d\d-\d\d-\d\d\.\d\d\.\d\d\.\d\d0000")


def mask_ts(recs):
    bad = [r[:16] for r in recs if not (TS.fullmatch(r[278:304]) and r[278:304] == r[304:330])]
    return [r[:278] + r[330:] for r in recs], bad


def compare(ref: Path, new: Path) -> int:
    return compare_dirs(ref, new, ["TRANSACT.txt", "ACCTDATA.after.txt"], {"TRANSACT.txt": mask_ts})


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    sys.exit(compare(Path(sys.argv[1]), Path(sys.argv[2])))
