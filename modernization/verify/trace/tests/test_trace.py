"""Tests of make trace: python3 -m unittest discover -s modernization/verify/trace/tests"""
from __future__ import annotations

import io
import json
import os
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
from tracelib import code, ledger, matrix, reports, spec  # noqa: E402
from tracelib import manifest as run_manifest  # noqa: E402

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
    def build(self, results, arch=frozenset({"DEF-BILL-001"}), led=None, declared=None):
        return matrix.build(make_spec(), led or make_ledger(), results, set(arch), declared=declared)

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

    @staticmethod
    def with_future_switch(units=("BILL", "MENU")):
        """A switch of an unconverted unit (STMT), an item it covers, and a must-preserve STMT item."""
        s = make_spec()
        for ident, fidelity in (("DEF-STMT-001", "defect-decide"), ("DEF-JSTMT-005", "defect-decide"),
                                ("BR-STMT-001", "must-preserve")):
            kind, unit, _ = ident.split("-")
            s.items[ident] = spec.Item(ident, kind, unit, "X", f"{unit}.md", 1, "t", fidelity, spec.classes_of(fidelity))
        s.units.update({"STMT": "s", "JSTMT": "j"})
        led = make_ledger(units)
        led.switches["DEF-STMT-001"] = {"value": "fixed", "code_switch": True}
        led.switches["DEF-JSTMT-005"] = {"value": "fixed", "code_switch": False, "note": "Covered by the switch DEF-STMT-001."}
        return s, led

    DECLARED = frozenset({"DEF-BILL-010", "BR-TRNA-003"})

    def test_switch_no_code_declares_is_planned_not_gated(self):
        s, led = self.with_future_switch()
        m = matrix.build(s, led, ALL_PASSING, {"DEF-BILL-001"}, declared=set(self.DECLARED))
        self.assertTrue(m.ok, m.gaps)
        self.assertEqual(dict(m.planned), {"DEF-STMT-001": "no passing test in legacy or fixed mode",
                                           "DEF-JSTMT-005": "no passing test in legacy or fixed mode (covered by the switch "
                                                            "DEF-STMT-001: its tests must cite this item too)"})
        self.assertEqual(m.ungated, [])
        self.assertEqual((m.rows["DEF-STMT-001"].status, m.rows["DEF-JSTMT-005"].status), ("planned", "planned"))
        self.assertEqual(m.rows["BR-STMT-001"].status, "out-of-scope")
        self.assertEqual(m.declared, sorted(self.DECLARED))
        # Without the code scan every switch stays gated, as before.
        gaps = dict(matrix.build(s, led, ALL_PASSING, {"DEF-BILL-001"}).gaps)
        self.assertEqual(set(gaps), {"DEF-STMT-001", "DEF-JSTMT-005"})

    def test_declared_switch_of_another_unit_is_gated_with_the_items_it_covers(self):
        s, led = self.with_future_switch()
        declared = set(self.DECLARED) | {"DEF-STMT-001"}
        m = matrix.build(s, led, ALL_PASSING, {"DEF-BILL-001"}, declared=declared)
        self.assertEqual(set(dict(m.gaps)), {"DEF-STMT-001", "DEF-JSTMT-005"})
        self.assertEqual(m.planned, [])
        both = ALL_PASSING + [r("s", ["DEF-STMT-001", "DEF-JSTMT-005"], mode=x) for x in ("LEGACY", "FIXED")]
        self.assertTrue(matrix.build(s, led, both, {"DEF-BILL-001"}, declared=declared).ok)
        # Only the covered item's own switch counts: a test of the switch that leaves the item out is not enough.
        switch_only = ALL_PASSING + [r("s", ["DEF-STMT-001"], mode=x) for x in ("LEGACY", "FIXED")]
        self.assertEqual(set(dict(matrix.build(s, led, switch_only, {"DEF-BILL-001"}, declared=declared).gaps)),
                         {"DEF-JSTMT-005"})

    def test_mixed_binding_of_another_unit_needs_both_modes_and_passing_tests(self):
        """BR-TRNA-003: a switch of TRNA that BILL code declares and BILL tests exercise."""
        declared = set(self.DECLARED)
        no_trna = [t for t in ALL_PASSING if t.name != "d"]
        self.assertEqual(dict(self.build(no_trna, declared=declared).gaps), {"BR-TRNA-003": "no passing test in legacy or fixed mode"})
        fixed_only = no_trna + [r("d", ["BR-TRNA-003", "BR-BILL-001"], mode="FIXED")]
        self.assertEqual(dict(self.build(fixed_only, declared=declared).gaps), {"BR-TRNA-003": "no passing test in legacy mode"})
        failing = ALL_PASSING + [r("e", ["BR-TRNA-003", "BR-BILL-001"], status="failed", mode="LEGACY")]
        self.assertEqual(dict(self.build(failing, declared=declared).gaps), {"BR-TRNA-003": "1 failing test", "BR-BILL-001": "1 failing test"})
        # With no declaration and no test citing it, the same switch is planned.
        m = self.build(no_trna, declared={"DEF-BILL-010"})
        self.assertTrue(m.ok)
        self.assertEqual([g for g, _ in m.planned], ["BR-TRNA-003"])

    def test_test_citing_a_switch_makes_it_live(self):
        s, led = self.with_future_switch()
        fixed_only = ALL_PASSING + [r("s", ["DEF-STMT-001"], mode="FIXED")]
        gaps = dict(matrix.build(s, led, fixed_only, {"DEF-BILL-001"}, declared=set(self.DECLARED)).gaps)
        self.assertEqual(gaps, {"DEF-STMT-001": "no passing test in legacy mode",
                                "DEF-JSTMT-005": "no passing test in legacy or fixed mode (covered by the switch "
                                                 "DEF-STMT-001: its tests must cite this item too)"})
        failing = ALL_PASSING + [r("s", ["DEF-JSTMT-005"], status="failed")]
        gaps = dict(matrix.build(s, led, failing, {"DEF-BILL-001"}, declared=set(self.DECLARED)).gaps)
        self.assertEqual(gaps, {"DEF-JSTMT-005": "1 failing test"})

    def test_unit_entering_scope_gates_its_missing_items(self):
        s, led = self.with_future_switch(units=("BILL", "MENU", "STMT"))
        m = matrix.build(s, led, ALL_PASSING, {"DEF-BILL-001"}, declared=set(self.DECLARED))
        self.assertEqual(dict(m.gaps), {
            "BR-STMT-001": "no passing test",
            "DEF-STMT-001": "no passing test in legacy or fixed mode",
            # Covered by a switch of a unit in scope, so gated although its own unit is not.
            "DEF-JSTMT-005": "no passing test in legacy or fixed mode (covered by the switch DEF-STMT-001: its tests must cite this item too)",
        })
        self.assertEqual(m.planned, [])

    def test_switch_in_scope_is_gated_even_when_no_code_declares_it(self):
        gaps = dict(self.build([t for t in ALL_PASSING if t.name != "c"], declared=set()).gaps)
        self.assertEqual(gaps, {"DEF-BILL-010": "no passing test in legacy or fixed mode"})

    def test_in_scope_failures_and_missing_citations_still_gate_with_the_code_scan(self):
        declared = set(self.DECLARED)
        self.assertEqual(dict(self.build(ALL_PASSING + [r("e", ["FLOW-BILL-001"], status="failed")], declared=declared).gaps),
                         {"FLOW-BILL-001": "1 failing test"})
        self.assertEqual(dict(self.build(ALL_PASSING[1:], declared=declared).gaps),
                         {"BR-BILL-001": "no passing test", "FLOW-BILL-001": "no passing test"})
        self.assertEqual(dict(self.build(ALL_PASSING[1:], declared=declared, led=make_ledger(("MENU",))).gaps), {})

    def test_unit_selection_lists_planned_switches_apart(self):
        s, led = self.with_future_switch()
        results = [t for t in ALL_PASSING if t.name not in ("a", "d")]
        m = matrix.build(s, led, results, {"DEF-BILL-001"}, ["MENU"], declared=set(self.DECLARED))
        self.assertTrue(m.ok)
        self.assertEqual({g for g, _ in m.ungated}, {"BR-BILL-001", "FLOW-BILL-001", "BR-TRNA-003"})
        self.assertEqual({g for g, _ in m.planned}, {"DEF-STMT-001", "DEF-JSTMT-005"})

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
        # A report directory given itself, as a run manifest or --reports names it.
        self.assertEqual(reports.discover(self.tmp / "a/target/spec-items"), [keep[0]])
        self.assertEqual(reports.discover(self.tmp / "a/target"), [keep[0]])

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
        ungated = {g["id"] for g in data["gate"]["ungated_gaps"]}
        planned = {g["id"] for g in data["gate"]["planned"]}
        self.assertEqual(ungated | planned, other_switches)
        self.assertEqual(ungated & planned, set())
        self.assertIn("BR-TRNA-003", ungated)

    def test_every_other_unit_switch_is_gated_or_planned_by_the_code(self):
        """One rule for every code switch outside scope.toml: gated when the code declares it, else planned."""
        (self.tmp / "reports").mkdir()
        rc, out = self.run_cli(self.tmp / "reports")
        self.assertEqual(rc, 1)
        data = json.loads((self.tmp / "out/matrix.json").read_text(encoding="utf-8"))
        led = ledger.load(REPO / "modernization/ledger")
        scanned = code.scan(REPO / "modernization", REPO)
        # A checkout without the Java code (a ledger-only branch) takes the fail-closed path.
        declared = set(scanned.switches) if scanned.files else None
        self.assertEqual(data["gate"]["declared_switches"], sorted(declared) if declared is not None else None)
        gaps = {g["id"] for g in data["gate"]["gaps"]}
        planned = {g["id"] for g in data["gate"]["planned"]}
        self.assertEqual(gaps & planned, set())
        for ident, e in led.switches.items():
            if ident.split("-")[1] in led.units or not (e.get("code_switch") is True or ledger.covering_switch(e)):
                continue
            owner = ident if e.get("code_switch") is True else ledger.covering_switch(e)
            with self.subTest(ident):
                self.assertIn(ident, gaps if declared is None or owner in declared else planned)
        self.assertLessEqual({"BR-TRNA-003", "DEF-ACUP-002"}, gaps)
        if declared is None:
            self.assertEqual(planned, set())
            return
        self.assertLessEqual({"BR-TRNA-003", "DEF-ACUP-002"}, declared)
        for g in gaps:
            owner = ledger.covering_switch(led.switches.get(g)) or g
            self.assertTrue(g.split("-")[1] in led.units or owner in declared or g.startswith(("INV-", "PINV-")), g)
        self.assertIn("planned code switch", out)
        md = (self.tmp / "out/matrix.md").read_text(encoding="utf-8")
        self.assertIn("## Planned code switches (not gated, not delivered)", md)

    def test_no_java_found_gates_every_switch(self):
        (self.tmp / "reports").mkdir()
        with unittest.mock.patch.object(trace_cli.code, "scan", return_value=code.Code()):
            rc, _ = self.run_cli(self.tmp / "reports")
        self.assertEqual(rc, 1)
        data = json.loads((self.tmp / "out/matrix.json").read_text(encoding="utf-8"))
        self.assertEqual(data["gate"]["planned"], [])
        self.assertIsNone(data["gate"]["declared_switches"])
        self.assertIn("DEF-STMT-001", {g["id"] for g in data["gate"]["gaps"]})

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
        # A green gate still lists the planned switches, and never as covered.
        planned = {g["id"] for g in data["gate"]["planned"]}
        self.assertEqual(bool(planned), data["gate"]["declared_switches"] is not None)
        self.assertEqual({statuses[i] for i in planned}, {"planned"} if planned else set())


class ManifestTest(unittest.TestCase):
    """trace --manifest: only the reports one run wrote count for its gate."""

    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp)
        self.reports = self.tmp / "reports"
        self.reports.mkdir()
        self.items = self.reports / "billpay/target/spec-items"
        self.manifest = self.tmp / "run/manifest.json"

    def run_cli(self, manifest=True, units=("BILL",)):
        out = io.StringIO()
        rc = trace_cli.run(REPO, REPO / "modernization/ledger", [self.reports], self.tmp / "out", out,
                           list(units) if units else None, self.manifest if manifest else None)
        return rc, out.getvalue()

    def bill_lines(self, status="SUCCESSFUL"):
        """A spec-items report that closes every BILL gap of the committed spec and ledger."""
        rc = trace_cli.run(REPO, REPO / "modernization/ledger", [self.tmp / "none"], self.tmp / "probe",
                           io.StringIO(), ["BILL"])
        self.assertEqual(rc, 1)
        gaps = json.loads((self.tmp / "probe/matrix.json").read_text(encoding="utf-8"))["gate"]["gaps"]
        return "\n".join(json.dumps({"class": "T", "method": g["id"], "displayName": mode, "items": [g["id"]],
                                     "status": status, "mode": mode})
                         for g in gaps for mode in ("LEGACY", "FIXED")) + "\n"

    def write(self, path, body, after_ns=None):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(body, encoding="utf-8")
        if after_ns is not None:
            # Set explicitly: some kernels stamp files from a coarse clock that can lag time.time_ns().
            os.utime(path, ns=(after_ns, after_ns))
        return path

    def begin(self, entries):
        return run_manifest.begin(self.manifest, self.tmp, "test-billpay",
                                  [(str(p), req) for p, req in entries], base="reports")

    def started(self):
        return json.loads(self.manifest.read_text(encoding="utf-8"))["started_ns"]

    def failing_firefox_report(self):
        data = {"suites": [{"title": "bill.spec.ts", "file": "bill.spec.ts", "specs": [
            {"title": "@BR-BILL-001 BILL-S5 pays", "file": "bill.spec.ts", "tags": [],
             "tests": [{"projectName": "firefox", "status": "unexpected", "results": [{"status": "failed"}]}]}]}]}
        return self.write(self.reports / "web/playwright-report/e2e-stack-fixed-firefox.json", json.dumps(data))

    def test_run_reads_only_its_reports_and_lists_the_others(self):
        lines = self.bill_lines()
        firefox = self.failing_firefox_report()
        self.begin([(self.items, True)])
        self.write(self.items / "T.jsonl", lines, self.started() + 10**9)
        rc, out = self.run_cli()
        self.assertEqual(rc, 0, out)
        self.assertIn("warning: 1 report outside this run has failing or unreadable results", out)
        self.assertIn("web/playwright-report/e2e-stack-fixed-firefox.json: 1 failed", out)
        data = json.loads((self.tmp / "out/matrix.json").read_text(encoding="utf-8"))
        self.assertEqual(data["reports"], [str(self.items / "T.jsonl")])
        self.assertEqual([(o["path"], o["failed"]) for o in data["run"]["outside"]], [(str(firefox), 1)])
        md = (self.tmp / "out/matrix.md").read_text(encoding="utf-8")
        self.assertIn("## Reports of this run", md)
        self.assertIn("## Reports outside this run (not read)", md)
        # Without a manifest every report counts, so the failing browser run keeps the gate red.
        rc, out = self.run_cli(manifest=False)
        self.assertEqual(rc, 1)
        self.assertIn("BR-BILL-001: 1 failing test", out)

    def test_failing_test_in_a_current_report_is_a_gap(self):
        lines = self.bill_lines()
        self.begin([(self.items, True)])
        self.write(self.items / "T.jsonl", lines, self.started() + 10**9)
        self.write(self.items / "U.jsonl", json.dumps({"class": "U", "method": "m", "displayName": "m()",
                                                       "items": ["BR-BILL-001"], "status": "FAILED"}) + "\n",
                   self.started() + 10**9)
        rc, out = self.run_cli()
        self.assertEqual(rc, 1)
        self.assertIn("BR-BILL-001: 1 failing test", out)

    def test_stale_file_from_the_same_second_is_not_current(self):
        lines = self.bill_lines()
        report = self.write(self.items / "T.jsonl", lines)
        self.begin([(self.items, True)])
        data = json.loads(self.manifest.read_text(encoding="utf-8"))
        started = (data["started_ns"] // 10**9) * 10**9 + 500_000_000
        data["started_ns"] = started
        self.manifest.write_text(json.dumps(data), encoding="utf-8")
        # Written 1 ns before the run began, in the same second, and changed since the fingerprint:
        # a whole-second comparison would take it for the run's own report.
        self.write(report, lines + "\n", started - 1)
        self.assertEqual(report.stat().st_mtime_ns // 10**9, started // 10**9)
        rc, out = self.run_cli()
        self.assertEqual(rc, 1)
        self.assertIn("report: required report", out)
        self.assertIn("is stale", out)
        data = json.loads((self.tmp / "out/matrix.json").read_text(encoding="utf-8"))
        self.assertEqual(data["reports"], [])
        self.assertEqual(data["run"]["reports"][0]["stale"], [str(report)])

    def test_unchanged_file_is_stale_even_when_its_time_looks_current(self):
        lines = self.bill_lines()
        report = self.write(self.items / "T.jsonl", lines)
        self.begin([(self.items, True)])
        data = json.loads(self.manifest.read_text(encoding="utf-8"))
        # A clock that runs behind the file server: the file looks newer than the start, but it is
        # exactly the file the run found when it began.
        data["started_ns"] = report.stat().st_mtime_ns - 1
        self.manifest.write_text(json.dumps(data), encoding="utf-8")
        rc, out = self.run_cli()
        self.assertEqual(rc, 1)
        self.assertIn("is stale", out)
        self.write(report, lines, report.stat().st_mtime_ns)
        self.write(report, lines + "\n", report.stat().st_mtime_ns + 1)
        rc, out = self.run_cli()
        self.assertEqual(rc, 0, out)

    def test_mixed_directory_reads_only_the_files_the_run_wrote(self):
        lines = self.bill_lines()
        old = self.write(self.items / "Old.jsonl", json.dumps({"class": "Old", "method": "m", "displayName": "m()",
                                                               "items": ["BR-BILL-001"], "status": "FAILED"}) + "\n")
        self.begin([(self.items, True)])
        new = self.write(self.items / "T.jsonl", lines, self.started() + 10**9)
        rc, out = self.run_cli()
        self.assertEqual(rc, 0, out)
        data = json.loads((self.tmp / "out/matrix.json").read_text(encoding="utf-8"))
        self.assertEqual(data["reports"], [str(new)])
        self.assertEqual(data["run"]["reports"][0]["stale"], [str(old)])
        self.assertEqual(data["run"]["outside"], [])

    def test_missing_empty_and_uncited_required_reports_are_gaps(self):
        lines = self.bill_lines()
        empty_dir = self.reports / "vitest/test-results"
        empty_dir.mkdir(parents=True)
        uncited = self.reports / "web/playwright-report/e2e-mock-fixed.json"
        self.begin([(self.items, True), (self.reports / "web/playwright-report/e2e-stack-fixed.json", True),
                    (empty_dir, True), (uncited, True), (self.reports / "optional/spec-items", False)])
        self.write(self.items / "T.jsonl", lines, self.started() + 10**9)
        self.write(uncited, json.dumps({"suites": []}), self.started() + 10**9)
        rc, out = self.run_cli()
        self.assertEqual(rc, 1)
        self.assertIn("e2e-stack-fixed.json of test-billpay is missing", out)
        self.assertIn("vitest/test-results of test-billpay holds no report file", out)
        self.assertIn("e2e-mock-fixed.json of test-billpay has no test result citing an item", out)
        self.assertNotIn("optional", out)
        self.assertEqual(out.rstrip().splitlines()[-1], "trace: FAILED (3 gaps)")

    def test_invalid_manifest_fails_closed(self):
        cases = [
            ("{", "not JSON"),
            ("[]", "not a JSON object"),
            (json.dumps({"schema": 1, "run": "r", "started_ns": 1.5, "reports": [{"path": "x", "required": True}]}),
             "started_ns must be"),
            (json.dumps({"schema": 1, "run": "r", "started_ns": 5, "reports": []}), "reports must be a non-empty list"),
            (json.dumps({"schema": 1, "run": "r", "started_ns": 5, "reports": [{"path": "x", "required": "yes"}]}),
             "required must be true or false"),
            (json.dumps({"schema": 1, "run": "r", "started_ns": 5, "extra": 1, "reports": [{"path": "x", "required": True}]}),
             "unknown field 'extra'"),
            (json.dumps({"schema": 1, "run": "r", "started_ns": 5,
                         "reports": [{"path": "x", "required": True, "before": [{"file": "a"}]}]}),
             "before[1] must have exactly file, mtime_ns, size and sha256"),
        ]
        self.manifest.parent.mkdir(parents=True)
        for body, message in cases:
            with self.subTest(message):
                self.manifest.write_text(body, encoding="utf-8")
                rc, out = self.run_cli()
                self.assertEqual(rc, 1)
                self.assertIn(message, out)
                self.assertEqual(out.rstrip().splitlines()[-1], "trace: FAILED (the run manifest is not valid)")
        self.manifest.unlink()
        rc, out = self.run_cli()
        self.assertEqual(rc, 1)
        self.assertIn("cannot read", out)

    def test_manifest_and_reports_cannot_be_combined(self):
        with unittest.mock.patch("sys.stderr", io.StringIO()), self.assertRaises(SystemExit) as cm:
            trace_cli.main(["--root", str(REPO), "--manifest", str(self.manifest), "--reports", str(self.reports)])
        self.assertEqual(cm.exception.code, 2)

    def test_begin_records_the_files_already_there(self):
        report = self.write(self.items / "T.jsonl", "x\n")
        data = self.begin([(self.items, True), (self.reports / "web/none.json", False)])
        self.assertEqual(data["reports"][0]["before"][0]["file"], "T.jsonl")
        self.assertEqual(data["reports"][0]["before"][0]["mtime_ns"], report.stat().st_mtime_ns)
        self.assertEqual(data["reports"][1]["before"], [])
        self.assertGreaterEqual(data["started_ns"], report.stat().st_mtime_ns)
        m, problems = run_manifest.load(self.manifest, self.tmp)
        self.assertEqual(problems, [])
        self.assertEqual(list(m.entries[0].before), [report])


if __name__ == "__main__":
    unittest.main()
