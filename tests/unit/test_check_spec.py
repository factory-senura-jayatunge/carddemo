"""docs/modernization/spec/tools/check_spec.py: the NL spec checker."""
from __future__ import annotations

import hashlib
import importlib.util
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
CHECKER = REPO / "docs" / "modernization" / "spec" / "tools" / "check_spec.py"

_spec = importlib.util.spec_from_file_location("check_spec", CHECKER)
check_spec = importlib.util.module_from_spec(_spec)
sys.modules["check_spec"] = check_spec
_spec.loader.exec_module(check_spec)

COBOL = "\n".join([
    "       IDENTIFICATION DIVISION.",
    "       PROGRAM-ID. TESTPGM.",
    "       PROCEDURE DIVISION.",
    "           PERFORM 1000-A.",
    "           STOP RUN.",
    "      *---------------------------------------------------------",
    "       1000-A.",
    "           DISPLAY 'A'.",
    "",
    "       2000-B.",
    "           DISPLAY 'B'",
    "           EXIT.",
]) + "\n"

JCL = "\n".join([
    "//TESTJOB JOB 'X',CLASS=A",
    "//*  a comment",
    "//STEP05 EXEC PGM=IEFBR14",
    "//DD1 DD DUMMY",
    "//*",
    "//STEP10 EXEC PGM=TESTPGM",
    "//SYSOUT DD SYSOUT=*",
]) + "\n"

COVERAGE = """\
| Paragraph | Lines | Covered by |
| :-- | :-- | :-- |
| (procedure division main line) | 4-5 | BR-TEST-001 |
| `1000-A` | 7-8 | BR-TEST-001 |
| `2000-B` | 10-12 | BR-TEST-001 |
"""


def spec_text(sha: str, unit: str = "TEST", coverage: str = COVERAGE, extra: str = "",
              item: str = "#### BR-TEST-001 · A rule\n- **When:** always.\n- **Source:** `:7-8`\n") -> str:
    sections = []
    for n in range(1, 17):
        title = "Paragraph coverage" if n == 16 else f"Section {n}"
        body = {7: item, 14: "| ID | Question | For |\n| :-- | :-- | :-- |\n| Q-TEST-001 | Why? | business |\n",
                16: coverage}.get(n, "None.\n")
        sections.append(f"## {n}. {title}\n\n{body}")
    return (f"---\nid: TESTPGM\ntitle: Test program\nkind: batch\nmodule: core\nunit_code: {unit}\n"
            f"source_rev: b5e0139\nsources:\n  - {{path: app/cbl/TESTPGM.cbl, sha256: {sha}, role: program}}  # main\n"
            f"spec_status: draft\n---\n\n# TESTPGM\n\n{extra}" + "\n".join(sections))


class CheckSpecTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        (self.root / "app" / "cbl").mkdir(parents=True)
        (self.root / "app" / "jcl").mkdir(parents=True)
        (self.root / "app" / "cbl" / "TESTPGM.cbl").write_text(COBOL)
        (self.root / "app" / "jcl" / "TESTJOB.jcl").write_text(JCL)
        self.spec_dir = self.root / "docs" / "modernization" / "spec"
        (self.spec_dir / "programs").mkdir(parents=True)
        (self.spec_dir / "entities.md").write_text("# Entities\n\n## ENT-THING · A thing\n")
        (self.spec_dir / "components.md").write_text("| ID | Invariant |\n| :-- | :-- |\n| INV-001 | x |\n")
        self.sha = hashlib.sha256(COBOL.encode()).hexdigest()[:12]

    def tearDown(self) -> None:
        self.tmp.cleanup()

    def write(self, text: str, name: str = "TESTPGM.md") -> None:
        (self.spec_dir / "programs" / name).write_text(text)

    def run_checker(self, *args: str) -> tuple[int, str]:
        proc = subprocess.run([sys.executable, str(CHECKER), "--root", str(self.root), *args],
                              capture_output=True, text=True, timeout=60)
        return proc.returncode, proc.stdout + proc.stderr

    def assert_error(self, text: str, needle: str) -> None:
        self.write(text)
        rc, out = self.run_checker()
        self.assertEqual(rc, 1, out)
        self.assertIn(needle, out)

    def test_valid_spec_passes(self) -> None:
        self.write(spec_text(self.sha, extra="Uses ENT-THING and INV-001; see BR-TEST-001 and Q-TEST-001.\n"))
        rc, out = self.run_checker()
        self.assertEqual(rc, 0, out)
        self.assertIn("COVERAGE: 2/2", out)
        self.assertIn("check_spec: OK", out)

    def test_missing_paragraph_row(self) -> None:
        cov = COVERAGE.replace("| `2000-B` | 10-12 | BR-TEST-001 |\n", "")
        self.assert_error(spec_text(self.sha, coverage=cov), "paragraph 2000-B (app/cbl/TESTPGM.cbl:10-12) has no coverage row")

    def test_wrong_end_line(self) -> None:
        cov = COVERAGE.replace("| 10-12 |", "| 10-11 |")
        self.assert_error(spec_text(self.sha, coverage=cov), "paragraph 2000-B ends at line 12, not 11")

    def test_row_for_unknown_paragraph(self) -> None:
        cov = COVERAGE + "| `9999-NOPE` | 10-12 | BR-TEST-001 |\n"
        self.assert_error(spec_text(self.sha, coverage=cov), "coverage row for 9999-NOPE, which is not a paragraph")

    def test_main_line_needs_a_row(self) -> None:
        cov = COVERAGE.replace("| (procedure division main line) | 4-5 | BR-TEST-001 |\n", "")
        self.assert_error(spec_text(self.sha, coverage=cov), "the code before the first paragraph (app/cbl/TESTPGM.cbl:4-5)")

    def test_empty_items_cell(self) -> None:
        cov = COVERAGE.replace("| 7-8 | BR-TEST-001 |", "| 7-8 |  |")
        self.assert_error(spec_text(self.sha, coverage=cov), "the items cell is empty")

    def test_stale_source_hash(self) -> None:
        self.assert_error(spec_text("0" * 12), "changed since the spec was written")

    def test_citation_past_end_of_file(self) -> None:
        item = "#### BR-TEST-001 · A rule\n- **Source:** `:7-99`\n"
        self.assert_error(spec_text(self.sha, item=item), "app/cbl/TESTPGM.cbl has 12 lines")

    def test_citation_of_missing_file(self) -> None:
        item = "#### BR-TEST-001 · A rule\n- **Source:** `app/cbl/NOPE.cbl:1-2`\n"
        self.assert_error(spec_text(self.sha, item=item), "file app/cbl/NOPE.cbl does not exist")

    def test_dangling_references(self) -> None:
        self.assert_error(spec_text(self.sha, extra="See BR-TEST-001..003, ENT-NOPE and INV-002.\n"),
                          "reference BR-TEST-002 is not defined")
        _, out = self.run_checker()
        self.assertIn("entity ENT-NOPE is not in entities.md", out)
        self.assertIn("invariant INV-002 is not in components.md", out)

    def test_malformed_item_id(self) -> None:
        item = "#### RULE-TEST-001 · A rule\n- **Source:** `:7-8`\n"
        self.assert_error(spec_text(self.sha, item=item), "item ID RULE-TEST-001 is not <KIND>-<UNIT>-<NNN>")

    def test_item_of_another_unit(self) -> None:
        item = "#### BR-OTHR-001 · A rule\n- **Source:** `:7-8`\n"
        self.assert_error(spec_text(self.sha, item=item), "does not use the spec's unit_code TEST")

    def test_duplicate_item(self) -> None:
        item = "#### BR-TEST-001 · A rule\n- **Source:** `:7-8`\n\n#### BR-TEST-001 · Again\n- **Source:** `:7-8`\n"
        self.assert_error(spec_text(self.sha, item=item), "BR-TEST-001 is already defined")

    def test_missing_section(self) -> None:
        self.assert_error(spec_text(self.sha).replace("## 12. Section 12", "## Twelve"), "missing 12")

    def test_front_matter_values(self) -> None:
        text = spec_text(self.sha).replace("kind: batch", "kind: job").replace("spec_status: draft", "spec_status: done")
        self.assert_error(text, "kind 'job' is not one of")
        _, out = self.run_checker()
        self.assertIn("spec_status 'done' is not one of", out)

    def test_unit_code_unique_and_reserved(self) -> None:
        self.write(spec_text(self.sha))
        other = spec_text(self.sha).replace("id: TESTPGM", "id: OTHERPGM")
        self.write(other, "OTHERPGM.md")
        rc, out = self.run_checker()
        self.assertEqual(rc, 1, out)
        self.assertIn("unit_code TEST is also used by", out)
        (self.spec_dir / "programs" / "OTHERPGM.md").unlink()
        (self.spec_dir / "areas").mkdir()
        (self.spec_dir / "areas" / "README.md").write_text("| TESTPGM | `TPGM` | d1 |\n")
        rc, out = self.run_checker()
        self.assertEqual(rc, 1, out)
        self.assertIn("differs from the code reserved for TESTPGM in areas/README.md (TPGM)", out)

    def test_missing_source_line_is_a_warning(self) -> None:
        self.write(spec_text(self.sha, item="#### BR-TEST-001 · A rule\n- **When:** always.\n"))
        rc, out = self.run_checker()
        self.assertEqual(rc, 0, out)
        self.assertIn("warning", out)
        self.assertIn("BR-TEST-001 has no **Source:** line", out)

    def test_require_all_and_program_filter(self) -> None:
        self.write(spec_text(self.sha))
        rc, out = self.run_checker("--require-all")
        self.assertEqual(rc, 1, out)
        self.assertIn("app/jcl/TESTJOB.jcl: in-scope source has no spec", out)
        rc, out = self.run_checker("--program", "TESTPGM")
        self.assertEqual(rc, 0, out)
        rc, out = self.run_checker("--program", "NOPE")
        self.assertEqual(rc, 1, out)
        self.assertIn("no spec named NOPE", out)

    def write_job(self, coverage: str, jcl: str = JCL) -> None:
        (self.root / "app" / "jcl" / "TESTJOB.jcl").write_text(jcl)
        sha = hashlib.sha256(jcl.encode()).hexdigest()[:12]
        text = (spec_text(sha, unit="JTEST", coverage=coverage,
                          item="#### FLOW-JTEST-001 · Steps\n- **Source:** `:3-7`\n")
                .replace("id: TESTPGM", "id: TESTJOB").replace("kind: batch", "kind: job")
                .replace("app/cbl/TESTPGM.cbl", "app/jcl/TESTJOB.jcl").replace("role: program", "role: job")
                .replace("Q-TEST-001", "Q-JTEST-001"))
        (self.spec_dir / "jobs").mkdir(exist_ok=True)
        (self.spec_dir / "jobs" / "TESTJOB.md").write_text(text)
        (self.spec_dir / "jobs" / "README.md").write_text("# Operations view\n\nNo front matter here.\n")

    def test_job_spec_with_duplicate_step_names(self) -> None:
        jcl = JCL + "//STEP05 EXEC PGM=IEFBR14\n//*\n"
        cov = ("| Step | Lines | Covered by |\n| :-- | :-- | :-- |\n| (job statement) | 1-1 | FLOW-JTEST-001 |\n"
               "| `STEP05` | 3-4 | FLOW-JTEST-001 |\n| `STEP10` | 6-7 | FLOW-JTEST-001 |\n| `STEP05` | 8-8 | FLOW-JTEST-001 |\n")
        self.write_job(cov, jcl)
        rc, out = self.run_checker("--program", "TESTJOB")
        self.assertEqual(rc, 0, out)
        self.assertIn("COVERAGE: 3/3", out)
        self.write_job(cov.replace("| `STEP05` | 8-8 |", "| `STEP05` | 9-9 |"), jcl)
        rc, out = self.run_checker("--program", "TESTJOB")
        self.assertEqual(rc, 1, out)
        self.assertIn("step STEP05 starts at line 3, 8, not 9", out)
        self.assertIn("step STEP05 (app/jcl/TESTJOB.jcl:8-8) has no coverage row", out)

    def test_job_step_end_is_exact(self) -> None:
        cov = ("| Step | Lines | Covered by |\n| :-- | :-- | :-- |\n"
               "| `STEP05` | 3-5 | FLOW-JTEST-001 |\n| `STEP10` | 6-7 | FLOW-JTEST-001 |\n")
        self.write_job(cov)
        rc, out = self.run_checker("--program", "TESTJOB")
        self.assertEqual(rc, 1, out)
        self.assertIn("step STEP05 ends at line 4, not 5", out)
    def test_jcl_steps(self) -> None:
        steps = check_spec.jcl_steps(self.root / "app" / "jcl" / "TESTJOB.jcl")
        self.assertEqual(steps, [("STEP05", 3, 4), ("STEP10", 6, 7)])

    def test_cobol_paragraphs(self) -> None:
        paras, pre = check_spec.cobol_paragraphs(self.root / "app" / "cbl" / "TESTPGM.cbl")
        self.assertEqual(paras, [("1000-A", 7, 8), ("2000-B", 10, 12)])
        self.assertEqual(pre, (4, 5))

    def test_front_matter_parser(self) -> None:
        data, end = check_spec.parse_front_matter(
            "---\nid: X  # comment\nlist: [a, {k: v, n: [1, 2]}, \"q, r\"]\nitems:\n  - {path: a/b.cbl, role: program}\n"
            "  - plain\nempty: []\n---\nbody\n")
        self.assertEqual(end, 8)
        self.assertEqual(data["id"], "X")
        self.assertEqual(data["list"], ["a", {"k": "v", "n": ["1", "2"]}, "q, r"])
        self.assertEqual(data["items"], [{"path": "a/b.cbl", "role": "program"}, "plain"])
        self.assertEqual(data["empty"], [])
        with self.assertRaises(check_spec.FrontMatterError):
            check_spec.parse_front_matter("---\nid: [unclosed\n---\n")

    def test_repository_specs_pass(self) -> None:
        proc = subprocess.run([sys.executable, str(CHECKER)], capture_output=True, text=True, timeout=120)
        self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)


if __name__ == "__main__":
    unittest.main()
