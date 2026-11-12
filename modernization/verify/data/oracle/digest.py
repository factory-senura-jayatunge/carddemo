"""Independent mirror of snapshot-digest.md v1. Never render returned hashes."""
from __future__ import annotations

from dataclasses import dataclass
from datetime import date, datetime, time
from decimal import Decimal
import hashlib
import re
from typing import Iterable, Mapping
from uuid import UUID

from modernization.verify.spec_jobs.common import InvalidFixture


@dataclass(frozen=True)
class Column:
    kind: str
    length: int | None = None
    scale: int = 0


def timestamp(value, *, clock: bool = False) -> str:
    if isinstance(value, datetime):
        text = value.isoformat(sep=" ")
    elif isinstance(value, time):
        text = value.isoformat()
    else:
        text = str(value).replace("T", " ")
    if clock:
        text = "1900-01-01 " + text
    match = re.fullmatch(r"(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2})(?:\.(\d{1,7}))?", text)
    if not match:
        raise InvalidFixture("DIGEST_TIMESTAMP")
    # Validate the calendar/clock without losing the seventh 100-ns digit.
    datetime.fromisoformat(match[1])
    return match[1] + "." + (match[2] or "").ljust(7, "0")


def value_bytes(value, column: Column) -> bytes | None:
    """The SQL conversion is defined by v1, not by a target implementation."""
    kind = column.kind.lower()
    allowed = {"char", "varchar", "nchar", "nvarchar", "decimal", "numeric",
               "tinyint", "smallint", "int", "bigint", "bit", "uniqueidentifier",
               "date", "datetime2", "time", "binary", "varbinary", "rowversion"}
    if kind not in allowed:
        raise InvalidFixture("DIGEST_UNSUPPORTED_TYPE")
    if value is None:
        return None
    try:
        if kind in ("binary", "varbinary", "rowversion"):
            if not isinstance(value, (bytes, bytearray, memoryview)):
                raise ValueError
            raw = bytes(value)
            width = 8 if kind == "rowversion" else column.length
            if width is not None and len(raw) > width:
                raise ValueError
            return raw.ljust(width, b"\0") if kind in ("binary", "rowversion") and width else raw
        if kind in ("char", "varchar", "nchar", "nvarchar"):
            text = str(value)
            units = len(text.encode("utf-16-le")) // 2
            if column.length is not None and units > column.length:
                raise ValueError
            if kind in ("char", "nchar"):
                if column.length is None:
                    raise ValueError
                text += " " * (column.length - units)
        elif kind in ("decimal", "numeric"):
            number = Decimal(str(value)).quantize(Decimal(1).scaleb(-column.scale))
            if not number.is_finite():
                raise ValueError
            text = format(number, "f")
            if number == 0:
                text = text.lstrip("-")
        elif kind in ("tinyint", "smallint", "int", "bigint"):
            text = str(int(value))
        elif kind == "bit":
            text = "1" if value else "0"
        elif kind == "uniqueidentifier":
            text = str(UUID(str(value))).upper()
        elif kind == "date":
            text = (value.date() if isinstance(value, datetime) else
                    value if isinstance(value, date) else date.fromisoformat(str(value))).isoformat()
        elif kind in ("datetime2", "time"):
            text = timestamp(value, clock=kind == "time")
        return text.encode("utf-16-le")
    except (ValueError, TypeError, ArithmeticError, UnicodeError, OverflowError):
        raise InvalidFixture("DIGEST_VALUE_ENCODING") from None


def frame(raw: bytes | None) -> bytes:
    if raw is None:
        return b"\0"
    if len(raw) >= 2 ** 32:
        raise InvalidFixture("DIGEST_FRAME_LENGTH")
    return b"\1" + len(raw).to_bytes(4, "big") + raw


def row_bytes(row: Mapping, columns: Iterable[str], types: Mapping[str, Column]) -> bytes:
    return b"".join(frame(value_bytes(row[name], types[name])) for name in columns)


def aggregate(rows: Iterable[Mapping], *, types: Mapping[str, Column],
              columns: tuple[str, ...], order_by: tuple[str, ...]) -> dict:
    """Return only count and digest. Ordering is on framed bytes, not values."""
    for names in (columns, order_by):
        if not names or len(names) != len(set(names)) or any(n not in types for n in names):
            raise InvalidFixture("DIGEST_COLUMN_LIST")
    if any(name.lower() == "cvv_cd" for name in columns + order_by):
        raise InvalidFixture("DIGEST_CVV_EXCLUDED")
    items = []
    for row in rows:
        key = row_bytes(row, order_by, types)
        hashed = hashlib.sha256(row_bytes(row, columns, types)).digest()
        items.append((key, hashed))
    result = hashlib.sha256()
    for _, hashed in sorted(items):
        result.update(hashed)
    return {"rows": len(items), "digest": result.hexdigest()}
