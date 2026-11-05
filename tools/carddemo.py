#!/usr/bin/env python3
"""Local build and batch runner for the CardDemo batch programs (GnuCOBOL 3.2).

Subcommands:
  check-toolchain   verify cobc version and INDEXED file support
  build             compile the batch programs, LE/assembler shims and helpers
  run NAME          run a batch job (by job name or program name) on a fresh
                    copy of the ASCII sample data in a scratch directory
  list              show which sources are built or excluded, and why
  jobs              show the runnable jobs and their DD mappings

The DD-name mapping relies on GnuCOBOL's runtime file mapping: a program that
says `SELECT X ASSIGN TO ACCTFILE` opens the path in the environment variable
DD_ACCTFILE. The runner sets those variables per job, mirroring the JCL.
"""
from __future__ import annotations

import argparse
import concurrent.futures
import dataclasses
import json
import os
import re
import shutil
import subprocess
import sys
import time
import tomllib
from pathlib import Path
from typing import Callable

ROOT = Path(__file__).resolve().parent.parent
MANIFEST_PATH = ROOT / "tools" / "batch-programs.toml"
REPRO_TEMPLATE = ROOT / "tools" / "templates" / "repro.cbl.in"
WORK_MARKER = ".carddemo-work"


class CardDemoError(Exception):
    pass


# --------------------------------------------------------------------------
# Configuration
# --------------------------------------------------------------------------
def _env_path(name: str, default: Path) -> Path:
    value = os.environ.get(name)
    if not value:
        return default
    path = Path(value)
    return path if path.is_absolute() else ROOT / path


def cobc_cmd() -> str:
    return os.environ.get("COBC", "cobc")


def build_root() -> Path:
    return _env_path("CARDDEMO_BUILD_DIR", ROOT / "build")


def data_dir() -> Path:
    return _env_path("CARDDEMO_DATA_DIR", ROOT / "app" / "data" / "ASCII")


def load_manifest() -> dict:
    with open(MANIFEST_PATH, "rb") as fh:
        return tomllib.load(fh)


@dataclasses.dataclass(frozen=True)
class Dataset:
    name: str
    lrecl: int
    organization: str
    source: str | None = None
    key: tuple[int, int] | None = None
    alternates: tuple[tuple[int, int, bool], ...] = ()

    @property
    def indexed(self) -> bool:
        return self.organization == "indexed"


def datasets(manifest: dict | None = None) -> dict[str, Dataset]:
    manifest = manifest or load_manifest()
    result = {}
    for name, spec in manifest["dataset"].items():
        key = spec.get("key")
        result[name] = Dataset(
            name=name,
            lrecl=spec["lrecl"],
            organization=spec["organization"],
            source=spec.get("source"),
            key=(key["offset"], key["length"]) if key else None,
            alternates=tuple(
                (a["offset"], a["length"], a.get("duplicates", False))
                for a in spec.get("alternate", [])
            ),
        )
    return result


# --------------------------------------------------------------------------
# Toolchain
# --------------------------------------------------------------------------
def _run_capture(cmd: list[str]) -> str:
    try:
        proc = subprocess.run(cmd, capture_output=True, text=True, check=False)
    except FileNotFoundError as exc:
        raise CardDemoError(f"{cmd[0]} not found on PATH; install GnuCOBOL 3.2") from exc
    if proc.returncode != 0:
        raise CardDemoError(f"{' '.join(cmd)} failed:\n{proc.stdout}{proc.stderr}")
    return proc.stdout


def cobc_info() -> dict[str, str]:
    info = {}
    for line in _run_capture([cobc_cmd(), "--info"]).splitlines():
        m = re.match(r"^([A-Za-z0-9_ -]+?)\s+:\s+(.*)$", line)
        if m:
            info[m.group(1).strip()] = m.group(2).strip()
    return info


def check_toolchain(verbose: bool = True) -> dict[str, str]:
    manifest = load_manifest()
    want = manifest["toolchain"]["cobc_version"]
    py_min = tuple(int(p) for p in manifest["toolchain"]["python_min"].split("."))
    if sys.version_info[:2] < py_min:
        raise CardDemoError(f"Python {'.'.join(map(str, py_min))}+ required, found {sys.version.split()[0]}")
    first = _run_capture([cobc_cmd(), "--version"]).splitlines()[0]
    m = re.search(r"\(GnuCOBOL\)\s+(\d+\.\d+)(?:\.(\d+))?", first)
    if not m:
        raise CardDemoError(f"cannot parse cobc version from: {first!r}")
    found = m.group(1)
    if found != want:
        raise CardDemoError(f"GnuCOBOL {want}.x required, found {found} ({first})")
    info = cobc_info()
    handler = info.get("indexed file handler", "")
    if not handler or "disabled" in handler.lower():
        raise CardDemoError("this cobc build has no INDEXED file handler (need BDB, LMDB or VBISAM)")
    result = {
        "cobc": f"{m.group(1)}.{m.group(2) or '0'}",
        "indexed": handler,
        "module_ext": info.get("COB_MODULE_EXT", "so"),
        "python": sys.version.split()[0],
    }
    if verbose:
        print(f"cobc {result['cobc']} (required {want}.x), indexed handler {handler}, "
              f"python {result['python']}: OK")
    return result


# --------------------------------------------------------------------------
# Build
# --------------------------------------------------------------------------
@dataclasses.dataclass
class Unit:
    name: str
    source: Path
    kind: str           # "executable" | "module"
    output: Path
    flags: list[str]
    role: str           # "program" | "shim" | "driver" | "tool"


@dataclasses.dataclass
class BuildLayout:
    root: Path

    @property
    def bin(self) -> Path:
        return self.root / "bin"

    @property
    def lib(self) -> Path:
        return self.root / "lib"

    @property
    def tools(self) -> Path:
        return self.root / "tools"

    @property
    def gen(self) -> Path:
        return self.root / "gen"


def layout(variant: str = "release") -> BuildLayout:
    return BuildLayout(build_root() / variant)


def repro_name(dataset: str) -> str:
    return f"repro-{dataset.lower()}"


def generate_repro(ds: Dataset) -> str:
    """COBOL source for a LOAD/UNLOAD utility (IDCAMS REPRO) for one KSDS shape."""
    segments = [(ds.key[0], ds.key[1], "KS-KEY")]
    alt_clauses = []
    for i, (off, length, dups) in enumerate(ds.alternates, start=1):
        segments.append((off, length, f"KS-ALT{i}"))
        alt_clauses.append(
            f"               ALTERNATE RECORD KEY IS KS-ALT{i}"
            + (" WITH DUPLICATES" if dups else "")
        )
    segments.sort()
    fields, pos = [], 0
    for off, length, name in segments:
        if off < pos:
            raise CardDemoError(f"{ds.name}: overlapping key segments")
        if off > pos:
            fields.append(f"           05  FILLER              PIC X({off - pos}).")
        fields.append(f"           05  {name:<19} PIC X({length}).")
        pos = off + length
    if pos < ds.lrecl:
        fields.append(f"           05  FILLER              PIC X({ds.lrecl - pos}).")
    template = REPRO_TEMPLATE.read_text()
    return (template
            .replace("@LRECL@", str(ds.lrecl))
            .replace("@ALTERNATE-KEYS@\n", "".join(c + "\n" for c in alt_clauses))
            .replace("@KS-FIELDS@\n", "".join(f + "\n" for f in fields))
            .replace("@DATASET@", ds.name))


def build_units(variant: str, module_ext: str, manifest: dict | None = None) -> list[Unit]:
    manifest = manifest or load_manifest()
    lay = layout(variant)
    base = list(manifest["toolchain"]["cobc_flags"])
    for d in manifest["toolchain"]["copybook_dirs"]:
        base += ["-I", str(ROOT / d)]
    program_flags = base + (["-ftraceall"] if variant == "trace" else [])
    units: list[Unit] = []
    for shim in manifest.get("shim", []):
        units.append(Unit(shim["name"], ROOT / shim["source"], "module",
                          lay.lib / f"{shim['name']}.{module_ext}", base, "shim"))
    for drv in manifest.get("driver", []):
        units.append(Unit(drv["name"], ROOT / drv["source"], "executable",
                          lay.bin / drv["name"], base, "driver"))
    for prog in manifest["program"]:
        if prog["type"] == "module":
            out = lay.lib / f"{prog['name']}.{module_ext}"
        else:
            out = lay.bin / prog["name"]
        units.append(Unit(prog["name"], ROOT / prog["source"], prog["type"], out,
                          program_flags, "program"))
    for ds in datasets(manifest).values():
        if ds.indexed:
            units.append(Unit(repro_name(ds.name), lay.gen / f"{repro_name(ds.name)}.cbl",
                              "executable", lay.tools / repro_name(ds.name), base, "tool"))
    return units


def _newest_input_mtime(manifest: dict) -> float:
    paths = [MANIFEST_PATH, Path(__file__), REPRO_TEMPLATE]
    for d in manifest["toolchain"]["copybook_dirs"]:
        paths += list((ROOT / d).iterdir())
    return max(p.stat().st_mtime for p in paths if p.is_file())


def _compile(unit: Unit) -> tuple[Unit, bool, str, float]:
    unit.output.parent.mkdir(parents=True, exist_ok=True)
    mode = "-x" if unit.kind == "executable" else "-m"
    cmd = [cobc_cmd(), mode, *unit.flags, "-o", str(unit.output), str(unit.source)]
    start = time.monotonic()
    proc = subprocess.run(cmd, capture_output=True, text=True, check=False)
    elapsed = time.monotonic() - start
    # clang on macOS prints this for every cobc -m link; it is not actionable.
    noise = re.compile(r"^ld: warning: -undefined suppress is deprecated$")
    output = "\n".join(l for l in (proc.stdout + proc.stderr).splitlines() if not noise.match(l))
    return unit, proc.returncode == 0, output, elapsed


def build(variant: str = "release", jobs: int | None = None, verbose: bool = True) -> BuildLayout:
    if variant not in ("release", "trace"):
        raise CardDemoError(f"unknown build variant {variant!r}")
    tc = check_toolchain(verbose=verbose)
    manifest = load_manifest()
    lay = layout(variant)
    lay.gen.mkdir(parents=True, exist_ok=True)
    for ds in datasets(manifest).values():
        if ds.indexed:
            src = lay.gen / f"{repro_name(ds.name)}.cbl"
            text = generate_repro(ds)
            if not src.exists() or src.read_text() != text:
                src.write_text(text)
    units = build_units(variant, tc["module_ext"], manifest)
    stamp = lay.root / "build-flags.json"
    flags_now = json.dumps({u.name: u.flags for u in units}, sort_keys=True)
    rebuild_all = not stamp.exists() or stamp.read_text() != flags_now
    newest_shared = _newest_input_mtime(manifest)
    todo = [u for u in units
            if rebuild_all or not u.output.exists()
            or u.output.stat().st_mtime < max(newest_shared, u.source.stat().st_mtime)]
    started = time.monotonic()
    results = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=jobs or os.cpu_count() or 2) as pool:
        results = list(pool.map(_compile, todo))
    failures = [(u, out) for u, ok, out, _ in results if not ok]
    timings = {u.name: round(t, 3) for u, _, _, t in results}
    wall = time.monotonic() - started
    (lay.root / "build-times.json").write_text(json.dumps(
        {"variant": variant, "wall_seconds": round(wall, 3), "compiled": timings}, indent=2) + "\n")
    if failures:
        for unit, out in failures:
            print(f"FAILED {unit.name} ({unit.source.relative_to(ROOT)}):\n{out}", file=sys.stderr)
        raise CardDemoError(f"{len(failures)} of {len(todo)} compile(s) failed")
    stamp.write_text(flags_now)
    if verbose:
        counts = {}
        for u in units:
            counts[u.role] = counts.get(u.role, 0) + 1
        print(f"build[{variant}]: {len(todo)} compiled, {len(units) - len(todo)} up to date "
              f"in {wall:.1f}s ({counts.get('program', 0)} CardDemo programs, "
              f"{counts.get('shim', 0)} shims, {counts.get('driver', 0)} drivers, "
              f"{counts.get('tool', 0)} KSDS utilities) -> {lay.root.relative_to(ROOT) if lay.root.is_relative_to(ROOT) else lay.root}")
        for name, secs in sorted(timings.items(), key=lambda kv: -kv[1])[:5]:
            print(f"  {secs:6.2f}s {name}")
    return lay


def all_cobol_sources() -> list[str]:
    found = []
    for path in (ROOT / "app").rglob("*"):
        if path.is_file() and path.suffix.lower() in (".cbl", ".cob"):
            found.append(path.relative_to(ROOT).as_posix())
    return sorted(found)


def classify_sources(manifest: dict | None = None) -> tuple[dict[str, str], dict[str, str], list[str]]:
    """Return (built source -> program, excluded source -> reason, unclassified)."""
    manifest = manifest or load_manifest()
    built = {p["source"]: p["name"] for p in manifest["program"]}
    excluded = {s: e["reason"] for e in manifest.get("exclude", []) for s in e["sources"]}
    unclassified = [s for s in all_cobol_sources() if s not in built and s not in excluded]
    return built, excluded, unclassified


# --------------------------------------------------------------------------
# Data preparation
# --------------------------------------------------------------------------
def read_text_records(path: Path, lrecl: int) -> list[bytes]:
    """Read a newline-delimited sample file into fixed-length records."""
    records = []
    for lineno, raw in enumerate(path.read_bytes().split(b"\n"), start=1):
        line = raw[:-1] if raw.endswith(b"\r") else raw
        if not line:
            continue
        if len(line) > lrecl:
            raise CardDemoError(f"{path}:{lineno}: record is {len(line)} bytes, LRECL is {lrecl}")
        records.append(line.ljust(lrecl, b" "))
    return records


def write_fixed(path: Path, records: list[bytes], lrecl: int) -> None:
    """Write a RECFM=FB dataset: fixed-length records, no delimiters."""
    with open(path, "wb") as fh:
        for rec in records:
            if len(rec) > lrecl:
                raise CardDemoError(f"{path}: record longer than LRECL {lrecl}")
            fh.write(rec.ljust(lrecl, b" "))


def read_fixed(path: Path, lrecl: int) -> list[bytes]:
    data = path.read_bytes()
    if len(data) % lrecl:
        raise CardDemoError(f"{path}: size {len(data)} is not a multiple of LRECL {lrecl}")
    return [data[i:i + lrecl] for i in range(0, len(data), lrecl)]


# --------------------------------------------------------------------------
# Jobs
# --------------------------------------------------------------------------
@dataclasses.dataclass(frozen=True)
class DD:
    dataset: str | None = None      # manifest dataset used as input (loaded on first use)
    new: str | None = None          # "FB", "VB" or "KSDS": created by the step
    lrecl: int | None = None
    file: str | None = None         # work-directory file written by a pre-step
    inline: str | None = None       # DD * content, one record per line


@dataclasses.dataclass(frozen=True)
class Job:
    name: str
    program: str
    dd: dict[str, DD]
    jcl: str | None = None
    parm: str | None = None
    sysin: str | None = None
    max_rc: int = 0
    after: tuple[str, ...] = ()
    pre: Callable[["WorkDir"], None] | None = None
    note: str = ""


@dataclasses.dataclass
class StepResult:
    job: str
    program: str
    rc: int
    sysout: str
    seconds: float
    outputs: dict[str, tuple[Path, int]]   # DD -> (path, record count)


class WorkDir:
    """A scratch directory holding the datasets for one or more chained jobs."""

    def __init__(self, path: Path, variant: str = "release", trace_file: Path | None = None):
        self.path = path
        self.variant = variant
        self.layout = layout(variant)
        self.datasets = datasets()
        self.trace_file = trace_file

    @classmethod
    def fresh(cls, path: Path, **kwargs) -> "WorkDir":
        path = path.resolve()
        if path.exists() and any(path.iterdir()) and not (path / WORK_MARKER).exists():
            raise CardDemoError(f"{path} exists and is not a CardDemo work directory; refusing to clear it")
        if path.exists():
            shutil.rmtree(path)
        path.mkdir(parents=True)
        (path / WORK_MARKER).write_text("scratch data for tools/carddemo.py; safe to delete\n")
        return cls(path, **kwargs)

    # dataset files ---------------------------------------------------------
    def dataset_path(self, name: str) -> Path:
        ds = self.datasets[name]
        return self.path / f"{name}.{'ksds' if ds.indexed else 'ps'}"

    def ensure_dataset(self, name: str) -> Path:
        """Materialize a dataset from the sample data unless a prior job left it."""
        ds = self.datasets[name]
        target = self.dataset_path(name)
        if target.exists():
            return target
        records = read_text_records(data_dir() / ds.source, ds.lrecl) if ds.source else []
        if ds.indexed:
            self.load_ksds(name, records)
        else:
            write_fixed(target, records, ds.lrecl)
        return target

    def repro(self, name: str, mode: str, ls_path: Path) -> None:
        exe = self.layout.tools / repro_name(name)
        env = self.base_env()
        env["DD_LSFILE"] = str(ls_path)
        env["DD_KSFILE"] = str(self.dataset_path(name))
        proc = subprocess.run([str(exe), mode], cwd=self.path, env=env,
                              capture_output=True, text=True, check=False)
        if proc.returncode != 0:
            raise CardDemoError(f"{mode} {name} failed (rc={proc.returncode}):\n{proc.stdout}{proc.stderr}")

    def load_ksds(self, name: str, records: list[bytes]) -> None:
        """IDCAMS DEFINE + REPRO equivalent: build the indexed file from records."""
        ds = self.datasets[name]
        for p in self.path.glob(f"{name}.ksds*"):
            p.unlink()
        ls_path = self.path / f".{name}.load.ps"
        write_fixed(ls_path, records, ds.lrecl)
        self.repro(name, "LOAD", ls_path)
        ls_path.unlink()

    def unload_ksds(self, name: str) -> list[bytes]:
        ds = self.datasets[name]
        ls_path = self.path / f".{name}.unload.ps"
        self.repro(name, "UNLOAD", ls_path)
        records = read_fixed(ls_path, ds.lrecl)
        ls_path.unlink()
        return records

    # running ---------------------------------------------------------------
    def base_env(self) -> dict[str, str]:
        env = {k: v for k, v in os.environ.items()
               if not k.upper().startswith("DD_") and not k.startswith("COB_TRACE")
               and k != "COB_SET_TRACE"}
        env["COB_LIBRARY_PATH"] = str(self.layout.lib)
        return env

    def resolve_dd(self, job: Job) -> dict[str, tuple[Path, DD]]:
        paths = {}
        for ddname, dd in job.dd.items():
            if dd.dataset and not dd.new:
                path = self.ensure_dataset(dd.dataset)
            elif dd.dataset and dd.new == "KSDS":
                path = self.dataset_path(dd.dataset)
                for p in self.path.glob(f"{path.name}*"):
                    p.unlink()
            elif dd.new:
                path = self.path / f"{job.name}.{ddname}.ps"
                path.unlink(missing_ok=True)
            elif dd.file:
                path = self.path / dd.file
                if not path.exists():
                    raise CardDemoError(f"{job.name}: {ddname} expects {dd.file}, which no step created")
            elif dd.inline is not None:
                path = self.path / f"{job.name}.{ddname}.in"
                write_fixed(path, [l.encode() for l in dd.inline.splitlines()], dd.lrecl or 80)
            else:
                raise CardDemoError(f"{job.name}: DD {ddname} has no source")
            paths[ddname] = (path, dd)
        return paths

    def run_job(self, job: Job) -> StepResult:
        program = program_entry(job.program)
        if job.pre:
            job.pre(self)
        dds = self.resolve_dd(job)
        env = self.base_env()
        for ddname, (path, _) in dds.items():
            env[f"DD_{ddname}"] = str(path)
        if self.trace_file is not None:
            env["COB_SET_TRACE"] = "Y"
            env["COB_TRACE_FILE"] = str(self.trace_file)
        if program["type"] == "module":
            cmd = [str(self.layout.bin / "PARMRUN"), job.program, job.parm or ""]
        else:
            cmd = [str(self.layout.bin / job.program)]
        if not Path(cmd[0]).exists():
            raise CardDemoError(f"{cmd[0]} is not built; run `make build`")
        start = time.monotonic()
        proc = subprocess.run(cmd, cwd=self.path, env=env, input=job.sysin or "",
                              capture_output=True, text=True, check=False)
        seconds = time.monotonic() - start
        if self.trace_file is not None:
            with open(self.trace_file, "a") as fh:
                fh.write("")  # ensure the file exists even if nothing was traced
        outputs = {}
        for ddname, (path, dd) in dds.items():
            if dd.new and dd.new != "KSDS" and path.exists():
                count = len(read_fixed(path, dd.lrecl)) if dd.new == "FB" else count_vb(path)
                outputs[ddname] = (path, count)
            elif dd.new == "KSDS" and path.exists():
                outputs[ddname] = (path, len(self.unload_ksds(dd.dataset)))
        sysout = proc.stdout + (proc.stderr if proc.stderr else "")
        return StepResult(job.name, job.program, proc.returncode, sysout, seconds, outputs)

    def run_chain(self, job: Job) -> list[StepResult]:
        results = []
        for prior in job.after:
            res = self.run_chain(JOBS[prior])
            results += res
            if res[-1].rc > JOBS[prior].max_rc:
                return results
        results.append(self.run_job(job))
        return results


def count_vb(path: Path) -> int:
    """Count records in a GnuCOBOL variable-length sequential file (4-byte RDW-style header)."""
    data, count, pos = path.read_bytes(), 0, 0
    while pos + 4 <= len(data):
        length = int.from_bytes(data[pos:pos + 2], "big")
        pos += 4 + length
        count += 1
    if pos != len(data):
        raise CardDemoError(f"{path}: malformed variable-length file")
    return count


def program_entry(name: str) -> dict:
    for prog in load_manifest()["program"]:
        if prog["name"] == name:
            return prog
    raise CardDemoError(f"{name} is not a locally built program; see `python3 tools/carddemo.py list`")


# Pre-steps that stand in for DFSORT/IDCAMS steps in the JCL ---------------
REPORT_START = "2022-01-01"   # TRANREPT.jcl SYMNAMES PARM-START-DATE
REPORT_END = "2022-07-06"     # TRANREPT.jcl SYMNAMES PARM-END-DATE


def pre_tranrept(work: WorkDir) -> None:
    """STEP05R REPRO TRANSACT -> BKUP, then SORT by card, INCLUDE proc date in range."""
    records = work.unload_ksds("TRANSACT")
    selected = [r for r in records if REPORT_START.encode() <= r[304:314] <= REPORT_END.encode()]
    selected.sort(key=lambda r: r[262:278])
    write_fixed(work.path / "TRANSACT.DALY.ps", selected, 350)


def pre_creastmt(work: WorkDir) -> None:
    """STEP010 SORT FIELDS=(263,16,CH,A,1,16,CH,A), OUTREC=(1:263,16,17:1,262,279:279,50);
    STEP020 REPRO into the TRXFL KSDS (key = card number + transaction id)."""
    records = work.unload_ksds("TRANSACT")
    records.sort(key=lambda r: (r[262:278], r[0:16]))
    rebuilt = [(r[262:278] + r[0:262] + r[278:328]).ljust(350, b" ") for r in records]
    work.load_ksds("TRXFL", rebuilt)


DATEPARM = f"{REPORT_START} {REPORT_END}"

JOBS: dict[str, Job] = {j.name: j for j in [
    Job("READACCT", "CBACT01C", jcl="app/jcl/READACCT.jcl", dd={
        "ACCTFILE": DD(dataset="ACCTDATA"),
        "OUTFILE": DD(new="FB", lrecl=107),
        "ARRYFILE": DD(new="FB", lrecl=110),
        "VBRCFILE": DD(new="VB", lrecl=84),
    }),
    Job("READCARD", "CBACT02C", jcl="app/jcl/READCARD.jcl", dd={"CARDFILE": DD(dataset="CARDDATA")}),
    Job("READXREF", "CBACT03C", jcl="app/jcl/READXREF.jcl", dd={"XREFFILE": DD(dataset="CARDXREF")}),
    Job("READCUST", "CBCUS01C", jcl="app/jcl/READCUST.jcl", dd={"CUSTFILE": DD(dataset="CUSTDATA")}),
    Job("POSTTRAN", "CBTRN02C", jcl="app/jcl/POSTTRAN.jcl", max_rc=4, dd={
        "TRANFILE": DD(dataset="TRANSACT", new="KSDS"),
        "DALYTRAN": DD(dataset="DALYTRAN"),
        "XREFFILE": DD(dataset="CARDXREF"),
        "DALYREJS": DD(new="FB", lrecl=430),
        "ACCTFILE": DD(dataset="ACCTDATA"),
        "TCATBALF": DD(dataset="TCATBALF"),
    }, note="RC=4 when any transaction is rejected"),
    Job("TRANVALD", "CBTRN01C", dd={
        "DALYTRAN": DD(dataset="DALYTRAN"),
        "CUSTFILE": DD(dataset="CUSTDATA"),
        "XREFFILE": DD(dataset="CARDXREF"),
        "CARDFILE": DD(dataset="CARDDATA"),
        "ACCTFILE": DD(dataset="ACCTDATA"),
        "TRANFILE": DD(dataset="TRANSACT"),
    }, note="no JCL in app/jcl; DDs taken from the program's SELECT clauses"),
    Job("INTCALC", "CBACT04C", jcl="app/jcl/INTCALC.jcl", parm="2022071800", dd={
        "TCATBALF": DD(dataset="TCATBALF"),
        "XREFFILE": DD(dataset="CARDXREF"),
        "ACCTFILE": DD(dataset="ACCTDATA"),
        "DISCGRP": DD(dataset="DISCGRP"),
        "TRANSACT": DD(new="FB", lrecl=350),
    }),
    Job("TRANREPT", "CBTRN03C", jcl="app/jcl/TRANREPT.jcl", after=("POSTTRAN",), pre=pre_tranrept, dd={
        "TRANFILE": DD(file="TRANSACT.DALY.ps"),
        "CARDXREF": DD(dataset="CARDXREF"),
        "TRANTYPE": DD(dataset="TRANTYPE"),
        "TRANCATG": DD(dataset="TRANCATG"),
        "DATEPARM": DD(inline=DATEPARM, lrecl=80),
        "TRANREPT": DD(new="FB", lrecl=133),
    }, note="runs POSTTRAN first to create TRANSACT"),
    Job("CREASTMT", "CBSTM03A", jcl="app/jcl/CREASTMT.JCL", after=("POSTTRAN",), pre=pre_creastmt, dd={
        "TRNXFILE": DD(dataset="TRXFL"),
        "XREFFILE": DD(dataset="CARDXREF"),
        "ACCTFILE": DD(dataset="ACCTDATA"),
        "CUSTFILE": DD(dataset="CUSTDATA"),
        "STMTFILE": DD(new="FB", lrecl=80),
        "HTMLFILE": DD(new="FB", lrecl=100),
    }, note="runs POSTTRAN first to create TRANSACT"),
    Job("CBEXPORT", "CBEXPORT", jcl="app/jcl/CBEXPORT.jcl", after=("POSTTRAN",), dd={
        "CUSTFILE": DD(dataset="CUSTDATA"),
        "ACCTFILE": DD(dataset="ACCTDATA"),
        "XREFFILE": DD(dataset="CARDXREF"),
        "TRANSACT": DD(dataset="TRANSACT"),
        "CARDFILE": DD(dataset="CARDDATA"),
        "EXPFILE": DD(dataset="EXPORT", new="KSDS"),
    }, note="runs POSTTRAN first to create TRANSACT"),
    Job("CBIMPORT", "CBIMPORT", jcl="app/jcl/CBIMPORT.jcl", after=("CBEXPORT",), dd={
        "EXPFILE": DD(dataset="EXPORT"),
        "CUSTOUT": DD(new="FB", lrecl=500),
        "ACCTOUT": DD(new="FB", lrecl=300),
        "XREFOUT": DD(new="FB", lrecl=50),
        "TRNXOUT": DD(new="FB", lrecl=350),
        "CARDOUT": DD(new="FB", lrecl=150),
        "ERROUT": DD(new="FB", lrecl=132),
    }, note="runs CBEXPORT first; CARDOUT is not in CBIMPORT.jcl but the program opens it"),
    Job("WAITSTEP", "COBSWAIT", jcl="app/jcl/WAITSTEP.jcl", sysin="00000050\n", dd={},
        note="SYSIN 00000050 (0.5 s); the JCL uses 00003600 (36 s)"),
]}


def find_job(name: str) -> Job:
    key = name.upper()
    if key in JOBS:
        return JOBS[key]
    matches = [j for j in JOBS.values() if j.program == key]
    if len(matches) == 1:
        return matches[0]
    raise CardDemoError(f"no job for {name!r}; choose one of: "
                        + ", ".join(f"{j.name} ({j.program})" for j in JOBS.values()))


def summarize_posttran(work: WorkDir, result: StepResult) -> list[str]:
    lines = []
    path, count = result.outputs.get("DALYREJS", (None, 0))
    if path:
        reasons: dict[str, int] = {}
        for rec in read_fixed(path, 430):
            code = rec[350:354].decode()
            desc = rec[354:430].decode().strip()
            reasons[f"{code} {desc}"] = reasons.get(f"{code} {desc}", 0) + 1
        for reason, n in sorted(reasons.items()):
            lines.append(f"  reject reason {reason}: {n}")
    return lines


SUMMARIES = {"POSTTRAN": summarize_posttran}


def cmd_run(args) -> int:
    job = find_job(args.name)
    if not args.no_build:
        build("release", verbose=False)
    workdir = Path(args.workdir) if args.workdir else build_root() / "run" / job.name
    work = WorkDir.fresh(workdir)
    print(f"== job {job.name}: program {job.program}"
          + (f" (JCL {job.jcl})" if job.jcl else "") + (f" - {job.note}" if job.note else ""))
    print(f"== work directory {work.path}")
    results = work.run_chain(job)
    worst = 0
    for res in results:
        jdef = JOBS[res.job]
        print(f"\n---- {res.job} / {res.program} SYSOUT ----")
        print(res.sysout.rstrip())
        print(f"---- {res.job} / {res.program} RETURN-CODE {res.rc} "
              f"(max accepted {jdef.max_rc}) in {res.seconds:.2f}s ----")
        for ddname, (path, count) in res.outputs.items():
            print(f"  {ddname:<9} {count:>6} records  {path.name}")
        for line in SUMMARIES.get(res.job, lambda *_: [])(work, res):
            print(line)
        if res.rc > jdef.max_rc:
            worst = max(worst, res.rc)
    final = results[-1]
    status = "OK" if worst == 0 and final.rc <= job.max_rc else "FAILED"
    print(f"\nRESULT {status}: {final.program} RETURN-CODE={final.rc}")
    return 0 if status == "OK" else (worst or final.rc or 1)


def cmd_list(_args) -> int:
    manifest = load_manifest()
    built, excluded, unclassified = classify_sources(manifest)
    print("Built locally:")
    for prog in manifest["program"]:
        print(f"  {prog['name']:<9} {prog['type']:<10} {prog['source']}  - {prog['description']}")
    print("\nExcluded:")
    for exc in manifest["exclude"]:
        print(f"  {exc['reason']}:")
        for src in exc["sources"]:
            print(f"    {src}")
    if unclassified:
        print("\nUNCLASSIFIED (add to tools/batch-programs.toml):")
        for src in unclassified:
            print(f"  {src}")
        return 1
    return 0


def cmd_jobs(_args) -> int:
    for job in JOBS.values():
        print(f"{job.name:<9} {job.program:<9} {job.jcl or '(no JCL)'}"
              + (f"  PARM='{job.parm}'" if job.parm else "")
              + (f"  after {', '.join(job.after)}" if job.after else ""))
        for ddname, dd in job.dd.items():
            what = (f"dataset {dd.dataset}" if dd.dataset and not dd.new
                    else f"new {dd.new}" + (f" {dd.dataset}" if dd.dataset else f" LRECL={dd.lrecl}")
                    if dd.new else f"file {dd.file}" if dd.file else "inline (DD *)")
            print(f"    {ddname:<9} {what}")
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("check-toolchain")
    b = sub.add_parser("build")
    b.add_argument("--variant", choices=["release", "trace"], default="release")
    b.add_argument("-j", "--jobs", type=int)
    r = sub.add_parser("run")
    r.add_argument("name", help="job name (e.g. POSTTRAN) or program name (e.g. CBTRN02C)")
    r.add_argument("--workdir", help="scratch directory (default build/run/<JOB>)")
    r.add_argument("--no-build", action="store_true")
    sub.add_parser("list")
    sub.add_parser("jobs")
    args = parser.parse_args(argv)
    try:
        if args.command == "check-toolchain":
            check_toolchain()
            return 0
        if args.command == "build":
            build(args.variant, jobs=args.jobs)
            return 0
        if args.command == "run":
            return cmd_run(args)
        if args.command == "list":
            return cmd_list(args)
        if args.command == "jobs":
            return cmd_jobs(args)
    except CardDemoError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2
    return 1


if __name__ == "__main__":
    sys.exit(main())
