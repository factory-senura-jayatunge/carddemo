"""snapshot_digest SQL text, identifier checks and the query wrappers."""
from __future__ import annotations

import unittest
from datetime import date, datetime
from decimal import Decimal

from modernization.verify.adapter import sqltext as s
from modernization.verify.data.oracle import digest as mirror

C = s.Column


def meta(*columns: C) -> dict:
    return {c.name: c for c in columns}


CARD = meta(C("card_id", "bigint"), C("card_num", "char", masked=True), C("acct_id", "char"),
            C("cvv_cd", "char", masked=True), C("expiry", "date"), C("row_version", "timestamp"))
MESSAGES = meta(C("msg_key", "varbinary"), C("received_at", "datetime2"))


class IdentifierTest(unittest.TestCase):
    def test_only_allowlisted_tables(self):
        self.assertEqual(s.quoted_table("pauth.processed_message"), "[pauth].[processed_message]")
        for bad in ("core.users", "core.card; DROP TABLE x", "dbo.card", "core.card ", "core"):
            with self.subTest(bad=bad), self.assertRaises(s.Refused) as caught:
                s.quoted_table(bad)
            self.assertNotIn("DROP", str(caught.exception))

    def test_quote_refuses_anything_but_plain_identifiers(self):
        self.assertEqual(s.quote("acct_id"), "[acct_id]")
        for bad in ("a]b", "a b", "", "1a", "a" * 129, "a;b", "[a]"):
            with self.subTest(bad=bad), self.assertRaises(s.Refused):
                s.quote(bad)

    def test_columns_sql_names_only_the_allowlisted_table(self):
        sql = s.columns_sql("core.card")
        self.assertIn("c.TABLE_SCHEMA = N'core' AND c.TABLE_NAME = N'card'", sql)
        self.assertIn("HAS_PERMS_BY_NAME(N'core.card', 'OBJECT', 'UNMASK', c.COLUMN_NAME, 'COLUMN')", sql)
        with self.assertRaises(s.Refused):
            s.columns_sql("core.card'--")

    def test_parse_columns(self):
        rows = [{"name": "a", "data_type": "BIGINT", "ordinal": 1, "masked": False, "can_unmask": False},
                {"name": "b", "data_type": "char", "ordinal": 2, "masked": True, "can_unmask": True}]
        parsed = s.parse_columns("core.card", rows)
        self.assertEqual(list(parsed), ["a", "b"])
        self.assertEqual(parsed["a"].data_type, "bigint")
        self.assertTrue(parsed["b"].masked and parsed["b"].can_unmask)
        for broken in ([], [dict(rows[0], name="a b")], [dict(rows[0], masked=None)], [rows[1], rows[0]],
                       [rows[0], dict(rows[1], name="a")], [dict(rows[0], ordinal="1")]):
            with self.subTest(broken=broken), self.assertRaises(s.Refused):
                s.parse_columns("core.card", broken)


class Latin1LiteralTest(unittest.TestCase):
    def test_a_printable_latin1_value_becomes_its_utf16_hex(self):
        self.assertEqual(s.latin1_literal("\u00e9"), "CAST(0xE900 AS NVARCHAR(1))")
        self.assertEqual(s.latin1_literal("A \u00a0\u00ff~"), "CAST(0x41002000A000FF007E00 AS NVARCHAR(5))")
        self.assertEqual(s.latin1_literal(""), "CAST(0x AS NVARCHAR(1))")
        self.assertTrue(s.latin1_literal("\u00e9" * 4000).endswith(" AS NVARCHAR(4000))"))
        self.assertTrue(s.latin1_literal("\u00e9" * 4001).endswith(" AS NVARCHAR(MAX))"))
        every = "".join(map(chr, [*range(0x20, 0x7f), *range(0xa0, 0x100)]))
        literal = s.latin1_literal(every)
        self.assertTrue(literal.isascii())
        self.assertEqual(bytes.fromhex(literal[7:literal.index(" AS ")]).decode("utf-16-le"), every)

    def test_controls_and_characters_outside_latin1_are_refused(self):
        for bad in ("\x00", "\t", "\n", "\r", "\x1f", "a\x7f", "\x80", "\x85", "\x9f", "\u0100", "\u20ac", "\ufeff",
                    "\U0001f600", None, b"abc", 5):
            with self.subTest(bad=bad), self.assertRaises(s.Refused) as caught:
                s.latin1_literal(bad)
            self.assertEqual(caught.exception.code, "LATIN1_VALUE")

class ValueExpressionTest(unittest.TestCase):
    """Section 3 of snapshot-digest v1.1, one row per type."""

    def test_expressions_per_type(self):
        plain = "CONVERT(VARBINARY(MAX), CONVERT(NVARCHAR(MAX), [c]))"
        for t in ("char", "varchar", "nchar", "nvarchar", "decimal", "numeric", "tinyint", "smallint", "int",
                  "bigint", "bit", "uniqueidentifier"):
            self.assertEqual(s.value_expr(C("c", t)), plain, t)
        self.assertEqual(s.value_expr(C("c", "date")), "CONVERT(VARBINARY(MAX), CONVERT(NVARCHAR(MAX), [c], 23))")
        for t in ("datetime2", "time"):
            self.assertEqual(s.value_expr(C("c", t)),
                             "CONVERT(VARBINARY(MAX), CONVERT(NVARCHAR(MAX), CAST([c] AS DATETIME2(7)), 121))")
        for t in ("binary", "varbinary", "rowversion", "timestamp"):
            self.assertEqual(s.value_expr(C("c", t)), "CAST([c] AS VARBINARY(MAX))")

    def test_refused_types(self):
        for t in ("datetime", "smalldatetime", "datetimeoffset", "float", "real", "money", "smallmoney", "xml",
                  "sql_variant", "text", "ntext", "image", "hierarchyid", "geometry", "geography"):
            with self.subTest(t=t), self.assertRaises(s.Refused):
                s.value_expr(C("c", t))

    def test_frame(self):
        v = "CONVERT(VARBINARY(MAX), CONVERT(NVARCHAR(MAX), [n]))"
        self.assertEqual(s.frame_expr(C("n", "int")),
                         "CASE WHEN [n] IS NULL THEN CAST(0x00 AS VARBINARY(MAX)) ELSE CAST(0x01 AS VARBINARY(MAX)) + "
                         f"CAST(CAST(DATALENGTH({v}) AS INT) AS BINARY(4)) + {v} END")


class AggregateTest(unittest.TestCase):
    def test_template_matches_section_8(self):
        f = s.frame_expr(C("acct_id", "char"))
        g = s.frame_expr(C("card_id", "bigint"))
        expected = ("SET NOCOUNT ON;\nWITH r AS (\n"
                    f"    SELECT HASHBYTES('SHA2_256', {f} + {g}) AS row_hash,\n"
                    f"           {g} AS order_key\n"
                    "    FROM [core].[card]\n"
                    "), h AS (\n"
                    "    SELECT CONVERT(VARCHAR(MAX), order_key, 2) COLLATE Latin1_General_BIN2 AS key_hex,\n"
                    "           CONVERT(VARCHAR(MAX), CONVERT(VARCHAR(64), row_hash, 2)) COLLATE Latin1_General_BIN2 "
                    "AS row_hex\n"
                    "    FROM r\n)\n"
                    "SELECT COUNT_BIG(*) AS row_count,\n"
                    "       LOWER(CONVERT(VARCHAR(64), HASHBYTES('SHA2_256',\n"
                    "           COALESCE(CONVERT(VARBINARY(MAX),\n"
                    "                    STRING_AGG(row_hex, '') WITHIN GROUP (ORDER BY key_hex, row_hex), 2), 0x)), "
                    "2)) AS digest\n"
                    "FROM h")
        self.assertEqual(s.table_aggregate_sql("core.card", CARD, ("acct_id", "card_id"), ("card_id",)), expected)

    def test_column_order_is_the_callers(self):
        a = s.table_aggregate_sql("core.card", CARD, ("acct_id", "card_id"), ("card_id",))
        b = s.table_aggregate_sql("core.card", CARD, ("card_id", "acct_id"), ("card_id",))
        self.assertNotEqual(a, b)
        self.assertLess(a.index("[acct_id]"), a.index("[card_id]"))

    def test_masked_columns_are_allowed_but_cvv_never(self):
        self.assertIn("[card_num]", s.table_aggregate_sql("core.card", CARD, ("card_num",), ("card_id",)))
        for columns, order in ((("cvv_cd",), ("card_id",)), (("card_id",), ("cvv_cd",)),
                               (("CVV_CD",), ("card_id",))):
            with self.assertRaises(s.Refused) as caught:
                s.table_aggregate_sql("core.card", meta(*CARD.values(), C("CVV_CD", "char")), columns, order)
            self.assertEqual(caught.exception.code, "CVV_EXCLUDED")

    def test_refusals(self):
        bad_type = meta(C("id", "bigint"), C("when", "datetime"))
        for columns, order, table_meta, code in (
                ((), ("card_id",), CARD, "COLUMN_LIST_EMPTY"), (("card_id",), (), CARD, "COLUMN_LIST_EMPTY"),
                (("nope",), ("card_id",), CARD, "UNKNOWN_COLUMN"),
                (("card_id", "card_id"), ("card_id",), CARD, "DUPLICATE_COLUMN"),
                (("when",), ("id",), bad_type, "UNSUPPORTED_TYPE")):
            with self.subTest(code=code), self.assertRaises(s.Refused) as caught:
                s.table_aggregate_sql("core.card", table_meta, columns, order)
            self.assertEqual(caught.exception.code, code)

    def test_parse_aggregate(self):
        digest = "a" * 64
        self.assertEqual(s.parse_aggregate("t", [{"row_count": 3, "digest": digest}]), {"rows": 3, "digest": digest})
        self.assertEqual(s.parse_aggregate("t", [{"row_count": 0, "digest": s.EMPTY_DIGEST}])["rows"], 0)
        for rows in ([], [{"row_count": 0, "digest": digest}], [{"row_count": 1, "digest": digest.upper()}],
                     [{"row_count": True, "digest": digest}], [{"row_count": 1, "digest": digest}] * 2,
                     [{"row_count": "1", "digest": digest}]):
            with self.subTest(rows=rows), self.assertRaises(s.Refused):
                s.parse_aggregate("t", rows)


class ConformanceTest(unittest.TestCase):
    ROWS = [
        dict(card_num="1111222233334444", acct_id="00000000011", amt=Decimal("-12.50"),
             ts=datetime(2022, 7, 6, 10, 15, 30, 123456), proc_dt=date(2022, 7, 6), flag=True, note=None, seq=42,
             id="6F9619FF-8B86-D011-B42D-00C04FC964FF", dig=b"\xde\xad\xbe\xef", name="Zo\u00eb"),
        dict(card_num="1111222233335555", acct_id="00000000012", amt=Decimal("0.05"),
             ts=datetime(2022, 7, 7), proc_dt=date(2022, 7, 7), flag=False, note="", seq=-7, id=None, dig=None,
             name=""),
    ]
    TYPES = dict(card_num=mirror.Column("char", 16), acct_id=mirror.Column("char", 11),
                 amt=mirror.Column("decimal", scale=2), ts=mirror.Column("datetime2"), proc_dt=mirror.Column("date"),
                 flag=mirror.Column("bit"), note=mirror.Column("varchar", 10), seq=mirror.Column("bigint"),
                 id=mirror.Column("uniqueidentifier"), dig=mirror.Column("binary", 4),
                 name=mirror.Column("nvarchar", 10))

    def test_expected_answers_agree_with_the_independent_mirror(self):
        columns = tuple(s.CONFORMANCE_TYPES)
        self.assertEqual(columns, tuple(self.TYPES))
        for order_by, where, expected in s.CONFORMANCE:
            rows = [] if where else self.ROWS
            self.assertEqual(mirror.aggregate(rows, types=self.TYPES, columns=columns, order_by=order_by), expected)

    def test_queries_use_the_section_9_source(self):
        queries = s.conformance_queries()
        self.assertEqual(len(queries), 3)
        for sql, _ in queries:
            self.assertIn("FROM " + s.CONFORMANCE_SOURCE, sql)
            sql.encode("ascii")
        self.assertIn(s.CONFORMANCE_SOURCE + " WHERE 1 = 0\n", queries[2][0])
        self.assertIn("ORDER BY key_hex", queries[1][0])

    def test_check_conformance(self):
        good = [[{"row_count": e["rows"], "digest": e["digest"]}] for _, e in s.conformance_queries()]
        self.assertTrue(s.check_conformance(good))
        self.assertFalse(s.check_conformance(good[:2]))
        self.assertFalse(s.check_conformance([good[1], good[0], good[2]]))
        self.assertFalse(s.check_conformance(good[:2] + [[]]))


class PerKeyTest(unittest.TestCase):
    def test_binary_keys_come_back_as_uppercase_hex(self):
        sql, hashed = s.per_key_sql("pauth.processed_message", MESSAGES, ("msg_key",))
        self.assertTrue(sql.startswith("SELECT CONVERT(VARCHAR(MAX), [msg_key], 2) AS [k0], LOWER("))
        self.assertTrue(sql.endswith("FROM [pauth].[processed_message]"))
        self.assertEqual(hashed, ("msg_key", "received_at"))
        rows = [{"k0": "DEADBEEF", "h": "0" * 64}, {"k0": "", "h": "1" * 64}]
        self.assertEqual(s.parse_per_key("pauth.processed_message", ("msg_key",), rows),
                         {("DEADBEEF",): "0" * 64, ("",): "1" * 64})

    def test_hashes_every_column_not_excluded_in_ordinal_order(self):
        sql, hashed = s.per_key_sql("core.card", CARD, ("card_id",), ("cvv_cd", "row_version"))
        self.assertEqual(hashed, ("card_id", "card_num", "acct_id", "expiry"))
        self.assertTrue(sql.startswith("SELECT [card_id] AS [k0], "))
        self.assertLess(sql.index("[card_num] IS NULL"), sql.index("[acct_id] IS NULL"))

    def test_refusals(self):
        for keys, exclude, code in ((("card_num",), ("cvv_cd",), "KEY_MASKED"),
                                    (("card_id",), (), "CVV_EXCLUDED"),
                                    (("card_id",), ("cvv_cd", "card_id"), "KEY_EXCLUDED"),
                                    (("card_id",), ("cvv_cd", "nope"), "UNKNOWN_COLUMN"),
                                    (("expiry",), ("cvv_cd",), "KEY_TYPE"),
                                    ((), ("cvv_cd",), "KEY_LIST_EMPTY"),
                                    (("card_id", "card_id"), ("cvv_cd",), "DUPLICATE_COLUMN")):
            with self.subTest(code=code), self.assertRaises(s.Refused) as caught:
                s.per_key_sql("core.card", CARD, keys, exclude)
            self.assertEqual(caught.exception.code, code)

    def test_login_path_refuses_masked_columns_it_cannot_unmask(self):
        with self.assertRaises(s.Refused) as caught:
            s.per_key_sql("core.card", CARD, ("card_id",), ("cvv_cd",), login_path=True)
        self.assertEqual(caught.exception.code, "MASKED_COLUMN_NOT_EXCLUDED")
        self.assertEqual(str(caught.exception), "MASKED_COLUMN_NOT_EXCLUDED: core.card (card_num)")
        s.per_key_sql("core.card", CARD, ("card_id",), ("cvv_cd", "card_num"), login_path=True)
        unmasked = dict(CARD, card_num=C("card_num", "char", masked=True, can_unmask=True))
        s.per_key_sql("core.card", unmasked, ("card_id",), ("cvv_cd",), login_path=True)
        # The admin path digests masked columns without UNMASK checks.
        s.per_key_sql("core.card", CARD, ("card_id",), ("cvv_cd",))

    def test_parse_per_key_refuses_bad_answers(self):
        good = {"k0": 5, "h": "a" * 64}
        self.assertEqual(s.parse_per_key("core.card", ("card_id",), [good]), {(5,): "a" * 64})
        for rows in ([good, good], [{"k0": None, "h": "a" * 64}], [{"k0": 5, "h": "A" * 64}],
                     [{"k0": 5}], [{"k0": 5, "h": "a" * 64, "x": 1}], [{"k0": 1.5, "h": "a" * 64}]):
            with self.subTest(rows=rows), self.assertRaises(s.Refused):
                s.parse_per_key("core.card", ("card_id",), rows)


class WrapperTest(unittest.TestCase):
    def test_snapshot_isolation_only_on_private_databases(self):
        self.assertEqual(s.allow_snapshot_sql("carddemo_verify_batch_x1"),
                         "ALTER DATABASE [carddemo_verify_batch_x1] SET ALLOW_SNAPSHOT_ISOLATION ON;")
        for bad in ("carddemo", "carddemo_e2e", "carddemo_verify_batch]; DROP", "carddemo_verify_batch_X"):
            with self.subTest(bad=bad), self.assertRaises(s.Refused):
                s.allow_snapshot_sql(bad)
        self.assertTrue(s.snapshot_read("SELECT 1").startswith(
            "SET NOCOUNT ON; SET TRANSACTION ISOLATION LEVEL SNAPSHOT; "))

    def test_json_query_shape(self):
        sql = s.json_query("SELECT a FROM t ORDER BY a")
        self.assertIn("(SELECT a FROM t ORDER BY a FOR JSON PATH, INCLUDE_NULL_VALUES)", sql)
        self.assertIn("ISOLATION LEVEL SNAPSHOT", s.json_query("SELECT 1 x", snapshot=True))
        self.assertNotIn("ISOLATION", sql)
        for bad in ("WITH x AS (SELECT 1 a) SELECT a FROM x", "UPDATE t SET a=1", "SELECT 1 FOR JSON PATH",
                    "SELECT 1 FOR XML PATH"):
            with self.subTest(bad=bad), self.assertRaises(s.Refused):
                s.json_query(bad)

    def test_parse_json_output(self):
        body = '[{"amt":12.50,"n":3,"t":"Zo\u00eb \U0001F600","x":null}]'
        units = len(body.encode("utf-16-le")) // 2
        rows = s.parse_json_output(f"{units}:{body}  \n")
        self.assertEqual(rows, [{"amt": "12.50", "n": 3, "t": "Zo\u00eb \U0001F600", "x": None}])
        self.assertEqual(units, len(body) + 1)
        self.assertEqual(s.parse_json_output("2:[]\n"), [])
        for bad in (f"{units}:{body[:-1]}", f"{units + 1}:{body}", "", "2:{}", "4:[1,2]", "x:[]",
                    "2:[]\n2:[]", "3:[\"\udc80\"]"):
            with self.subTest(bad=bad), self.assertRaises(s.Refused):
                s.parse_json_output(bad)

    def test_execute_batch(self):
        self.assertEqual(s.execute_batch(" DELETE FROM t "), "SET XACT_ABORT ON; SET NOCOUNT ON; DELETE FROM t")


if __name__ == "__main__":
    unittest.main()
