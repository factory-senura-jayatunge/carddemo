"""Build the CBSTM03B stress scenarios (deterministic).

Usage: python3 gen_stress.py OUT_DIR
Writes OUT_DIR/stress and OUT_DIR/missing, each a scenario directory as
described in reference.py. The files come from the sample data (TRXFL built
from the daily transactions the way CREASTMT.JCL STEP010 builds it from
TRANSACT) with a few extra records; requests.txt exercises every DD and
operation, unopened and doubly opened files, reads past end of file, key
lengths 1-25 on both keyed files, unknown DD names and operations, and a
seeded random mix of all of them. In `missing` the account file does not exist.
"""
from __future__ import annotations

import random
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[4]
ASCII = REPO / "app" / "data" / "ASCII"


def sample_lines(name: str, lrecl: int) -> list[bytes]:
    text = (ASCII / name).read_bytes().decode("latin-1").replace("\r\n", "\n")
    return [l.encode("latin-1").ljust(lrecl) for l in text.split("\n") if l]


def trxfl(daily: list[bytes]) -> list[bytes]:
    """SORT FIELDS=(263,16,CH,A,1,16,CH,A), OUTREC=(1:263,16,17:1,262,279:279,50)."""
    ordered = sorted(daily, key=lambda r: (r[262:278], r[0:16]))
    return [(r[262:278] + r[0:262] + r[278:328]).ljust(350) for r in ordered]


def req(dd: str, op: str, key: str = "", ln: int | None = None) -> str:
    ln = len(key) if ln is None else ln
    return f"{dd:<8.8}{op:1.1}{ln:04d}{key:<25.25}"


def base_files() -> dict[str, list[bytes]]:
    daily = sample_lines("dailytran.txt", 350)
    xref = sample_lines("cardxref.txt", 50)
    cust = sample_lines("custdata.txt", 500)
    acct = sample_lines("acctdata.txt", 300)
    # A card whose transactions span more than one record, a customer key with
    # letters, an account at the top of the key range.
    extra = daily[5][:262] + b"4999000011112222" + daily[5][278:]
    daily += [extra, b"0000000000099999" + extra[16:]]
    xref.append(b"4999000011112222" + b"999999999" + b"99999999999" + b"X" * 14)
    cust.append(b"ABC000001" + cust[0][9:])
    acct.append(b"99999999999" + acct[0][11:])
    return {"TRXFL": trxfl(daily), "CARDXREF": xref, "CUSTDATA": cust, "ACCTDATA": acct}


def requests(rnd: random.Random, files: dict[str, list[bytes]]) -> list[str]:
    cust_ids = [r[:9].decode() for r in files["CUSTDATA"]]
    acct_ids = [r[:11].decode() for r in files["ACCTDATA"]]
    r: list[str] = []
    # Before any OPEN: unopened files, unsupported operations, unknown DD names.
    r += [req("XREFFILE", "R"), req("CUSTFILE", "K", "000000001"), req("ACCTFILE", "C"),
          req("TRNXFILE", "W"), req("CUSTFILE", "Z"), req("trnxfile", "O"), req("TRNXFIL", "O"),
          req("", "O"), req("ACCTFILEX", "O")]
    r += [req(dd, "O") for dd in ("TRNXFILE", "XREFFILE", "CUSTFILE", "ACCTFILE")]
    r += [req("TRNXFILE", "O"), req("CUSTFILE", "O")]                     # already open
    n = len(files["TRXFL"])
    r += [req("TRNXFILE", "R")] * (n + 3)                                 # past end of file
    r += [req("TRNXFILE", "K", "4999000011112222"), req("TRNXFILE", "Z"), req("TRNXFILE", "o")]
    r += [req("XREFFILE", "R")] * 5 + [req("XREFFILE", "C"), req("XREFFILE", "R"),
                                       req("XREFFILE", "O"), req("XREFFILE", "R")]
    # Customer keys: exact, truncated by the key length, short (space padded), long.
    r += [req("CUSTFILE", "K", "000000007"), req("CUSTFILE", "K", "0000000070000"),
          req("CUSTFILE", "K", "000000007", 13), req("CUSTFILE", "K", "00000007"),
          req("CUSTFILE", "K", "000000009XYZ", 9), req("CUSTFILE", "K", "ABC000001"),
          req("CUSTFILE", "K", "abc000001"), req("CUSTFILE", "K", "999999999"),
          req("CUSTFILE", "R"), req("CUSTFILE", "k", "000000003"),
          req("CUSTFILE", "K", "0000000050000000000000000"), req("CUSTFILE", "K", "0", 1)]
    # Account keys: digits only; the key moves into a 9(11) numeric key.
    r += [req("ACCTFILE", "K", "00000000001"), req("ACCTFILE", "K", "1"), req("ACCTFILE", "K", "50"),
          req("ACCTFILE", "K", "51"), req("ACCTFILE", "K", "123456789012"),
          req("ACCTFILE", "K", "000000000050"), req("ACCTFILE", "K", "0000000000000000000000050"),
          req("ACCTFILE", "K", "99999999999"), req("ACCTFILE", "K", "199999999999"),
          req("ACCTFILE", "K", "0000000002345", 3), req("ACCTFILE", "R"), req("ACCTFILE", "W")]
    # Seeded random mix.
    ops = "OCRKWZ"
    dds = ["TRNXFILE", "XREFFILE", "CUSTFILE", "ACCTFILE", "TRNXFILE", "XREFFILE", "NOSUCHDD"]
    for _ in range(400):
        dd = rnd.choice(dds)
        op = rnd.choice(ops + "RRRKKK")
        key, ln = "", 0
        if op == "K":
            if dd == "ACCTFILE":
                v = rnd.choice(acct_ids + ["00000000077", "77"])
                if rnd.random() < 0.5:
                    v = v.lstrip("0") or "0"
                key = v
            else:
                v = rnd.choice(cust_ids + ["000000099", "ZZZ"])
                key = v + rnd.choice(["", "", "9", "  ", "XYZ"])
            ln = rnd.randint(1, len(key)) if rnd.random() < 0.2 else len(key)
            ln = max(ln, 1)
        r.append(req(dd, op, key, ln))
    r += [req(dd, "C") for dd in ("TRNXFILE", "XREFFILE", "CUSTFILE", "ACCTFILE")]
    r += [req(dd, "C") for dd in ("TRNXFILE", "XREFFILE", "CUSTFILE", "ACCTFILE")]   # not open
    r += [req("XREFFILE", "R"), req("ACCTFILE", "K", "1")]
    return r


def write(scen: Path, files: dict[str, list[bytes]], reqs: list[str], skip: str = "") -> None:
    scen.mkdir(parents=True, exist_ok=True)
    for name, recs in files.items():
        p = scen / f"{name}.ps"
        p.unlink(missing_ok=True)
        if name != skip:
            p.write_bytes(b"".join(recs))
    (scen / "requests.txt").write_text("".join(x + "\n" for x in reqs))


def main() -> int:
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    out = Path(sys.argv[1])
    files = base_files()
    rnd = random.Random(3031)
    for recs in files.values():
        rnd.shuffle(recs)                 # file order is irrelevant: indexed files
    write(out / "stress", files, requests(rnd, files))
    write(out / "missing", files, requests(random.Random(4041), files), skip="ACCTDATA")
    print(f"scenarios in {out}: stress, missing")
    return 0


if __name__ == "__main__":
    sys.exit(main())
