"""Compare the POSTTRAN reimplementation's outputs (NEW) with the GnuCOBOL run (REF).

Usage: python3 compare.py REF NEW
"""
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from harness import Report, read_records  # noqa: E402

# TRAN-PROC-TS (bytes 305-330) comes from the wall clock, so only its layout is compared.
PROC_TS = (304, 330)
PROC_TS_RE = re.compile(r"2022-07-06-\d\d\.\d\d\.\d\d\.\d\d0000")
SYSOUT_PATTERNS = [
    r"TRANSACTIONS PROCESSED :\d{9}",
    r"TRANSACTIONS REJECTED  :\d{9}",
    r"TCATBAL record not found for key : \d{17}\.\. Creating\.",
    r"START OF EXECUTION OF PROGRAM CBTRN02C",
    r"END OF EXECUTION OF PROGRAM CBTRN02C",
]


def compare(ref: Path, new: Path) -> int:
    rep = Report()
    for name in ["DALYREJS.txt", "ACCTDATA.after.txt", "TCATBALF.after.txt", "TRANSACT.after.txt"]:
        a, b = read_records(ref / name), read_records(new / name)
        if name == "TRANSACT.after.txt":
            lo, hi = PROC_TS
            bad = [r[:16] for r in b if not PROC_TS_RE.fullmatch(r[lo:hi])]
            rep.line(f"{name} PROC-TS layout", not bad, f"{len(bad)} bad")
            a = [r[:lo] + r[hi:] for r in a]
            b = [r[:lo] + r[hi:] for r in b]
        rep.records(name, a, b)
    ref_out = (ref / "sysout.txt").read_text(errors="replace")
    new_out = (new / "sysout.txt").read_text(errors="replace")
    for pat in SYSOUT_PATTERNS:
        x, y = re.findall(pat, ref_out), re.findall(pat, new_out)
        rep.line(f"sysout /{pat}/", x == y, f"ref={len(x)} new={len(y)}")
    rc_ref = (ref / "rc.txt").read_text().strip()
    rc_new = (new / "rc.txt").read_text().strip()
    rep.line("return code", rc_ref == rc_new, f"ref={rc_ref} new={rc_new}")
    return rep.finish()


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    sys.exit(compare(Path(sys.argv[1]), Path(sys.argv[2])))
