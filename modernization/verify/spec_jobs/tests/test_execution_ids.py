"""Archive tag observer IDs: exact nonnegative signed-BIGINT domain."""
from dataclasses import replace
import unittest
from unittest.mock import patch

from modernization.verify.spec_jobs import common, runner, tranbkp
from modernization.verify.spec_jobs.session import Execution, FixtureLock
from modernization.verify.spec_jobs.tests.test_atomic_proof import AtomicSession, evidence


class IntLike(int):
    pass


INVALID_IDS = (-1, -(2**63), 2**63, 2**64, False, True, 0.0, "0", None, IntLike(0))


class ExecutionIdSession(AtomicSession):
    def __init__(self, execution_id):
        super().__init__()
        self.requested_id = execution_id
        self.result_id = execution_id
        self.proof_transform = lambda proof: replace(proof, execution_id=execution_id)

    def launch(self, job, parameters):
        running = super().launch(job, parameters)
        running.execution_id = self.requested_id
        return running


class ExecutionIdTest(unittest.TestCase):
    def test_exact_domain_endpoints_are_accepted(self):
        for execution_id in (0, 1, 2**63 - 2, 2**63 - 1):
            with self.subTest(execution_id=execution_id):
                self.assertEqual(common.require_execution_id(execution_id), execution_id)
                self.assertEqual(Execution("COMPLETED", execution_id=execution_id).execution_id, execution_id)
                valid = evidence()
                valid = replace(valid, execution_id=execution_id,
                                proof=replace(valid.proof, execution_id=execution_id))
                self.assertFalse(tranbkp.atomic_evidence_findings("atomic", valid))

    def test_guard_and_proof_refuse_invalid_ids_without_coercion(self):
        for execution_id in INVALID_IDS:
            with self.subTest(kind=type(execution_id).__name__):
                with self.assertRaises(common.InvalidFixture):
                    common.require_execution_id(execution_id)
                with self.assertRaises(common.InvalidFixture):
                    replace(evidence().proof, execution_id=execution_id)

    def test_execution_metadata_keeps_missing_id_but_refuses_invalid_present_id(self):
        self.assertIsNone(Execution("NOT_RUN").execution_id)
        for execution_id in INVALID_IDS:
            if execution_id is not None:
                with self.assertRaises(common.InvalidFixture):
                    Execution("COMPLETED", execution_id=execution_id)

    def test_pure_evidence_refuses_invalid_running_id(self):
        for execution_id in INVALID_IDS:
            problems = tranbkp.atomic_evidence_findings("atomic", replace(evidence(), execution_id=execution_id))
            self.assertEqual([p.check for p in problems], ["BLOCK_PROOF_SHAPE"])

    def test_zero_and_max_running_id_complete_both_mode_paths(self):
        for mode in ("legacy", "fixed"):
            for execution_id in (0, 2**63 - 1):
                with self.subTest(mode=mode, execution_id=execution_id):
                    session = ExecutionIdSession(execution_id)
                    with patch.object(tranbkp, "validate", wraps=tranbkp.validate) as validate:
                        findings, execution = runner.archive(session, "atomic", mode)
                    self.assertFalse(findings)
                    self.assertEqual(execution.execution_id, execution_id)
                    actual = validate.call_args.kwargs["atomic_evidence"]
                    self.assertEqual(actual.execution_id, execution_id)
                    self.assertEqual(actual.proof.execution_id, execution_id)
                    self.assertEqual(session.events, ["lock", "launch", "proof", "done", "observe", "release", "wait"])
                    self.assertFalse(session.windows)

    def test_invalid_running_id_is_usage_error_before_any_observer_callback(self):
        for mode in ("legacy", "fixed"):
            for execution_id in INVALID_IDS:
                with self.subTest(mode=mode, kind=type(execution_id).__name__):
                    session = ExecutionIdSession(execution_id)
                    with patch.object(tranbkp, "validate", side_effect=AssertionError("PRODUCT_RESULT_NOT_EXPECTED")):
                        with self.assertRaises(common.InvalidFixture):
                            runner.archive(session, "atomic", mode)
                    # No proof SQL, status/done lookup, cleanup wait or state
                    # observation can be requested with an invalid handle ID.
                    self.assertEqual(session.events, ["lock", "launch", "release"])
                    self.assertFalse(session.windows)

    def test_execution_zero_does_not_weaken_holder_session_domain(self):
        with self.assertRaises(ValueError):
            FixtureLock(0)
        valid = evidence()
        valid = replace(valid, execution_id=0, proof=replace(valid.proof, execution_id=0, waiting_session_id=0))
        self.assertEqual([p.check for p in tranbkp.atomic_evidence_findings("atomic", valid)], ["BLOCK_HOLDER"])


if __name__ == "__main__":
    unittest.main()
