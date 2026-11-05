"""Reference runner for the d1-core-batch equivalence checks.

Like harness.run_reference, but it runs one step without its prior jobs, keeps
stdout and stderr apart, can replace the TRANREPT pre-step input, the DATEPARM
record and the PARM, and writes binary outputs as hex.

Output files in OUT_DIR:
  sysout.txt         the program's stdout, unchanged
  stderr.txt         the program's stderr (the CEE3ABD shim line on an abend)
  rc.txt             the return code
  <DDNAME>.txt       each new FB output, one record per line (text outputs)
  <DDNAME>.hex       each new FB or VB output, one record per line in hex (binary outputs)
  <DATASET>.after.txt  each KSDS named in unload, after the run, in key order
"""
from __future__ import annotations

import dataclasses
import os
import subprocess
from pathlib import Path

from harness import carddemo, write_records


def _data_records(data_dir: Path, name: str, lrecl: int) -> list[bytes]:
    return carddemo.read_text_records(data_dir / name, lrecl)


def run_reference(job_name: str, data_dir: Path, out_dir: Path, unload: tuple[str, ...] = (),
                  hex_outputs: bool = False) -> int:
    """Run one job step on data_dir with the GnuCOBOL build.

    data_dir holds the sample-format files (acctdata.txt, ...). Optional extras:
    parm.txt (the JCL PARM), tranfile.txt (TRANREPT's sorted TRANFILE, used
    instead of running POSTTRAN and the SORT stand-in), dateparm.txt (the
    DATEPARM record).
    """
    out_dir.mkdir(parents=True, exist_ok=True)
    job = carddemo.find_job(job_name)
    changes: dict = {"after": ()}
    if (data_dir / "parm.txt").exists():
        changes["parm"] = (data_dir / "parm.txt").read_text().strip("\r\n")
    dd = dict(job.dd)
    if (data_dir / "tranfile.txt").exists():
        recs = _data_records(data_dir, "tranfile.txt", 350)
        changes["pre"] = lambda work: carddemo.write_fixed(work.path / "TRANSACT.DALY.ps", recs, 350)
    if (data_dir / "dateparm.txt").exists():
        dd["DATEPARM"] = carddemo.DD(inline=(data_dir / "dateparm.txt").read_text().rstrip("\r\n"), lrecl=80)
    changes["dd"] = dd
    job = dataclasses.replace(job, **changes)

    old = os.environ.get("CARDDEMO_DATA_DIR")
    os.environ["CARDDEMO_DATA_DIR"] = str(data_dir.resolve())
    try:
        work = carddemo.WorkDir.fresh(out_dir / "work")
        if job.pre:
            job.pre(work)
        dds = work.resolve_dd(job)
        env = work.base_env()
        for ddname, (path, _) in dds.items():
            env[f"DD_{ddname}"] = str(path)
        if carddemo.program_entry(job.program)["type"] == "module":
            cmd = [str(work.layout.bin / "PARMRUN"), job.program, job.parm or ""]
        else:
            cmd = [str(work.layout.bin / job.program)]
        proc = subprocess.run(cmd, cwd=work.path, env=env, input=b"", capture_output=True,
                              timeout=300, check=False)
    finally:
        if old is None:
            os.environ.pop("CARDDEMO_DATA_DIR", None)
        else:
            os.environ["CARDDEMO_DATA_DIR"] = old

    (out_dir / "sysout.txt").write_bytes(proc.stdout)
    (out_dir / "stderr.txt").write_bytes(proc.stderr)
    (out_dir / "rc.txt").write_text(f"{proc.returncode}\n")
    for ddname, (path, d) in dds.items():
        if not d.new or d.new == "KSDS" or not path.exists():
            continue
        recs = carddemo.read_fixed(path, d.lrecl) if d.new == "FB" else carddemo.read_vb(path)
        if hex_outputs:
            (out_dir / f"{ddname}.hex").write_text("".join(r.hex() + "\n" for r in recs))
        else:
            write_records(out_dir / f"{ddname}.txt", recs)
    for name in unload:
        write_records(out_dir / f"{name}.after.txt", work.unload_ksds(name))
    return proc.returncode


def lines(path: Path) -> list[str]:
    """The lines of a text output, or an empty list when the file is missing."""
    if not path.exists():
        return []
    return path.read_bytes().decode("latin-1").splitlines()

def split_lines(path: Path) -> list[str]:
    """Lines of a text file split on LF only (record bytes such as X'85' stay inside a line)."""
    if not path.exists():
        return []
    text = path.read_bytes().decode("latin-1")
    parts = text.split("\n")
    if parts and parts[-1] == "":
        parts.pop()
    return parts


def compare_dirs(ref: Path, new: Path, names: list[str], masks: dict | None = None) -> int:
    """Compare output files, sysout, the abend line on stderr and the return code.

    masks maps a file name to a function(list[str]) -> (list[str], problems), applied to
    both sides before the comparison; problems found in NEW are reported as a failure.
    """
    from harness import Report
    rep = Report()
    masks = masks or {}
    for name in names:
        a, b = split_lines(ref / name), split_lines(new / name)
        if not (ref / name).exists():
            rep.line(name, not (new / name).exists(), "absent in REF" + ("" if not (new / name).exists() else ", present in NEW"))
            continue
        if name in masks:
            a, _ = masks[name](a)
            b, bad = masks[name](b)
            rep.line(f"{name} masked fields", not bad, f"{len(bad)} bad")
        rep.records(name, a, b)
    rep.records("sysout.txt", split_lines(ref / "sysout.txt"), split_lines(new / "sysout.txt"))
    abend = lambda p: [l for l in split_lines(p) if l.startswith("CEE3ABD")]
    rep.records("stderr CEE3ABD line", abend(ref / "stderr.txt"), abend(new / "stderr.txt"))
    rc_ref = (ref / "rc.txt").read_text().strip()
    rc_new = (new / "rc.txt").read_text().strip() if (new / "rc.txt").exists() else "missing"
    rep.line("return code", rc_ref == rc_new, f"ref={rc_ref} new={rc_new}")
    return rep.finish()


def run_check(job: str, here: Path, script: str, unload: tuple[str, ...], compare, prepare=None,
              hex_outputs: bool = False) -> int:
    """Shared body of the run_check.py scripts: reference run, reimplementation run, comparison."""
    import argparse
    import shutil
    import sys
    import tempfile
    from harness import SAMPLE_DATA
    ap = argparse.ArgumentParser(description=f"{job} equivalence check: GnuCOBOL build vs the spec-only reimplementation")
    ap.add_argument("--stress", action="store_true", help="use the edge-case data from gen_stress.py")
    ap.add_argument("--keep", help="work in DIR and keep it (default: a temporary directory)")
    args = ap.parse_args()
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(args.keep).resolve() if args.keep else Path(tmp)
        if root.exists() and args.keep:
            for sub in ("data", "ref", "new"):
                shutil.rmtree(root / sub, ignore_errors=True)
        root.mkdir(parents=True, exist_ok=True)
        data = root / "data"
        if args.stress:
            subprocess.run([sys.executable, str(here / "gen_stress.py"), str(data)], check=True)
        else:
            shutil.copytree(SAMPLE_DATA, data)
            if prepare:
                prepare(data, root)
        run_reference(job, data, root / "ref", unload, hex_outputs=hex_outputs)
        subprocess.run([sys.executable, str(here / script), "--data", str(data), "--out", str(root / "new")],
                       check=False)
        return compare(root / "ref", root / "new")
