"""Compare the CBEXPORT reimplementation's outputs (NEW) with the GnuCOBOL run (REF).

Usage: python3 compare.py REF NEW
Both directories hold EXPORT.ps (the export file in key order, 500-byte
records, present only if the program created it), stdout.txt, stderr.txt and
rc.txt. The clock is pinned for both runs, so nothing is masked: every export
byte (timestamp included), every sysout line and the return code must match.
From stderr only the CEE3ABD abend line is compared (libcob adds its own
warnings there).
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from harness import Report  # noqa: E402

LRECL = 500


def records(path: Path) -> list[str]:
    d = path.read_bytes() if path.exists() else b""
    return [d[i:i + LRECL].decode("latin-1") for i in range(0, len(d), LRECL)]


def text_lines(path: Path) -> list[str]:
    return path.read_text(encoding="latin-1").splitlines() if path.exists() else []


def compare(ref: Path, new: Path, label: str = "", rep: Report | None = None) -> int:
    own = rep is None
    rep = rep or Report()
    pre = f"{label} " if label else ""
    exists = ((ref / "EXPORT.ps").exists(), (new / "EXPORT.ps").exists())
    rep.line(f"{pre}EXPORT.ps created", exists[0] == exists[1], f"ref={exists[0]} new={exists[1]}")
    a, b = records(ref / "EXPORT.ps"), records(new / "EXPORT.ps")
    rep.records(f"{pre}EXPORT.ps", a, b)
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
