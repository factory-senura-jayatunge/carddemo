"""Run the CBSTM03B equivalence check: GnuCOBOL build vs the spec-only reimplementation.

Usage: python3 run_check.py [--stress] [--keep DIR]
Needs `make build` (the driver links the built CBSTM03B module) and cobc.
The sample scenario is the request sequence CBSTM03A issues on the sample
data: open the four files, read every statement transaction, then for each
cross-reference record read its customer and its account by key, and close.
--stress uses the scenarios of gen_stress.py instead. Exits 0 when every
comparison passes.
"""
import argparse
import subprocess
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
sys.path.insert(0, str(HERE))
import harness  # noqa: E402
import reference  # noqa: E402
from compare import compare  # noqa: E402
from gen_stress import req, sample_lines, trxfl, write  # noqa: E402


def sample_scenario(scen: Path) -> None:
    files = {"TRXFL": trxfl(sample_lines("dailytran.txt", 350)),
             "CARDXREF": sample_lines("cardxref.txt", 50),
             "CUSTDATA": sample_lines("custdata.txt", 500),
             "ACCTDATA": sample_lines("acctdata.txt", 300)}
    r = [req("TRNXFILE", "O")] + [req("TRNXFILE", "R")] * (len(files["TRXFL"]) + 1)
    r += [req("XREFFILE", "O"), req("CUSTFILE", "O"), req("ACCTFILE", "O")]
    for x in sorted(files["CARDXREF"], key=lambda rec: rec[:16]):
        r += [req("XREFFILE", "R"), req("CUSTFILE", "K", x[16:25].decode()),
              req("ACCTFILE", "K", x[25:36].decode())]
    r += [req("XREFFILE", "R")]
    r += [req(dd, "C") for dd in ("TRNXFILE", "XREFFILE", "CUSTFILE", "ACCTFILE")]
    write(scen, files, r)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--stress", action="store_true", help="use the edge-case scenarios from gen_stress.py")
    ap.add_argument("--keep", help="work in DIR and keep it (default: a temporary directory)")
    args = ap.parse_args()
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(args.keep).resolve() if args.keep else Path(tmp)
        root.mkdir(parents=True, exist_ok=True)
        if args.stress:
            subprocess.run([sys.executable, str(HERE / "gen_stress.py"), str(root / "data")], check=True)
            names = ["stress", "missing"]
        else:
            sample_scenario(root / "data" / "sample")
            names = ["sample"]
        rep = harness.Report()
        for name in names:
            scen = root / "data" / name
            reference.run(scen, root / "ref" / name)
            subprocess.run([sys.executable, str(HERE / "cbstm03b_from_spec.py"), "--data", str(scen),
                            "--out", str(root / "new" / name)], check=False)
            compare(root / "ref" / name, root / "new" / name, name, rep)
        return rep.finish()


if __name__ == "__main__":
    sys.exit(main())
