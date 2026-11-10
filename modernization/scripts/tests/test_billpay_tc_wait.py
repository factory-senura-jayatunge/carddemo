"""Tests of test-billpay's Testcontainers wait: python3 -m unittest discover -s modernization/scripts/tests"""
from __future__ import annotations

import os
import subprocess
import sys
import unittest
import unittest.mock

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import test_billpay  # noqa: E402

SQL = "a" * 64
RYUK = "b" * 64
OLD = "c" * 64
NET = "d" * 64


class FakeDocker:
    """Answers docker ps and docker network ls from a timeline; records every command."""

    def __init__(self, timeline):
        # timeline: [(from_time, [(id, state, name)], [(id, name)])]
        self.timeline = timeline
        self.now = 0.0
        self.calls = []
        self.fail = None

    def clock(self):
        return self.now

    def sleep(self, seconds):
        self.now += seconds

    def state(self):
        current = self.timeline[0]
        for entry in self.timeline:
            if entry[0] <= self.now:
                current = entry
        return current

    def __call__(self, argv, **kwargs):
        self.calls.append(argv)
        if self.fail:
            return self.fail(argv)
        _, containers, networks = self.state()
        if argv[1] == "ps":
            out = "".join(f"{i}\t{s}\t{n}\n" for i, s, n in containers)
        else:
            out = "".join(f"{i}\t{n}\n" for i, n in networks)
        return subprocess.CompletedProcess(argv, 0, out, "")


def barrier(docker):
    return test_billpay.TestcontainersBarrier({}, runner=docker, clock=docker.clock, sleep=docker.sleep)


class TestcontainersBarrierTest(unittest.TestCase):
    def assert_only_lists(self, docker):
        for argv in docker.calls:
            self.assertIn(argv[1:3], (["ps", "-a"], ["network", "ls"]), argv)
            self.assertIn("label=org.testcontainers=true", argv)

    def test_nothing_from_testcontainers_passes_at_once(self):
        docker = FakeDocker([(0, [], [])])
        b = barrier(docker)
        b.before()
        self.assertIn("0 Testcontainers", b.wait())
        self.assertEqual(docker.now, 0)
        self.assert_only_lists(docker)

    def test_waits_for_ryuks_cleanup_about_10_s_after_maven(self):
        docker = FakeDocker([(0, [], [])])
        b = barrier(docker)
        b.before()
        # After Maven: SQL Server and Ryuk still run; Ryuk removes SQL Server at 10 s and itself at 10.3 s.
        docker.timeline = [(0, [(SQL, "running", "pedantic_sql"), (RYUK, "running", "testcontainers-ryuk-x")], []),
                           (10.0, [(RYUK, "running", "testcontainers-ryuk-x")], []),
                           (10.3, [], [])]
        message = b.wait()
        self.assertEqual(docker.now, 10.5)
        self.assertIn("2 Testcontainers", message)
        self.assertIn("after 10.5 s", message)
        self.assert_only_lists(docker)

    def test_containers_from_before_maven_are_not_waited_for(self):
        docker = FakeDocker([(0, [(OLD, "running", "other_sql")], [(NET, "tc-net")])])
        b = barrier(docker)
        b.before()
        self.assertIn("0 Testcontainers", b.wait())
        self.assertEqual(docker.now, 0)

    def test_a_new_id_counts_even_under_an_old_name(self):
        docker = FakeDocker([(0, [(OLD, "running", "testcontainers-ryuk-x")], [])])
        b = barrier(docker)
        b.before()
        docker.timeline = [(0, [(RYUK, "running", "testcontainers-ryuk-x")], []), (3.0, [], [])]
        b.wait()
        self.assertEqual(docker.now, 3.0)

    def test_exited_dead_or_created_containers_count_as_down(self):
        docker = FakeDocker([(0, [], [])])
        b = barrier(docker)
        b.before()
        docker.timeline = [(0, [(SQL, "exited", "sql"), (RYUK, "created", "r"), (OLD, "dead", "d")], [])]
        self.assertIn("0 Testcontainers", b.wait())
        self.assertEqual(docker.now, 0)

    def test_waits_for_a_new_network_to_go(self):
        docker = FakeDocker([(0, [], [])])
        b = barrier(docker)
        b.before()
        docker.timeline = [(0, [], [(NET, "tc-net")]), (2.0, [], [])]
        self.assertIn("1 Testcontainers", b.wait())
        self.assertEqual(docker.now, 2.0)

    def test_still_up_at_the_limit_fails_and_stops_nothing(self):
        docker = FakeDocker([(0, [(OLD, "running", "theirs")], [])])
        b = barrier(docker)
        b.before()
        docker.timeline = [(0, [(SQL, "running", "pedantic_sql"), (OLD, "running", "theirs")], [(NET, "tc-net")])]
        with self.assertRaises(test_billpay.StepFailed) as caught:
            b.wait()
        message = str(caught.exception)
        self.assertIn("still up after 60 s", message)
        self.assertIn(f"container {SQL[:12]} pedantic_sql (running)", message)
        self.assertIn(f"network {NET[:12]} tc-net", message)
        self.assertNotIn("theirs", message)
        self.assertEqual(docker.now, test_billpay.TC_GONE_LIMIT)
        self.assert_only_lists(docker)

    def test_limit_covers_ryuks_worst_case(self):
        # 10 s reconnection timeout + 10 removal retries 1 s apart + one 10 s Docker request.
        self.assertGreaterEqual(test_billpay.TC_GONE_LIMIT, 10 + 10 * 1 + 10)

    def test_cli_error_after_maven_fails_closed(self):
        docker = FakeDocker([(0, [], [])])
        b = barrier(docker)
        b.before()
        docker.fail = lambda argv: subprocess.CompletedProcess(
            argv, 1, "", "Cannot connect to the Docker daemon\nsecond line SECRET\n")
        with self.assertRaises(test_billpay.StepFailed) as caught:
            b.wait()
        message = str(caught.exception)
        self.assertIn("cannot list", message)
        self.assertIn("Cannot connect to the Docker daemon", message)
        self.assertNotIn("SECRET", message)
        self.assertEqual(docker.now, 0)

    def test_missing_or_hanging_cli_after_maven_fails_closed(self):
        for error in (FileNotFoundError(2, "docker"), subprocess.TimeoutExpired(["docker"], 20)):
            docker = FakeDocker([(0, [], [])])
            b = barrier(docker)
            b.before()

            def fail(argv, error=error):
                raise error

            docker.fail = fail
            with self.assertRaises(test_billpay.StepFailed):
                b.wait()

    def test_no_docker_before_or_after_passes(self):
        docker = FakeDocker([(0, [], [])])

        def fail(argv):
            raise FileNotFoundError(2, "docker")

        docker.fail = fail
        b = barrier(docker)
        b.before()
        self.assertIn("not reachable before or after maven", b.wait())

    def test_no_docker_before_waits_for_everything_after(self):
        docker = FakeDocker([(0, [], [])])
        docker.fail = lambda argv: subprocess.CompletedProcess(argv, 1, "", "down")
        b = barrier(docker)
        b.before()
        docker.fail = None
        docker.timeline = [(0, [(SQL, "running", "sql")], []), (4.0, [], [])]
        self.assertIn("on the engine", b.wait())
        self.assertEqual(docker.now, 4.0)


class MainOrderTest(unittest.TestCase):
    def test_snapshot_before_maven_and_wait_before_the_first_browser(self):
        order = []

        class Barrier:
            def __init__(self, env):
                pass

            def before(self):
                order.append("before")

            def wait(self):
                order.append("wait")
                return "ok"

        class Session:
            db = "carddemo_e2e"

            def __init__(self, name):
                pass

            def __enter__(self):
                return self

            def __exit__(self, *exc):
                return False

            def fresh(self):
                order.append("stack")

            def env(self, **extra):
                return {}

        with unittest.mock.patch.multiple(
            test_billpay,
            TestcontainersBarrier=Barrier,
            run=lambda step, argv, **kw: order.append(step),
            playwright=lambda step, target, env: order.append(step),
            trace_bill=lambda: order.append("trace"),
        ), unittest.mock.patch.object(test_billpay.manifest, "begin", lambda *a: order.append("manifest")), \
                unittest.mock.patch.object(test_billpay.e2e_stack, "Session", Session), \
                unittest.mock.patch.object(test_billpay.e2e_stack, "install_signal_exit", lambda: None), \
                unittest.mock.patch("builtins.print"):
            self.assertEqual(test_billpay.main(), 0)
        self.assertEqual(order, ["manifest", "before", "maven", "vitest", "vitest", "wait", "e2e-mock", "stack",
                                 "e2e-stack", "trace"])


if __name__ == "__main__":
    unittest.main()
