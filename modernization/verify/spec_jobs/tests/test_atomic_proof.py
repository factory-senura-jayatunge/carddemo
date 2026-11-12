"""G2 refuses pre-launch/stale observations; these are offline contract models."""
from contextlib import contextmanager
from dataclasses import replace
from pathlib import Path
import time
import unittest
from unittest.mock import patch

from modernization.verify.spec_jobs import runner, tranbkp
from modernization.verify.spec_jobs.common import InvalidFixture
from modernization.verify.spec_jobs.session import ArchiveBlockProof, Execution, FixtureLock
from modernization.verify.spec_jobs.tests.fake import FakeSession


def evidence():
    proof = ArchiveBlockProof(True, 7, 102, 101, "core.transaction", "KEY", "LCK_M_U", 110)
    return tranbkp.AtomicEvidence(FixtureLock(101), proof, 7, False, 100, 120, 130, 140, 200)


class AtomicSession(FakeSession):
    """Deliberate pending launch, not FakeSession's eager/default launch."""
    def __init__(self):
        super().__init__(Path("/unused"), tables={
            "core.transaction": [dict(tran_pk=1, in_master=True)]})
        self.holder = FixtureLock(101)
        self.held = None
        self.events = []
        self.started_on_launch = True
        self.started = False
        self.proof_returned = False
        self.done_result = False
        self.proof_transform = lambda proof: proof
        self.on_observe = lambda: None
        self.count = 0
        self.result_id = 7
        self.queries["SELECT tran_pk,in_master"] = lambda s: s.tables["core.transaction"]
        self.queries["SELECT archived_count"] = lambda s: [{"value": s.count}]
        self.queries["FROM sys.indexes"] = lambda s: [
            dict(column_name="tran_id", is_unique=True, filter_definition="([in_master]=(1))")]

    @contextmanager
    def fixture_lock(self, sql):
        self.events.append("lock")
        self.held = self.holder
        try:
            yield self.holder
        finally:
            self.events.append("release")
            self.held = None

    def launch(self, job, parameters):
        self.events.append("launch")
        self.started = self.started_on_launch
        self.handle = Pending(self)
        return self.handle

    def wait_for_archive_blocked(self, running, holder, *, timeout=30.0):
        assert running is self.handle and holder is self.held
        assert timeout == runner.ATOMIC_PROBE_TIMEOUT
        self.events.append("proof")
        proof = ArchiveBlockProof(self.started, 7, 102, holder.session_id,
                                  "core.transaction", "KEY", "LCK_M_U", time.monotonic_ns())
        self.proof_returned = True
        return self.proof_transform(proof)

    def snapshot_digest(self, table, *args, **kwargs):
        if table == "core.transaction" and self.held is not None:
            assert self.proof_returned
            self.events.append("observe")
            self.on_observe()
        return super().snapshot_digest(table, *args, **kwargs)


class Pending:
    execution_id = 7

    def __init__(self, session):
        self.session = session

    def done(self):
        assert self.session.held is self.session.holder
        self.session.events.append("done")
        return self.session.done_result

    def wait(self, timeout=120):
        session = self.session
        assert session.held is None
        session.events.append("wait")
        if session.started:
            session.count = 1
            session.tables["core.transaction"][0]["in_master"] = False
        return Execution("COMPLETED" if session.started else "FAILED", execution_id=session.result_id,
                         step_counts={"archive": {"WRITE_COUNT": session.count}})


class AtomicEvidenceTest(unittest.TestCase):
    def test_valid_key_x_and_u_proofs(self):
        for wait_type in ("LCK_M_X", "LCK_M_U"):
            valid = evidence()
            valid = replace(valid, proof=replace(valid.proof, wait_type=wait_type))
            self.assertFalse(tranbkp.atomic_evidence_findings("atomic", valid))

    def test_matching_before_state_without_proof_cannot_pass(self):
        before = {1: dict(in_master=True, digest="opaque")}
        after = {1: dict(in_master=False, digest="opaque")}
        params = dict(status="COMPLETED", recorded_count=1, index_valid=True,
                      daily_before={}, daily_after={}, observations=(before, after))
        self.assertFalse(tranbkp.validate("atomic", before, after, atomic_evidence=evidence(), **params))
        problems = tranbkp.validate("atomic", before, after, **params)
        self.assertEqual([p.check for p in problems], ["BLOCK_PROOF_REQUIRED"])
        self.assertEqual([p.check for p in tranbkp.atomic_evidence_findings(
            "atomic", replace(evidence(), proof=None))], ["BLOCK_PROOF_REQUIRED"])

    def test_exact_holder_execution_resource_and_readiness(self):
        valid = evidence()
        mutants = (
            (replace(valid, proof=replace(valid.proof, ready=False)), "BLOCK_NOT_READY"),
            (replace(valid, proof=replace(valid.proof, execution_id=8)), "BLOCK_EXECUTION"),
            (replace(valid, proof=replace(valid.proof, blocking_session_id=103)), "BLOCK_HOLDER"),
            (replace(valid, proof=replace(valid.proof, waiting_session_id=101)), "BLOCK_HOLDER"),
            (replace(valid, proof=replace(valid.proof, waiting_session_id=0)), "BLOCK_HOLDER"),
            (replace(valid, proof=replace(valid.proof, object_name="core.daily_tran")), "BLOCK_RESOURCE"),
            (replace(valid, proof=replace(valid.proof, resource_type="PAGE")), "BLOCK_RESOURCE"),
            (replace(valid, proof=replace(valid.proof, wait_type="LCK_M_S")), "BLOCK_WAIT_TYPE"),
            (replace(valid, handle_done=True), "BLOCK_RUNNING_REQUIRED"),
            (replace(valid, handle_done=0), "BLOCK_RUNNING_REQUIRED"),
        )
        for mutant, check in mutants:
            with self.subTest(check=check):
                self.assertEqual([p.check for p in tranbkp.atomic_evidence_findings("atomic", mutant)], [check])

    def test_stale_future_and_misordered_observation_timestamps(self):
        valid = evidence()
        mutants = (
            replace(valid, wait_started_ns=0),
            replace(valid, proof=replace(valid.proof, observed_at_ns=99)),
            replace(valid, proof=replace(valid.proof, observed_at_ns=121)),
            replace(valid, observation_started_ns=119),
            replace(valid, observation_received_ns=129),
            replace(valid, deadline_ns=139),
        )
        for mutant in mutants:
            self.assertEqual([p.check for p in tranbkp.atomic_evidence_findings("atomic", mutant)],
                             ["BLOCK_OBSERVATION_ORDER"])
        # Worst-case endpoint: the window is inclusive, but one ns later fails.
        endpoint = replace(valid, observation_received_ns=valid.deadline_ns)
        self.assertFalse(tranbkp.atomic_evidence_findings("atomic", endpoint))
        self.assertTrue(tranbkp.atomic_evidence_findings(
            "atomic", replace(endpoint, observation_received_ns=valid.deadline_ns + 1)))

    def test_protocol_exact_type_guards(self):
        for value in (True, 101.0, "101", None, 0, -1):
            with self.assertRaises(ValueError):
                FixtureLock(value)
        for field in ("execution_id", "waiting_session_id", "blocking_session_id", "observed_at_ns"):
            for value in (True, 1.0, "1", None):
                with self.subTest(field=field), self.assertRaises(ValueError):
                    replace(evidence().proof, **{field: value})
        for field in ("ready", "object_name", "resource_type", "wait_type"):
            with self.subTest(field=field), self.assertRaises(ValueError):
                replace(evidence().proof, **{field: 1})
        for field in ("execution_id", "wait_started_ns", "proof_received_ns", "observation_started_ns",
                      "observation_received_ns", "deadline_ns"):
            for value in (True, 1.0, "1", None):
                problems = tranbkp.atomic_evidence_findings("atomic", replace(evidence(), **{field: value}))
                self.assertEqual([p.check for p in problems], ["BLOCK_PROOF_SHAPE"])


class AtomicRunnerTest(unittest.TestCase):
    def test_valid_path_proves_then_observes_then_releases_exact_holder(self):
        for mode in ("legacy", "fixed"):
            for wait_type in ("LCK_M_X", "LCK_M_U"):
                with self.subTest(mode=mode, wait_type=wait_type):
                    session = AtomicSession()
                    session.proof_transform = lambda proof: replace(proof, wait_type=wait_type)
                    with patch.object(tranbkp, "validate", wraps=tranbkp.validate) as validate:
                        self.assertFalse(runner.archive(session, "atomic", mode)[0])
                    actual = validate.call_args.kwargs["atomic_evidence"]
                    self.assertIs(actual.holder, session.holder)
                    self.assertFalse(tranbkp.atomic_evidence_findings("atomic", actual))
                    self.assertEqual(actual.deadline_ns - actual.wait_started_ns, 30_000_000_000)
                    self.assertEqual(session.events, ["lock", "launch", "proof", "done", "observe", "release", "wait"])
                    self.assertFalse(session.windows)

    def test_invalid_proofs_fail_before_observation_and_drain_after_release(self):
        mutations = {
            "not_ready": lambda p: replace(p, ready=False),
            "wrong_execution": lambda p: replace(p, execution_id=8),
            "wrong_blocker": lambda p: replace(p, blocking_session_id=103),
            "wrong_waiter": lambda p: replace(p, waiting_session_id=p.blocking_session_id),
            "wrong_object": lambda p: replace(p, object_name="core.daily_tran"),
            "wrong_resource": lambda p: replace(p, resource_type="PAGE"),
            "wrong_wait": lambda p: replace(p, wait_type="LCK_M_S"),
            "stale_timestamp": lambda p: replace(p, observed_at_ns=1),
            "future_timestamp": lambda p: replace(p, observed_at_ns=p.observed_at_ns + 31_000_000_000),
            "missing_proof": lambda p: None,
        }
        for mode in ("legacy", "fixed"):
            for name, mutation in mutations.items():
                with self.subTest(mode=mode, mutation=name):
                    session = AtomicSession()
                    session.proof_transform = mutation
                    with self.assertRaises(InvalidFixture):
                        runner.archive(session, "atomic", mode)
                    self.assertNotIn("observe", session.events)
                    self.assertEqual(session.events[-2:], ["release", "wait"])
                    self.assertFalse(session.windows)

    def test_not_started_and_completed_handle_fail(self):
        for mode in ("legacy", "fixed"):
            for started, done in ((False, False), (True, True), (True, 0)):
                session = AtomicSession()
                session.started_on_launch = started
                session.done_result = done
                with self.assertRaises(InvalidFixture):
                    runner.archive(session, "atomic", mode)
                self.assertNotIn("observe", session.events)
                self.assertEqual(session.events[-2:], ["release", "wait"])

    def test_missing_method_and_unconfigured_fake_never_skip(self):
        class MissingMethod(AtomicSession):
            @property
            def wait_for_archive_blocked(self):
                raise AttributeError("ARCHIVE_BLOCK_PROOF_REQUIRED")
        for cls in (AtomicSession, MissingMethod):
            session = cls()
            if cls is AtomicSession:
                session.wait_for_archive_blocked = lambda *a, **k: FakeSession.wait_for_archive_blocked(
                    session, *a, **k)
            with patch.dict(runner.CASES, {"TRANBKP": ("atomic",)}):
                output = []
                self.assertEqual(runner.run(session, "TRANBKP", output.append), 1)
            self.assertNotIn("observe", session.events)
            self.assertEqual(session.events.count("wait"), 2)
            self.assertEqual(sum(line.startswith("FAIL TRANBKP") for line in output), 2)
            self.assertNotIn("PARTIAL", "\n".join(output))

    def test_missing_holder_refuses_before_launch(self):
        session = AtomicSession()
        session.holder = None
        with self.assertRaises(InvalidFixture):
            runner.archive(session, "atomic", "legacy")
        self.assertEqual(session.events, ["lock", "release"])

    def test_final_execution_must_match_blocked_handle(self):
        session = AtomicSession()
        session.result_id = 8
        with self.assertRaises(InvalidFixture):
            runner.archive(session, "atomic", "legacy")
        self.assertEqual(session.events[-2:], ["release", "wait"])

    def test_proof_and_observation_share_bounded_call_window(self):
        for phase in ("proof", "observe"):
            for overrun in (0, 1):
                session = AtomicSession()
                now = [1_000_000_000]
                def advance():
                    now[0] += 30_000_000_000 + overrun
                if phase == "proof":
                    def transform(proof):
                        advance()
                        return proof
                    session.proof_transform = transform
                else:
                    session.on_observe = advance
                with patch.object(runner.time, "monotonic_ns", side_effect=lambda: now[0]):
                    if overrun:
                        with self.assertRaises(InvalidFixture):
                            runner.archive(session, "atomic", "legacy")
                    else:
                        self.assertFalse(runner.archive(session, "atomic", "legacy")[0])
                if phase == "proof" and overrun:
                    self.assertNotIn("observe", session.events)
                self.assertEqual(session.events[-2:], ["release", "wait"])

    def test_proof_exception_remains_value_free_and_fails_both_modes(self):
        session = AtomicSession()
        sentinel = runner.fixtures.SENTINELS[1]
        def broken(proof):
            raise ValueError(sentinel)
        session.proof_transform = broken
        with patch.dict(runner.CASES, {"TRANBKP": ("atomic",)}):
            output = []
            self.assertEqual(runner.run(session, "TRANBKP", output.append), 1)
        report = "\n".join(output)
        self.assertNotIn(sentinel, report)
        self.assertEqual(report.count("CASE_EXECUTION_FAILED"), 2)
        self.assertEqual(session.events.count("wait"), 2)
        self.assertNotIn("observe", session.events)


if __name__ == "__main__":
    unittest.main()
