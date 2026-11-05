"""tests/oracle.py helpers that model COBOL storage rules."""
from __future__ import annotations

import unittest
from decimal import Decimal

from tests.oracle import WS_TEMP_BAL_DIGITS, cobol_store


class CobolStoreTest(unittest.TestCase):
    def test_values_that_fit_are_unchanged(self):
        for v in ("0.00", "12.34", "-45.67", "999999999.99", "-999999999.99"):
            self.assertEqual(cobol_store(Decimal(v), 9), Decimal(v))

    def test_high_order_digits_are_dropped_and_sign_kept(self):
        # Values seen from CBTRN02C when WS-TEMP-BAL was S9(09)V99:
        # 12.34 + 999999999.99 was stored as 12.33, and a later payment of
        # -45.67 against a cycle credit of 1000000012.33 as 999999966.66.
        self.assertEqual(cobol_store(Decimal("1000000012.33"), 9), Decimal("12.33"))
        self.assertEqual(cobol_store(Decimal("999999966.66"), 9), Decimal("999999966.66"))
        self.assertEqual(cobol_store(Decimal("-1000000000.01"), 9), Decimal("-0.01"))

    def test_extra_decimals_are_truncated_not_rounded(self):
        self.assertEqual(cobol_store(Decimal("1.239"), 9), Decimal("1.23"))
        self.assertEqual(cobol_store(Decimal("-1.239"), 9), Decimal("-1.23"))

    def test_widened_temp_balance_holds_every_possible_value(self):
        largest = Decimal("9999999999.99") * 2 + Decimal("999999999.99")
        self.assertEqual(cobol_store(largest, WS_TEMP_BAL_DIGITS), largest)
        self.assertEqual(cobol_store(-largest, WS_TEMP_BAL_DIGITS), -largest)


if __name__ == "__main__":
    unittest.main()
