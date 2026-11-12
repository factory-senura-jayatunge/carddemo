"""In-memory Session for offline tests. It models the adapter contract only."""
from __future__ import annotations

from contextlib import contextmanager
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Callable
from modernization.verify.data.oracle import digest

from modernization.verify.spec_jobs.session import LOGINS, Execution, FixtureLock


class PermissionDenied(RuntimeError):
    pass


@dataclass
class FakeRunning:
    execution: Execution
    polls: int = 1

    @property
    def execution_id(self):
        return self.execution.execution_id

    def done(self) -> bool:
        self.polls -= 1
        return self.polls < 0

    def wait(self, timeout: float = 120) -> Execution:
        return self.execution


@dataclass
class FakeSession:
    var_dir: Path
    tables: dict[str, list[dict]] = field(default_factory=dict)
    column_types: dict[str, dict[str, digest.Column]] = field(default_factory=dict)
    masked: dict[str, set[str]] = field(default_factory=dict)
    unmask: dict[str, set[tuple[str, str]]] = field(default_factory=dict)
    login: str = "batch"
    jobs: dict[str, Callable[["FakeSession", dict], Execution]] = field(default_factory=dict)
    queries: dict[str, Callable[["FakeSession"], list[dict]]] = field(default_factory=dict)
    windows: list = field(default_factory=list)
    log: bytes = b""
    resets: list = field(default_factory=list)
    fixtures: list = field(default_factory=list)

    def as_login(self, login_id: str) -> "FakeSession":
        if login_id not in LOGINS:
            raise PermissionDenied("LOGIN")
        sibling = type(self).__new__(type(self))
        sibling.__dict__.update(self.__dict__)
        sibling.login = login_id
        return sibling

    def reset(self, *, mode, properties=None):
        self.resets.append((mode, dict(properties or {})))

    def load(self, source_dir, *, manifest=None, authorization_file=None):
        return self.jobs["dataLoadJob"](self, {"source_dir": source_dir, "manifest": manifest,
                                               "authorization_file": authorization_file})

    def query(self, sql):
        for marker, handler in self.queries.items():
            if marker in sql:
                return handler(self)
        raise KeyError("UNKNOWN_QUERY")

    def execute(self, sql):
        self.fixtures.append((self.login, sql))

    def fixture_execute(self, sql):
        self.as_login("fixture").execute(sql)

    @contextmanager
    def hold_lock(self, sql):
        yield FixtureLock(101)

    def fixture_lock(self, sql):
        return self.as_login("fixture").hold_lock(sql)

    def wait_for_archive_blocked(self, running, holder, *, timeout=30.0):
        # Atomic tests must deliberately model a blocking execution. A normal
        # fake launch executes eagerly and cannot certify a database wait.
        raise NotImplementedError("ARCHIVE_BLOCK_PROOF_REQUIRED")

    def cycle(self, business_date, *, completed=False):
        return {"2022-07-05": 1}.get(business_date, 2)

    def launch(self, job, parameters):
        return FakeRunning(self.jobs[job](self, dict(parameters)))

    @contextmanager
    def window(self, *, cycle_id=None, run_key=None):
        self.windows.append(cycle_id or run_key)
        try:
            yield
        finally:
            self.windows.pop()

    def _digest(self, table, keys, exclude):
        return {tuple(row[k] for k in keys):
                repr(sorted((c, v) for c, v in row.items() if c not in exclude))
                for row in self.tables.get(table, [])}

    def snapshot(self, table, keys, *, exclude=()):
        hidden = self.masked.get(table, set()) - set(exclude)
        if any((table, c) not in self.unmask.get(self.login, set()) for c in hidden):
            raise PermissionDenied("MASKED_COLUMN_NOT_EXCLUDED")
        return self._digest(table, keys, exclude)

    def snapshot_digest(self, table, keys=(), *, exclude=(), order_by=(), columns=(), aggregate=False):
        if aggregate:
            if "cvv_cd" in columns or "cvv_cd" in order_by:
                raise PermissionDenied("CVV_EXCLUDED")
            return digest.aggregate(self.tables.get(table, []), types=self.column_types[table],
                                    columns=columns, order_by=order_by)
        return self._digest(table, keys, exclude)

    def log_bytes(self):
        return self.log
