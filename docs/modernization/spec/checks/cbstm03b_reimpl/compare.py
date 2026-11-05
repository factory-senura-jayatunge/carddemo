"""Compare the CBSTM03B reimplementation's outputs (NEW) with the GnuCOBOL run (REF).

Usage: python3 compare.py REF NEW
Every call line (return code and all 1000 bytes of the data area) must be
identical, in order, and so must the driver's return code. Nothing is masked.
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from harness import Report  # noqa: E402


def lines(path: Path) -> list[str]:
    if not path.exists():
        return []
    return [l for l in path.read_bytes().decode("latin-1").split("\n") if l != ""]


def compare(ref: Path, new: Path, label: str = "", rep: Report | None = None) -> int:
    own = rep is None
    rep = rep or Report()
    pre = f"{label} " if label else ""
    a, b = lines(ref / "calls.txt"), lines(new / "calls.txt")
    rep.records(f"{pre}calls.txt", a, b)
    rc = [a[i][3:5] for i in range(len(a))]
    rep.line(f"{pre}return codes seen", True, " ".join(sorted(set(rc))))
    rc_ref = (ref / "rc.txt").read_text().strip()
    rc_new = (new / "rc.txt").read_text().strip() if (new / "rc.txt").exists() else "missing"
    rep.line(f"{pre}return code", rc_ref == rc_new, f"ref={rc_ref} new={rc_new}")
    return rep.finish() if own else (0 if rep.ok else 1)


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    sys.exit(compare(Path(sys.argv[1]), Path(sys.argv[2])))
