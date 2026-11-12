"""Value-free checks shared by the independent validators."""
from __future__ import annotations

from dataclasses import dataclass
from typing import Iterable, Mapping


@dataclass(frozen=True)
class Finding:
    case: str
    check: str
    source: str
    line: int = 0
    column: str = "-"

    def __str__(self) -> str:
        return f"{self.case}: {self.check}: {self.source}:{self.line}: column {self.column}"


class InvalidFixture(ValueError):
    """Never attach the input or a database error to this exception."""


def require_integer(value) -> int:
    """Numeric counts/IDs must not accept bool, float or coercible text."""
    if type(value) is not int:
        raise InvalidFixture("INTEGER_REQUIRED")
    return value


def require_execution_id(value) -> int:
    """Archive tag observer domain; zero is the first allocated execution ID."""
    execution_id = require_integer(value)
    if not 0 <= execution_id <= (1 << 63) - 1:
        raise InvalidFixture("EXECUTION_ID_RANGE")
    return execution_id


def equal_scalar(actual, expected) -> bool:
    return (type(actual) is int and actual == expected) if type(expected) is int else actual == expected


def unchanged(before: Mapping, after: Mapping, case: str, source: str) -> list[Finding]:
    """Mappings contain keys -> SQL SHA2_256 row digests, never rendered."""
    return [] if before == after else [Finding(case, "UNCHANGED", source)]


def pii_scan(case: str, sources: Mapping[str, str | bytes],
             sentinels: Iterable[str]) -> list[Finding]:
    """Do not include deliverable listings in sources. Never print a sentinel."""
    needles = [s.encode("utf-8") for s in sentinels if s]
    findings = []
    for name, content in sources.items():
        raw = content.encode("utf-8") if isinstance(content, str) else content
        for line, value in enumerate(raw.splitlines(), 1):
            if any(needle in value for needle in needles):
                findings.append(Finding(case, "PII_SENTINEL", name, line))
    return findings


def completed(case: str, status: str) -> list[Finding]:
    return [] if status == "COMPLETED" else [Finding(case, "EXECUTION_COMPLETED", "execution")]
