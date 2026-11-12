#!/usr/bin/env python3
"""make verify-batch-spec: the spec validators' live cases on a private verify-batch database.

  spec_cli.py            every required job of spec_jobs.runner (PRTCATBL, TRANBKP,
                         PURGE, DISCGRP, DATALOAD), each case in legacy and fixed mode
  ONLY=PURGE,DISCGRP     a subset: ends "verify-batch-spec: PARTIAL" and exits 3
                         even when it passed, never OK

Runs from the repository root (python3 -m modernization.verify.adapter.spec_cli),
inside make's with_env and the heavy lock. The cases use transport.SpecSession on
the verify-batch driver's locked database (CARDDEMO_VERIFY_BATCH_DB_NAME), never
the demo one. It fails closed: an open load report gap, a missing .env, jar or
switch list, a held lock, a database that is not private, a fixture login that
existed before the run or could not be dropped, a fixture ownership record left
by an earlier run, or a held lock lost before its release is FAILED, never
skipped. No SQL result, input value or
exception text is printed; an exception shows as its fixed code or its type.
Exit status 0 OK, 1 FAILED, 2 usage, 3 PARTIAL.
"""
from __future__ import annotations

import argparse
import os
import sys
import tempfile
from pathlib import Path

if __package__ in (None, ""):
    sys.path.insert(0, str(Path(__file__).resolve().parents[3]))

from modernization.verify.adapter import transport  # noqa: E402
from modernization.verify.differential import live  # noqa: E402
from modernization.verify.spec_jobs import runner  # noqa: E402

FAILED_EXIT, USAGE_EXIT, PARTIAL_EXIT = 1, 2, 3
SUMMARY = "verify-batch-spec: "


def describe(error: BaseException) -> str:
    if isinstance(error, transport.TransportError):
        return error.code
    # The driver's own refusals name settings, paths and the lock holder only.
    if isinstance(error, live.d.Unsupported):
        return str(error)[:300]
    return type(error).__name__


def say(line: str) -> None:
    print(line, flush=True)


def main(argv: list[str] | None = None, out=say) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--name", default="verify-batch-spec")
    ap.add_argument("--keep", help="work in DIR and keep it (default: a temporary directory)")
    args = ap.parse_args(argv)
    name = args.name
    only = os.environ.get("ONLY") or None
    try:
        runner.selected(only)
    except ValueError:
        out(f"{name}: FAILED (ONLY names no required job; choose from {','.join(runner.REQUIRED)})")
        return USAGE_EXIT
    if live.d.LOAD_REPORT_GAP:
        out(f"{name}: FAILED (not run: {live.d.LOAD_REPORT_GAP})")
        return FAILED_EXIT
    # The runner's summary waits until the fixture login is dropped, so a run
    # whose clean-up failed never shows OK.
    held: list[str] = []

    def collect(line: str) -> None:
        if line.startswith(SUMMARY):
            held.append(line)
        else:
            out(line)

    with tempfile.TemporaryDirectory(prefix="verify-batch-spec-") as tmp:
        root = Path(args.keep).resolve() if args.keep else Path(tmp)
        if args.keep and (root.exists() or Path(args.keep).is_symlink()) and not root.is_dir():
            out(f"{name}: FAILED (--keep {root} is not a directory)")
            return USAGE_EXIT
        if args.keep and root.exists() and any(root.iterdir()):
            out(f"{name}: FAILED (--keep {root} is not empty)")
            return USAGE_EXIT
        # Server logs and exports of the cases may hold card numbers.
        try:
            root.mkdir(parents=True, exist_ok=True, mode=0o700)
            os.chmod(root, 0o700)
        except OSError as error:
            out(f"{name}: FAILED (--keep {root}: {type(error).__name__})")
            return USAGE_EXIT
        session = None
        try:
            live.d.batch_switches()
            with live.Session(root, name) as locked:
                locked.jars()
                session = transport.SpecSession(locked)
                try:
                    code = runner.run(session, only, out=collect)
                finally:
                    session.close()
        except Exception as error:
            record = getattr(session, "fixture_record", None)
            if record is not None and getattr(error, "code", None) == "FIXTURE_LOGIN_FOREIGN":
                out(f"{name}: kept: foreign login (its name, another SID); its ownership record {record} stays, "
                    "and every run refuses at its first case's fixture login until the operator clears it (README)")
            elif record is not None:
                out(f"{name}: the fixture login was not dropped; its ownership record {record} stays, "
                    "and every run refuses at its first case's fixture login until the login is dropped by hand "
                    "(README)")
            out(f"{name}: FAILED ({describe(error)})")
            return FAILED_EXIT
    # Checked before the held summary, so an unexpected code never shows OK.
    if code not in (0, 1, PARTIAL_EXIT):
        out(f"{name}: FAILED (the runner ended {code!r})")
        return FAILED_EXIT
    for line in held:
        out(line)
    return code


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
