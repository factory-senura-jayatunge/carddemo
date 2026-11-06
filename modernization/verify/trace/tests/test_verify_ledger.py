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

    def test_bill_pay_slice_entries(self):
        led = ledger.load(REPO / "modernization/ledger")
        switches = {i for i, e in led.switches.items() if e.get("code_switch") is True}
        self.assertEqual(switches, {"DEF-BILL-010", "DEF-BILL-014", "BR-TRNA-003", "DEF-SGN-011",
                                    "DEF-SGN-002", "DEF-SGN-003", "DEF-SGN-005"})
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
        self.assertEqual(sorted(led.units), ["BILL", "MENU", "SGN"])

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
