"""Step counts, statuses and contexts for one job execution."""
from __future__ import annotations

import gzip
import json
import unittest
from pathlib import Path

from modernization.verify.adapter import batchmeta, javaser
from modernization.verify.spec_jobs.session import Execution

CASES = json.loads(gzip.decompress(Path(__file__).with_name("spring_batch_contexts.json.gz").read_bytes()))


def step(step_id, name, status="COMPLETED", context="empty", **counts):
    row = {"STEP_EXECUTION_ID": step_id, "STEP_NAME": name, "STATUS": status,
           **{c: 0 for c in batchmeta.COUNT_COLUMNS}, "SHORT_CONTEXT": CASES[context]["stored"],
           "SERIALIZED_CONTEXT": None}
    row.update(counts)
    return row


class StepsTest(unittest.TestCase):
    def test_latest_step_execution_per_name_wins(self):
        rows = [step(9, "loadAccounts", "COMPLETED", "mixed", READ_COUNT=50, WRITE_COUNT=50),
                step(7, "loadAccounts", "FAILED", "empty", READ_COUNT=3),
                step(8, "loadCustomers", "COMPLETED", "linked", WRITE_COUNT=50)]
        counts, statuses, contexts = batchmeta.steps(rows)
        self.assertEqual(statuses, {"loadAccounts": "COMPLETED", "loadCustomers": "COMPLETED"})
        self.assertEqual(counts["loadAccounts"]["READ_COUNT"], 50)
        self.assertEqual(set(counts["loadAccounts"]), set(batchmeta.COUNT_COLUMNS))
        self.assertEqual(contexts["loadAccounts"]["accountsMigrated"], 44)
        self.assertEqual(contexts["loadCustomers"], {"b": 2, "a": "one"})
        execution = Execution("COMPLETED", step_counts=counts, step_statuses=statuses, contexts=contexts)
        self.assertEqual(execution.step_counts.get("loadCustomers", {}).get("WRITE_COUNT"), 50)

    def test_failed_step_is_reported_as_failed(self):
        _, statuses, _ = batchmeta.steps([step(0, "loadCardXref", "FAILED")])
        self.assertEqual(statuses, {"loadCardXref": "FAILED"})

    def test_long_context_is_read_from_serialized_context(self):
        row = step(4, "big")
        full = CASES["longstring"]["stored"]
        row.update(SHORT_CONTEXT=full[:2492] + " ...", SERIALIZED_CONTEXT=full)
        self.assertEqual(batchmeta.steps([row])[2]["big"]["n"], 1)

    def test_missing_context_row_is_empty(self):
        row = step(4, "noContext")
        row.update(SHORT_CONTEXT=None)
        self.assertEqual(batchmeta.steps([row])[2]["noContext"], {})

    def test_malformed_rows_are_refused(self):
        for change in ({"READ_COUNT": None}, {"WRITE_COUNT": True}, {"FILTER_COUNT": -1},
                       {"STATUS": "DONE"}, {"STEP_NAME": ""}, {"STEP_EXECUTION_ID": "1"},
                       {"STEP_EXECUTION_ID": -1}):
            with self.subTest(change=change):
                row = step(1, "s")
                row.update(change)
                with self.assertRaises(batchmeta.MetadataError):
                    batchmeta.steps([row])
        with self.assertRaises(batchmeta.MetadataError):
            batchmeta.steps([step(1, "a"), step(1, "b")])
        bad = step(1, "s")
        bad["SHORT_CONTEXT"] = "!!"
        with self.assertRaises(javaser.ContextError):
            batchmeta.steps([bad])


class JobTest(unittest.TestCase):
    def row(self, **change):
        row = {"JOB_EXECUTION_ID": 12, "JOB_INSTANCE_ID": 3, "STATUS": "FAILED", "EXIT_CODE": "FAILED",
               "EXIT_MESSAGE": "LEASE_LOST", "SHORT_CONTEXT": CASES["mixed"]["stored"], "SERIALIZED_CONTEXT": None}
        row.update(change)
        return row

    def test_job_row(self):
        job = batchmeta.job([self.row()], 12)
        self.assertEqual((job["status"], job["execution_id"], job["job_instance_id"], job["exit_description"]),
                         ("FAILED", 12, 3, "LEASE_LOST"))
        self.assertEqual(job["job_context"]["acctdataSha256"], "c" * 64)
        self.assertEqual(batchmeta.job([self.row(EXIT_MESSAGE=None)], 12)["exit_description"], "")

    def test_job_row_must_be_this_execution(self):
        self.assertEqual(batchmeta.job([self.row(JOB_EXECUTION_ID=2 ** 63 - 1)], 2 ** 63 - 1)["execution_id"], 2 ** 63 - 1)
        for rows, wanted in (([self.row()], 13), ([], 12), ([self.row(), self.row()], 12),
                             ([self.row(STATUS=None)], 12), ([self.row(JOB_INSTANCE_ID=None)], 12),
                             ([self.row(JOB_EXECUTION_ID=True)], 1), ([self.row(JOB_EXECUTION_ID=12.0)], 12)):
            with self.assertRaises(batchmeta.MetadataError):
                batchmeta.job(rows, wanted)


class SqlTest(unittest.TestCase):
    def test_queries_name_only_this_execution(self):
        self.assertIn("WHERE s.JOB_EXECUTION_ID = 0 ORDER BY s.STEP_EXECUTION_ID", batchmeta.steps_sql(0))
        self.assertIn("FROM batch.BATCH_STEP_EXECUTION s", batchmeta.steps_sql(5))
        self.assertIn("WHERE e.JOB_EXECUTION_ID = 42", batchmeta.job_sql(42))
        # Load report contract 1.4: up to Long.MAX_VALUE.
        self.assertIn("WHERE s.JOB_EXECUTION_ID = 9223372036854775807 ", batchmeta.steps_sql(2 ** 63 - 1))
        self.assertIn("WHERE e.JOB_EXECUTION_ID = 9223372036854775807", batchmeta.job_sql(2 ** 63 - 1))
        for bad in (True, "5", -1, 2 ** 63, 1.0, None):
            with self.subTest(bad=bad), self.assertRaises(batchmeta.MetadataError):
                batchmeta.steps_sql(bad)
            with self.assertRaises(batchmeta.MetadataError):
                batchmeta.job_sql(bad)


if __name__ == "__main__":
    unittest.main()
