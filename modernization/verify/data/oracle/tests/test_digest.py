from copy import deepcopy
from datetime import date, datetime, time
from decimal import Decimal
import hashlib
import unittest

from modernization.verify.data.oracle import digest
from modernization.verify.data.oracle.digest import Column
from modernization.verify.spec_jobs.common import InvalidFixture


TYPES = dict(card_num=Column("char", 16), acct_id=Column("char", 11),
             amt=Column("decimal", scale=2), ts=Column("datetime2", scale=6),
             proc_dt=Column("date"), flag=Column("bit"), note=Column("varchar", 10),
             seq=Column("bigint"), id=Column("uniqueidentifier"), dig=Column("binary", 4),
             name=Column("nvarchar", 10))
ROWS = [
    dict(card_num="1111222233334444", acct_id="00000000011", amt=Decimal("-12.50"),
         ts=datetime(2022, 7, 6, 10, 15, 30, 123456), proc_dt=date(2022, 7, 6),
         flag=True, note=None, seq=42, id="6f9619ff-8b86-d011-b42d-00c04fc964ff",
         dig=bytes.fromhex("DEADBEEF"), name="Zoë"),
    dict(card_num="1111222233335555", acct_id="00000000012", amt=Decimal("0.05"),
         ts=datetime(2022, 7, 7), proc_dt=date(2022, 7, 7), flag=False, note="",
         seq=-7, id=None, dig=None, name="")]


class DigestTest(unittest.TestCase):
    def test_contract_field_vectors(self):
        vectors = [
            ("AB", Column("char", 4), "01000000084100420020002000"),
            ("", Column("varchar", 10), "0100000000"),
            (None, Column("varchar", 10), "00"),
            ("Zoë", Column("nvarchar", 10), "01000000065A006F00EB00"),
            (Decimal("-12.50"), Column("decimal", scale=2), "010000000C2D00310032002E0035003000"),
            (Decimal("0.05"), Column("decimal", scale=2), "010000000830002E0030003500"),
            (7, Column("numeric"), "01000000023700"),
            (-5, Column("int"), "01000000042D003500"),
            (date(2022, 7, 6), Column("date"), "010000001432003000320032002D00300037002D0030003600"),
            (datetime(2022, 7, 6, 10, 15, 30, 120000), Column("datetime2", scale=3),
             "010000003632003000320032002D00300037002D00300036002000310030003A00310035003A00330030002E003100320030003000300030003000"),
            (time(10, 15, 30), Column("time"),
             "010000003631003900300030002D00300031002D00300031002000310030003A00310035003A00330030002E003000300030003000300030003000"),
            (True, Column("bit"), "01000000023100"),
            ("6f9619ff-8b86-d011-b42d-00c04fc964ff", Column("uniqueidentifier"),
             "0100000048360046003900360031003900460046002D0038004200380036002D0044003000310031002D0042003400320044002D00300030004300300034004600430039003600340046004600"),
            (bytes.fromhex("DEADBEEF"), Column("binary", 4), "0100000004DEADBEEF")]
        for number, (value, column, wanted) in enumerate(vectors, 1):
            actual = digest.frame(digest.value_bytes(value, column))
            self.assertTrue(actual == bytes.fromhex(wanted), f"FIELD_VECTOR_{number}")

    def test_contract_row_hash_vectors(self):
        expected = ("D8FF0886AD3BEA770F4716E003EDF533460995CA7B73864EC559D09A867F8353",
                    "91F3091E100F7F8632AB98796F39A82D4CBF2964B7F2A264DD3A290CA1463953")
        for number, (row, wanted) in enumerate(zip(ROWS, expected), 1):
            actual = hashlib.sha256(digest.row_bytes(row, tuple(TYPES), TYPES)).hexdigest()
            self.assertTrue(actual == wanted.lower(), f"ROW_VECTOR_{number}")

    def test_contract_aggregate_order_vectors(self):
        for key, wanted in (
            ("card_num", "7c7a8aefb37896f238c53f58c3a3598065e569249511d9cd3e3ec8560ab43910"),
            ("amt", "d363733f92bcef3396f08d1fdbd2f1389e5d972cd0be3ef5cdacb6ca745edc4f")):
            actual = digest.aggregate(ROWS, types=TYPES, columns=tuple(TYPES), order_by=(key,))
            self.assertTrue(actual == {"rows": 2, "digest": wanted}, "AGGREGATE_VECTOR_" + key)
            reverse = digest.aggregate(reversed(ROWS), types=TYPES, columns=tuple(TYPES), order_by=(key,))
            self.assertTrue(actual == reverse, "INPUT_ORDER_INDEPENDENCE")

    def test_contract_empty_vector(self):
        actual = digest.aggregate([], types=TYPES, columns=tuple(TYPES), order_by=("card_num",))
        self.assertTrue(actual == {"rows": 0, "digest":
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"}, "EMPTY_VECTOR")

    def test_masked_byte_and_each_column_mutations(self):
        expected = digest.aggregate(ROWS, types=TYPES, columns=tuple(TYPES), order_by=("card_num",))
        for column in TYPES:
            rows = deepcopy(ROWS)
            rows[0][column] = None
            if column == "note":
                rows[0][column] = ""
            actual = digest.aggregate(rows, types=TYPES, columns=tuple(TYPES), order_by=("card_num",))
            self.assertTrue(actual != expected, "COLUMN_MUTATION_" + column)
        rows = deepcopy(ROWS)
        rows[0]["card_num"] = "1111222233334445"
        self.assertTrue(digest.aggregate(rows, types=TYPES, columns=tuple(TYPES),
                                         order_by=("card_num",)) != expected, "MASKED_BYTE_MUTATION")

    def test_framed_key_ties_and_key_outside_columns(self):
        rows = [dict(k="same", a="ab", b="c"), dict(k="same", a="a", b="bc")]
        types = dict.fromkeys(("k", "a", "b"), Column("nvarchar"))
        actual = digest.aggregate(rows, types=types, columns=("a", "b"), order_by=("k",))
        self.assertTrue(actual == digest.aggregate(reversed(rows), types=types,
                                                   columns=("a", "b"), order_by=("k",)), "KEY_TIE")
        self.assertTrue(digest.row_bytes(rows[0], ("a", "b"), types) !=
                        digest.row_bytes(rows[1], ("a", "b"), types), "FRAME_AMBIGUITY")

    def test_padding_negative_zero_and_100ns(self):
        self.assertTrue(digest.value_bytes("A", Column("nchar", 2)) == b"A\0 \0")
        self.assertTrue(digest.value_bytes(b"A", Column("binary", 2)) == b"A\0")
        self.assertTrue(digest.value_bytes(Decimal("-0.00"), Column("decimal", scale=2)) == "0.00".encode("utf-16-le"))
        self.assertTrue(digest.value_bytes("0001-01-01 00:00:00.1234567", Column("datetime2", scale=7)) ==
                        "0001-01-01 00:00:00.1234567".encode("utf-16-le"))
        self.assertTrue(digest.value_bytes("01:02:03.1234567", Column("time", scale=7)) ==
                        "1900-01-01 01:02:03.1234567".encode("utf-16-le"))

    def test_unsupported_types_and_cvv_are_refused(self):
        for kind in ("float", "xml", "datetime"):
            with self.assertRaises(InvalidFixture):
                digest.value_bytes(None, Column(kind))
        for columns, order_by in (((), ("card_num",)), (("amt", "amt"), ("card_num",)),
                                   (("cvv_cd",), ("card_num",)), (("amt",), ("cvv_cd",))):
            with self.assertRaises(InvalidFixture):
                digest.aggregate([], types={**TYPES, "cvv_cd": Column("char", 3)},
                                 columns=columns, order_by=order_by)


if __name__ == "__main__":
    unittest.main()
