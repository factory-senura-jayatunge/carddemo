"""Tests of make trace: python3 -m unittest discover -s modernization/verify/trace/tests"""
from __future__ import annotations

import io
import json
import shutil
import sys
import tempfile
import textwrap
import unittest
import unittest.mock
from pathlib import Path

HERE = Path(__file__).resolve().parent
TRACE = HERE.parent
sys.path.insert(0, str(TRACE))

import trace_matrix as trace_cli  # noqa: E402
from tracelib import ledger, matrix, reports, spec  # noqa: E402

REPO = TRACE.parents[2]


def make_spec() -> spec.Spec:
    s = spec.Spec(Path("."))

    def item(ident, fidelity):
        kind, unit, _ = ident.split("-")
        s.items[ident] = spec.Item(ident, kind, unit, "X", f"docs/modernization/spec/programs/{unit}.md", 1,
                                   f"title of {ident}", fidelity, spec.classes_of(fidelity))

    item("BR-BILL-001", "must-preserve")
    item("FLOW-BILL-001", "")
    item("DEF-BILL-001", "defect-decide")
    item("DEF-BILL-009", "defect-decide")
    item("DEF-BILL-010", "defect-decide")
    item("DEF-BILL-011", "incidental")
    item("BR-MENU-002", "must-preserve")
    item("BR-TRNA-003", "must-preserve; defect-decide")
    item("BR-POST-001", "must-preserve")
    s.questions = {"Q-BILL-001": "x"}
    s.invariants = {"INV-002"}
    s.proposed_invariants = {"PINV-g-jobs-ops-03"}
    s.units = {"BILL": "b", "MENU": "m", "TRNA": "t", "POST": "p"}
    return s


def make_ledger(units=("BILL", "MENU")) -> ledger.Ledger:
    led = ledger.Ledger(Path("."))
    led.switches = {
        "DEF-BILL-009": {"value": "legacy", "code_switch": False},
        "DEF-BILL-010": {"value": "fixed", "code_switch": True},
        "BR-TRNA-003": {"value": "fixed", "code_switch": True},
        "Q-BILL-001": {"value": "legacy", "code_switch": False},
    }
    led.deviations = {"DEF-BILL-001": {"kind": "architecture-dependent", "target": "one transaction"}}
    led.units = {u: {"spec": "x"} for u in units}
    led.units.get("MENU", {})["excluded"] = {"BR-MENU-002": "not converted"}
    return led


def r(name, items, status="passed", mode=None):
    return reports.Result(name, "t", status, mode, list(items))


ALL_PASSING = [
    r("a", ["BR-BILL-001", "FLOW-BILL-001"]),
    r("b", ["DEF-BILL-009"]),
    r("c", ["DEF-BILL-010"], mode="LEGACY"),
    r("c", ["DEF-BILL-010"], mode="FIXED"),
    r("d", ["BR-TRNA-003"], mode="LEGACY"),
    r("d", ["BR-TRNA-003"], mode="FIXED"),
]


class GateTest(unittest.TestCase):
    def build(self, results, arch=frozenset({"DEF-BILL-001"}), led=None):
        return matrix.build(make_spec(), led or make_ledger(), results, set(arch))

    def gaps(self, results, **kw):
        return dict(self.build(results, **kw).gaps)

    def test_everything_covered(self):
        m = self.build(ALL_PASSING)
        self.assertEqual(m.gaps, [])
        self.assertEqual(m.rows["DEF-BILL-001"].status, "deviation")
        self.assertEqual(m.rows["DEF-BILL-011"].status, "not-required")
        self.assertEqual(m.rows["BR-MENU-002"].status, "excluded")
        self.assertEqual(m.rows["BR-POST-001"].status, "out-of-scope")
        self.assertEqual(m.rows["Q-BILL-001"].status, "not-required")
        self.assertEqual(m.rows["BR-BILL-001"].status, "covered")

    def test_must_preserve_and_unclassified_need_a_passing_test(self):
        gaps = self.gaps(ALL_PASSING[1:])
        self.assertEqual(gaps["BR-BILL-001"], "no passing test")
        self.assertEqual(gaps["FLOW-BILL-001"], "no passing test")

    def test_skipped_test_does_not_count(self):
        gaps = self.gaps([r("a", ["BR-BILL-001", "FLOW-BILL-001"], status="skipped")] + ALL_PASSING[1:])
        self.assertIn("BR-BILL-001", gaps)

    def test_failing_test_is_a_gap_even_with_a_passing_one(self):
        gaps = self.gaps(ALL_PASSING + [r("e", ["BR-BILL-001"], status="failed")])
        self.assertEqual(gaps, {"BR-BILL-001": "1 failing test"})

    def test_code_switch_needs_both_modes(self):
        gaps = self.gaps([t for t in ALL_PASSING if not (t.name == "c" and t.mode == "FIXED")])
        self.assertEqual(gaps, {"DEF-BILL-010": "no passing test in fixed mode"})

    def test_code_switch_outside_scope_still_needs_both_modes(self):
        gaps = self.gaps([t for t in ALL_PASSING if t.name != "d"])
        self.assertEqual(gaps, {"BR-TRNA-003": "no passing test in legacy or fixed mode"})

    def test_decided_item_needs_a_test_in_the_ledger_mode(self):
        results = [t for t in ALL_PASSING if t.name != "b"] + [r("b", ["DEF-BILL-009"], mode="FIXED")]
        self.assertEqual(self.gaps(results), {"DEF-BILL-009": "no passing test in legacy mode"})

    def test_undecided_defect_decide_item_needs_both_modes(self):
        led = make_ledger()
        del led.switches["DEF-BILL-009"]
        gaps = self.gaps(ALL_PASSING, led=led)
        self.assertEqual(gaps, {"DEF-BILL-009": "no passing test in legacy or fixed mode"})

    def test_architecture_item_needs_a_deviation(self):
        led = make_ledger()
        led.deviations = {}
        results = ALL_PASSING + [r("f", ["DEF-BILL-001"], mode=m) for m in ("LEGACY", "FIXED")]
        self.assertEqual(self.gaps(results, led=led), {"DEF-BILL-001": "architecture-dependent item without a deviation"})

    def test_unknown_and_malformed_ids(self):
        gaps = self.gaps(ALL_PASSING + [r("g", ["BR-BILL-999", "PINV-001", "PINV-g-jobs-ops-03", "INV-002"])])
        self.assertEqual(set(gaps), {"BR-BILL-999", "PINV-001"})
        self.assertIn("not defined by the spec (g)", gaps["BR-BILL-999"])

    def test_no_scope_only_code_switches(self):
        self.assertEqual(self.gaps(ALL_PASSING, led=make_ledger(units=())), {})

    def test_item_covered_by_a_switch_needs_both_modes_from_the_switch_tests(self):
        s = make_spec()
        s.items["FLOW-POST-001"] = spec.Item("FLOW-POST-001", "FLOW", "POST", "X", "p.md", 1, "t",
                                             "must-preserve (defect-decide, BR-TRNA-003)", spec.classes_of("must-preserve (defect-decide, BR-TRNA-003)"))
        led = make_ledger()
        led.switches["FLOW-POST-001"] = {"value": "fixed", "code_switch": False, "note": "Covered by the switch BR-TRNA-003: tests cite both."}
        both = [t for t in ALL_PASSING if t.name != "d"] + [r("d", ["BR-TRNA-003", "FLOW-POST-001"], mode=m) for m in ("LEGACY", "FIXED")]
        m = matrix.build(s, led, both, {"DEF-BILL-001"})
        self.assertEqual(m.gaps, [])
        self.assertEqual((m.rows["FLOW-POST-001"].requirement, m.rows["FLOW-POST-001"].status), ("both-modes", "covered"))
        fixed_only = [t for t in ALL_PASSING if t.name != "d"] + [r("d", ["BR-TRNA-003"], mode=m) for m in ("LEGACY", "FIXED")] \
            + [r("e", ["FLOW-POST-001"], mode="FIXED")]
        gaps = dict(matrix.build(s, led, fixed_only, {"DEF-BILL-001"}).gaps)
        self.assertEqual(gaps, {"FLOW-POST-001": "no passing test in legacy mode (covered by the switch BR-TRNA-003: its tests must cite this item too)"})

    def test_decided_fix_without_switch_needs_only_its_mode(self):
        led = make_ledger()
        led.switches["DEF-BILL-009"] = {"value": "fixed", "code_switch": False}
        results = [t for t in ALL_PASSING if t.name != "b"] + [r("b", ["DEF-BILL-009"], mode="FIXED")]
        self.assertEqual(self.gaps(results, led=led), {})
        self.assertEqual(self.build(results, led=led).rows["DEF-BILL-009"].requirement, "ledger-mode:FIXED")

    def test_unit_selection_gates_only_those_units(self):
        results = [t for t in ALL_PASSING if t.name not in ("a", "d")]
        m = matrix.build(make_spec(), make_ledger(), results, {"DEF-BILL-001"}, ["BILL"])
        self.assertEqual(dict(m.gaps), {"BR-BILL-001": "no passing test", "FLOW-BILL-001": "no passing test"})
        self.assertEqual(dict(m.ungated), {"BR-TRNA-003": "no passing test in legacy or fixed mode"})
        self.assertEqual(m.gate_units, ["BILL"])
        m = matrix.build(make_spec(), make_ledger(), results, {"DEF-BILL-001"}, ["MENU"])
        self.assertTrue(m.ok)
        self.assertEqual({g for g, _ in m.ungated}, {"BR-BILL-001", "FLOW-BILL-001", "BR-TRNA-003"})
        self.assertEqual(m.rows["BR-BILL-001"].status, "gap")

    def test_unit_selection_keeps_unknown_ids_of_the_unit(self):
        results = ALL_PASSING + [r("g", ["BR-BILL-999"]), r("h", ["PINV-001", "BR-BILL-001"]), r("i", ["DEF-POST-999"])]
        m = matrix.build(make_spec(), make_ledger(), results, {"DEF-BILL-001"}, ["BILL"])
        self.assertEqual({g for g, _ in m.gaps}, {"BR-BILL-999", "PINV-001"})
        self.assertEqual({g for g, _ in m.ungated}, {"DEF-POST-999"})
        m = matrix.build(make_spec(), make_ledger(), results, {"DEF-BILL-001"}, ["MENU"])
        self.assertEqual(m.gaps, [])


class ReportsTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp)

    def write(self, rel, body):
        p = self.tmp / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(body, encoding="utf-8")
        return p

    def read(self, p):
        results, problems = reports.read(p, self.tmp)
        self.assertEqual(problems, [])
        return [(x.name, x.status, x.mode, x.items) for x in results]

    def test_spec_items_jsonl(self):
        p = self.write("services/billpay-service/target/spec-items/com.carddemo.BillPayTest.jsonl", "\n".join(json.dumps(x) for x in [
            {"class": "com.carddemo.BillPayTest", "method": "pays", "displayName": "[1] LEGACY", "items": ["DEF-BILL-010", "SE-BILL-001"], "status": "SUCCESSFUL", "mode": "LEGACY"},
            {"class": "com.carddemo.BillPayTest", "method": "pays", "displayName": "[2] FIXED", "items": ["DEF-BILL-010"], "status": "FAILED", "mode": "FIXED"},
            {"class": "com.carddemo.BillPayTest", "method": "blank", "displayName": "blank()", "items": ["VAL-BILL-001"], "status": "DISABLED", "mode": None},
        ]) + "\n")
        self.assertEqual(self.read(p), [
            ("com.carddemo.BillPayTest#pays [1] LEGACY", "passed", "LEGACY", ["DEF-BILL-010", "SE-BILL-001"]),
            ("com.carddemo.BillPayTest#pays [2] FIXED", "failed", "FIXED", ["DEF-BILL-010"]),
            ("com.carddemo.BillPayTest#blank", "skipped", None, ["VAL-BILL-001"]),
        ])

    def test_junit_xml_properties_and_title_tags(self):
        p = self.write("web/test-results/vitest-junit.xml", textwrap.dedent('''\
            <?xml version="1.0" encoding="UTF-8"?>
            <testsuites>
              <testsuite name="bill-pay">
                <testcase classname="BillPay.test.tsx" name="locks the account ID @DEF-BILL-014 @fixed"/>
                <testcase classname="BillPay.test.tsx" name="shows the header @FLOW-BILL-009 @NUM-BILL-004">
                  <failure message="x"/>
                </testcase>
                <testcase classname="BillPay.test.tsx" name="no tags here"/>
                <testcase classname="test_dags" name="test_order">
                  <properties><property name="spec" value="INV-052, PINV-g-jobs-ops-03"/><property name="mode" value="legacy"/></properties>
                  <skipped/>
                </testcase>
              </testsuite>
            </testsuites>
            '''))
        self.assertEqual(self.read(p), [
            ("BillPay.test.tsx#locks the account ID @DEF-BILL-014 @fixed", "passed", "FIXED", ["DEF-BILL-014"]),
            ("BillPay.test.tsx#shows the header @FLOW-BILL-009 @NUM-BILL-004", "failed", None, ["FLOW-BILL-009", "NUM-BILL-004"]),
            ("test_dags#test_order", "skipped", "LEGACY", ["INV-052", "PINV-g-jobs-ops-03"]),
        ])

    def test_playwright_json(self):
        data = {"suites": [{"title": "bill-pay.spec.ts", "file": "bill-pay.spec.ts", "specs": [], "suites": [
            {"title": "BILL @VAL-BILL-001", "specs": [
                {"title": "S5 shows the balance @NUM-BILL-001 @legacy", "file": "bill-pay.spec.ts", "tags": ["@DEF-BILL-014"],
                 "tests": [{"projectName": "chromium", "status": "expected", "results": [{"status": "passed"}]}]},
                {"title": "S3 blank", "file": "bill-pay.spec.ts", "tags": [],
                 "tests": [{"projectName": "chromium", "results": [{"status": "timedOut"}]}]},
            ]}]}]}
        p = self.write("web/test-results/playwright.json", json.dumps(data))
        self.assertEqual(self.read(p), [
            ("bill-pay.spec.ts: BILL @VAL-BILL-001 › S5 shows the balance @NUM-BILL-001 @legacy [chromium]", "passed", "LEGACY",
             ["VAL-BILL-001", "NUM-BILL-001", "DEF-BILL-014"]),
            ("bill-pay.spec.ts: BILL @VAL-BILL-001 › S3 blank [chromium]", "failed", None, ["VAL-BILL-001"]),
        ])

    def test_bracketed_ids_are_not_counted_but_kept(self):
        p = self.write("web/test-results/vitest-junit.xml", textwrap.dedent('''\
            <?xml version="1.0" encoding="UTF-8"?>
            <testsuites>
              <testsuite name="bill-pay">
                <testcase classname="BillPay.test.tsx" name="[FLOW-BILL-002] shows the header"/>
                <testcase classname="BillPay.test.tsx" name="BILL-S5 fixed: locks the ID [DEF-BILL-014, NUM-BILL-001] @DEF-BILL-014"/>
                <testcase classname="BillPay.test.tsx" name="[1] LEGACY @VAL-BILL-001"/>
              </testsuite>
            </testsuites>
            '''))
        results, problems = reports.read(p, self.tmp)
        self.assertEqual(problems, [])
        self.assertEqual([(x.items, x.uncounted, x.mode) for x in results], [
            ([], ["FLOW-BILL-002"], None),
            (["DEF-BILL-014"], ["NUM-BILL-001"], None),
            (["VAL-BILL-001"], [], None),
        ])
        data = {"suites": [{"title": "bill-pay.spec.ts", "file": "bill-pay.spec.ts", "specs": [
            {"title": "[SE-BILL-001] pays", "file": "bill-pay.spec.ts", "tags": [],
             "tests": [{"projectName": "chromium", "status": "expected"}]}]}]}
        p = self.write("web/test-results/playwright.json", json.dumps(data))
        self.assertEqual([(x.items, x.uncounted) for x in reports.read(p, self.tmp)[0]], [([], ["SE-BILL-001"])])
        p = self.write("svc/target/spec-items/T.jsonl", json.dumps(
            {"class": "T", "method": "m", "displayName": "[DEF-BILL-010] pays", "items": ["DEF-BILL-010"], "status": "SUCCESSFUL"}) + "\n")
        self.assertEqual([(x.items, x.uncounted) for x in reports.read(p, self.tmp)[0]], [(["DEF-BILL-010"], [])])
        m = matrix.build(make_spec(), make_ledger(), ALL_PASSING + reports.read(
            self.tmp / "web/test-results/vitest-junit.xml", self.tmp)[0][:1], {"DEF-BILL-001"})
        self.assertEqual(m.gaps, [])

    def test_discover_skips_node_modules_and_other_dirs(self):
        keep = [self.write("a/target/spec-items/T.jsonl", ""), self.write("build/test-results/backend/TEST-x.xml", "<testsuite/>"),
                self.write("web/test-results/p.json", "{}")]
        self.write("web/node_modules/x/test-results/y.xml", "<testsuite/>")
        self.write("a/target/classes/z.json", "{}")
        self.write("var/run/test-results/v.xml", "<testsuite/>")
        self.assertEqual(sorted(reports.discover(self.tmp)), sorted(keep))

    def test_broken_reports_are_problems(self):
        p = self.write("build/test-results/x.xml", "<testsuite>")
        self.assertTrue(reports.read(p, self.tmp)[1][0].startswith("build/test-results/x.xml: not XML"))
        p = self.write("build/test-results/x.jsonl", "{\n")
        self.assertIn("x.jsonl:1: not JSON", reports.read(p, self.tmp)[1][0])


class CliTest(unittest.TestCase):
    """trace.py on the committed spec and ledger, with report files in a scratch directory."""

    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp)

    def run_cli(self, reports_dir, units=None):
        out = io.StringIO()
        rc = trace_cli.run(REPO, REPO / "modernization/ledger", [reports_dir], self.tmp / "out", out, units)
        return rc, out.getvalue()

    def test_unit_option_gates_one_unit(self):
        (self.tmp / "reports").mkdir()
        _, out_all = self.run_cli(self.tmp / "reports")
        all_gaps = json.loads((self.tmp / "out/matrix.json").read_text(encoding="utf-8"))["gate"]["gaps"]
        out = io.StringIO()
        argv = ["--root", str(REPO), "--reports", str(self.tmp / "reports"), "--out", str(self.tmp / "out"), "--unit", "BILL"]
        with unittest.mock.patch("sys.stdout", out):
            rc = trace_cli.main(argv)
        self.assertEqual(rc, 1)
        data = json.loads((self.tmp / "out/matrix.json").read_text(encoding="utf-8"))
        self.assertEqual(data["gate"]["units"], ["BILL"])
        bill = [g["id"] for g in all_gaps if g["id"].split("-")[1] == "BILL"]
        self.assertEqual([g["id"] for g in data["gate"]["gaps"]], bill)
        self.assertEqual(len(data["gate"]["gaps"]) + len(data["gate"]["ungated_gaps"]), len(all_gaps))
        self.assertIn("BR-TRNA-003", {g["id"] for g in data["gate"]["ungated_gaps"]})
        self.assertEqual(out.getvalue().rstrip().splitlines()[-1], f"trace: FAILED ({len(bill)} gaps)")
        self.assertIn("for the selected units: BILL", (self.tmp / "out/matrix.md").read_text(encoding="utf-8"))
        with unittest.mock.patch("sys.stdout", io.StringIO()):
            self.assertEqual(trace_cli.main(argv + ["--unit", "SGN", "--unit", "MENU"]), 1)
        data = json.loads((self.tmp / "out/matrix.json").read_text(encoding="utf-8"))
        self.assertEqual(data["gate"]["units"], ["BILL", "MENU", "SGN"])
        led = ledger.load(REPO / "modernization/ledger")
        other_switches = {i for i, e in led.switches.items()
                          if (e.get("code_switch") is True or ledger.covering_switch(e)) and i.split("-")[1] not in led.units}
        self.assertIn("BR-TRNA-003", other_switches)
        self.assertEqual({g["id"] for g in data["gate"]["ungated_gaps"]}, other_switches)

    def test_unit_option_rejects_a_unit_outside_the_scope(self):
        (self.tmp / "reports").mkdir()
        rc, out = self.run_cli(self.tmp / "reports", ["POST"])
        self.assertEqual(rc, 1)
        self.assertEqual(out.strip(), "trace: FAILED (--unit POST not in scope.toml; units in scope: BILL, MENU, SGN)")

    def test_bracketed_ids_are_reported(self):
        results = self.tmp / "reports/web/test-results"
        results.mkdir(parents=True)
        (results / "junit.xml").write_text(
            '<testsuite><testcase classname="B" name="[FLOW-BILL-002] header"/>'
            '<testcase classname="B" name="[SE-BILL-001] pays @SE-BILL-001"/></testsuite>', encoding="utf-8")
        rc, out = self.run_cli(self.tmp / "reports", ["BILL"])
        self.assertIn("warning: 1 test carries bracketed IDs that are not counted (cite an item as @<ID>):", out)
        self.assertIn("    B#[FLOW-BILL-002] header: FLOW-BILL-002", out)
        self.assertIn("FLOW-BILL-002: no passing test", out)
        data = json.loads((self.tmp / "out/matrix.json").read_text(encoding="utf-8"))
        self.assertEqual([x["ids"] for x in data["uncounted_bracketed"]], [["FLOW-BILL-002"]])
        self.assertIn("## Bracketed IDs not counted", (self.tmp / "out/matrix.md").read_text(encoding="utf-8"))

    def test_no_reports_fails_and_writes_the_matrix(self):
        (self.tmp / "reports").mkdir()
        rc, out = self.run_cli(self.tmp / "reports")
        self.assertEqual(rc, 1)
        self.assertRegex(out.rstrip().splitlines()[-1], r"^trace: FAILED \(\d+ gaps\)$")
        self.assertIn("DEF-BILL-010: no passing test in legacy or fixed mode", out)
        data = json.loads((self.tmp / "out/matrix.json").read_text(encoding="utf-8"))
        self.assertFalse(data["gate"]["ok"])
        self.assertEqual(data["scope"], ["BILL", "MENU", "SGN"])
        md = (self.tmp / "out/matrix.md").read_text(encoding="utf-8")
        self.assertIn("### BILL", md)
        self.assertIn("| DEF-BILL-001 |", md)

    def test_every_gap_closed_by_reports_passes(self):
        (self.tmp / "reports").mkdir()
        self.run_cli(self.tmp / "reports")
        data = json.loads((self.tmp / "out/matrix.json").read_text(encoding="utf-8"))
        lines = []
        for gap in data["gate"]["gaps"]:
            for mode in ("LEGACY", "FIXED"):
                lines.append(json.dumps({"class": "T", "method": gap["id"], "displayName": mode, "items": [gap["id"]],
                                         "status": "SUCCESSFUL", "mode": mode}))
        spec_items = self.tmp / "reports/svc/target/spec-items"
        spec_items.mkdir(parents=True)
        (spec_items / "T.jsonl").write_text("\n".join(lines) + "\n", encoding="utf-8")
        rc, out = self.run_cli(self.tmp / "reports")
        self.assertEqual(rc, 0, out)
        self.assertEqual(out.rstrip().splitlines()[-1], "trace: OK")
        data = json.loads((self.tmp / "out/matrix.json").read_text(encoding="utf-8"))
        statuses = {i["id"]: i["status"] for i in data["items"]}
        self.assertEqual(statuses["DEF-BILL-001"], "deviation")
        self.assertEqual(statuses["BR-MENU-002"], "excluded")
        self.assertEqual(statuses["DEF-BILL-010"], "covered")


if __name__ == "__main__":
    unittest.main()
