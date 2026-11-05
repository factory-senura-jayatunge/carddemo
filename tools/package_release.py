#!/usr/bin/env python3
"""Package build/release into a release archive plus SHA256SUMS.

    python3 tools/package_release.py VERSION [--out dist]

The archive carddemo-batch-VERSION-<os>-<arch>.tar.gz holds the compiled
programs (bin/), modules (lib/), KSDS utilities (tools/), BUILDINFO.json
and, when present, the test results of the run that produced the build.
Entries are sorted with fixed owners and timestamps (SOURCE_DATE_EPOCH,
default 0), so the same build gives the same archive. The binaries link
libcob dynamically: run them where GnuCOBOL 3.2's runtime is installed,
e.g. the dev container.
"""
from __future__ import annotations

import argparse
import gzip
import hashlib
import io
import json
import os
import platform
import re
import subprocess
import sys
import tarfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PARTS = ("bin", "lib", "tools")
TEST_RESULTS = ("junit.xml", "summary.json", "coverage.json", "timings.json")
VERSION_RE = re.compile(r"^v?\d+\.\d+\.\d+([-.][0-9A-Za-z.]+)?$")


def git_commit(root: Path) -> str | None:
    try:
        out = subprocess.run(["git", "-C", str(root), "rev-parse", "HEAD"], capture_output=True,
                             text=True, check=True)
    except (OSError, subprocess.CalledProcessError):
        return None
    return out.stdout.strip()


def build_info(version: str, build: Path, root: Path) -> dict:
    flags_file = build / "build-flags.json"
    flags = json.loads(flags_file.read_text()) if flags_file.exists() else {}
    prefix = str(root) + os.sep
    # Absolute include paths are machine-specific; record them relative to the repo.
    flags = {name: [f.replace(prefix, "") for f in args] for name, args in sorted(flags.items())}
    cobc = None
    try:
        cobc = subprocess.run([os.environ.get("COBC", "cobc"), "--version"], capture_output=True,
                              text=True, check=True).stdout.splitlines()[0]
    except (OSError, subprocess.CalledProcessError, IndexError):
        pass
    return {"version": version, "commit": git_commit(root), "cobc": cobc,
            "platform": f"{platform.system().lower()}-{platform.machine()}", "cobc_flags": flags}


def _add(tar: tarfile.TarFile, name: str, data: bytes, mode: int, mtime: int) -> None:
    info = tarfile.TarInfo(name)
    info.size, info.mode, info.mtime = len(data), mode, mtime
    info.uid = info.gid = 0
    info.uname = info.gname = ""
    tar.addfile(info, io.BytesIO(data))


def package(version: str, build: Path, out: Path, root: Path = ROOT,
            test_results: Path | None = None, mtime: int = 0) -> Path:
    if not VERSION_RE.match(version):
        raise SystemExit(f"version {version!r} is not like v1.2.3")
    missing = [p for p in PARTS[:1] if not (build / p).is_dir()]
    if missing:
        raise SystemExit(f"{build} has no {', '.join(missing)}/; run `make build` first")
    info = build_info(version, build, root)
    stem = f"carddemo-batch-{version}-{info['platform']}"
    files: list[tuple[str, bytes, int]] = []
    for part in PARTS:
        base = build / part
        if not base.is_dir():
            continue
        for p in sorted(base.rglob("*")):
            if p.is_file():
                mode = 0o755 if os.access(p, os.X_OK) else 0o644
                files.append((f"{stem}/{part}/{p.relative_to(base).as_posix()}", p.read_bytes(), mode))
    if test_results and (test_results / "summary.json").exists():
        for name in TEST_RESULTS:
            if (test_results / name).exists():
                files.append((f"{stem}/test-results/{name}", (test_results / name).read_bytes(), 0o644))
    files.append((f"{stem}/BUILDINFO.json", (json.dumps(info, indent=2) + "\n").encode(), 0o644))

    out.mkdir(parents=True, exist_ok=True)
    archive = out / f"{stem}.tar.gz"
    raw = io.BytesIO()
    with tarfile.open(fileobj=raw, mode="w", format=tarfile.PAX_FORMAT) as tar:
        for name, data, mode in sorted(files):
            _add(tar, name, data, mode, mtime)
    archive.write_bytes(gzip.compress(raw.getvalue(), mtime=mtime))
    sums = out / "SHA256SUMS"
    lines = [l for l in (sums.read_text().splitlines() if sums.exists() else [])
             if not l.endswith("  " + archive.name)]
    lines.append(f"{hashlib.sha256(archive.read_bytes()).hexdigest()}  {archive.name}")
    sums.write_text("\n".join(sorted(lines, key=lambda l: l.split("  ", 1)[1])) + "\n")
    return archive


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("version", help="release version, e.g. v1.0.0 (the tag name)")
    ap.add_argument("--out", type=Path, default=ROOT / "dist")
    ap.add_argument("--build", type=Path, default=ROOT / "build" / "release")
    ap.add_argument("--test-results", type=Path, default=ROOT / "build" / "test-results")
    args = ap.parse_args(argv)
    mtime = int(os.environ.get("SOURCE_DATE_EPOCH", "0"))
    archive = package(args.version, args.build, args.out, ROOT, args.test_results, mtime)
    print(f"{archive} ({archive.stat().st_size} bytes)")
    print((args.out / "SHA256SUMS").read_text(), end="")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
