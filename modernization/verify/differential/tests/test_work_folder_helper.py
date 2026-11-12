"""Unit tests of the refusal mode's folder helper (no stack, no database).

python3 -m unittest discover -s modernization/verify/differential/tests

The helper is loaded from the one file the exporter's jar resource is built
from, by compiling its text: no bytecode is written next to it. Each case
builds a private instance root in a temporary folder. The EACCES cases need a
user other than root, as CI's harness job is; under root they fail, never skip.
"""
from __future__ import annotations

import contextlib
import hashlib
import io
import json
import os
import shutil
import signal
import stat
import subprocess
import sys
import tempfile
import unittest
import uuid
from pathlib import Path
from types import SimpleNamespace
from unittest import mock

HERE = Path(__file__).resolve().parent
HELPER = (HERE.parent / "src" / "main" / "resources" / "org" / "carddemo" / "verify" / "differential"
          / "work_folder_helper.py")


def load() -> dict:
    namespace = {"__name__": "work_folder_helper", "__file__": str(HELPER)}
    exec(compile(HELPER.read_bytes(), str(HELPER), "exec"), namespace)  # noqa: S102
    return namespace


H = load()
FACTS = 0
REFUSE = 3
CANARY_DB = "CANARYDB-\u00e9"
CANARY_ID = "c0ffee00-cafe-4bad-8eed-0123456789ab"
CANARY_ENTRY = "CANARYENTRY"
CANARY_VAR = "CANARYVAR"
CYCLE, ATTEMPT, EXECUTION = 7, 2, 41


@contextlib.contextmanager
def deadline(seconds: int = 10):
    """The outer limit of a FIFO case: an open that blocked would end the case, not the run."""
    def expired(signum, frame):
        raise TimeoutError("the case did not finish in time")
    previous = signal.signal(signal.SIGALRM, expired)
    signal.alarm(seconds)
    try:
        yield
    finally:
        signal.alarm(0)
        signal.signal(signal.SIGALRM, previous)


class Tree:
    """<var>/out/<sha256>/<id>/ with its marker, then statements/ and .work/ as a case asks."""

    def __init__(self, base: Path, database: str = CANARY_DB, instance_id: str = CANARY_ID):
        self.database = database
        self.instance_id = instance_id
        self.var = base / CANARY_VAR
        self.out = self.var / "out"
        self.component = self.out / hashlib.sha256(database.encode("utf-8")).hexdigest()
        self.root = self.component / instance_id
        for folder in (self.var, self.out, self.component, self.root):
            folder.mkdir(mode=0o700)
            os.chmod(folder, 0o700)
        self.marker = self.root / ".instance"
        self.write_marker(H["marker"](database.encode("utf-8"), instance_id))
        self.statements = self.root / "statements"
        self.final = self.statements / f"{CYCLE}-{ATTEMPT}"
        self.work = self.statements / ".work"
        self.target = self.work / f"{CYCLE}-{ATTEMPT}.{EXECUTION}"

    def write_marker(self, content: bytes, mode: int = 0o600) -> None:
        self.marker.write_bytes(content)
        os.chmod(self.marker, mode)

    def folders(self, *paths: Path) -> None:
        for p in paths:
            p.mkdir(mode=0o700)
            os.chmod(p, 0o700)

    def full(self) -> None:
        self.folders(self.statements, self.work)

    def inputs(self, **changes) -> dict:
        value = {"var_dir": str(self.var), "database": self.database, "instance_id": self.instance_id,
                 "cycle_id": CYCLE, "attempt": ATTEMPT, "job_execution_id": EXECUTION}
        value.update(changes)
        return value


def stdin(value) -> io.BytesIO:
    data = value if isinstance(value, bytes) else json.dumps(value).encode("utf-8")
    return io.BytesIO(data)


def answer(value, probe=lambda: True) -> tuple[int, bytes]:
    return H["answer"](stdin(value), probe)


def facts(final: bool, work: str) -> tuple[int, bytes]:
    return FACTS, f"facts final={'true' if final else 'false'} work={work}\n".encode("ascii")


def refuse(code: str) -> tuple[int, bytes]:
    return REFUSE, f"refuse {code}\n".encode("ascii")


class Case(unittest.TestCase):

    def setUp(self) -> None:
        self.base = Path(os.path.realpath(tempfile.mkdtemp(prefix="wfh-")))
        os.chmod(self.base, 0o700)
        self.addCleanup(self.remove)
        self.tree = Tree(self.base)

    def remove(self) -> None:
        for dirpath, dirnames, _ in os.walk(self.base, followlinks=False):
            for name in dirnames:
                path = os.path.join(dirpath, name)
                if not os.path.islink(path):
                    os.chmod(path, 0o700)
        shutil.rmtree(self.base, ignore_errors=True)

    def check(self, expected: tuple[int, bytes], value=None) -> None:
        got = answer(self.tree.inputs() if value is None else value)
        self.assertEqual(got, expected)
        for canary in (CANARY_DB, CANARY_ID, CANARY_ENTRY, CANARY_VAR, str(self.base)):
            self.assertNotIn(canary.encode("utf-8"), got[1])


class WalkTest(Case):
    """Every V1-e and W-8 case, below a valid root."""

    def test_an_absent_statements_folder_gives_false_and_absent(self) -> None:
        self.check(facts(False, "absent"))

    def test_an_absent_work_folder_parent_gives_absent(self) -> None:
        self.tree.folders(self.tree.statements)
        self.check(facts(False, "absent"))
        self.tree.folders(self.tree.final)
        self.check(facts(True, "absent"))

    def test_the_four_work_folder_values(self) -> None:
        self.tree.full()
        self.check(facts(False, "absent"))
        self.tree.folders(self.tree.target)
        self.check(facts(False, "empty"))
        (self.tree.target / CANARY_ENTRY).write_bytes(b"x")
        self.check(facts(False, "nonempty"))
        shutil.rmtree(self.tree.target)
        self.tree.target.write_bytes(b"")
        self.check(facts(False, "not-a-directory"))

    def test_a_folder_entry_counts_and_is_never_opened(self) -> None:
        self.tree.full()
        self.tree.folders(self.tree.target, self.tree.target / CANARY_ENTRY)
        os.chmod(self.tree.target / CANARY_ENTRY, 0o000)
        self.check(facts(False, "nonempty"))

    def test_the_final_folder_exists_for_any_entry_a_link_included(self) -> None:
        self.tree.full()
        self.tree.folders(self.tree.final)
        self.check(facts(True, "absent"))
        os.rmdir(self.tree.final)
        self.tree.final.write_bytes(b"")
        self.check(facts(True, "absent"))
        os.remove(self.tree.final)
        os.symlink(self.base / "nowhere", self.tree.final)
        self.check(facts(True, "absent"))
        os.remove(self.tree.final)
        os.symlink(self.tree.root, self.tree.final)
        self.check(facts(True, "absent"))
        os.remove(self.tree.final)
        with deadline():
            os.mkfifo(self.tree.final, 0o600)
            self.check(facts(True, "absent"))

    def test_links_at_statements_and_work_refuse(self) -> None:
        real = self.base / "real"
        self.tree.folders(real)
        for level in ("statements", ".work"):
            with self.subTest(level=level):
                if level == ".work":
                    self.tree.folders(self.tree.statements)
                link = self.tree.statements if level == "statements" else self.tree.work
                for to in (self.base / "dangling", real):
                    os.symlink(to, link)
                    self.check(refuse("work-folder-refused"))
                    os.remove(link)

    def test_a_link_at_the_target_is_not_a_directory(self) -> None:
        self.tree.full()
        real = self.base / "real"
        self.tree.folders(real)
        for to in (self.base / "dangling", real):
            os.symlink(to, self.tree.target)
            self.check(facts(False, "not-a-directory"))
            os.remove(self.tree.target)

    def test_a_file_or_fifo_at_statements_or_work_refuses(self) -> None:
        for level in ("statements", ".work"):
            for kind in ("file", "fifo"):
                with self.subTest(level=level, kind=kind), deadline():
                    if level == ".work" and not self.tree.statements.exists():
                        self.tree.folders(self.tree.statements)
                    path = self.tree.statements if level == "statements" else self.tree.work
                    if kind == "file":
                        path.write_bytes(b"")
                    else:
                        os.mkfifo(path, 0o600)
                    self.check(refuse("work-folder-refused"))
                    os.remove(path)

    def test_a_fifo_at_the_target_is_not_a_directory(self) -> None:
        self.tree.full()
        with deadline():
            os.mkfifo(self.tree.target, 0o600)
            self.check(facts(False, "not-a-directory"))

    def test_eacces_at_each_level_refuses_and_is_never_absent(self) -> None:
        self.assertNotEqual(os.geteuid(), 0, "the EACCES cases need a user other than root")
        self.tree.full()
        self.tree.folders(self.tree.target)
        for path in (self.tree.statements, self.tree.work, self.tree.target):
            with self.subTest(level=path.name):
                os.chmod(path, 0o000)
                self.check(refuse("work-folder-refused"))
                os.chmod(path, 0o700)

    def test_group_or_other_write_below_the_root_refuses(self) -> None:
        self.tree.full()
        self.tree.folders(self.tree.target)
        for path in (self.tree.statements, self.tree.work, self.tree.target):
            for mode in (0o720, 0o702):
                with self.subTest(level=path.name, mode=oct(mode)):
                    os.chmod(path, mode)
                    self.check(refuse("work-folder-refused"))
                    os.chmod(path, 0o700)
            os.chmod(path, 0o755)
        self.check(facts(False, "empty"))

    def test_another_owner_refuses(self) -> None:
        # chown needs root, so the helper's own user ID is stubbed: every folder then has another owner.
        self.tree.full()
        with mock.patch.object(os, "geteuid", return_value=os.geteuid() + 1):
            self.check(refuse("instance-root-refused"))


class AnchorTest(Case):
    """The positive anchor: <var>, out, <sha256>, the root and the marker."""

    def levels(self) -> list[Path]:
        return [self.tree.var, self.tree.out, self.tree.component, self.tree.root]

    def test_a_link_file_or_fifo_at_any_level_refuses(self) -> None:
        for index in range(4):
            for kind in ("link", "file", "fifo"):
                with self.subTest(level=index, kind=kind), deadline():
                    level = self.levels()[index]
                    aside = self.base / "aside"
                    os.rename(level, aside)
                    if kind == "link":
                        os.symlink(aside, level)
                    elif kind == "file":
                        level.write_bytes(b"")
                    else:
                        os.mkfifo(level, 0o600)
                    self.check(refuse("instance-root-refused"))
                    os.remove(level)
                    os.rename(aside, level)

    def test_an_absent_root_refuses_and_never_gives_absent(self) -> None:
        shutil.rmtree(self.tree.root)
        self.check(refuse("instance-root-refused"))
        shutil.rmtree(self.tree.var)
        self.check(refuse("instance-root-refused"))

    def test_eacces_at_each_level_refuses(self) -> None:
        self.assertNotEqual(os.geteuid(), 0, "the EACCES cases need a user other than root")
        for level in self.levels():
            with self.subTest(level=level.name):
                os.chmod(level, 0o000)
                self.check(refuse("instance-root-refused"))
                os.chmod(level, 0o700)

    def test_any_group_or_other_bit_below_var_refuses(self) -> None:
        for level in self.levels()[1:]:
            for bit in (0o040, 0o020, 0o010, 0o004, 0o002, 0o001):
                with self.subTest(level=level.name, bit=oct(bit)):
                    os.chmod(level, 0o700 | bit)
                    self.check(refuse("instance-root-refused"))
                    os.chmod(level, 0o700)

    def test_var_refuses_group_or_other_write_only(self) -> None:
        for mode in (0o720, 0o702, 0o722):
            with self.subTest(mode=oct(mode)):
                os.chmod(self.tree.var, mode)
                self.check(refuse("instance-root-refused"))
        for mode in (0o755, 0o750, 0o705):
            with self.subTest(mode=oct(mode)):
                os.chmod(self.tree.var, mode)
                self.check(facts(False, "absent"))

    def test_the_marker_must_be_a_private_regular_file_with_the_exact_bytes(self) -> None:
        good = H["marker"](CANARY_DB.encode("utf-8"), CANARY_ID)
        for bit in (0o040, 0o020, 0o010, 0o004, 0o002, 0o001):
            with self.subTest(bit=oct(bit)):
                self.tree.write_marker(good, 0o600 | bit)
                self.check(refuse("instance-root-refused"))
        other_id = str(uuid.UUID(int=1))
        for content in (good + b"x", good[:-1], b"", good.replace(b"instance=", b"instance ", 1),
                        H["marker"](CANARY_DB.encode("utf-8"), other_id),
                        H["marker"]("another".encode("utf-8"), CANARY_ID)):
            with self.subTest(content=len(content)):
                self.tree.write_marker(content)
                self.check(refuse("instance-root-refused"))
        self.tree.write_marker(good)
        self.check(facts(False, "absent"))

    def test_a_marker_that_is_a_link_fifo_or_folder_refuses(self) -> None:
        good = self.base / "good-marker"
        good.write_bytes(H["marker"](CANARY_DB.encode("utf-8"), CANARY_ID))
        os.chmod(good, 0o600)
        for kind in ("link", "fifo", "folder", "missing"):
            with self.subTest(kind=kind), deadline():
                os.remove(self.tree.marker)
                if kind == "link":
                    os.symlink(good, self.tree.marker)
                elif kind == "fifo":
                    os.mkfifo(self.tree.marker, 0o600)
                elif kind == "folder":
                    self.tree.folders(self.tree.marker)
                self.check(refuse("instance-root-refused"))
                if kind == "folder":
                    os.rmdir(self.tree.marker)
                elif kind != "missing":
                    os.remove(self.tree.marker)
                self.tree.write_marker(good.read_bytes())

    def test_siblings_of_the_root_are_never_read(self) -> None:
        staging = self.tree.component / f".{CANARY_ID}.{uuid.UUID(int=2)}.tmp"
        earlier = self.tree.component / str(uuid.UUID(int=3))
        for sibling in (staging, earlier):
            self.tree.folders(sibling)
            os.chmod(sibling, 0o000)
        self.tree.full()
        self.check(facts(False, "absent"))

    def test_the_marker_vector(self) -> None:
        # Shared with OutputRoot's Java test: the same bytes, from the same name and ID.
        vector = (b"carddemo-output-root 1\ndatabase=Y2FyZGRlbW9fw6k\n"
                  b"instance=0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0\n")
        self.assertEqual(H["marker"]("carddemo_\u00e9".encode("utf-8"), "0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0"), vector)


class InputTest(Case):
    """Everything but the exact object refuses work-folder-input."""

    def refused(self, value) -> None:
        self.assertEqual(answer(value), refuse("work-folder-input"))

    def test_the_raw_bytes(self) -> None:
        good = json.dumps(self.tree.inputs()).encode("utf-8")
        self.assertEqual(answer(good + b" " * (8192 - len(good))), facts(False, "absent"))
        self.refused(good + b" " * (8193 - len(good)))
        self.refused(b"")
        self.refused(b"\xff" + good)
        self.refused(b"\xef\xbb\xbf" + good)
        self.refused(b"[" + good + b"]")
        self.refused(good[:-1] + b', "cycle_id": 7}')

    def test_constants_and_integers(self) -> None:
        for raw in ("NaN", "Infinity", "-Infinity"):
            text = json.dumps(self.tree.inputs()).replace(f'"cycle_id": {CYCLE}', f'"cycle_id": {raw}')
            with self.subTest(raw=raw):
                self.refused(text.encode("ascii"))
        for key in ("cycle_id", "attempt", "job_execution_id"):
            for bad in (True, False, 0, -1, 2**63, 1.0, "1", None):
                with self.subTest(key=key, bad=bad):
                    self.refused(self.tree.inputs(**{key: bad}))
        self.assertEqual(answer(self.tree.inputs(job_execution_id=2**63 - 1)), facts(False, "absent"))

    def test_the_key_set(self) -> None:
        missing = self.tree.inputs()
        del missing["attempt"]
        self.refused(missing)
        self.refused(self.tree.inputs(extra=1))

    def test_the_texts(self) -> None:
        self.refused(self.tree.inputs(var_dir="relative/var"))
        self.refused(self.tree.inputs(var_dir=str(self.tree.var) + "\0"))
        self.refused(self.tree.inputs(var_dir=""))
        self.refused(self.tree.inputs(database=""))
        self.refused(self.tree.inputs(database="db\0"))
        self.refused(self.tree.inputs(database="\ud800"))
        self.refused(self.tree.inputs(database=1))

    def test_the_instance_id_is_canonical(self) -> None:
        for bad in (CANARY_ID.upper(), CANARY_ID[:8] + CANARY_ID[9] + "-" + CANARY_ID[10:], CANARY_ID[:-1],
                    CANARY_ID + "0", CANARY_ID[:-1] + "g", "{" + CANARY_ID[1:-1] + "}"):
            with self.subTest(bad=bad):
                self.refused(self.tree.inputs(instance_id=bad))


class StartTest(Case):
    """The capability check runs on every start and has no fallback."""

    def flags(self, **changes) -> SimpleNamespace:
        value = {"isolated": 1, "no_site": 1, "dont_write_bytecode": 1}
        value.update(changes)
        return SimpleNamespace(**value)

    def test_a_missing_capability_is_unsupported(self) -> None:
        supported = H["supported"]
        self.assertTrue(supported((3, 11, 0), self.flags(), os))
        self.assertFalse(supported((3, 10, 9), self.flags(), os))
        for name in ("isolated", "no_site", "dont_write_bytecode"):
            with self.subTest(flag=name):
                self.assertFalse(supported((3, 12, 0), self.flags(**{name: 0}), os))
        for name in ("O_NOFOLLOW", "O_DIRECTORY", "O_NONBLOCK", "O_CLOEXEC"):
            stub = SimpleNamespace(**{n: getattr(os, n) for n in dir(os) if not n.startswith("__") and n != name})
            with self.subTest(missing=name):
                self.assertFalse(supported((3, 12, 0), self.flags(), stub))
        for field, value in (("supports_dir_fd", {os.stat}), ("supports_follow_symlinks", set()),
                             ("supports_fd", set())):
            stub = SimpleNamespace(**{n: getattr(os, n) for n in dir(os) if not n.startswith("__")})
            setattr(stub, field, value)
            with self.subTest(field=field):
                self.assertFalse(supported((3, 12, 0), self.flags(), stub))

    def test_an_unsupported_start_reads_nothing(self) -> None:
        stream = mock.Mock()
        self.assertEqual(H["answer"](stream, lambda: False), refuse("work-folder-unsupported"))
        stream.read.assert_not_called()

    def test_the_test_interpreter_itself_is_not_isolated(self) -> None:
        if sys.flags.isolated != 1:
            self.assertEqual(H["answer"](stdin(self.tree.inputs())), refuse("work-folder-unsupported"))

    def test_any_exception_is_a_fixed_refusal_without_traceback(self) -> None:
        def broken():
            raise RuntimeError(CANARY_DB)
        self.assertEqual(H["answer"](stdin(self.tree.inputs()), broken), refuse("work-folder-refused"))

        def interrupted():
            raise KeyboardInterrupt
        self.assertEqual(H["answer"](stdin(self.tree.inputs()), interrupted), refuse("work-folder-refused"))

    def test_the_codes(self) -> None:
        self.assertEqual(H["HELPER_CODES"], ("work-folder-unsupported", "work-folder-input",
                                             "instance-root-refused", "work-folder-refused"))


class ProcessTest(Case):
    """The helper as the exporter starts it: the test interpreter, isolated, with no environment."""

    def start(self, data: bytes) -> subprocess.CompletedProcess:
        return subprocess.run([sys.executable, "-I", "-B", "-S", "-c", HELPER.read_text("utf-8")], input=data,
                              stdout=subprocess.PIPE, stderr=subprocess.PIPE, cwd="/", env={}, timeout=60,
                              check=False)

    def test_facts_and_refusals_with_their_exit_status(self) -> None:
        self.tree.full()
        done = self.start(json.dumps(self.tree.inputs()).encode("utf-8"))
        self.assertEqual((done.returncode, done.stdout, done.stderr), (0, b"facts final=false work=absent\n", b""))
        done = self.start(b"{}")
        self.assertEqual((done.returncode, done.stdout, done.stderr), (3, b"refuse work-folder-input\n", b""))
        self.assertFalse((HELPER.parent / "__pycache__").exists())


if __name__ == "__main__":
    unittest.main()
