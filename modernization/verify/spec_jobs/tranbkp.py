"""SE-JTBKP-001 and D-57: archive flags only; failed archive is atomic."""
from __future__ import annotations

from dataclasses import dataclass

from .common import Finding, unchanged, equal_scalar, require_integer, require_execution_id
from .session import ArchiveBlockProof, FixtureLock


@dataclass(frozen=True)
class AtomicEvidence:
    holder: FixtureLock
    proof: ArchiveBlockProof
    execution_id: int
    handle_done: bool
    wait_started_ns: int
    proof_received_ns: int
    observation_started_ns: int
    observation_received_ns: int
    deadline_ns: int


def atomic_evidence_findings(case: str, evidence: AtomicEvidence | None) -> list[Finding]:
    if not isinstance(evidence, AtomicEvidence) or not isinstance(evidence.holder, FixtureLock) or not isinstance(
            evidence.proof, ArchiveBlockProof):
        return [Finding(case, "BLOCK_PROOF_REQUIRED", "execution")]
    proof = evidence.proof
    try:
        require_execution_id(evidence.execution_id)
        require_execution_id(proof.execution_id)
        for value in (evidence.holder.session_id,
                      proof.waiting_session_id, proof.blocking_session_id, proof.observed_at_ns,
                      evidence.wait_started_ns, evidence.proof_received_ns, evidence.observation_started_ns,
                      evidence.observation_received_ns, evidence.deadline_ns):
            require_integer(value)
    except ValueError:
        return [Finding(case, "BLOCK_PROOF_SHAPE", "execution")]
    findings = []
    if proof.ready is not True:
        findings.append(Finding(case, "BLOCK_NOT_READY", "execution"))
    if evidence.handle_done is not False:
        findings.append(Finding(case, "BLOCK_RUNNING_REQUIRED", "execution"))
    if proof.execution_id != evidence.execution_id:
        findings.append(Finding(case, "BLOCK_EXECUTION", "execution"))
    if (proof.blocking_session_id != evidence.holder.session_id or proof.waiting_session_id <= 0 or
            proof.blocking_session_id <= 0 or proof.waiting_session_id == proof.blocking_session_id):
        findings.append(Finding(case, "BLOCK_HOLDER", "execution"))
    if proof.object_name != "core.transaction" or proof.resource_type != "KEY":
        findings.append(Finding(case, "BLOCK_RESOURCE", "execution"))
    if proof.wait_type not in ("LCK_M_X", "LCK_M_U"):
        findings.append(Finding(case, "BLOCK_WAIT_TYPE", "execution"))
    if not (0 < evidence.wait_started_ns <= proof.observed_at_ns <= evidence.proof_received_ns <=
            evidence.observation_started_ns <= evidence.observation_received_ns <= evidence.deadline_ns):
        findings.append(Finding(case, "BLOCK_OBSERVATION_ORDER", "execution"))
    return findings


def validate(case: str, before: dict, after: dict, *, status: str, recorded_count: int,
             index_valid: bool, daily_before: dict, daily_after: dict,
             previous_count: int | None = None, no_window: bool = False,
             observations: tuple[dict, ...] = (), atomic_evidence: AtomicEvidence | None = None) -> list[Finding]:
    """Rows are tran_pk -> {in_master, digest}; digest excludes only in_master.

    Atomic observations are full row sets obtained in separate SNAPSHOT reads.
    The index probe must establish UX_tran_master exists on tran_id, filtered
    by in_master=1 and unique, not merely that an index has that name.
    """
    findings = unchanged(daily_before, daily_after, case, "core.daily_tran")
    if case == "atomic":
        findings += atomic_evidence_findings(case, atomic_evidence)
    count = sum(bool(r["in_master"]) for r in before.values())
    if case == "rerun":
        if count:
            findings.append(Finding(case, "RERUN_MASTER_EMPTY", "core.transaction"))
        findings += unchanged(before, after, case, "core.transaction")
    archived = {key: {**row, "in_master": False} for key, row in before.items()}
    accepted = status == "COMPLETED"
    if no_window:
        if status != "BATCH_WINDOW_REQUIRED":
            findings.append(Finding(case, "WINDOW_REFUSAL", "execution"))
        accepted = False
    elif status not in (("COMPLETED", "FAILED") if case == "atomic" else ("COMPLETED",)):
        findings.append(Finding(case, "EXECUTION_COMPLETED", "execution"))
    wanted = archived if accepted else before
    if after != wanted:
        findings.append(Finding(case, "ARCHIVE_ROWS", "core.transaction"))
    if accepted and not equal_scalar(recorded_count, previous_count if previous_count is not None else count):
        findings.append(Finding(case, "ARCHIVE_COUNT", "batch.cycle"))
    if not index_valid:
        findings.append(Finding(case, "MASTER_INDEX", "sys.indexes"))
    if any(view != before and view != archived for view in observations):
        findings.append(Finding(case, "ATOMIC_VISIBILITY", "core.transaction"))
    if case == "atomic" and not observations:
        findings.append(Finding(case, "ATOMIC_OBSERVATIONS_REQUIRED", "core.transaction"))
    elif case == "atomic" and observations[0] != before:
        findings.append(Finding(case, "LOCKED_MASTER_UNCHANGED", "core.transaction"))
    return findings
