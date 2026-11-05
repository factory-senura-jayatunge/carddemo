"""Compare the CBIMPORT reimplementation's outputs (NEW) with the GnuCOBOL run (REF).

Usage: python3 compare.py REF NEW
Both directories hold the six output files CUSTOUT.ps, ACCTOUT.ps, XREFOUT.ps,
TRNXOUT.ps, CARDOUT.ps, ERROUT.ps (fixed-length records without separators,
each present only if the program created it), stdout.txt, stderr.txt and
rc.txt. The date and time are pinned for both runs. In each error record the
hundredths of a second and the UTC offset (bytes 15-21) come from the wall
clock and the machine's time zone, so only their layout is compared. Every
other byte, every sysout line and the return code must match. From stderr only
the CEE3ABD abend line is compared (libcob adds its own warnings there).
"""
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from harness import Report  # noqa: E402

LRECL = {"CUSTOUT": 500, "ACCTOUT": 300, "XREFOUT": 50, "TRNXOUT": 350, "CARDOUT": 150, "ERROUT": 132}
CLOCK_TAIL = (14, 21)
CLOCK_TAIL_RE = re.compile(r"\d\d[+-]\d{4}")


def records(path: Path, lrecl: int) -> list[str]:
    d = path.read_bytes() if path.exists() else b""
    return [d[i:i + lrecl].decode("latin-1") for i in range(0, len(d), lrecl)]


def text_lines(path: Path) -> list[str]:
    return path.read_text(encoding="latin-1").splitlines() if path.exists() else []


def compare(ref: Path, new: Path, label: str = "", rep: Report | None = None) -> int:
    own = rep is None
    rep = rep or Report()
    pre = f"{label} " if label else ""
    for dd, lrecl in LRECL.items():
        name = f"{dd}.ps"
        exists = ((ref / name).exists(), (new / name).exists())
        rep.line(f"{pre}{name} created", exists[0] == exists[1], f"ref={exists[0]} new={exists[1]}")
        size_ok = all((d / name).stat().st_size % lrecl == 0 for d in (ref, new) if (d / name).exists())
        rep.line(f"{pre}{name} whole records of {lrecl}", size_ok)
        a, b = records(ref / name, lrecl), records(new / name, lrecl)
        if dd == "ERROUT":
            lo, hi = CLOCK_TAIL
            bad = [i for i, r in enumerate(a + b) if not CLOCK_TAIL_RE.fullmatch(r[lo:hi])]
            rep.line(f"{pre}{name} hundredths and UTC offset layout", not bad, f"{len(bad)} bad")
            a = [r[:lo] + r[hi:] for r in a]
            b = [r[:lo] + r[hi:] for r in b]
        rep.records(f"{pre}{name}", a, b)
    rep.records(f"{pre}sysout", text_lines(ref / "stdout.txt"), text_lines(new / "stdout.txt"))
    abend = [[l for l in text_lines(d / "stderr.txt") if l.startswith("CEE3ABD:")] for d in (ref, new)]
    rep.line(f"{pre}abend line", abend[0] == abend[1], f"ref={abend[0]} new={abend[1]}")
    rc = [(d / "rc.txt").read_text().strip() if (d / "rc.txt").exists() else "missing" for d in (ref, new)]
    rep.line(f"{pre}return code", rc[0] == rc[1], f"ref={rc[0]} new={rc[1]}")
    return rep.finish() if own else (0 if rep.ok else 1)


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    sys.exit(compare(Path(sys.argv[1]), Path(sys.argv[2])))
