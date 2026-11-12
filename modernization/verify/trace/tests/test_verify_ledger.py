"""Tests of make verify-ledger: python3 -m unittest discover -s modernization/verify/trace/tests"""
from __future__ import annotations

import io
import shutil
import sys
import tempfile
import textwrap
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
TRACE = HERE.parent
sys.path.insert(0, str(TRACE))

import verify_ledger  # noqa: E402
from tracelib import catalog, code, ledger, spec  # noqa: E402

REPO = TRACE.parents[2]


def make_spec() -> spec.Spec:
    s = spec.Spec(Path("."))

    def item(ident, fidelity):
        kind, unit, _ = ident.split("-")
        s.items[ident] = spec.Item(ident, kind, unit, "COBIL00C", "docs/modernization/spec/programs/COBIL00C.md",
                                   1, "t", fidelity, spec.classes_of(fidelity))

    item("DEF-BILL-001", "defect-decide (Q-BILL-003)")
    item("DEF-BILL-010", "defect-decide (Q-BILL-002)")
    item("BR-BILL-001", "must-preserve")
    item("BR-BILL-002", "must-preserve (defect-decide for the rounding)")
    item("FLOW-BILL-007", "")
    s.questions = {"Q-BILL-001": "docs/modernization/spec/programs/COBIL00C.md"}
    s.invariants = {"INV-002"}
    s.units = {"BILL": "docs/modernization/spec/programs/COBIL00C.md"}
    s.scenarios = {"COBIL00C": {"S1", "S2"}}
    return s


FIDELITY = """\
schema = 1

[switch."DEF-BILL-010"]
value = "fixed"
code_switch = true
decision = "D-28"
approved_by = "user"
approved_on = "2026-11-06"

[switch."BR-BILL-002"]
value = "legacy"
code_switch = false
decision = "none"
approved_by = "user"
approved_on = 2026-11-06

[switch."Q-BILL-001"]
value = "legacy"
code_switch = false
decision = "D-30"
approved_by = "user"
approved_on = "2026-11-06"
"""
DEVIATIONS = """\
schema = 1

[deviation."DEF-BILL-001"]
kind = "architecture-dependent"
decision = "D-29"
target = "One database transaction"
approved_by = "user"
approved_on = "2026-11-06"

[deviation."INV-002"]
kind = "decision"
decision = "D-18"
target = "Sign-on stays available"
scenarios = ["COBIL00C S1"]
approved_by = "user"
approved_on = "2026-11-06"

[deviation."FLOW-BILL-007"]
kind = "incidental-ui"
decision = "none"
target = "Routes"
approved_by = "user"
approved_on = "2026-11-06"
"""
SCOPE = """\
schema = 1

[unit.BILL]
spec = "docs/modernization/spec/programs/COBIL00C.md"
excluded = { "BR-BILL-001" = "not converted" }
"""
DECISIONS = {"D-18", "D-28", "D-29", "D-30"}
ARCH = [["DEF-BILL-001"]]


class LedgerCase(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp)
        self.spec = make_spec()
        self.files = {"fidelity.toml": FIDELITY, "deviations.toml": DEVIATIONS, "scope.toml": SCOPE}

    def problems(self, code_scan=None, arch=ARCH):
        for name, body in self.files.items():
            (self.tmp / name).write_text(body, encoding="utf-8")
        led = ledger.load(self.tmp)
        out = list(led.problems)
        out += ledger.check_switches(led, self.spec, DECISIONS)
        out += ledger.check_deviations(led, self.spec, DECISIONS, arch)
        out += ledger.check_scope(led, self.spec)
        if code_scan is not None:
            out += ledger.check_code(led, code_scan)[0]
        return out

    def edit(self, name, old, new):
        self.assertIn(old, self.files[name])
        self.files[name] = self.files[name].replace(old, new, 1)

    def assertProblem(self, fragment, problems=None):
        problems = self.problems() if problems is None else problems
        self.assertTrue(any(fragment in p for p in problems), f"{fragment!r} not in {problems}")


class SwitchTest(LedgerCase):
    def test_valid_ledger_has_no_problems(self):
        self.assertEqual(self.problems(), [])

    def test_defect_decide_item_needs_an_entry_or_a_deviation(self):
        self.edit("deviations.toml", '[deviation."DEF-BILL-001"]', '[deviation."BR-BILL-001"]')
        self.assertProblem("DEF-BILL-001: defect-decide item", self.problems(arch=[]))

    def test_entry_for_undefined_item(self):
        self.edit("fidelity.toml", '[switch."Q-BILL-001"]', '[switch."Q-BILL-009"]')
        self.assertProblem("fidelity.toml: Q-BILL-009: not an item the spec defines")

    def test_entry_for_must_preserve_item(self):
        self.edit("fidelity.toml", '[switch."BR-BILL-002"]', '[switch."BR-BILL-001"]')
        self.assertProblem("BR-BILL-001: the spec classes it 'must-preserve'; only defect-decide")

    def test_decided_fix_of_must_preserve_item(self):
        fix = '\n[switch."BR-BILL-001"]\nvalue = "fixed"\ncode_switch = false\ndecision = "D-28"\napproved_by = "architect"\napproved_on = "2026-11-10"\n'
        self.files["fidelity.toml"] += fix
        self.assertEqual(self.problems(), [])
        base = self.files["fidelity.toml"]
        for old, new in (('value = "fixed"\ncode_switch = false\ndecision = "D-28"', 'value = "legacy"\ncode_switch = false\ndecision = "D-28"'),
                         ('code_switch = false\ndecision = "D-28"', 'code_switch = true\ndecision = "D-28"'),
                         ('code_switch = false\ndecision = "D-28"', 'code_switch = false\ndecision = "none"')):
            self.files["fidelity.toml"] = base[:base.index(fix)] + fix.replace(old, new)
            self.assertProblem("BR-BILL-001: the spec classes it 'must-preserve'; only defect-decide")

    def test_required_fields(self):
        self.edit("fidelity.toml", 'decision = "D-28"\napproved_by = "user"\napproved_on = "2026-11-06"\n', "")
        problems = self.problems()
        for key in ("decision", "approved_by", "approved_on"):
            self.assertProblem(f"DEF-BILL-010: {key} missing", problems)

    def test_approved_on_must_be_a_date(self):
        self.edit("fidelity.toml", 'approved_on = "2026-11-06"', 'approved_on = "6 Nov 2026"')
        self.assertProblem("DEF-BILL-010: approved_on must be a date")

    def test_empty_approver(self):
        self.edit("fidelity.toml", 'approved_by = "user"', 'approved_by = " "')
        self.assertProblem("DEF-BILL-010: approved_by must be a non-empty string")

    def test_bad_value_code_switch_and_unknown_field(self):
        self.edit("fidelity.toml", 'value = "fixed"\ncode_switch = true', 'value = "both"\ncode_switch = "yes"\nswitch = true')
        problems = self.problems()
        self.assertProblem("DEF-BILL-010: value must be legacy or fixed", problems)
        self.assertProblem("DEF-BILL-010: code_switch must be true or false", problems)
        self.assertProblem("DEF-BILL-010: unknown field 'switch'", problems)

    def test_covered_by_must_name_a_code_switch(self):
        self.files["fidelity.toml"] += textwrap.dedent('''
            [switch."DEF-BILL-001"]
            value = "fixed"
            code_switch = true
            decision = "D-29"
            note = "Covered by the switch BR-BILL-002: its tests cite both."
            approved_by = "user"
            approved_on = "2026-11-06"
            ''')
        problems = self.problems()
        self.assertProblem("DEF-BILL-001: covered by the switch BR-BILL-002, so code_switch must be false", problems)
        self.assertProblem("DEF-BILL-001: covered by BR-BILL-002, but BR-BILL-002 has no entry with code_switch = true", problems)
        self.files["fidelity.toml"] = self.files["fidelity.toml"].replace("code_switch = true\ndecision = \"D-29\"\nnote = \"Covered by the switch BR-BILL-002",
                                                                          "code_switch = false\ndecision = \"D-29\"\nnote = \"Covered by the switch DEF-BILL-010")
        self.assertFalse(any("covered" in p for p in self.problems()), self.problems())

    def test_decision_not_in_catalog(self):
        self.edit("fidelity.toml", 'decision = "D-28"', 'decision = "D-99"')
        self.assertProblem("DEF-BILL-010: decision D-99 is not in the decision catalog")

    def test_setting_needs_fixed(self):
        self.edit("fidelity.toml", 'value = "legacy"\ncode_switch = false\ndecision = "none"',
                  'value = "legacy"\ncode_switch = false\nsetting = "WRITES_BLOCKED"\ndecision = "none"')
        self.assertProblem('BR-BILL-002: setting needs value = "fixed"')

    def test_code_switch_on_architecture_dependent_item(self):
        self.files["fidelity.toml"] += textwrap.dedent('''
            [switch."DEF-BILL-001"]
            value = "fixed"
            code_switch = true
            decision = "D-29"
            approved_by = "user"
            approved_on = "2026-11-06"
            ''')
        self.assertProblem("DEF-BILL-001: code_switch = true, but deviations.toml records it as architecture-dependent")

    def test_old_inline_layout_is_rejected(self):
        self.files["fidelity.toml"] = 'schema = 1\n[items]\n"DEF-BILL-010" = { mode = "fixed" }\n'
        self.assertProblem("fidelity.toml: unknown top-level key 'items'")


class DeviationTest(LedgerCase):
    def test_required_fields_and_kind(self):
        self.edit("deviations.toml", 'kind = "incidental-ui"\ndecision = "none"\ntarget = "Routes"\n', 'kind = "cosmetic"\n')
        problems = self.problems()
        self.assertProblem("FLOW-BILL-007: kind must be one of", problems)
        self.assertProblem("FLOW-BILL-007: decision missing", problems)
        self.assertProblem("FLOW-BILL-007: target missing", problems)

    def test_undefined_item_and_scenario(self):
        self.edit("deviations.toml", '[deviation."INV-002"]', '[deviation."INV-999"]')
        self.edit("deviations.toml", 'scenarios = ["COBIL00C S1"]', 'scenarios = ["COBIL00C S9", "S1"]')
        problems = self.problems()
        self.assertProblem("deviations.toml: INV-999: not an ID the spec defines", problems)
        self.assertProblem("scenario 'COBIL00C S9'", problems)
        self.assertProblem("scenario 'S1'", problems)

    def test_architecture_item_needs_kind_architecture_dependent(self):
        self.edit("deviations.toml", 'kind = "architecture-dependent"', 'kind = "decision"')
        self.assertProblem('DEF-BILL-001: architecture-dependent item has no entry of kind "architecture-dependent"')

    def test_architecture_table_names_undefined_id(self):
        self.assertProblem("architecture decisions.md: DEF-BILL-077", self.problems(arch=[["DEF-BILL-077"]]))

    def test_schema_and_syntax(self):
        self.edit("scope.toml", "schema = 1", "schema = 2")
        self.files["deviations.toml"] = "not toml ="
        problems = self.problems(arch=[])
        self.assertProblem("scope.toml: schema must be 1", problems)
        self.assertProblem("deviations.toml: not valid TOML", problems)


class PartialDeviationTest(LedgerCase):
    """retained, requires_test, gate_units and gate_excluded (architecture transaction-service.md section 9)."""

    PARTIAL = '[deviation."DEF-BILL-001"]\nkind = "architecture-dependent"\n'
    INV = textwrap.dedent("""\

        [deviation."INV-001"]
        kind = "architecture-dependent"
        decision = "none"
        target = "Claim check"
        retained = ["BILL: two payments never get the same ID.", "POST: a duplicate never posts."]
        requires_test = true
        gate_units = ["BILL", "POST"]
        approved_by = "architect"
        approved_on = "2026-11-10"
        """)

    def setUp(self):
        super().setUp()
        self.spec.invariants |= {"INV-001"}
        self.spec.invariant_units = {"INV-001": ("BILL", "POST")}
        self.spec.units["POST"] = "docs/modernization/spec/programs/CBTRN02C.md"

    def partial(self, fields):
        self.edit("deviations.toml", self.PARTIAL, self.PARTIAL + fields)

    def test_valid_partial_item_and_invariant(self):
        self.partial('retained = ["One row per add.", "Nothing written on failure."]\nrequires_test = true\n')
        self.files["deviations.toml"] += self.INV
        self.assertEqual(self.problems(), [])

    def test_requires_test_needs_retained_and_retained_needs_requires_test(self):
        self.partial("requires_test = true\n")
        self.assertProblem("requires_test = true needs retained")
        self.setUp()
        self.partial('retained = ["One row per add."]\n')
        self.assertProblem("retained needs requires_test = true")
        self.setUp()
        self.partial('retained = ["One row per add."]\nrequires_test = false\n')
        self.assertProblem("requires_test must be true")

    def test_retained_must_be_distinct_non_empty_statements(self):
        for value, fragment in (('"One row."', "retained must be a non-empty list"), ("[]", "retained must be a non-empty list"),
                                ('["  "]', "retained must be a non-empty list"), ("[1]", "retained must be a non-empty list"),
                                ('["One row.", "One row."]', "retained lists 'One row.' more than once")):
            self.setUp()
            self.partial(f"retained = {value}\nrequires_test = true\n")
            self.assertProblem(fragment)

    def test_question_cannot_require_a_test(self):
        self.files["deviations.toml"] += textwrap.dedent("""\

            [deviation."Q-BILL-001"]
            kind = "decision"
            decision = "D-30"
            target = "t"
            retained = ["x"]
            requires_test = true
            approved_by = "user"
            approved_on = "2026-11-06"
            """)
        self.assertProblem("requires_test on a question")

    def test_gate_units_only_on_an_invariant(self):
        self.partial('retained = ["x"]\nrequires_test = true\ngate_units = ["BILL"]\n')
        self.assertProblem("gate_units is only for an invariant")

    def test_invariant_needs_gate_units_equal_to_its_component_units(self):
        cases = [
            (None, "an invariant with requires_test = true needs gate_units"),
            ('["BILL"]', "gate_units leaves out POST, units of its components"),
            ('["BILL", "POST", "MENU"]', "gate_units names 'MENU', which is not the unit code of a spec"),
            ('["BILL", "POST", "BILL"]', "gate_units lists 'BILL' more than once"),
            ("[]", "gate_units must be a non-empty list"),
            ('["BILL", 7]', "gate_units must be a non-empty list"),
        ]
        for value, fragment in cases:
            self.setUp()
            line = 'gate_units = ["BILL", "POST"]\n'
            self.files["deviations.toml"] += self.INV.replace(line, "" if value is None else f"gate_units = {value}\n")
            self.assertProblem(fragment)
        self.setUp()
        self.spec.units["TRNA"] = "t"
        self.files["deviations.toml"] += self.INV.replace('["BILL", "POST"]', '["BILL", "POST", "TRNA"]')
        self.assertProblem("TRNA is not a unit of its components in components.md (BILL, POST)")
        self.setUp()
        self.spec.invariant_units = {}
        self.files["deviations.toml"] += self.INV
        self.assertProblem("components.md names no component of INV-001 with a unit")

    def test_gate_excluded_needs_a_reason_and_accounts_for_the_unit(self):
        inv = self.INV.replace('gate_units = ["BILL", "POST"]\n', 'gate_units = ["BILL"]\ngate_excluded = { "POST" = "ruled out" }\n')
        self.files["deviations.toml"] += inv
        self.assertEqual(self.problems(), [])
        for excluded, fragment in (('{ "POST" = " " }', "gate_excluded POST needs the reason"),
                                   ('"POST"', "gate_excluded must be a table"),
                                   ('{ "POST" = "x", "BILL" = "y" }', "BILL is both in gate_units and in gate_excluded")):
            self.setUp()
            self.files["deviations.toml"] += self.INV.replace('gate_units = ["BILL", "POST"]\n',
                                                              f'gate_units = ["BILL"]\ngate_excluded = {excluded}\n')
            self.assertProblem(fragment)
        self.setUp()
        self.files["deviations.toml"] += self.INV.replace('gate_units = ["BILL", "POST"]\n', 'gate_excluded = { "POST" = "x" }\n')
        self.assertProblem("gate_excluded needs gate_units")

    def test_gate_units_need_requires_test(self):
        inv = self.INV.replace('retained = ["BILL: two payments never get the same ID.", "POST: a duplicate never posts."]\n'
                               'requires_test = true\n', "")
        self.files["deviations.toml"] += inv
        self.assertProblem("gate_units needs requires_test = true")

    def test_summary_counts_partial_deviations(self):
        self.files["deviations.toml"] += self.INV
        for name, body in self.files.items():
            (self.tmp / name).write_text(body, encoding="utf-8")
        self.assertIn("4 deviations (", ledger.summary(ledger.load(self.tmp), None))
        self.assertIn("; 1 partial with a retained part to test)", ledger.summary(ledger.load(self.tmp), None))


class ScopeTest(LedgerCase):
    def test_unknown_unit_wrong_spec_and_bad_exclusion(self):
        self.files["scope.toml"] += '\n[unit.NOPE]\nspec = "x"\n'
        self.edit("scope.toml", 'spec = "docs/modernization/spec/programs/COBIL00C.md"', 'spec = "programs/COBIL00C.md"')
        self.edit("scope.toml", '{ "BR-BILL-001" = "not converted" }', '{ "BR-MENU-002" = "" }')
        problems = self.problems()
        self.assertProblem("scope.toml: NOPE: not the unit code of a spec", problems)
        self.assertProblem("scope.toml: BILL: spec must be docs/modernization/spec/programs/COBIL00C.md", problems)
        self.assertProblem("excluded BR-MENU-002 is not an item of unit BILL", problems)
        self.assertProblem("excluded BR-MENU-002 needs a reason", problems)


class CodeTest(LedgerCase):
    JAVA = textwrap.dedent('''\
        class Switches {
            static final FidelitySwitch DEF_BILL_010 = FidelitySwitch.of(
                    "DEF-BILL-010", "legacy text", "fixed text");
        }
        ''')

    def scan(self, java: str, yml: str | None = None, props: str | None = None) -> code.Code:
        top = self.tmp / "mod"
        src = top / "services/billpay-service/src/main/java/com/carddemo/Switches.java"
        src.parent.mkdir(parents=True, exist_ok=True)
        src.write_text(java, encoding="utf-8")
        res = top / "services/billpay-service/src/main/resources"
        res.mkdir(parents=True, exist_ok=True)
        if yml is not None:
            (res / "application.yml").write_text(yml, encoding="utf-8")
        if props is not None:
            (res / "application.properties").write_text(props, encoding="utf-8")
        test_res = top / "services/billpay-service/src/test/resources"
        test_res.mkdir(parents=True, exist_ok=True)
        (test_res / "application.yml").write_text("carddemo:\n  fidelity:\n    DEF-BILL-404: fixed\n", encoding="utf-8")
        (top / "services/billpay-service/src/test/java").mkdir(parents=True)
        (top / "services/billpay-service/src/test/java/T.java").write_text(
            'class T { void t() { switches.fixed("DEF-BILL-405"); } }', encoding="utf-8")
        return code.scan(top, self.tmp)

    def test_declared_switch_with_code_switch_entry(self):
        scanned = self.scan(self.JAVA, "spring:\n  application:\n    name: billpay   # c\n")
        self.assertEqual(scanned.switches, {"DEF-BILL-010": ["mod/services/billpay-service/src/main/java/com/carddemo/Switches.java:2"]})
        self.assertEqual(scanned.config, {})
        self.assertEqual(self.problems(scanned), [])

    def test_string_reads_are_switches(self):
        java = 'class S { boolean f() { return switches.fixed("BR-BILL-002") || switches.mode( "Q-BILL-001") == null; } }'
        scanned = self.scan(java)
        self.assertEqual(sorted(scanned.switches), ["BR-BILL-002", "Q-BILL-001"])
        problems = self.problems(scanned)
        self.assertProblem("switch BR-BILL-002 is in the code, but fidelity.toml has code_switch = false", problems)

    def test_switch_without_entry(self):
        java = self.JAVA.replace('"DEF-BILL-010"', '"DEF-BILL-011"')
        self.assertProblem("switch DEF-BILL-011 has no entry in fidelity.toml", self.problems(self.scan(java)))

    def test_code_switch_entry_missing_from_code_is_a_warning(self):
        scanned = self.scan("class S {}")
        self.assertEqual(self.problems(scanned), [])
        led = ledger.load(self.tmp)
        self.assertEqual(ledger.check_code(led, scanned)[1],
                         ["fidelity.toml: DEF-BILL-010: code_switch = true, but no code declares the switch yet"])

    def test_configuration_overriding_the_ledger(self):
        yml = textwrap.dedent('''\
            carddemo:
              fidelity:
                "[def-bill-010]": legacy
                BR-BILL-002: legacy
            ---
            spring:
              config:
                activate:
                  on-profile: test
            carddemo:
              fidelity:
                DEF-BILL-010: fixed
            ''')
        scanned = self.scan(self.JAVA, yml)
        self.assertEqual([v for _, v in scanned.config["DEF-BILL-010"]], ["legacy"])
        problems = self.problems(scanned)
        self.assertProblem("carddemo.fidelity.DEF-BILL-010 is legacy, fidelity.toml says fixed", problems)
        self.assertFalse(any("BR-BILL-002" in p for p in problems), problems)
        self.assertNotIn("DEF-BILL-404", scanned.config)
        self.assertNotIn("DEF-BILL-405", scanned.switches)

    def test_properties_bad_value_and_no_entry(self):
        props = "carddemo.fidelity.DEF_BILL_010=fixed\ncarddemo.fidelity.DEF-BILL-001=maybe\ncarddemo.fidelity.DEF-BILL-002=fixed\n"
        problems = self.problems(self.scan(self.JAVA, None, props))
        self.assertProblem("carddemo.fidelity.DEF-BILL-001: 'maybe' is not legacy or fixed", problems)
        self.assertProblem("carddemo.fidelity.DEF-BILL-002 is set, but fidelity.toml has no entry", problems)
        self.assertFalse(any("DEF-BILL-010" in p for p in problems), problems)


class CatalogTest(unittest.TestCase):
    def test_architecture_rows_expand_abbreviated_ids(self):
        rows = catalog.architecture_dependent(REPO)
        flat = [i for row in rows for i in row]
        self.assertIn(["DEF-AUTH-001", "DEF-AUTH-002", "DEF-AUTH-003", "DEF-AUTH-004", "DEF-AUTH-009", "DEF-AUTH-013"], rows)
        self.assertIn("Q-JCOMB-001", flat)
        self.assertNotIn("Item", flat)

    def test_catalog_decisions(self):
        found = catalog.decisions(REPO)
        self.assertIn("D-01", found)
        self.assertIn("D-75", found)


class RepositoryLedgerTest(unittest.TestCase):
    """The committed ledger passes, and the real checks catch a gap in a copy of it."""

    def run_on(self, ledger_dir: Path) -> tuple[int, str]:
        out = io.StringIO()
        rc = verify_ledger.run(REPO, ledger_dir, None, out)
        return rc, out.getvalue()

    def test_committed_ledger_passes(self):
        rc, out = self.run_on(REPO / "modernization/ledger")
        self.assertEqual(rc, 0, out)
        self.assertEqual(out.rstrip().splitlines()[-1], "verify-ledger: OK")

    def test_invariant_units_come_from_the_component_list(self):
        s = spec.load(REPO)
        self.assertEqual(s.invariant_units["INV-001"], ("BILL", "TRNA", "POST", "INTC"))
        self.assertEqual(s.invariant_units["INV-004"], ("POST", "BILL", "TRNA"))

    def test_bill_pay_slice_entries(self):
        led = ledger.load(REPO / "modernization/ledger")
        switches = {i for i, e in led.switches.items() if e.get("code_switch") is True}
        # architecture/switches.md: the targeted policy's list, and nothing else.
        self.assertEqual(switches, {"DEF-BILL-010", "DEF-BILL-014", "BR-TRNA-003", "DEF-SGN-011",
                                    "DEF-SGN-002", "DEF-SGN-003", "DEF-SGN-005",
                                    "DEF-ACUP-002", "DEF-JINTC-001", "DEF-INTC-010", "DEF-INTC-012", "DEF-TRPT-001",
                                    "DEF-TRPT-004", "DEF-JPCAT-001", "DEF-STMT-005", "DEF-MQAC-002", "DEF-MQDT-002",
                                    "DEF-MQDT-003"})
        self.assertIs(led.switches["DEF-DTCK-004"]["code_switch"], False)
        covered = {i: ledger.covering_switch(e) for i, e in led.switches.items() if ledger.covering_switch(e)}
        # Statement build ruling Q1: the five follow the statement format that DEF-STMT-005 selects, so it covers them all.
        stmt = ("DEF-STMT-001", "DEF-STMT-002", "DEF-STMT-003", "DEF-STMT-004", "DEF-STMT-007",
                "BR-STMT-005", "BR-STMT-006", "BR-STMT-008", "DEF-JSTMT-005")
        self.assertEqual(covered, {"FLOW-TRPT-001": "DEF-TRPT-001", "NUM-JINTC-001": "DEF-INTC-010",
                                   **{i: "DEF-STMT-005" for i in stmt}})
        self.assertEqual(led.switches["BR-TRNA-007"]["value"], "fixed")
        self.assertEqual(led.deviations["PINV-c-05"]["decision"], "D-27")
        for ident in ("FLOW-SGN-005", "FLOW-MENU-006", "DEF-SGN-007"):
            self.assertEqual((led.deviations[ident]["kind"], led.deviations[ident]["decision"]), ("incidental-ui", "none"))
        self.assertIn("Q-SGN-006", led.deviations["DEF-SGN-007"]["note"])
        for ident in ("DEF-BILL-009", "Q-BILL-001", "Q-BILL-002", "Q-BILL-003", "Q-BILL-004", "Q-BILL-006",
                      "Q-MENU-003", "Q-SGN-001", "Q-SGN-002", "Q-SGN-003", "Q-SGN-004", "Q-SGN-005"):
            self.assertIn(ident, led.switches)
        for ident in ("DEF-BILL-001", "DEF-BILL-003", "DEF-BILL-005", "DEF-BILL-006", "DEF-BILL-007",
                      "DEF-BILL-008", "DEF-BILL-011", "DEF-BILL-012", "DEF-BILL-013", "DEF-BILL-015",
                      "ERR-BILL-002", "ERR-BILL-003", "ERR-BILL-004", "FLOW-BILL-007", "FLOW-BILL-008",
                      "BR-BILL-004", "DEF-SGN-001", "DEF-SGN-004", "DEF-SGN-006", "DEF-SGN-012",
                      "DEF-MENU-003", "DEF-SGN-008", "ERR-SGN-001", "DEF-MENU-004", "Q-MENU-003"):
            self.assertIn(ident, led.deviations)
        self.assertNotIn("NUM-BILL-002", led.switches)
        self.assertNotIn("DEF-BILL-002", led.switches)
        self.assertEqual(led.deviations["ERR-SGN-001"]["scenarios"], ["COSGN00C S10"])
        self.assertEqual(sorted(led.units), ["BILL", "DBUL", "DTFT", "JCSDD", "JCSDM", "JCSDUP", "JDBPA", "JDCUST",
                                             "JESRR", "JFTP", "JGDGD", "JIRD1", "JIRD2", "JLDPA", "JREPT", "JRPROC",
                                             "JULGS", "JULPA", "MENU", "MWAT", "PDLD", "PDUL", "SGN", "WAIT"])

    def test_account_service_entries(self):
        led = ledger.load(REPO / "modernization/ledger")
        fixes = {"VAL-ACVW-005": "D-75", "Q-ACVW-004": "D-75", "Q-ACUP-104": "D-50", "Q-ACUP-108": "D-50",
                 "VAL-CRDL-005": "D-55", "Q-CRDL-007": "D-55", "VAL-CRDL-006": "D-69", "Q-CRDL-009": "D-69",
                 "VAL-CRDU-011": "D-69", "VAL-CRDU-006": "D-51", "Q-CRDU-008": "D-51", "BR-CRDU-005": "D-68",
                 "Q-CRDU-007": "D-68"}
        for ident, decision in fixes.items():
            e = led.switches[ident]
            self.assertEqual((e["value"], e["code_switch"], e["decision"]), ("fixed", False, decision), ident)
            self.assertNotIn(ident, led.deviations)
        for ident in ("ERR-ACVW-003", "ERR-ACVW-004", "FLOW-ACVW-008", "FLOW-ACUP-011", "ERR-ACUP-006",
                      "ERR-CRDV-002", "ERR-CRDV-003", "FLOW-CRDV-003", "FLOW-CRDV-008", "FLOW-CRDV-011",
                      "FLOW-CRDV-012", "FLOW-CRDV-014", "FLOW-CRDL-005", "FLOW-CRDL-012", "FLOW-CRDU-002",
                      "FLOW-CRDU-019", "ERR-CRDL-001", "ERR-ACUP-005"):
            self.assertEqual(led.deviations[ident]["kind"], "architecture-dependent", ident)
            self.assertNotIn(ident, led.switches)
        self.assertIn("D-ARCH-12", led.deviations["FLOW-CRDL-012"]["target"])
        # Preserved by the confirmed contract v5 table: no entry, so trace keeps requiring their tests.
        for ident in ("BR-ACUP-010", "FLOW-ACUP-003", "FLOW-ACUP-004", "FLOW-ACUP-009"):
            self.assertNotIn(ident, led.switches)
            self.assertNotIn(ident, led.deviations)
        c1 = [e for e in led.deviations.values() if e.get("group") == "DEV-C1"]
        self.assertIn("INV-006", led.deviations)
        self.assertTrue(c1)
        for e in c1:
            self.assertIn("every unmasked view is audited (D-09, D-SEC-05); ADMIN reads the account and card views "
                          "read-only and may unmask", e["target"])
            self.assertNotIn("every view is audited", e["target"])

    def test_authorization_service_entries(self):
        led = ledger.load(REPO / "modernization/ledger")
        fixes = {"BR-AUTH-002": "D-32"} | {i: "D-58" for i in (
            "ERR-AUSM-001", "ERR-AUSM-002", "ERR-AUSM-003", "ERR-AUSM-004", "ERR-AUSM-005",
            "ERR-AUDT-002", "ERR-AUDT-003", "ERR-AUDT-004", "ERR-AUDT-006")}
        for ident, decision in fixes.items():
            e = led.switches[ident]
            self.assertEqual((e["value"], e["code_switch"], e["decision"]), ("fixed", False, decision), ident)
        self.assertNotIn("ERR-AUDT-005", led.switches)
        self.assertIn("PSB-scheduling part cannot occur", led.switches["ERR-AUSM-004"]["note"])
        self.assertIn("System error while FRAUD Tagging, ROLLBACK", led.switches["ERR-AUDT-004"]["note"])
        for ident in ("DEF-AUTH-010", "DEF-AUSM-001", "DEF-AUDT-004", "DEF-AUSM-009", "ERR-AUDT-001", "FLOW-AUTH-001"):
            self.assertEqual(led.deviations[ident]["kind"], "architecture-dependent", ident)
            self.assertNotIn(ident, led.switches)
        self.assertFalse([i for i in led.switches if i.startswith("ERR-AUFR")])

    def test_transaction_service_entries(self):
        led = ledger.load(REPO / "modernization/ledger")
        for ident, decision in (("ERR-RPTS-001", "D-58"), ("Q-TRNV-001", "D-09")):
            e = led.switches[ident]
            self.assertEqual((e["value"], e["code_switch"], e["decision"]), ("fixed", False, decision), ident)
        for ident in ("DEF-TRNV-004", "Q-TRNV-001"):
            self.assertIn("DEV-C1", led.switches[ident]["note"])
        for ident in ("FLOW-TRNL-001", "FLOW-TRNV-001", "FLOW-TRNA-001", "FLOW-RPTS-001"):
            e = led.deviations[ident]
            self.assertEqual((e["kind"], e["decision"], e["group"]), ("security-c1", "D-01", "DEV-C1"), ident)
            self.assertEqual(e["target"], led.deviations["FLOW-CRDL-001"]["target"])
        for ident in ("FLOW-TRNL-008", "FLOW-TRNL-009", "ERR-TRNL-002", "ERR-TRNL-003", "ERR-TRNL-004",
                      "FLOW-TRNV-008", "FLOW-TRNV-009", "ERR-TRNV-002", "ERR-TRNV-003",
                      "BR-TRNA-004", "SE-TRNA-001", "SE-TRNA-002", "ERR-TRNA-002", "ERR-TRNA-003", "FLOW-TRNA-008",
                      "FLOW-TRNA-009", "BR-RPTS-007", "SE-RPTS-001", "ERR-RPTS-002", "ERR-RPTS-003", "FLOW-RPTS-006"):
            self.assertEqual(led.deviations[ident]["kind"], "architecture-dependent", ident)
        for ident in ("ERR-POST-004", "DEF-POST-009", "INV-001", "DEF-POST-008"):
            self.assertEqual((led.deviations[ident]["kind"], led.deviations[ident]["group"]),
                             ("architecture-dependent", "DEV-ARCH-05"), ident)
        for ident in ("Q-TRNL-001", "Q-TRNL-002", "Q-TRNL-004"):
            self.assertNotIn(ident, led.switches)

    def test_reserved_range_notes(self):
        """batch.md rule 1, option A, approved by the user on 2026-11-10."""
        led = ledger.load(REPO / "modernization/ledger")
        notes = {"BR-TRNA-003": led.switches["BR-TRNA-003"]["note"]}
        notes.update({i: led.deviations[i]["note"] for i in ("DEF-TRNA-003", "DEF-BILL-003", "INV-001")})
        for ident, note in notes.items():
            self.assertIn("16 ASCII digits starting with 9", note, ident)
            self.assertIn("approved the range on 2026-11-10", note, ident)
        self.assertIn("TRAN_ID_RESERVED", notes["BR-TRNA-003"])
        self.assertIn("TRAN_ID_RESERVED", led.deviations["INV-001"]["target"])

    def test_partial_deviations(self):
        """transaction-service.md section 9: what stays tested, and the items that carry the whole-item entries."""
        led = ledger.load(REPO / "modernization/ledger")
        partial = {"BR-TRNA-004", "SE-TRNA-002", "BR-RPTS-007", "SE-RPTS-001",
                   "FLOW-TRNL-001", "FLOW-TRNV-001", "FLOW-TRNA-001", "FLOW-RPTS-001",
                   "ERR-TRNL-004", "ERR-TRNV-003", "ERR-TRNA-003", "ERR-RPTS-003",
                   "ERR-POST-004", "DEF-POST-009", "INV-001", "ERR-CRDL-001", "ERR-AUDT-001",
                   "ERR-ACVW-004", "ERR-CRDV-002", "ERR-ACUP-006",
                   "ERR-BILL-004", "BR-MENU-003", "FLOW-CRDL-001", "DEF-ACVW-009", "FLOW-CRDL-008", "DEF-ACUP-013",
                   # inquiry-service.md (arch 2b5e102)
                   "SE-MQAC-004", "ERR-MQAC-005", "SE-MQDT-003", "BR-MQAC-007", "BR-MQDT-005", "DEF-MQAC-005",
                   "FLOW-MQAC-003", "FLOW-MQDT-003", "FLOW-MQAC-004", "FLOW-MQDT-004", "SE-MQAC-001", "SE-MQDT-001",
                   "SE-MQAC-005", "SE-MQDT-004", "ERR-MQAC-004", "ERR-MQDT-004", "ERR-MQAC-006", "ERR-MQDT-005",
                   # batch.md Spec-only validators (arch 104321a)
                   "ERR-JDISC-002"}
        self.assertEqual({i for i, e in led.deviations.items() if ledger.requires_test(e)}, partial)
        # D-18 gives the window but does not list ERR-JDISC-002, so the architect's ruling is not a catalog decision.
        jdisc = led.deviations["ERR-JDISC-002"]
        self.assertEqual((jdisc["kind"], jdisc["decision"]), ("architecture-dependent", "none"))
        self.assertTrue(jdisc["approved_by"].startswith("architect ("))
        inv = led.deviations["INV-001"]
        self.assertEqual(inv["gate_units"], ["BILL", "TRNA", "POST", "INTC"])
        self.assertNotIn("gate_excluded", inv)
        self.assertEqual([s.split(":")[0] for s in inv["retained"]], inv["gate_units"])
        whole = {"SE-TRNA-001": ["BR-TRNA-003", "BR-TRNA-006"], "FLOW-RPTS-006": ["VAL-RPTS-001", "VAL-RPTS-005"],
                 "ERR-TRNA-002": ["VAL-TRNA-013"], "ERR-TRNL-003": ["DEF-TRNL-004"]}
        for u in ("TRNL", "TRNV", "TRNA"):
            whole[f"FLOW-{u}-008"] = ["D-WEB-02"]
            whole[f"FLOW-{u}-009"] = [f"FLOW-{u}-004"]
        for u in ("TRNL", "TRNV", "RPTS"):
            whole[f"ERR-{u}-002"] = []
        # account-service.md section 9
        whole.update({"FLOW-ACVW-008": ["BR-ACVW-005"], "FLOW-ACUP-011": ["BR-ACUP-010", "FLOW-ACUP-004", "ERR-ACUP-004"],
                      "FLOW-CRDV-008": ["BR-CRDV-003", "FLOW-CRDV-005"], "FLOW-CRDU-019": ["FLOW-CRDU-005", "BR-CRDU-009"],
                      "ERR-ACVW-003": [], "ERR-CRDV-003": ["VAL-CRDV-007"], "FLOW-CRDV-014": ["VAL-CRDV-007"]})
        for ident, carriers in whole.items():
            e = led.deviations[ident]
            self.assertTrue(e["note"].startswith("Whole item: "), ident)
            for carrier in carriers:
                self.assertIn(carrier, e["note"], ident)
                self.assertNotIn(carrier, led.deviations, f"{ident}: carrier {carrier} must stay required")
        for ident in ("ERR-ACUP-005", "DEF-AUTH-010", "DEF-AUSM-001", "DEF-AUDT-004", "DEF-AUSM-009", "FLOW-AUTH-001",
                      "DEF-ACUP-005"):
            self.assertFalse(ledger.requires_test(led.deviations[ident]), ident)
        # The abend handler's retained part claims no write outcome; the atomic save stays with ERR-ACUP-004 (D-16).
        self.assertNotIn("write", " ".join(led.deviations["ERR-ACUP-006"]["retained"]))
        self.assertEqual(led.switches["ERR-ACUP-004"]["decision"], "D-16")

    def test_slice_and_account_classification(self):
        """billpay-slice.md section 11 and account-service.md section 9: the BILL, SGN, MENU and account rows of 2026-11-10."""
        led = ledger.load(REPO / "modernization/ledger")
        partial = {"ERR-BILL-004": "500 INTERNAL_ERROR", "BR-MENU-003": "403 ACCESS_DENIED",
                   "FLOW-CRDL-001": "401 UNAUTHENTICATED", "DEF-ACVW-009": "401 UNAUTHENTICATED",
                   "FLOW-CRDL-008": "user ID and role are unchanged", "DEF-ACUP-013": "400 FIELD_NOT_EDITABLE"}
        for ident, fragment in partial.items():
            e = led.deviations[ident]
            self.assertTrue(ledger.requires_test(e), ident)
            self.assertIn(fragment, " ".join(e["retained"]), ident)
            self.assertNotIn("gate_units", e, ident)
        self.assertEqual(led.deviations["FLOW-CRDL-001"]["retained"], led.deviations["FLOW-TRNL-001"]["retained"])
        self.assertEqual(led.deviations["DEF-ACVW-009"]["retained"], led.deviations["FLOW-TRNL-001"]["retained"])
        role = ["FLOW-CRDL-008", "BR-MENU-003"]
        keys = ["VAL-CRDL-006", "VAL-CRDU-011", "FLOW-CRDV-002", "DEF-CRDV-005"]
        whole = {
            "DEF-BILL-003": ["INV-001"], "DEF-BILL-005": ["ERR-BILL-001"],
            "DEF-BILL-012": ["ERR-BILL-001"], "DEF-BILL-013": ["VAL-BILL-001", "VAL-BILL-005", "ERR-BILL-001"],
            "ERR-BILL-002": ["VAL-BILL-001", "VAL-BILL-005"], "ERR-BILL-003": ["ERR-BILL-001"],
            "DEF-BILL-001": ["ERR-BILL-001"], "DEF-BILL-011": ["ERR-BILL-001", "BR-BILL-001", "BR-BILL-007"],
            "DEF-MENU-002": ["BR-MENU-001"], "DEF-MENU-003": ["FLOW-MENU-001"], "DEF-SGN-006": ["BR-MENU-003", "BR-SGN-004"],
            "DEF-SGN-012": ["FLOW-MENU-001", "FLOW-BILL-001"],
            "FLOW-CRDL-005": keys, "FLOW-CRDV-012": keys, "FLOW-CRDU-002": keys,
            "FLOW-CRDL-012": ["BR-CRDL-005", "BR-CRDL-006", "FLOW-CRDL-011", "FLOW-CRDL-003"],
            "SE-CRDL-003": ["BR-CRDL-007", "FLOW-CRDL-009", "FLOW-CRDL-010", "BR-CRDV-003", "FLOW-CRDU-005", "FLOW-CRDL-007"],
            "FLOW-CRDV-003": ["FLOW-CRDV-004", "FLOW-CRDV-005", "BR-CRDV-003", "FLOW-CRDV-006", "FLOW-CRDV-007"],
            "FLOW-CRDV-011": ["FLOW-CRDV-009", "FLOW-CRDV-010", "BR-CRDV-002", "FLOW-CRDV-007"],
            "DEF-CRDL-003": ["FLOW-CRDL-001"], "DEF-CRDL-005": ["FLOW-CRDL-008"],
            "DEF-CRDL-004": role, "DEF-CRDV-004": role, "DEF-CRDU-007": role, "DEF-ACVW-011": role, "DEF-ACUP-009": role,
            "DEF-CRDV-003": ["FLOW-CRDV-006"], "DEF-ACUP-105": ["VAL-ACUP-005", "VAL-ACUP-010"]}
        for ident, carriers in whole.items():
            e = led.deviations[ident]
            self.assertFalse(ledger.requires_test(e), ident)
            self.assertIn("Whole item: ", e["note"], ident)
            for carrier in carriers:
                self.assertIn(carrier, e["note"], ident)
                # A carrier stays required: no entry, a fixed or legacy switch, or a partial entry.
                self.assertTrue(carrier not in led.deviations or ledger.requires_test(led.deviations[carrier]),
                                f"{ident}: carrier {carrier} must stay required")
        self.assertTrue(ledger.requires_test(led.deviations["ERR-CRDL-001"]))
        self.assertIn("the next transaction ID comes from the sequence (fixed) or from one query for the highest ID "
                      "under an application lock (legacy)", led.deviations["DEF-BILL-012"]["target"])
        self.assertNotIn("lowest card number", led.deviations["DEF-BILL-012"]["target"])
        # FLOW-MENU-001 is observable (MENU S1), and every option has a page, so MENU excludes nothing.
        self.assertEqual(set(led.units["MENU"].get("excluded", {})), set())
    def test_inquiry_service_entries(self):
        """inquiry-service.md (arch 2b5e102), ratified 2026-11-11: three switches, absent IDs, the finite crash limit."""
        led = ledger.load(REPO / "modernization/ledger")
        inquiry = {i for i, e in led.switches.items() if e["code_switch"] and "-MQ" in i}
        self.assertEqual(inquiry, {"DEF-MQAC-002", "DEF-MQDT-002", "DEF-MQDT-003"})
        self.assertEqual(sum(e["code_switch"] for e in led.switches.values()), 18)
        for ident in sorted(inquiry):
            self.assertIn("no fourth switch", led.switches[ident]["note"], ident)
        for ident in ("BR-MQAC-005", "DEF-MQAC-003", "W1, W2 and W6"):
            self.assertIn(ident, led.switches["DEF-MQAC-002"]["note"])
        self.assertNotIn("BR-MQAC-005", led.switches)
        for ident in ("DEF-MQAC-002", "DEF-MQDT-003"):
            self.assertIn("never rejected or dead-lettered for the absence", led.switches[ident]["note"], ident)
        for ident in ("DEF-MQAC-007", "ERR-MQAC-005"):
            e = led.switches[ident]
            self.assertEqual((e["value"], e["code_switch"], e["decision"]), ("fixed", False, "D-74"), ident)
            for fragment in ("record, reply, reject, in that order", "x-delivery-limit 10", "6 deliveries"):
                self.assertIn(fragment, e["note"], ident)
        for ident in ("SE-MQAC-004", "ERR-MQAC-005", "SE-MQDT-003"):
            e = led.deviations[ident]
            self.assertEqual((e["kind"], e["decision"]), ("architecture-dependent", "none"), ident)
            self.assertIn("no record is left when every delivery from the sixth to the eleventh ends", e["target"], ident)
            self.assertIn("ratified under the product delegation on 2026-11-11", e["note"], ident)
            self.assertIn("exactly one", " ".join(e["retained"]), ident)
        for ident in ("BR-MQAC-007", "BR-MQDT-005"):
            e = led.deviations[ident]
            self.assertIn("At least once on the crash path", e["target"], ident)
            self.assertIn("exactly one confirmed reply", " ".join(e["retained"]), ident)
        self.assertIn("48 characters", " ".join(led.deviations["DEF-MQAC-005"]["retained"]))
        for ident in ("SE-MQAC-004", "ERR-MQAC-005", "BR-MQAC-007", "DEF-MQAC-005"):
            self.assertIn("2b5e102", led.deviations[ident]["source"], ident)
        notes = [e.get("note", "") for i, e in {**led.switches, **led.deviations}.items() if "-MQ" in i]
        self.assertFalse([n for n in notes if "exactly once" in n.lower()])
        for ident in ("SE-MQAC-001", "SE-MQDT-001"):
            retained = " ".join(led.deviations[ident]["retained"])
            self.assertIn("by the broker backstop (x-delivery-limit 10) after the eleventh delivery (I26", retained, ident)
        for ident in ("SE-MQAC-004", "ERR-MQAC-005", "SE-MQDT-003"):
            target = led.deviations[ident]["target"]
            self.assertIn("neither message_id nor correlation_id cannot be matched", target, ident)
        for ident in ("SE-MQAC-004", "SE-MQDT-003"):
            self.assertIn("for an oversized request no record is left", led.deviations[ident]["target"], ident)
        sources = [e.get("source", "") for i, e in led.deviations.items() if "-MQ" in i]
        self.assertFalse([s for s in sources if "(listeners)" in s or "inquiry-service.md section 5 (" in s])
        self.assertNotIn("Appendix B", led.deviations["DEF-MQDT-005"]["source"])
        contract = [s for s in sources if "inquiry-service contract" in s]
        self.assertEqual(len(contract), 28)
        self.assertFalse([s for s in contract if "inquiry-service contract v8 section 13" not in s])
        retained = " ".join(led.deviations["ERR-MQAC-006"]["retained"])
        self.assertNotIn("(I19)", retained)
        self.assertIn("gets no error reply (I31)", retained)
        self.assertIn("(I30)", retained)
        self.assertNotIn("MQAC", led.units)
        self.assertNotIn("MQDT", led.units)
    def test_menu_dormant_placeholder_note(self):
        """BR-MENU-005 is dormant in target: a scope note only, no entry and no gate."""
        led = ledger.load(REPO / "modernization/ledger")
        note = led.units["MENU"]["note"]
        self.assertIn("dormant in target: every option implemented; code path covered at unit level", note)
        self.assertIn("no e2e test reaches it", note)
        self.assertNotIn("BR-MENU-005", led.switches)
        self.assertNotIn("BR-MENU-005", led.deviations)
        self.assertNotIn("BR-MENU-005", led.excluded("MENU"))
    def test_text_repertoire_notes(self):
        """data-model.md text repertoire (arch 6ff09c0): notes only; the three items keep their class and have no entry."""
        led = ledger.load(REPO / "modernization/ledger")
        text = (REPO / "modernization/ledger/fidelity.toml").read_text(encoding="utf-8")
        notes = {}
        for block in text.split("\n\n"):
            if block.startswith("# Note on "):
                ident = block.split()[3]
                notes[ident] = " ".join(line.lstrip("# ").strip() for line in block.splitlines())
        self.assertEqual(set(notes), {"BR-ACUP-108", "VAL-ACUP-022", "VAL-TRNA-006"})
        for ident, note in notes.items():
            self.assertNotIn(ident, led.switches, ident)
            self.assertNotIn(ident, led.deviations, ident)
            for fragment in ("U+0000 to U+00FF", "400 UNSUPPORTED_CHARACTER", "No class change",
                             "No ASCII or byte cap and no switch is added", "trailing spaces (U+0020)"):
                self.assertIn(fragment, note, ident)
            self.assertNotIn("V410", note, ident)
        self.assertIn("govtIssuedId (20)", notes["BR-ACUP-108"])
        self.assertIn("merchantCity (25)", notes["VAL-TRNA-006"])
        self.assertNotIn("ACUP", led.units)
        self.assertNotIn("TRNA", led.units)
    def test_dropped_entry_and_architecture_deviation_fail(self):
        tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, tmp)
        for name in ("fidelity.toml", "deviations.toml", "scope.toml"):
            shutil.copy(REPO / "modernization/ledger" / name, tmp / name)
        fid = (tmp / "fidelity.toml").read_text(encoding="utf-8")
        (tmp / "fidelity.toml").write_text(fid.replace('[switch."DEF-BILL-010"]', '[switch."DEF-BILL-404"]'), encoding="utf-8")
        dev = (tmp / "deviations.toml").read_text(encoding="utf-8")
        (tmp / "deviations.toml").write_text(dev.replace('[deviation."DEF-POST-008"]', '[deviation."DEF-POST-404"]'), encoding="utf-8")
        rc, out = self.run_on(tmp)
        self.assertEqual(rc, 1)
        self.assertIn("DEF-BILL-010: defect-decide item", out)
        self.assertIn("DEF-BILL-404: not an item the spec defines", out)
        self.assertIn("DEF-POST-008: architecture-dependent item has no entry", out)
        self.assertRegex(out.rstrip().splitlines()[-1], r"^verify-ledger: FAILED \(\d+ problems\)$")


if __name__ == "__main__":
    unittest.main()
