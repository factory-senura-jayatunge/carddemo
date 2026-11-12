from copy import deepcopy
from dataclasses import replace
from decimal import Decimal
import unittest

from modernization.verify.spec_jobs import purge
from modernization.verify.spec_jobs.common import InvalidFixture


class PurgeTest(unittest.TestCase):
    def setUp(self):
        self.summaries = {"a": dict(acct_id="a", approved_auth_cnt=3, approved_auth_amt="30",
                          declined_auth_cnt=2, declined_auth_amt="15", credit_balance="50", other="kept"),
                          "b": dict(acct_id="b", approved_auth_cnt=5, approved_auth_amt="100",
                          declined_auth_cnt=0, declined_auth_amt="0", credit_balance="100", other="kept")}
        base = dict(acct_id="a", processed_at="2022-12-28T23:59:59.999", auth_fraud=" ",
                    auth_resp_code="00", approved_amt="10", transaction_amt="8", match_status="P", other="kept")
        self.details = {1: base, 2: {**base, "match_status": "M"},
                        3: {**base, "auth_fraud": "F"},
                        4: {**base, "auth_resp_code": "05"},
                        5: {**base, "auth_resp_code": "05", "transaction_amt": "7"},
                        6: {**base, "processed_at": "2022-12-29"},
                        7: {**base, "processed_at": "2023-01-03"}}
        self.messages = {1: dict(received_at="2022-12-28"), 2: dict(received_at="2022-12-29")}
        self.outbox = {1: dict(created_at="2022-12-28", sent_at="2022-12-28"),
                       2: dict(created_at="2022-12-28", sent_at=None),
                       3: dict(created_at="2022-12-29", sent_at="2022-12-29")}

    def result(self, **kwargs):
        params = dict(business_date="2023-01-02", database_utc_date="2023-01-02")
        params.update(kwargs)
        return purge.expected(self.summaries, self.details, self.messages, self.outbox, **params)

    def test_independent_edge_totals(self):
        result = self.result()
        self.assertEqual(set(result.details), {3, 6, 7})
        self.assertEqual(set(result.summaries), {"a"})
        self.assertEqual(result.summaries["a"]["approved_auth_cnt"], 1)
        self.assertEqual(result.summaries["a"]["approved_auth_amt"], Decimal("10"))
        self.assertEqual(result.summaries["a"]["credit_balance"], Decimal("40"))
        self.assertEqual(result.summaries["a"]["declined_auth_cnt"], 0)
        self.assertEqual(result.summaries["a"]["declined_auth_amt"], 0)
        self.assertEqual(result.totals, dict(summaries_read=2, details_read=7, summaries_deleted=1,
                                           details_deleted=4, summaries_nonzero_deleted=1))
        self.assertEqual(set(result.messages), {2})
        self.assertEqual(set(result.outbox), {2, 3})
        self.assertEqual(result.details[3], self.details[3])
        self.assertEqual(self.summaries["a"]["approved_auth_cnt"], 3)

    def test_clock_caps_message_deletion(self):
        self.assertEqual(self.result(database_utc_date="2023-01-01").messages, self.messages)
        self.assertEqual(self.result(database_utc_date="0001-01-01").messages, self.messages)

    def test_nonzero_summary_counter_checks_all_six_values(self):
        for column in (*purge.TOTALS, "credit_balance", "cash_balance"):
            row = dict.fromkeys((*purge.TOTALS, "credit_balance", "cash_balance"), 0)
            row[column] = 1
            result = purge.expected({"account": row}, {}, {}, {}, business_date="2023-01-02",
                                    database_utc_date="2023-01-02")
            self.assertEqual(result.totals["summaries_nonzero_deleted"], 1, column)

    def test_default_five_and_one(self):
        self.assertEqual(self.result(), self.result(expiry_days=5))
        self.assertNotEqual(self.result().totals, self.result(expiry_days=1).totals)

    def test_invalid_params(self):
        for value in (0, -1, 10**10, True, "5"):
            with self.assertRaises(InvalidFixture):
                self.result(expiry_days=value)

    def test_every_summary_and_detail_column_mutation(self):
        wanted = self.result()
        for name in ("summaries", "details", "messages", "outbox", "totals"):
            source = getattr(wanted, name)
            for key in source:
                actual = deepcopy(source)
                if isinstance(actual[key], dict):
                    for column in actual[key]:
                        changed = deepcopy(source)
                        changed[key][column] = "defect"
                        self.assertTrue(purge.validate("edges", wanted, replace(wanted, **{name: changed}),
                                        status="COMPLETED", fraud_before={}, fraud_after={}))
                else:
                    actual[key] += 1
                    self.assertTrue(purge.validate("edges", wanted, replace(wanted, **{name: actual}),
                                    status="COMPLETED", fraud_before={}, fraud_after={}))

    def test_deletion_mutations(self):
        wanted = self.result()
        for name in ("summaries", "details", "messages", "outbox"):
            self.assertTrue(purge.validate("edges", wanted, replace(wanted, **{name: {}}),
                            status="COMPLETED", fraud_before={}, fraud_after={}))

    def test_rerun(self):
        first = self.result()
        second = purge.expected(first.summaries, first.details, first.messages, first.outbox,
                                business_date="2023-01-02", database_utc_date="2023-01-02")
        self.assertEqual(second.summaries, first.summaries)
        self.assertEqual(second.details, first.details)
        self.assertEqual(second.totals, dict(summaries_read=1, details_read=3, summaries_deleted=0,
                                           details_deleted=0, summaries_nonzero_deleted=0))

    def test_failures_and_fraud(self):
        wanted = self.result()
        for params in (dict(status="FAILED"), dict(fraud_after={1: "changed"}), dict(window_open=True),
                       dict(cycle_completed=False)):
            args = dict(status="COMPLETED", fraud_before={}, fraud_after={})
            args.update(params)
            self.assertTrue(purge.validate("edges", wanted, wanted, **args))

    def test_invalid_failure_validation(self):
        self.assertFalse(purge.validate_invalid("params", status="FAILED", exit_description="INVALID_JOB_PARAMETERS",
                                               before={}, after={}))
        for status, description, after in (("COMPLETED", "INVALID_PARAMETERS", {}),
                                          ("FAILED", "other", {}), ("FAILED", "INVALID_PARAMETERS", {1: "changed"})):
            self.assertTrue(purge.validate_invalid("params", status=status, exit_description=description,
                                                  before={}, after=after))


if __name__ == "__main__":
    unittest.main()
