"""Shared helpers for the spec-only reimplementation checks.

A check runs the real program through tools/carddemo.py on a data directory,
writes its outputs as newline-separated records, runs the reimplementation on
the same data and compares the two. Run `make build` first.
"""
from __future__ import annotations

import os
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[4]
sys.path.insert(0, str(REPO / "tools"))
sys.path.insert(0, str(REPO))

import carddemo  # noqa: E402

SAMPLE_DATA = REPO / "app" / "data" / "ASCII"


def write_records(path: Path, records: list[bytes]) -> None:
    path.write_bytes(b"".join(r + b"\n" for r in records))


def read_records(path: Path) -> list[str]:
    return [l for l in path.read_bytes().decode("latin-1").split("\n") if l != ""]


def run_reference(job_name: str, data_dir: Path, out_dir: Path, unload: list[str]) -> int:
    """Run a job on data_dir with the GnuCOBOL build and write its outputs to out_dir.

    Writes sysout.txt, rc.txt, <DDNAME>.txt for every new sequential output
    and <DATASET>.after.txt for every KSDS named in unload. Returns the
    return code of the job's last step.
    """
    out_dir.mkdir(parents=True, exist_ok=True)
    old = os.environ.get("CARDDEMO_DATA_DIR")
    os.environ["CARDDEMO_DATA_DIR"] = str(data_dir.resolve())
    try:
        job = carddemo.find_job(job_name)
        work = carddemo.WorkDir.fresh(out_dir / "work")
        results = work.run_chain(job)
    finally:
        if old is None:
            os.environ.pop("CARDDEMO_DATA_DIR", None)
        else:
            os.environ["CARDDEMO_DATA_DIR"] = old
    final = results[-1]
    if final.job != job.name:
        raise carddemo.CardDemoError(f"{job_name}: prior step {final.job} failed (rc={final.rc})")
    (out_dir / "sysout.txt").write_text(final.sysout)
    (out_dir / "rc.txt").write_text(f"{final.rc}\n")
    for ddname, dd in job.dd.items():
        path = work.path / f"{job.name}.{ddname}.ps"
        if dd.new == "FB" and path.exists():
            write_records(out_dir / f"{ddname}.txt", carddemo.read_fixed(path, dd.lrecl))
    for name in unload:
        write_records(out_dir / f"{name}.after.txt", work.unload_ksds(name))
    return final.rc


class Report:
    def __init__(self) -> None:
        self.ok = True

    def line(self, name: str, good: bool, detail: str = "") -> None:
        self.ok &= good
        print(f"{'PASS' if good else 'FAIL'} {name} {detail}".rstrip())

    def records(self, name: str, ref: list[str], new: list[str]) -> None:
        diffs = [i for i, (a, b) in enumerate(zip(ref, new)) if a != b]
        detail = f"ref={len(ref)} new={len(new)} differing={len(diffs)}"
        if diffs:
            i = diffs[0]
            detail += f" first at #{i}: ref={ref[i]!r} new={new[i]!r}"
        self.line(name, len(ref) == len(new) and not diffs, detail)

    def finish(self) -> int:
        print("OVERALL", "PASS" if self.ok else "FAIL")
        return 0 if self.ok else 1
