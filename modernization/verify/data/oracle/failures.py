"""Prepare rejected input files without invoking a target parser."""
from __future__ import annotations

import hashlib
from pathlib import Path
import shutil

from . import source
from modernization.verify.spec_jobs.discgrp import lines


def rejected(case: str, mode: str) -> bool:
    """Manifest bytes are checked only when DEF-ACUP-002 is fixed."""
    if mode not in ("legacy", "fixed"):
        raise ValueError("MODE")
    return case != "manifest_bytes" or mode == "fixed"


def prepare(sample: Path, target: Path, case: str, table_name: str = "core.account") -> Path | None:
    """Return an optional explicit manifest. All mutations are value-free."""
    shutil.copytree(sample, target, dirs_exist_ok=True)
    for copied in target.rglob("*"):
        copied.chmod(0o700 if copied.is_dir() else 0o600)
    table = source.TABLES[table_name]
    path = target / table.filename
    raw = path.read_bytes()
    records = lines(raw)
    manifest = None
    if case == "out_of_order":
        records[0], records[1] = records[1], records[0]
    elif case == "duplicate":
        records.insert(1, records[0])
    elif case == "missing_account":
        if table_name != "core.card_xref":
            raise ValueError("XREF_REQUIRED")
        # 26-36 is the account key. This key is absent from the source account file.
        missing = b"87361920451"
        if any(r[:11] == missing for r in lines((target / "acctdata.txt").read_bytes())):
            raise ValueError("ABSENT_KEY_REQUIRED")
        records[0] = records[0][:25] + missing + records[0][36:]
    elif case == "manifest_bytes":
        if table_name != "core.account":
            raise ValueError("ACCOUNT_REQUIRED")
        manifest = target / "account-layout.toml"
        manifest.write_text(f'schema = 1\nsha256 = "{hashlib.sha256(raw).hexdigest()}"\n'
                            'default_layout = "ACUP_UPDATE"\nother_layout_accounts = []\n')
        manifest.chmod(0o600)
        records[0] = records[0][:122] + b"!" + records[0][123:]
    else:
        raise ValueError("UNKNOWN_LOAD_FAILURE")
    path.write_bytes(b"\n".join(records) + b"\n")
    return manifest


def blank_response(raw: bytes) -> bytes:
    """Change one PAUTDTL1 response at entity positions 63-64, retaining RDW."""
    result = bytearray(raw)
    offset = 0
    while offset < len(raw):
        length = int.from_bytes(raw[offset:offset + 2], "big")
        if length < 4 or offset + length > len(raw):
            raise ValueError("HD_DESCRIPTOR")
        if length == 240 and raw[offset + 10:offset + 18].decode("cp037") == "PAUTDTL1":
            result[offset + 39 + 62:offset + 39 + 64] = b"\x40\x40"
            return bytes(result)
        offset += length
    raise ValueError("DETAIL_REQUIRED")
