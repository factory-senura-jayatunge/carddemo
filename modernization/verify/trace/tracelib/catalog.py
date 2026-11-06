"""Decision IDs of the decision catalog and the architecture-dependent items of the architecture."""
from __future__ import annotations

import re
from pathlib import Path

CATALOG = "docs/modernization/decisions/README.md"
ARCHITECTURE = "docs/modernization/architecture/decisions.md"
ARCH_SECTION = "## Architecture-dependent fidelity items"
DECISION_HEAD_RE = re.compile(r"^###\s+(D-\d{2,3})\b", re.M)


def decisions(root: Path) -> set[str]:
    path = root / CATALOG
    if not path.is_file():
        return set()
    return set(DECISION_HEAD_RE.findall(path.read_text(encoding="utf-8")))


def architecture_dependent(root: Path) -> list[list[str]]:
    """One list of item IDs per row of the architecture-dependent table.

    A cell may abbreviate IDs of the same kind and unit to their number:
    "DEF-AUTH-001, 002" means DEF-AUTH-001 and DEF-AUTH-002.
    """
    path = root / ARCHITECTURE
    if not path.is_file():
        return []
    text = path.read_text(encoding="utf-8")
    if ARCH_SECTION not in text:
        return []
    section = text.split(ARCH_SECTION, 1)[1]
    section = re.split(r"^## ", section, maxsplit=1, flags=re.M)[0]
    rows = []
    for line in section.splitlines():
        if not line.startswith("|"):
            continue
        first = line.strip().strip("|").split("|")[0].strip()
        if first in ("Item", "") or first.startswith(":"):
            continue
        ids: list[str] = []
        for token in (t.strip() for t in first.split(",")):
            if re.fullmatch(r"\d{2,3}", token) and ids:
                token = ids[-1].rsplit("-", 1)[0] + "-" + token
            ids.append(token)
        rows.append(ids)
    return rows
