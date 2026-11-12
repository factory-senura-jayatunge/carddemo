"""Tests of verify-migrations' schema fingerprint: python3 -m unittest discover -s modernization/scripts/tests

A fake catalog stands in for SQL Server: the column and database lines are
built by evaluating the CONCAT arguments of FINGERPRINT_SQL itself over fake
sys.columns, sys.masked_columns and sys.databases rows, then compared the way
main() compares the fresh and the upgraded database."""
from __future__ import annotations

import os
import re
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import verify_migrations as vm  # noqa: E402


def concat_args(kind: str) -> list[str]:
    """The CONCAT arguments of the INSERT that builds the '<kind>|' lines."""
    m = re.search(r"INSERT @r SELECT CONCAT\(('" + kind + r"\|'.*?)\)\n  FROM", vm.FINGERPRINT_SQL, re.S)
    if not m:
        raise AssertionError(f"no {kind} line in FINGERPRINT_SQL")
    return [a.strip() for a in m.group(1).split(",")]


def concat(args: list[str], row: dict) -> str:
    out = []
    for a in args:
        if a.startswith("'"):
            out.append(a[1:-1])
        else:
            v = row[a]  # a KeyError means the fake catalog lacks a fingerprinted property
            out.append("" if v is None else str(int(v)) if isinstance(v, bool) else str(v))
    return "".join(out)


def column(table: str, name: str, column_id: int, **props) -> dict:
    """A sys.columns row of a user table, with its default and computed definition."""
    schema, obj = table.split(".")
    row = {"s.name": schema, "o.name": obj, "c.name": name, "ty.name": "char", "c.max_length": 16,
           "c.precision": 0, "c.scale": 0, "c.is_nullable": False, "c.collation_name": "Latin1_General_100_BIN2",
           "c.is_identity": False, "dc.definition": None, "cc.definition": None, "c.column_id": column_id,
           "c.is_masked": False}
    row.update({f"c.{k}": v for k, v in props.items()})
    return row


def fingerprint(columns: list[dict], masked: dict[tuple[str, str], str], db: dict) -> list[str]:
    """The col and db lines; masked maps (table, column) to its masking_function
    (sys.masked_columns, joined on the column)."""
    col_args, db_args = concat_args("col"), concat_args("db")
    lines = []
    for c in columns:
        key = (f"{c['s.name']}.{c['o.name']}", c["c.name"])
        row = dict(c, **{"mc.masking_function": masked.get(key)})
        if key in masked:
            row["c.is_masked"] = True
        lines.append(concat(col_args, row))
    lines.append(concat(db_args, db))
    return lines


# As sys.masked_columns shows them.
CARD = "partial(0, \"XXXXXXXXXXXX\", 4)"
DB = {"d.collation_name": "Latin1_General_100_CI_AS_SC_UTF8", "d.is_read_committed_snapshot_on": True,
      "d.snapshot_isolation_state_desc": "ON"}
COLUMNS = [column("core.card", "card_num", 1), column("core.card", "cvv_cd", 2, max_length=3),
           column("core.customer", "ssn", 3, max_length=9)]
MASKS = {("core.card", "card_num"): CARD, ("core.card", "cvv_cd"): "default()",
         ("core.customer", "ssn"): "partial(0, \"XXXXX\", 4)"}


class FingerprintTest(unittest.TestCase):
    def test_the_same_catalog_has_no_difference(self):
        self.assertEqual(vm.compare(fingerprint(COLUMNS, MASKS, DB), fingerprint(COLUMNS, dict(MASKS), dict(DB))), [])

    def test_a_different_masking_function_alone_is_a_difference(self):
        upgraded = {**MASKS, ("core.card", "cvv_cd"): "partial(0, \"XX\", 1)"}
        diff = vm.compare(fingerprint(COLUMNS, MASKS, DB), fingerprint(COLUMNS, upgraded, DB))
        self.assertEqual(len(diff), 2)
        self.assertTrue(diff[0].startswith("only fresh:    col|core.card.cvv_cd|"))
        self.assertTrue(diff[1].startswith("only upgraded: col|core.card.cvv_cd|"))

    def test_a_dropped_mask_alone_is_a_difference(self):
        upgraded = {k: v for k, v in MASKS.items() if k != ("core.customer", "ssn")}
        diff = vm.compare(fingerprint(COLUMNS, MASKS, DB), fingerprint(COLUMNS, upgraded, DB))
        self.assertEqual([d.split("|")[1] for d in diff], ["core.customer.ssn", "core.customer.ssn"])

    def test_the_database_row_versioning_alone_is_a_difference(self):
        upgraded = dict(DB, **{"d.is_read_committed_snapshot_on": False})
        diff = vm.compare(fingerprint(COLUMNS, MASKS, DB), fingerprint(COLUMNS, MASKS, upgraded))
        self.assertEqual([d.split("|")[0] for d in diff], ["only fresh:    db", "only upgraded: db"])

    def test_the_column_line_has_every_column_field(self):
        args = concat_args("col")
        for _label, expr in vm.COLUMN_FIELDS:
            self.assertIn(expr, args)
        self.assertIn("LEFT JOIN sys.masked_columns mc ON mc.object_id = c.object_id AND mc.column_id = c.column_id",
                      vm.FINGERPRINT_SQL)

    def test_every_catalog_property_of_the_schema_check_is_fingerprinted(self):
        """Columns (type, length, collation, nullability, default, mask), indexes with
        their columns and filters, check and default constraints, foreign keys,
        view definitions, object and column permissions."""
        col = concat_args("col")
        for expr in ("ty.name", "c.max_length", "c.collation_name", "c.is_nullable", "dc.definition",
                     "c.is_masked", "mc.masking_function"):
            self.assertIn(expr, col)
        sql = vm.FINGERPRINT_SQL
        for fragment in ("'idx|'", "i.filter_definition", "sys.index_columns", "'chk|'", "k.definition",
                         "'dfname|'", "'fk|'", "sys.foreign_key_columns", "HASHBYTES('SHA2_256', m.definition)",
                         "'V'", "'perm|'", "COL_NAME(p.major_id, p.minor_id)", "'db|'"):
            self.assertIn(fragment, sql)


if __name__ == "__main__":
    unittest.main()
