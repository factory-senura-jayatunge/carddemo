from copy import deepcopy
from datetime import date, timedelta
from decimal import Decimal
from pathlib import Path
import re
import unittest

from modernization.verify.spec_jobs import purge, purge_live
from modernization.verify.spec_jobs.session import Execution
from modernization.verify.spec_jobs.tests.fake import FakeSession, FakeRunning
from modernization.verify.data.oracle import hd
from modernization.verify.data.oracle.live import HD_FILE


class PurgeSession(FakeSession):
    def seed(self):
        sums, details = hd.parse(HD_FILE.read_bytes())
        self.tables["pauth.auth_summary"] = [dict(s, row_version=b"old") for s in sums]
        self.tables["pauth.auth_detail"] = [dict(d, auth_id=i) for i, d in enumerate(details, 1)]
        self.tables.update({"pauth.auth_fraud": [], "pauth.processed_message": [], "pauth.outbox": []})
        self.parameters = []
        self.mutation = None

    def fixture_execute(self, sql):
        super().fixture_execute(sql)
        if "UPDATE pauth.auth_detail" not in sql:
            return
        cutoff = re.search(r"WHEN 1 THEN '([0-9-]+)'", sql)[1]
        later = re.search(r"WHEN 2 THEN '([0-9-]+)'", sql)[1]
        future = re.search(r"WHEN 3 THEN '([0-9-]+)'", sql)[1]
        for i, row in enumerate(self.tables["pauth.auth_detail"], 1):
            row.update(processed_at={1: cutoff, 2: later, 3: future}.get(i, "2022-06-01"), auth_fraud=" ")
            if i <= 8:
                row.update(auth_fraud="F" if i == 4 else " ", auth_resp_code="05" if i == 7 else "00",
                           match_status="M" if i == 6 else "P", card_num="4987654321098765")
        orphan = dict(self.tables["pauth.auth_summary"][0], acct_id="87361920451",
                      credit_balance=5, cash_balance=0, approved_auth_cnt=1, declined_auth_cnt=1,
                      approved_auth_amt=3, declined_auth_amt=4)
        self.tables["pauth.auth_summary"].append(orphan)
        self.tables["pauth.auth_fraud"] = [dict(auth_id=4, protected="fraud")]
        self.tables["pauth.processed_message"] = [dict(msg_key=str(i), received_at=d, reply_body="private")
                                                  for i, d in enumerate((cutoff, later), 1)]
        self.tables["pauth.outbox"] = [dict(outbox_id=i, created_at=d, sent_at=sent, body="private")
                                     for i, (d, sent) in enumerate(((cutoff, cutoff), (later, later), (cutoff, None)), 1)]

    def query(self, sql):
        if "SYSUTCDATETIME()" in sql:
            return [{"utc_date": "2026-11-11"}]
        if "FROM batch.cycle" in sql:
            return [{"status": "COMPLETED"}]
        if "FROM ops.batch_window" in sql:
            return []
        table = re.search(r"FROM (pauth\.[a-z_]+)", sql)[1]
        rows = deepcopy(self.tables[table])
        if table == "pauth.auth_summary":
            return [{**{c: r[c] for c in purge_live.SUMMARY_COLUMNS}, "_row_version": r["row_version"]} for r in rows]
        columns = {"pauth.auth_detail": ("auth_id", "acct_id", "processed_at", "auth_fraud", "auth_resp_code",
                                         "approved_amt", "transaction_amt", "match_status"),
                   "pauth.processed_message": ("msg_key", "received_at"),
                   "pauth.outbox": ("outbox_id", "created_at", "sent_at")}[table]
        return [{c: r[c] for c in columns} for r in rows]

    def launch(self, job, parameters):
        self.parameters.append(dict(parameters))
        expiry = parameters.get("expiryDays", 5)
        try:
            if expiry < 1:
                raise ValueError
            cutoff = date.fromisoformat(parameters["businessDate"]) - timedelta(days=expiry)
        except (ValueError, OverflowError):
            return FakeRunning(Execution("FAILED", exit_description="INVALID_JOB_PARAMETERS"))
        summaries = self.tables["pauth.auth_summary"]
        details = self.tables["pauth.auth_detail"]
        ctx = dict(summariesRead=len(summaries), detailsRead=len(details), summariesDeleted=0, detailsDeleted=0,
                   summariesDeletedWithNonZeroTotals=0, fraudDetailsKept=0, creditReleased="0")
        released = Decimal(0)
        for summary in list(summaries):
            touched = False
            for detail in list(details):
                if detail["acct_id"] != summary["acct_id"] or date.fromisoformat(detail["processed_at"][:10]) > cutoff:
                    continue
                if detail["auth_fraud"] == "F":
                    ctx["fraudDetailsKept"] += 1
                    continue
                approved = detail["auth_resp_code"] == "00"
                cnt, amt = (("approved_auth_cnt", "approved_auth_amt") if approved else
                            ("declined_auth_cnt", "declined_auth_amt"))
                summary[cnt] -= 1
                summary[amt] = Decimal(str(summary[amt])) - detail["approved_amt" if approved else "transaction_amt"]
                if approved and detail["match_status"] == "P":
                    summary["credit_balance"] = Decimal(str(summary["credit_balance"])) - detail["approved_amt"]
                    released += detail["approved_amt"]
                details.remove(detail)
                ctx["detailsDeleted"] += 1
                touched = True
            if not any(d["acct_id"] == summary["acct_id"] for d in details):
                ctx["summariesDeletedWithNonZeroTotals"] += int(any(
                    Decimal(str(summary[c])) != 0 for c in (*purge.TOTALS, "credit_balance", "cash_balance")))
                summaries.remove(summary)
                ctx["summariesDeleted"] += 1
            elif touched:
                summary["row_version"] = b"new"
        ctx["creditReleased"] = str(released)
        capped = min(cutoff, date(2026, 11, 11) - timedelta(days=expiry))
        messages = self.tables["pauth.processed_message"]
        outbox = self.tables["pauth.outbox"]
        msgs = [r for r in messages if date.fromisoformat(r["received_at"][:10]) <= capped]
        outs = [r for r in outbox if r["sent_at"] is not None and date.fromisoformat(r["created_at"][:10]) <= capped]
        for rows, removed in ((messages, msgs), (outbox, outs)):
            for row in removed:
                rows.remove(row)
        msgctx = dict(processedMessagesDeleted=len(msgs), outboxRowsDeleted=len(outs),
                      messagesBefore=(capped + timedelta(days=1)).isoformat() + "T00:00")
        if self.mutation == "count":
            ctx["detailsRead"] += 1
        elif self.mutation == "kept" and details:
            details[0]["merchant_name"] = "defect"
        return FakeRunning(Execution("COMPLETED", contexts={"purge": ctx, "purgeMessages": msgctx}))


class PurgeLiveTest(unittest.TestCase):
    def test_summary_version_excluded_only_for_expected_updates(self):
        before = purge.Result(
            {"updated": {"_row_version": "old"}, "untouched": {"_row_version": "old"}},
            {1: {"acct_id": "updated"}, 2: {"acct_id": "untouched"}}, {}, {}, {})
        wanted = deepcopy(before)
        del wanted.details[1]
        actual = deepcopy(wanted)
        actual.summaries["updated"]["_row_version"] = "new"
        purge_live.exclude_updated_metadata(before, wanted, actual)
        self.assertEqual(wanted, actual)
        self.assertNotIn("_row_version", actual.summaries["updated"])
        self.assertEqual(actual.summaries["untouched"]["_row_version"], "old")
        actual.summaries["untouched"]["_row_version"] = "defect"
        self.assertNotEqual(wanted, actual)

    def test_missing_or_ambiguous_totals_never_pass(self):
        values = {**dict.fromkeys(purge_live.PUBLIC_CONTEXT_KEYS, 0), "fraudDetailsKept": 0, "creditReleased": "0"}
        messages = dict(processedMessagesDeleted=0, outboxRowsDeleted=0, messagesBefore="2022-07-02T00:00")
        actual = purge_live.totals(Execution("COMPLETED", contexts={"purge": values, "purgeMessages": messages}))
        self.assertEqual(actual["credit_released"], 0)
        for contexts in ({}, {"purge": {}}, {"wrong_step": values}):
            with self.assertRaises(ValueError):
                purge_live.totals(Execution("COMPLETED", contexts=contexts))

    def test_all_live_purge_cases_both_modes(self):
        from modernization.verify.spec_jobs.runner import CASES
        for mode in ("legacy", "fixed"):
            for case in CASES["PURGE"]:
                with self.subTest(mode=mode, case=case):
                    session = PurgeSession(Path("/unused"))
                    session.seed()
                    problems, _ = purge_live.run(session, case, mode)
                    self.assertFalse(problems, "\n".join(map(str, problems)))
                    if case == "default":
                        self.assertNotIn("expiryDays", session.parameters[0])
                    if case.startswith("interval_"):
                        self.assertEqual(session.parameters[0]["commitInterval"], int(case.split("_")[1]))

    def test_live_counter_and_preserved_detail_mutations(self):
        for mutation in ("count", "kept"):
            session = PurgeSession(Path("/unused"))
            session.seed()
            session.mutation = mutation
            problems, _ = purge_live.run(session, "edges", "fixed")
            self.assertTrue(problems)

    def test_counter_boundary_literal_and_underflow(self):
        before = purge.Result({}, {}, {1: {"received_at": "2022-07-01"}}, {}, {})
        wanted = purge.expected({}, {}, before.messages, {}, business_date="2022-07-06",
                                database_utc_date="2022-07-06")
        counted = purge_live.expected_counters(before, wanted, "2022-07-06", "2022-07-06", 5)
        self.assertEqual(counted.totals["messages_before"], "2022-07-02T00:00")
        self.assertEqual(counted.totals["processed_messages_deleted"], 1)
        wanted = purge.expected({}, {}, before.messages, {}, business_date="2022-07-06",
                                database_utc_date="0001-01-01")
        counted = purge_live.expected_counters(before, wanted, "2022-07-06", "0001-01-01", 5)
        self.assertEqual(counted.totals["messages_before"], "none")
        self.assertEqual(counted.totals["processed_messages_deleted"], 0)


if __name__ == "__main__":
    unittest.main()
