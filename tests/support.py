"""Shared helpers for the CardDemo test suite.

Every test gets its own temporary directory (removed afterwards) and builds
its datasets there, so tests can run in any order and in parallel.
"""
from __future__ import annotations

import datetime
import os
import subprocess
import sys
import tempfile
import threading
import tomllib
import unittest
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "tools"))

import carddemo  # noqa: E402

CONFIG_PATH = ROOT / "tests" / "test-config.toml"
VARIANT = os.environ.get("CARDDEMO_TEST_VARIANT", "trace")
_build_lock = threading.Lock()
_built: set[str] = set()


def load_config() -> dict:
    with open(CONFIG_PATH, "rb") as fh:
        return tomllib.load(fh)


# Seconds one program run may take before the test errors out.
TIMEOUT = float(os.environ.get("CARDDEMO_TEST_TIMEOUT") or load_config()["run"]["program_timeout_seconds"])


def business_date() -> str:
    """YYYY-MM-DD the programs see as today (CARDDEMO_BUSINESS_DATE)."""
    setting = carddemo.business_date_setting()
    if setting is None:
        return datetime.date.today().isoformat()
    return setting[:10].replace("/", "-")


def driver_sources() -> list[Path]:
    pattern = load_config()["naming"]["cobol_driver_glob"]
    return sorted((ROOT / "tests").glob(pattern))


def driver_dir(variant: str = VARIANT) -> Path:
    return carddemo.layout(variant).root / "tests"


def build_drivers(variant: str = VARIANT) -> None:
    """Compile the COBOL test drivers (tests/**/cobol/*_test.cbl)."""
    manifest = carddemo.load_manifest()
    flags = list(manifest["toolchain"]["cobc_flags"])
    for d in manifest["toolchain"]["copybook_dirs"]:
        flags += ["-I", str(ROOT / d)]
    out_dir = driver_dir(variant)
    out_dir.mkdir(parents=True, exist_ok=True)
    for src in driver_sources():
        exe = out_dir / src.stem
        if exe.exists() and exe.stat().st_mtime >= src.stat().st_mtime:
            continue
        unit = carddemo.Unit(src.stem, src, "executable", exe, flags, "test-driver")
        _, ok, output, _ = carddemo._compile(unit)
        if not ok:
            raise carddemo.CardDemoError(f"cannot compile test driver {src.name}:\n{output}")


def ensure_built(variant: str = VARIANT) -> None:
    """Build programs and drivers once per process (the runner does this up front)."""
    with _build_lock:
        if variant in _built:
            return
        carddemo.build(variant, verbose=False)
        build_drivers(variant)
        _built.add(variant)


class CardDemoTestCase(unittest.TestCase):
    """Base class: a private scratch directory plus helpers to run COBOL."""

    maxDiff = None

    def setUp(self) -> None:
        ensure_built()
        tmp = tempfile.TemporaryDirectory(prefix="carddemo-test-")
        self.addCleanup(tmp.cleanup)
        self.tmp = Path(tmp.name)

    def trace_file(self) -> Path | None:
        trace_dir = os.environ.get("CARDDEMO_TRACE_DIR")
        if not trace_dir:
            return None
        return Path(trace_dir) / f"{self.id()}-{uuid.uuid4().hex[:8]}.trace"

    def new_work(self, name: str = "work") -> carddemo.WorkDir:
        return carddemo.WorkDir.fresh(self.tmp / name, variant=VARIANT, trace_file=self.trace_file(),
                                      timeout=TIMEOUT)

    def run_job(self, name: str, work: carddemo.WorkDir | None = None) -> tuple[carddemo.WorkDir, list[carddemo.StepResult]]:
        work = work or self.new_work()
        return work, work.run_chain(carddemo.JOBS[name])

    def assert_rc(self, result: carddemo.StepResult, expected: int) -> None:
        self.assertEqual(result.rc, expected, f"{result.program} RC {result.rc}; SYSOUT:\n{result.sysout}")

    def _run(self, cmd: list[str], stdin: str, work: carddemo.WorkDir,
             dd: dict[str, Path] | None) -> subprocess.CompletedProcess:
        env = work.base_env()
        for name, path in (dd or {}).items():
            env[f"DD_{name}"] = str(path)
        work.add_trace_env(env)
        return subprocess.run(cmd, input=stdin, cwd=work.path, env=env, capture_output=True,
                              text=True, check=False, timeout=work.timeout)

    def run_driver(self, stem: str, stdin: str, work: carddemo.WorkDir | None = None,
                   dd: dict[str, Path] | None = None) -> subprocess.CompletedProcess:
        """Run a COBOL test driver; stdin lines are its requests (END is appended)."""
        work = work or self.new_work()
        return self._run([str(driver_dir() / stem)], stdin + "END\n", work, dd)

    def run_program(self, program: str, dd: dict[str, Path], work: carddemo.WorkDir | None = None,
                    stdin: str = "", parm: str | None = None) -> subprocess.CompletedProcess:
        """Run a built CardDemo program with explicit DD paths (no job definition)."""
        work = work or self.new_work()
        if carddemo.program_entry(program)["type"] == "module":
            cmd = [str(work.layout.bin / "PARMRUN"), program, parm or ""]
        else:
            cmd = [str(work.layout.bin / program)]
        return self._run(cmd, stdin, work, dd)
