"""SQL text for the validator adapter: snapshot digests, metadata reads, wrappers.

The bytes that snapshot_digest hashes are fixed by snapshot-digest v1.1; the
expressions here are the adapter's implementation of them, confirmed at the
first database slot by the conformance queries (CONFORMANCE). Every identifier
comes from the fixed table allowlist or from INFORMATION_SCHEMA.COLUMNS of the
checked verify database and is bracket-quoted as QUOTENAME does; no value is
ever put into SQL text. Refusals name the table and column names only.
"""
from __future__ import annotations

import json
import re
from dataclasses import dataclass
from typing import Iterable, Mapping, Sequence

ALLOWED_TABLES = frozenset({
    "core.daily_tran", "core.transaction", "core.account", "core.card", "core.card_xref",
    "core.customer", "core.tcat_bal", "ref.disclosure_group", "pauth.auth_summary",
    "pauth.auth_detail", "pauth.auth_fraud", "pauth.outbox", "pauth.processed_message"})
# Same rule as the live driver: only a private verify-batch database.
DB_PATTERN = re.compile(r"carddemo_verify_batch(_[a-z0-9]+)*\Z")
IDENTIFIER = re.compile(r"[A-Za-z_][A-Za-z0-9_]{0,127}\Z")
CVV_COLUMN = "cvv_cd"
TEXT_TYPES = frozenset({"char", "varchar", "nchar", "nvarchar"})
# Types whose value bytes are the UTF-16LE of CONVERT(NVARCHAR(MAX), c) with no style.
PLAIN_TYPES = TEXT_TYPES | {"decimal", "numeric", "tinyint", "smallint", "int", "bigint", "bit",
                            "uniqueidentifier"}
BINARY_TYPES = frozenset({"binary", "varbinary", "rowversion", "timestamp"})
CLOCK_TYPES = frozenset({"datetime2", "time"})
# INFORMATION_SCHEMA reports a ROWVERSION column as "timestamp".
SUPPORTED_TYPES = PLAIN_TYPES | BINARY_TYPES | CLOCK_TYPES | {"date"}
KEY_TYPES = frozenset({"char", "varchar", "nchar", "nvarchar", "tinyint", "smallint", "int", "bigint",
                       "uniqueidentifier", "binary", "varbinary"})
HEX64 = re.compile(r"[0-9a-f]{64}\Z")
EMPTY_DIGEST = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
SNAPSHOT_ISOLATION = "SET TRANSACTION ISOLATION LEVEL SNAPSHOT;"


class Refused(ValueError):
    """A snapshot request refused before or after the query; names identifiers only."""

    def __init__(self, code: str, table: str, columns: Iterable[str] = ()):
        names = ",".join(columns)
        super().__init__(f"{code}: {table}" + (f" ({names})" if names else ""))
        self.code = code


@dataclass(frozen=True)
class Column:
    name: str
    data_type: str
    masked: bool = False
    can_unmask: bool = False


def quote(name: str) -> str:
    """QUOTENAME for an identifier that already passed IDENTIFIER."""
    if not IDENTIFIER.match(name):
        raise Refused("IDENTIFIER", "-")
    return "[" + name.replace("]", "]]") + "]"


# Printable Latin-1: every one of these is also in code page 1252 and in
# UTF-8, so no VARCHAR column of the database narrows it. The C0 and C1
# controls are not, and are refused.
LATIN1_PRINTABLE = re.compile(r"[\x20-\x7e\xa0-\xff]*\Z")


def latin1_literal(value: str) -> str:
    """An NVARCHAR expression for a printable Latin-1 value: its UTF-16LE as
    a hex literal, so the SQL text stays ASCII and no client or code page
    conversion touches the value on its way to the server."""
    if not isinstance(value, str) or not LATIN1_PRINTABLE.match(value):
        raise Refused("LATIN1_VALUE", "-")
    size = "MAX" if len(value) > 4000 else str(max(len(value), 1))
    return f"CAST(0x{value.encode('utf-16-le').hex().upper()} AS NVARCHAR({size}))"


def split_table(table: str) -> tuple[str, str]:
    if table not in ALLOWED_TABLES:
        raise Refused("TABLE_NOT_ALLOWED", table if re.fullmatch(r"[A-Za-z0-9_.]{1,128}", table) else "-")
    schema, name = table.split(".")
    return schema, name


def quoted_table(table: str) -> str:
    schema, name = split_table(table)
    return f"{quote(schema)}.{quote(name)}"


def columns_sql(table: str) -> str:
    """Column metadata of one allowlisted table as the calling login sees it.

    can_unmask is that login's UNMASK on the column, so the login-path snapshot
    refuses a masked column the login would only see masked.
    """
    schema, name = split_table(table)
    return ("SELECT c.COLUMN_NAME AS name, c.DATA_TYPE AS data_type, c.ORDINAL_POSITION AS ordinal, "
            "CAST(CASE WHEN m.is_masked = 1 THEN 1 ELSE 0 END AS BIT) AS masked, "
            f"CAST(COALESCE(HAS_PERMS_BY_NAME(N'{schema}.{name}', 'OBJECT', 'UNMASK', c.COLUMN_NAME, 'COLUMN'), 0) "
            "AS BIT) AS can_unmask "
            "FROM INFORMATION_SCHEMA.COLUMNS c "
            f"LEFT JOIN sys.masked_columns m ON m.object_id = OBJECT_ID(N'{schema}.{name}') "
            "AND m.name = c.COLUMN_NAME "
            f"WHERE c.TABLE_CATALOG = DB_NAME() AND c.TABLE_SCHEMA = N'{schema}' AND c.TABLE_NAME = N'{name}' "
            "ORDER BY c.ORDINAL_POSITION")


def parse_columns(table: str, rows: Sequence[Mapping]) -> dict[str, Column]:
    """Ordered name -> Column; refuses an empty or malformed answer."""
    result: dict[str, Column] = {}
    ordinals = []
    for row in rows:
        name, data_type, ordinal = row.get("name"), row.get("data_type"), row.get("ordinal")
        masked, unmask = row.get("masked"), row.get("can_unmask")
        if (not isinstance(name, str) or not IDENTIFIER.match(name) or not isinstance(data_type, str)
                or type(ordinal) is not int or masked not in (True, False) or unmask not in (True, False)
                or name in result):
            raise Refused("COLUMN_METADATA", table)
        ordinals.append(ordinal)
        result[name] = Column(name, data_type.lower(), masked, unmask)
    if not result or ordinals != sorted(ordinals):
        raise Refused("COLUMN_METADATA", table)
    return result


def _known(table: str, meta: Mapping[str, Column], names: Sequence[str], what: str) -> None:
    unknown = [n for n in names if n not in meta]
    if unknown:
        raise Refused("UNKNOWN_COLUMN", table, unknown)
    if len(set(names)) != len(names):
        raise Refused("DUPLICATE_COLUMN", table, sorted({n for n in names if list(names).count(n) > 1}))
    unsupported = [n for n in names if meta[n].data_type not in SUPPORTED_TYPES]
    if unsupported:
        raise Refused("UNSUPPORTED_TYPE", table, unsupported)


def value_expr(column: Column) -> str:
    """V for one column, snapshot-digest section 3."""
    c, t = quote(column.name), column.data_type
    if t in PLAIN_TYPES:
        return f"CONVERT(VARBINARY(MAX), CONVERT(NVARCHAR(MAX), {c}))"
    if t == "date":
        return f"CONVERT(VARBINARY(MAX), CONVERT(NVARCHAR(MAX), {c}, 23))"
    if t in CLOCK_TYPES:
        return f"CONVERT(VARBINARY(MAX), CONVERT(NVARCHAR(MAX), CAST({c} AS DATETIME2(7)), 121))"
    if t in BINARY_TYPES:
        return f"CAST({c} AS VARBINARY(MAX))"
    raise Refused("UNSUPPORTED_TYPE", "-", [column.name])


def frame_expr(column: Column) -> str:
    """The field frame, snapshot-digest section 4."""
    v = value_expr(column)
    return (f"CASE WHEN {quote(column.name)} IS NULL THEN CAST(0x00 AS VARBINARY(MAX)) "
            f"ELSE CAST(0x01 AS VARBINARY(MAX)) + CAST(CAST(DATALENGTH({v}) AS INT) AS BINARY(4)) + {v} END")


def frames(columns: Iterable[Column]) -> str:
    return " + ".join(frame_expr(c) for c in columns)


def aggregate_sql(source: str, meta: Mapping[str, Column], columns: Sequence[str],
                  order_by: Sequence[str], *, table: str, where: str = "") -> str:
    """The fixed template of snapshot-digest section 8 over `source`."""
    for names in (columns, order_by):
        if not names:
            raise Refused("COLUMN_LIST_EMPTY", table)
        _known(table, meta, names, "columns")
    if any(n.lower() == CVV_COLUMN for n in (*columns, *order_by)):
        raise Refused("CVV_EXCLUDED", table, [CVV_COLUMN])
    return ("SET NOCOUNT ON;\n"
            "WITH r AS (\n"
            f"    SELECT HASHBYTES('SHA2_256', {frames(meta[n] for n in columns)}) AS row_hash,\n"
            f"           {frames(meta[n] for n in order_by)} AS order_key\n"
            f"    FROM {source}{where}\n"
            "), h AS (\n"
            "    SELECT CONVERT(VARCHAR(MAX), order_key, 2) COLLATE Latin1_General_BIN2 AS key_hex,\n"
            "           CONVERT(VARCHAR(MAX), CONVERT(VARCHAR(64), row_hash, 2)) COLLATE Latin1_General_BIN2 AS row_hex\n"
            "    FROM r\n"
            ")\n"
            "SELECT COUNT_BIG(*) AS row_count,\n"
            "       LOWER(CONVERT(VARCHAR(64), HASHBYTES('SHA2_256',\n"
            "           COALESCE(CONVERT(VARBINARY(MAX),\n"
            "                    STRING_AGG(row_hex, '') WITHIN GROUP (ORDER BY key_hex, row_hex), 2), 0x)), 2)) AS digest\n"
            "FROM h")


def table_aggregate_sql(table: str, meta: Mapping[str, Column], columns: Sequence[str],
                        order_by: Sequence[str]) -> str:
    return aggregate_sql(quoted_table(table), meta, columns, order_by, table=table)


def parse_aggregate(table: str, rows: Sequence[Mapping]) -> dict:
    if len(rows) != 1:
        raise Refused("DIGEST_RESULT", table)
    count, digest = rows[0].get("row_count"), rows[0].get("digest")
    if type(count) is not int or count < 0 or not isinstance(digest, str) or not HEX64.match(digest):
        raise Refused("DIGEST_RESULT", table)
    if count == 0 and digest != EMPTY_DIGEST:
        raise Refused("DIGEST_RESULT", table)
    return {"rows": count, "digest": digest}


def per_key_sql(table: str, meta: Mapping[str, Column], keys: Sequence[str],
                exclude: Sequence[str] = (), *, login_path: bool = False) -> tuple[str, tuple[str, ...]]:
    """Per-key row hashes over every column not excluded, in ordinal order, keys included.

    Keys come back as stored, except binary keys: uppercase hex without 0x
    (snapshot-digest 5.1). CVV must be excluded. On the login path a masked
    column that is not excluded is refused unless the login has UNMASK on it,
    so a masked value can never make two different rows hash alike.
    Returns the SQL and the hashed column names.
    """
    if not keys:
        raise Refused("KEY_LIST_EMPTY", table)
    _known(table, meta, keys, "keys")
    unknown = [n for n in exclude if n not in meta]
    if unknown:
        raise Refused("UNKNOWN_COLUMN", table, unknown)
    excluded = set(exclude)
    if set(keys) & excluded:
        raise Refused("KEY_EXCLUDED", table, [k for k in keys if k in excluded])
    masked_keys = [k for k in keys if meta[k].masked]
    if masked_keys:
        raise Refused("KEY_MASKED", table, masked_keys)
    bad_keys = [k for k in keys if meta[k].data_type not in KEY_TYPES]
    if bad_keys:
        raise Refused("KEY_TYPE", table, bad_keys)
    hashed = tuple(n for n in meta if n not in excluded)
    if any(n.lower() == CVV_COLUMN for n in hashed):
        raise Refused("CVV_EXCLUDED", table, [CVV_COLUMN])
    if not hashed:
        raise Refused("COLUMN_LIST_EMPTY", table)
    _known(table, meta, hashed, "columns")
    if login_path:
        hidden = [n for n in hashed if meta[n].masked and not meta[n].can_unmask]
        if hidden:
            raise Refused("MASKED_COLUMN_NOT_EXCLUDED", table, hidden)
    key_exprs = []
    for i, k in enumerate(keys):
        expr = (f"CONVERT(VARCHAR(MAX), {quote(k)}, 2)" if meta[k].data_type in ("binary", "varbinary")
                else quote(k))
        key_exprs.append(f"{expr} AS [k{i}]")
    sql = ("SELECT " + ", ".join(key_exprs) + ", "
           f"LOWER(CONVERT(VARCHAR(64), HASHBYTES('SHA2_256', {frames(meta[n] for n in hashed)}), 2)) AS [h] "
           f"FROM {quoted_table(table)}")
    return sql, hashed


def parse_per_key(table: str, keys: Sequence[str], rows: Sequence[Mapping]) -> dict:
    result = {}
    for row in rows:
        if set(row) != {f"k{i}" for i in range(len(keys))} | {"h"}:
            raise Refused("DIGEST_RESULT", table)
        key = tuple(row[f"k{i}"] for i in range(len(keys)))
        if any(v is None or isinstance(v, (bool, float, list, dict)) for v in key):
            raise Refused("KEY_VALUE", table, keys)
        if not isinstance(row["h"], str) or not HEX64.match(row["h"]):
            raise Refused("DIGEST_RESULT", table)
        if key in result:
            raise Refused("DUPLICATE_KEY", table, keys)
        result[key] = row["h"]
    return result


# Snapshot-digest section 9: the conformance source, its declared types and the
# expected answers (vectors 7.2, 7.3, 7.4). ASCII only.
CONFORMANCE_SOURCE = """(VALUES
 (CAST('1111222233334444' AS CHAR(16)), CAST('00000000011' AS CHAR(11)), CAST(-12.50 AS DECIMAL(11,2)),
  CAST('2022-07-06 10:15:30.123456' AS DATETIME2(6)), CAST('2022-07-06' AS DATE), CAST(1 AS BIT),
  CAST(NULL AS VARCHAR(10)), CAST(42 AS BIGINT), CAST('6F9619FF-8B86-D011-B42D-00C04FC964FF' AS UNIQUEIDENTIFIER),
  CAST(0xDEADBEEF AS BINARY(4)), CAST(N'Zo' + NCHAR(235) AS NVARCHAR(10))),
 (CAST('1111222233335555' AS CHAR(16)), CAST('00000000012' AS CHAR(11)), CAST(0.05 AS DECIMAL(11,2)),
  CAST('2022-07-07 00:00:00' AS DATETIME2(6)), CAST('2022-07-07' AS DATE), CAST(0 AS BIT),
  CAST('' AS VARCHAR(10)), CAST(-7 AS BIGINT), CAST(NULL AS UNIQUEIDENTIFIER),
  CAST(NULL AS BINARY(4)), CAST(N'' AS NVARCHAR(10)))
) AS t(card_num, acct_id, amt, ts, proc_dt, flag, note, seq, id, dig, name)"""
CONFORMANCE_TYPES = {name: Column(name, data_type) for name, data_type in (
    ("card_num", "char"), ("acct_id", "char"), ("amt", "decimal"), ("ts", "datetime2"), ("proc_dt", "date"),
    ("flag", "bit"), ("note", "varchar"), ("seq", "bigint"), ("id", "uniqueidentifier"), ("dig", "binary"),
    ("name", "nvarchar"))}
CONFORMANCE = (
    (("card_num",), "", {"rows": 2, "digest": "7c7a8aefb37896f238c53f58c3a3598065e569249511d9cd3e3ec8560ab43910"}),
    (("amt",), "", {"rows": 2, "digest": "d363733f92bcef3396f08d1fdbd2f1389e5d972cd0be3ef5cdacb6ca745edc4f"}),
    (("card_num",), " WHERE 1 = 0", {"rows": 0, "digest": EMPTY_DIGEST}),
)


def conformance_queries() -> list[tuple[str, dict]]:
    columns = tuple(CONFORMANCE_TYPES)
    return [(aggregate_sql(CONFORMANCE_SOURCE, CONFORMANCE_TYPES, columns, order_by,
                           table="conformance", where=where), expected)
            for order_by, where, expected in CONFORMANCE]


def check_conformance(answers: Sequence[Sequence[Mapping]]) -> bool:
    """True only when every conformance query gave its vector."""
    expected = [e for _, _, e in CONFORMANCE]
    if len(answers) != len(expected):
        return False
    try:
        return all(parse_aggregate("conformance", rows) == want for rows, want in zip(answers, expected))
    except Refused:
        return False


def allow_snapshot_sql(db: str) -> str:
    """The provisioning template for I2, only for a private verify-batch database."""
    if not DB_PATTERN.match(db):
        raise Refused("DATABASE_NOT_PRIVATE", "-")
    return f"ALTER DATABASE {quote(db)} SET ALLOW_SNAPSHOT_ISOLATION ON;"


def snapshot_read(sql: str) -> str:
    """A read on the second handle: its own session in SNAPSHOT isolation (I2)."""
    return f"SET NOCOUNT ON; {SNAPSHOT_ISOLATION} {sql}"


def json_query(sql: str, *, snapshot: bool = False) -> str:
    """One SELECT as a single framed NVARCHAR(MAX) line: <UTF-16 units>:<JSON array>.

    The scalar FOR JSON subquery returns one value instead of 2033-character
    chunks, and the unit count lets the parser detect any client truncation.
    """
    text = sql.strip()
    if not re.match(r"SELECT\b", text, re.IGNORECASE) or re.search(r"\bFOR\s+(JSON|XML)\b", text, re.IGNORECASE):
        raise Refused("QUERY_SHAPE", "-")
    body = ("DECLARE @j NVARCHAR(MAX) = (" + text + " FOR JSON PATH, INCLUDE_NULL_VALUES); "
            "SET @j = COALESCE(@j, N'[]'); "
            "SELECT CONCAT(CAST(DATALENGTH(@j) / 2 AS VARCHAR(20)), ':', @j);")
    return snapshot_read(body) if snapshot else "SET NOCOUNT ON; " + body


def parse_json_output(output: str) -> list[dict]:
    """The rows of json_query's single output line; decimals stay exact strings."""
    line = output.strip("\r\n").rstrip(" ")
    match = re.fullmatch(r"([0-9]{1,19}):(.*)", line, re.DOTALL)
    try:
        if not match or len(match[2].encode("utf-16-le")) // 2 != int(match[1]):
            raise Refused("QUERY_OUTPUT", "-")
        rows = json.loads(match[2], parse_float=str)
    except (ValueError, UnicodeError):
        raise Refused("QUERY_OUTPUT", "-") from None
    if not isinstance(rows, list) or not all(isinstance(r, dict) for r in rows):
        raise Refused("QUERY_OUTPUT", "-")
    return rows


def execute_batch(sql: str) -> str:
    """One write batch: XACT_ABORT makes any error roll back and fail it."""
    return f"SET XACT_ABORT ON; SET NOCOUNT ON; {sql.strip()}"
