#!/usr/bin/env python3
"""Writes the CSUTLDTC vectors that LegacyDateCheck (Java and TypeScript) is tested against.

Compiles the real app/cbl/CSUTLDTC.cbl with the CEEDAYS stand-in tools/shims/CEEDAYS.cbl and
the driver tests/unit/cobol/csutldtc_test.cbl, runs every input below through it and records the
80-byte result line. Needs GnuCOBOL 3.2 (cobc). Run from anywhere:

    python3 modernization/platform/validation/src/test/cobol/gen_csutldtc_vectors.py [--check]
"""
from __future__ import annotations

import argparse
import json
import os
import pathlib
import subprocess
import sys
import tempfile

HERE = pathlib.Path(__file__).resolve()
REPO = HERE.parents[6]
OUT = HERE.parents[1] / "resources" / "org" / "carddemo" / "platform" / "validation" / "csutldtc-vectors.json"
FLAGS = ["-std=ibm", "-fsign=EBCDIC", "-ftab-width=4"]
DASHED = "YYYY-MM-DD"
COMPACT = "YYYYMMDD"


def cases() -> list[tuple[str, str]]:
    out: list[tuple[str, str]] = []
    # Spec scenarios S1-S15 (docs/modernization/spec/programs/CSUTLDTC.md section 15).
    for d in ["2022-07-06", "2024-02-29", "1582-10-15", "9999-12-31", "2023-02-29", "2022-04-31",
              "2022-07-00", "2022-13-01", "2022-00-10", "1582-10-14", "0022-01-01", "0000-01-01",
              "1500-01-01", "2022-7-6", "2022-0A-01", "2022/07/06", ""]:
        out.append((d, DASHED))
    out += [("2022-07-06", "YYYY-XX-DD"), ("2022-07-06", ""), ("20220706", COMPACT), ("20230229", COMPACT)]
    # Every month and the day boundaries of each, in a leap and a common year.
    for year in ("2024", "2023", "1900", "2000", "1600", "1582", "9999"):
        for month in range(0, 14):
            for day in (0, 1, 28, 29, 30, 31, 32):
                out.append((f"{year}-{month:02d}-{day:02d}", DASHED))
    # The Gregorian cut-over.
    for d in ("1582-10-01", "1582-10-16", "1582-09-30", "1582-11-01", "1581-12-31", "1583-01-01", "1582-10-00",
              "1582-13-01", "1581-02-30", "1582-10-32"):
        out.append((d, DASHED))
    # Characters where digits belong, separators, padding, case and lengths.
    for d in ("2022-07-0 ", " 2022-07-0", "2022-07-6 ", "202A-07-06", "2022-07-0A", "+022-07-06", "-022-07-06",
              "2022.07.06", "2022 07 06", "20220706  ", "2022-07-06X", "2022-07", "    -  -  ", "abcd-ef-gh",
              "2022--7-06", "2022-1-123", "ÿ022-07-06", "2022-07-06\u0000"):
        out.append((d, DASHED))
    for d in ("20220706", "20240229", "20230229", "20221301", "15000101", "2022070", "2022-07-", "20220706XX",
              "        ", "2022 706", "00000000"):
        out.append((d, COMPACT))
    # Pictures: case, order, separators, missing and repeated parts, other letters.
    for p in ("yyyy-mm-dd", "Yyyy-Mm-Dd", "DD-MM-YYYY", "MM/DD/YYYY", "YYYY/MM/DD", "YYYYDDMM", "YYYY-MM",
              "YYYY-MM-D", "YYYY-MM-DDX", "YYYY-MM-DD", "YYYYYYMMDD", "YYYY-MM-MM", "YY-MM-DD", "YYYY MM DD",
              "  YYYYMMDD", "YYYY.MM.DD", "YYYY-MM-1D", "MMDDYYYY", "YYYY-MMMDD", "YYYYMMDDDD"):
        for d in ("2022-07-06", "06-07-2022", "07/06/2022", "20220706", "20220607", "  20220706", "2022-07"):
            out.append((d, p))
    seen: set[tuple[str, str]] = set()
    unique = []
    for c in out:
        if c not in seen:
            seen.add(c)
            unique.append(c)
    return unique


def field(text: str) -> str:
    """The 10 bytes a caller's PIC X(10) holds after MOVE: truncated or padded with spaces."""
    return text[:10].ljust(10)


def compile_cobol(kind: str, out: pathlib.Path, src: pathlib.Path) -> None:
    # Without -o, cobc -m names the module with the platform's extension (.so, .dylib).
    target = [] if kind == "-m" else ["-o", str(out)]
    proc = subprocess.run(["cobc", kind, *FLAGS, *target, str(src)], cwd=out.parent, capture_output=True, text=True)
    if proc.returncode != 0:
        sys.exit(f"cobc failed for {src}:\n{proc.stderr}")


def run(inputs: list[tuple[str, str]]) -> list[dict]:
    with tempfile.TemporaryDirectory() as tmp:
        t = pathlib.Path(tmp)
        for src in (REPO / "app/cbl/CSUTLDTC.cbl", REPO / "tools/shims/CEEDAYS.cbl"):
            compile_cobol("-m", t / src.stem, src)
        driver = t / "csutldtt"
        compile_cobol("-x", driver, REPO / "tests/unit/cobol/csutldtc_test.cbl")
        stdin = "".join(field(d) + field(p) + "\n" for d, p in inputs) + "END\n"
        proc = subprocess.run([str(driver)], input=stdin.encode("latin-1"), capture_output=True,
                              env={**os.environ, "COB_LIBRARY_PATH": tmp})
    if proc.returncode != 0:
        sys.exit(f"driver failed ({proc.returncode}): {proc.stderr.decode('latin-1')}")
    lines = proc.stdout.decode("latin-1").splitlines()
    if len(lines) != len(inputs):
        sys.exit(f"driver printed {len(lines)} lines for {len(inputs)} inputs")
    vectors = []
    for (d, p), line in zip(inputs, lines):
        result = line[12:].ljust(80)
        vectors.append({"date": d, "picture": p, "returnCode": int(line[3:7]), "severity": int(result[0:4]),
                        "messageNumber": int(result[15:19]), "line": result})
    return vectors


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--check", action="store_true", help="fail when the committed vectors differ")
    args = ap.parse_args()
    vectors = run(cases())
    body = json.dumps({"source": "app/cbl/CSUTLDTC.cbl with tools/shims/CEEDAYS.cbl, GnuCOBOL "
                       + " ".join(FLAGS), "vectors": vectors}, indent=1, ensure_ascii=False) + "\n"
    if args.check:
        same = OUT.is_file() and OUT.read_text(encoding="utf-8") == body
        print("csutldtc vectors: " + ("OK" if same else "differ; rerun without --check"))
        return 0 if same else 1
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(body, encoding="utf-8")
    print(f"csutldtc vectors: {len(vectors)} written to {OUT}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
