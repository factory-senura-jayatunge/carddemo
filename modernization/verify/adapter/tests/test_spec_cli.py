"""verify-batch-spec's command line without a database: the locked session, the
transport and the runner are stand-ins."""
from __future__ import annotations

import os
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from modernization.verify.adapter import spec_cli, transport
from modernization.verify.differential import live

SENTINEL = "4111222233334444"


class FakeLocked:
    def __init__(self, root, name, jars_error=None):
        self.root, self.name, self.jars_error = root, name, jars_error
        self.entered = self.exited = False

    def __enter__(self):
        self.entered = True
        return self

    def __exit__(self, *exc):
        self.exited = True

    def jars(self):
        if self.jars_error:
            raise self.jars_error
        return "batch.jar", "exporter.jar"


class FakeSpec:
    made = []

    def __init__(self, locked, close_error=None):
        self.locked, self.closed, self.close_error = locked, False, close_error
        FakeSpec.made.append(self)

    def close(self):
        self.closed = True
        if self.close_error:
            raise self.close_error


class CliTest(unittest.TestCase):
    def setUp(self):
        FakeSpec.made = []
        self.lines = []
        self.locked = []
        for target, name, value in ((live.d, "LOAD_REPORT_GAP", ""),
                                    (live.d, "batch_switches", lambda: ["DEF-ACUP-002"]),
                                    (live, "Session", self.make_locked),
                                    (transport, "SpecSession", FakeSpec)):
            patcher = mock.patch.object(target, name, value)
            patcher.start()
            self.addCleanup(patcher.stop)
        env = mock.patch.dict(os.environ, {"ONLY": ""})
        env.start()
        self.addCleanup(env.stop)
        self.jars_error = None

    def make_locked(self, root, name):
        locked = FakeLocked(root, name, self.jars_error)
        self.locked.append(locked)
        return locked

    def run_cli(self, result=0, *, raises=None, argv=()):
        def fake_run(session, only, out):
            self.only = only
            out("ok PRTCATBL legacy sample")
            if raises:
                raise raises
            out(f"verify-batch-spec: {({0: 'OK', 1: 'FAILED (1 checks)', 3: 'PARTIAL'}).get(result, 'X')}")
            return result
        with mock.patch.object(spec_cli.runner, "run", fake_run):
            return spec_cli.main(list(argv), out=self.lines.append)

    def test_a_full_run_ends_with_the_runners_summary_after_clean_up(self):
        self.assertEqual(self.run_cli(0), 0)
        self.assertEqual(self.lines, ["ok PRTCATBL legacy sample", "verify-batch-spec: OK"])
        self.assertIsNone(self.only)
        self.assertTrue(FakeSpec.made[0].closed)
        self.assertTrue(self.locked[0].exited)
        self.assertEqual(self.locked[0].name, "verify-batch-spec")

    def test_only_is_partial_and_passed_on(self):
        with mock.patch.dict(os.environ, {"ONLY": "purge,DISCGRP"}):
            self.assertEqual(self.run_cli(3), 3)
        self.assertEqual(self.only, "purge,DISCGRP")
        self.assertEqual(self.lines[-1], "verify-batch-spec: PARTIAL")

    def test_failures_exit_1(self):
        self.assertEqual(self.run_cli(1), 1)

    def test_an_unknown_job_is_a_usage_error_before_anything_runs(self):
        with mock.patch.dict(os.environ, {"ONLY": "POSTTRAN"}):
            self.assertEqual(self.run_cli(0), 2)
        self.assertEqual(self.locked, [])
        self.assertIn("choose from PRTCATBL,TRANBKP,PURGE,DISCGRP,DATALOAD", self.lines[0])

    def test_the_load_report_gap_fails_before_the_lock(self):
        with mock.patch.object(live.d, "LOAD_REPORT_GAP", "the gap"):
            self.assertEqual(self.run_cli(0), 1)
        self.assertEqual(self.lines, ["verify-batch-spec: FAILED (not run: the gap)"])
        self.assertEqual(self.locked, [])

    def test_missing_parts_fail_closed(self):
        self.jars_error = live.d.Unsupported("no exec jar in batch/batch-service/target; run make build")
        self.assertEqual(self.run_cli(0), 1)
        self.assertEqual(self.lines[-1], "verify-batch-spec: FAILED (no exec jar in batch/batch-service/target; "
                                         "run make build)")
        self.assertEqual(FakeSpec.made, [])
        self.lines.clear()
        with mock.patch.object(live.d, "batch_switches", mock.Mock(side_effect=live.d.Unsupported("no switches"))):
            self.assertEqual(self.run_cli(0), 1)
        self.assertEqual(self.lines, ["verify-batch-spec: FAILED (no switches)"])

    def test_a_failed_clean_up_never_shows_ok(self):
        with mock.patch.object(transport, "SpecSession",
                               lambda locked: FakeSpec(locked, transport.TransportError("ADMIN_SQL_FAILED"))):
            self.assertEqual(self.run_cli(0), 1)
        self.assertNotIn("verify-batch-spec: OK", self.lines)
        self.assertEqual(self.lines[-1], "verify-batch-spec: FAILED (ADMIN_SQL_FAILED)")

    def test_a_login_left_behind_names_its_ownership_record(self):
        record = Path("/state/carddemo_verify_batch.fixture-login.json")

        def left(locked):
            spec = FakeSpec(locked, transport.TransportError("FIXTURE_DROP"))
            spec.fixture_record = record
            return spec
        with mock.patch.object(transport, "SpecSession", left):
            self.assertEqual(self.run_cli(0), 1)
        self.assertEqual(self.lines[-2:], [
            f"verify-batch-spec: the fixture login was not dropped; its ownership record {record} stays, "
            "and every run refuses at its first case's fixture login until the login is dropped by hand (README)",
            "verify-batch-spec: FAILED (FIXTURE_DROP)"])
        self.assertNotIn("verify-batch-spec: OK", self.lines)

    def test_a_foreign_login_is_reported_as_kept(self):
        record = Path("/state/carddemo_verify_batch.fixture-login.json")

        def foreign(locked):
            spec = FakeSpec(locked, transport.TransportError("FIXTURE_LOGIN_FOREIGN"))
            spec.fixture_record = record
            return spec
        with mock.patch.object(transport, "SpecSession", foreign):
            self.assertEqual(self.run_cli(0), 1)
        self.assertEqual(self.lines[-2:], [
            f"verify-batch-spec: kept: foreign login (its name, another SID); its ownership record {record} stays, "
            "and every run refuses at its first case's fixture login until the operator clears it (README)",
            "verify-batch-spec: FAILED (FIXTURE_LOGIN_FOREIGN)"])
        self.assertFalse(any("not dropped" in line for line in self.lines))

    def test_no_record_line_when_the_login_was_dropped(self):
        def dropped(locked):
            spec = FakeSpec(locked, transport.TransportError("ADMIN_SQL_FAILED"))
            spec.fixture_record = None
            return spec
        with mock.patch.object(transport, "SpecSession", dropped):
            self.assertEqual(self.run_cli(0), 1)
        self.assertFalse([line for line in self.lines if "ownership record" in line])

    def test_exception_text_is_never_printed(self):
        self.assertEqual(self.run_cli(raises=RuntimeError(SENTINEL)), 1)
        self.assertEqual(self.lines[-1], "verify-batch-spec: FAILED (RuntimeError)")
        self.assertNotIn(SENTINEL, "\n".join(self.lines))
        self.assertTrue(FakeSpec.made[0].closed)
        self.lines.clear()
        self.assertEqual(self.run_cli(raises=live.d.PipelineError(SENTINEL)), 1)
        self.assertNotIn(SENTINEL, "\n".join(self.lines))

    def test_an_unexpected_runner_code_fails(self):
        self.assertEqual(self.run_cli(5), 1)

    def test_an_unexpected_runner_code_never_shows_ok(self):
        def fake_run(session, only, out):
            out("verify-batch-spec: OK")
            return 7
        with mock.patch.object(spec_cli.runner, "run", fake_run):
            self.assertEqual(spec_cli.main([], out=self.lines.append), 1)
        self.assertEqual(self.lines, ["verify-batch-spec: FAILED (the runner ended 7)"])

    def test_keep_that_is_not_a_directory_is_a_usage_error(self):
        with tempfile.TemporaryDirectory() as tmp:
            target = Path(tmp) / "file"
            target.write_text("x")
            link = Path(tmp) / "dangling"
            link.symlink_to(Path(tmp) / "missing")
            for keep in (target, target / "below", link):
                self.lines.clear()
                self.assertEqual(self.run_cli(0, argv=["--keep", str(keep)]), 2)
                self.assertEqual(len(self.lines), 1)
                self.assertTrue(self.lines[0].startswith("verify-batch-spec: FAILED (--keep "), self.lines)
            self.assertEqual(target.read_text(), "x")
        self.assertEqual(self.locked, [])

    def test_keep_must_be_empty_and_private(self):
        with tempfile.TemporaryDirectory() as tmp:
            keep = Path(tmp) / "keep"
            self.assertEqual(self.run_cli(0, argv=["--keep", str(keep)]), 0)
            self.assertEqual(keep.stat().st_mode & 0o777, 0o700)
            self.assertEqual(self.locked[0].root, keep.resolve())
            (keep / "left").write_text("x")
            self.assertEqual(self.run_cli(0, argv=["--keep", str(keep)]), 2)


if __name__ == "__main__":
    unittest.main()
