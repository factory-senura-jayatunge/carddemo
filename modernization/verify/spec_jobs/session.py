"""Live-driver boundary. Infra implements it on the verify-batch driver.

Every handle runs as one named ServiceLogin of the private verify database
(default ``batch``); ``as_login`` gives a sibling with the same lock, VAR dir
and log. No handle is SA, and the grants forbid DDL. A permission error fails
the case: callers never retry as another login. Exceptions crossing this
boundary must be value-free.
"""
from __future__ import annotations

from contextlib import AbstractContextManager
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Mapping, Protocol
from .common import require_integer, require_execution_id

LOGINS = ("identity", "billpay", "account", "transaction", "reference",
          "authorization", "inquiry", "batch", "fixture")


@dataclass(frozen=True)
class FixtureLock:
    session_id: int

    def __post_init__(self):
        if require_integer(self.session_id) <= 0:
            raise ValueError("LOCK_SESSION_REQUIRED")


@dataclass(frozen=True)
class ArchiveBlockProof:
    ready: bool
    execution_id: int
    waiting_session_id: int
    blocking_session_id: int
    object_name: str
    resource_type: str
    wait_type: str
    observed_at_ns: int

    def __post_init__(self):
        require_execution_id(self.execution_id)
        for value in (self.waiting_session_id, self.blocking_session_id, self.observed_at_ns):
            require_integer(value)
        if type(self.ready) is not bool or any(type(v) is not str for v in (
                self.object_name, self.resource_type, self.wait_type)):
            raise ValueError("ARCHIVE_BLOCK_PROOF_SHAPE")


@dataclass(frozen=True)
class Execution:
    status: str
    execution_id: int | None = None
    job_instance_id: int | None = None
    exit_description: str = ""
    # Step execution contexts from BATCH_STEP_EXECUTION_CONTEXT, by step name.
    contexts: Mapping[str, Any] = field(default_factory=dict)
    # The database instance's output root, resolved by the adapter.
    instance_root: Path | None = None
    step_counts: Mapping[str, Mapping[str, int]] = field(default_factory=dict)
    step_statuses: Mapping[str, str] = field(default_factory=dict)
    job_context: Mapping[str, Any] = field(default_factory=dict)

    def __post_init__(self):
        if self.execution_id is not None:
            require_execution_id(self.execution_id)
        if self.job_instance_id is not None:
            require_integer(self.job_instance_id)


class Running(Protocol):
    """Require an exact int ID in 0..2**63-1 before any execution observer call."""

    execution_id: int

    def wait(self, timeout: float = 120) -> Execution: ...
    def done(self) -> bool: ...


class Session(Protocol):
    var_dir: Path

    def as_login(self, login_id: str) -> Session: ...

    def reset(self, *, mode: str, properties: Mapping[str, str] | None = None) -> None:
        """db-reset plus the sample load on carddemo_verify_batch[_suffix], no window."""
        ...

    def load(self, source_dir: Path, *, manifest: Path | None = None,
             authorization_file: Path | None = None) -> Execution:
        """One dataLoadJob launch on a prepared copy of the sample files, inside its window.

        The data-load failure cases need it: a failing load must be observable
        as an execution, not as an exception from reset.
        """
        ...

    def query(self, sql: str) -> list[dict[str, Any]]:
        """One SELECT, wrapped by the adapter in FOR JSON PATH, INCLUDE_NULL_VALUES.

        Decimals come back as exact strings, dates and times as ISO text.
        """
        ...

    def execute(self, sql: str) -> None:
        """One batch with XACT_ABORT ON; raises on error."""
        ...

    def cycle(self, business_date: str, *, completed: bool = False) -> int: ...

    def launch(self, job: str, parameters: Mapping[str, Any]) -> Running:
        """batch-service internal API, DAG parameters; refusals come back as status."""
        ...

    def window(self, *, cycle_id: int | None = None,
               run_key: str | None = None) -> AbstractContextManager[None]: ...

    def hold_lock(self, sql: str) -> AbstractContextManager[FixtureLock]:
        """Separate connection: BEGIN TRAN + sql; exit releases this exact holder."""
        ...

    def fixture_execute(self, sql: str) -> None:
        """as_login("fixture").execute: db_datareader/db_datawriter, DML only."""
        ...

    def fixture_lock(self, sql: str) -> AbstractContextManager[FixtureLock]:
        """as_login("fixture").hold_lock."""
        ...

    def wait_for_archive_blocked(self, running: Running, holder: FixtureLock, *,
                                 timeout: float = 30.0) -> ArchiveBlockProof:
        """Required, bounded G2 proof for this exact archive execution.

        Map the actual execution to its SQL request, not merely the same DB or
        login. It must wait behind holder.session_id on a core.transaction KEY
        with LCK_M_X or LCK_M_U. Record monotonic_ns at the proof's receipt in
        this process. No missing, optional, stale or not-ready proof is a pass.
        Refuse invalid execution IDs before SQL as usage errors, never product
        results. The valid domain is exact int 0..2**63-1; session IDs stay >0.
        Fixed admin templates only; no caller SQL or execution values logged.
        """
        ...

    def snapshot(self, table: str, keys: tuple[str, ...], *,
                 exclude: tuple[str, ...] = ()) -> dict:
        """SNAPSHOT read as this login: tuple key -> framed SHA2_256 of the other columns.

        Raises when a masked column is not excluded and the login lacks UNMASK,
        so a masked value can never make two different rows look equal.
        """
        ...

    def snapshot_digest(self, table: str, keys: tuple[str, ...] = (), *,
                        exclude: tuple[str, ...] = (), order_by: tuple[str, ...] = (),
                        columns: tuple[str, ...] = (), aggregate: bool = False) -> dict:
        """Fixed admin template: tuple key -> digest over every column, masked ones included.

        Returns digests only, never values. Keys must be non-PII surrogate IDs.
        Required (architecture f7d4801) for core.transaction, the waiting
        core.daily_tran adds, the purge's kept pauth rows and the load failures.
        With aggregate=True, columns/order_by are a fixed allowlisted template;
        return {"rows": int, "digest": str} over ordered framed row hashes,
        with no keys. Bytes follow snapshot-digest.md v1; digest is lowercase.
        CVV is never an allowed aggregate column.
        """
        ...

    def log_bytes(self) -> bytes:
        """work/server.log (0600), read in process, never echoed."""
        ...
