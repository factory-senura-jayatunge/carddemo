"""Rule tests for scripts/cobol_lint.py.

Each test builds a throwaway repository in a temporary directory with the real
cobol-lint.json, writes a small source that breaks (or must not break) one rule
and runs the linter's CLI against it. Run with:

    python3 -m unittest discover -s scripts/tests -v
"""
from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
LINT = REPO / "scripts" / "cobol_lint.py"
HAVE_COBC = shutil.which("cobc") is not None


def card(code: str = "", ind: str = " ", seq: str = "      ", tag: str = "") -> str:
    """One fixed-format line: sequence area, indicator, code area, optional tag."""
    line = seq + ind + code
    if tag:
        line = line.ljust(72) + tag
    return line


def program(name: str, proc: list[str], ws: list[str] | None = None, extra_id: str | None = None) -> str:
    lines = [
        card("IDENTIFICATION DIVISION."),
        card(f"PROGRAM-ID. {extra_id or name}."),
        card("DATA DIVISION."),
        card("WORKING-STORAGE SECTION."),
        *(ws or [card("01  WS-FLAG                PIC X VALUE 'N'.")]),
        card("PROCEDURE DIVISION."),
        *proc,
    ]
    return "\n".join(lines) + "\n"


class LintRepo:
    def __init__(self, tmp: Path):
        self.root = tmp
        shutil.copy(REPO / "cobol-lint.json", tmp / "cobol-lint.json")

    def write(self, rel: str, text: str | bytes) -> Path:
        p = self.root / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        if isinstance(text, bytes):
            p.write_bytes(text)
        else:
            p.write_text(text, encoding="latin-1")
        return p

    def run(self, *args: str) -> subprocess.CompletedProcess:
        return subprocess.run([sys.executable, str(LINT), "--root", str(self.root), *args],
                              capture_output=True, text=True)

    def findings(self, *args: str, cmd: str = "lint") -> list[str]:
        out = self.run(cmd, "--no-baseline", *args).stdout
        return [l for l in out.splitlines() if " [" in l and "] " in l and l.split(":", 1)[0].startswith("app/")]


GOOD_MAIN = [
    card("0000-MAIN."),
    card("    PERFORM 1000-WORK"),
    card("    GOBACK."),
    card("1000-WORK."),
    card("    MOVE 'Y' TO WS-FLAG."),
]


class RuleTests(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.repo = LintRepo(Path(self._tmp.name))

    def tearDown(self):
        self._tmp.cleanup()

    def rules_hit(self, *args, cmd="lint"):
        return [l.split("[", 1)[1].split("]", 1)[0] for l in self.repo.findings(*args, cmd=cmd)]

    def assertRule(self, rule, *args, cmd="lint"):
        hits = self.rules_hit(*args, cmd=cmd)
        self.assertIn(rule, hits, f"{rule} not reported; got {hits}")

    def assertNoRule(self, rule, *args, cmd="lint"):
        hits = self.rules_hit(*args, cmd=cmd)
        self.assertNotIn(rule, hits, f"{rule} unexpectedly reported")

    # -- a clean program produces no findings at all
    def test_clean_program(self):
        self.repo.write("app/cbl/CBGOOD1C.cbl", program("CBGOOD1C", GOOD_MAIN))
        self.assertEqual(self.repo.findings(), [])
        self.assertEqual(self.repo.run("lint").returncode, 0)

    # -- format
    def test_crlf(self):
        self.repo.write("app/cbl/CBCRLF1C.cbl", program("CBCRLF1C", GOOD_MAIN).replace("\n", "\r\n").encode())
        self.assertRule("crlf-line-ending")

    def test_tab(self):
        src = program("CBTAB01C", GOOD_MAIN).replace("    MOVE 'Y'", "\tMOVE 'Y'")
        self.repo.write("app/cbl/CBTAB01C.cbl", src)
        self.assertRule("tab-character")

    def test_trailing_whitespace_but_not_record_padding(self):
        lines = program("CBTWS01C", GOOD_MAIN).splitlines()
        lines[0] = lines[0] + "   "
        lines[1] = lines[1].ljust(80)
        self.repo.write("app/cbl/CBTWS01C.cbl", "\n".join(lines) + "\n")
        hits = [l for l in self.repo.findings() if "[trailing-whitespace]" in l]
        self.assertEqual(len(hits), 1, hits)
        self.assertTrue(hits[0].startswith("app/cbl/CBTWS01C.cbl:1:"), hits)

    def test_line_too_long(self):
        src = program("CBLONG1C", GOOD_MAIN).replace("    GOBACK.", "    GOBACK.".ljust(65) + "00010000XX")
        self.repo.write("app/cbl/CBLONG1C.cbl", src)
        self.assertRule("line-too-long")

    def test_text_past_column_72(self):
        good = program("CBSEQ01C", [card(l[7:], tag="00010000") for l in GOOD_MAIN])
        self.repo.write("app/cbl/CBSEQ01C.cbl", good)
        self.assertNoRule("text-past-column-72")
        good_line = card("    PERFORM 1000-WORK", tag="00010000")
        self.assertIn(good_line, good)
        bad = good.replace(good_line, card("    PERFORM 1000-WORK", tag=" WS-FLAG"))
        self.assertNotEqual(bad, good)
        self.repo.write("app/cbl/CBSEQ01C.cbl", bad)
        self.assertRule("text-past-column-72")

    def test_invalid_indicator(self):
        src = program("CBIND01C", GOOD_MAIN + [card(" bad", ind="X")])
        self.repo.write("app/cbl/CBIND01C.cbl", src)
        self.assertRule("invalid-indicator")

    def test_sequence_area_text(self):
        src = program("CBSQA01C", GOOD_MAIN + [card("*****", ind="*", seq="  ****")])
        self.repo.write("app/cbl/CBSQA01C.cbl", src)
        self.assertRule("sequence-area-text")

    def test_continuation_area_a(self):
        ws = [card("01  WS-LIT PIC X(80) VALUE 'ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789ABCDEFGHIJ"),
              card("X'.", ind="-")]
        self.repo.write("app/cbl/CBCNT01C.cbl", program("CBCNT01C", GOOD_MAIN, ws=ws))
        self.assertRule("continuation-area-a")

    def test_missing_final_newline(self):
        self.repo.write("app/cbl/CBNLN01C.cbl", program("CBNLN01C", GOOD_MAIN).rstrip("\n"))
        self.assertRule("missing-final-newline")

    # -- structure
    def test_unresolved_copy_and_external_members(self):
        ws = [card("COPY NOSUCHCP."), card("COPY DFHAID.")]
        self.repo.write("app/cbl/COUNR01C.cbl", program("COUNR01C", GOOD_MAIN + [card("    EXEC CICS RETURN END-EXEC.")], ws=ws))
        hits = [l for l in self.repo.findings() if "[unresolved-copy]" in l]
        self.assertEqual(len(hits), 1, hits)
        self.assertIn("NOSUCHCP", hits[0])

    def test_unterminated_exec(self):
        proc = [card("0000-MAIN."), card("    EXEC CICS RETURN"), card("    EXEC CICS RETURN END-EXEC.")]
        self.repo.write("app/cbl/COEXC01C.cbl", program("COEXC01C", proc))
        self.assertRule("unterminated-exec")

    def test_duplicate_paragraph(self):
        self.repo.write("app/cbl/CBDUP01C.cbl", program("CBDUP01C", GOOD_MAIN + [card("1000-WORK."), card("    CONTINUE.")]))
        self.assertRule("duplicate-paragraph")

    def test_stop_run_in_cics(self):
        proc = [card("0000-MAIN."), card("    EXEC CICS RETURN END-EXEC"), card("    STOP RUN.")]
        self.repo.write("app/cbl/COSTP01C.cbl", program("COSTP01C", proc))
        self.assertRule("stop-run-in-cics")

    # -- naming
    def test_program_id_mismatch(self):
        self.repo.write("app/cbl/CBPID01C.cbl", program("CBPID01C", GOOD_MAIN, extra_id="OTHERPGM"))
        self.assertRule("program-id-mismatch")

    def test_program_id_on_next_line_matches(self):
        src = program("CBPID02C", GOOD_MAIN).replace("PROGRAM-ID. CBPID02C.", "PROGRAM-ID.\n" + card("    CBPID02C."))
        self.repo.write("app/cbl/CBPID02C.cbl", src)
        self.assertNoRule("program-id-mismatch")

    def test_program_name_format(self):
        self.repo.write("app/cbl/CBTOOLONG1.cbl", program("CBTOOLONG1", GOOD_MAIN))
        self.assertRule("program-name-format")

    def test_program_prefix(self):
        proc = [card("0000-MAIN."), card("    EXEC CICS RETURN END-EXEC.")]
        self.repo.write("app/cbl/CBONL01C.cbl", program("CBONL01C", proc))
        self.repo.write("app/cbl/XXONL01C.cbl", program("XXONL01C", proc))
        hits = [l for l in self.repo.findings() if "[program-prefix]" in l]
        self.assertEqual(len(hits), 2, hits)
        self.assertEqual(sorted(h.split(":")[0] for h in hits), ["app/cbl/CBONL01C.cbl", "app/cbl/XXONL01C.cbl"], hits)

    def test_copybook_name_and_prefix(self):
        self.repo.write("app/cpy/CVTOOLONG.cpy", card("01  X PIC X.") + "\n")
        self.repo.write("app/cpy/ZZREC01Y.cpy", card("01  Y PIC X.") + "\n")
        self.repo.write("app/cbl/CBUSE01C.cbl", program("CBUSE01C", GOOD_MAIN, ws=[card("COPY CVTOOLONG."), card("COPY ZZREC01Y.")]))
        hits = self.rules_hit()
        self.assertIn("copybook-name-format", hits)
        self.assertIn("copybook-prefix", hits)

    def test_bms_copybook_name(self):
        self.repo.write("app/bms/COMAP01.bms", "dummy\n")
        self.repo.write("app/cpy-bms/COMAP01.CPY", card("01  COMAP01I PIC X.") + "\n")
        self.repo.write("app/cpy-bms/COMAP99.CPY", card("01  COMAP99I PIC X.") + "\n")
        hits = [l for l in self.repo.findings() if "[bms-copybook-name]" in l]
        self.assertEqual(len(hits), 1, hits)
        self.assertIn("COMAP99", hits[0])

    def test_paragraph_naming(self):
        proc = [card("0000-MAIN."), card("    PERFORM 1000-A"), card("    PERFORM 2000-B"),
                card("    PERFORM 3000-C"), card("    PERFORM HELPER"), card("    GOBACK."),
                card("1000-A."), card("    CONTINUE."), card("2000-B."), card("    CONTINUE."),
                card("3000-C."), card("    CONTINUE."), card("HELPER."), card("    CONTINUE.")]
        self.repo.write("app/cbl/CBNAM01C.cbl", program("CBNAM01C", proc))
        hits = [l for l in self.repo.findings() if "[paragraph-naming]" in l]
        self.assertEqual(len(hits), 1, hits)
        self.assertIn("HELPER", hits[0])

    # -- complexity and size
    def test_paragraph_and_program_complexity(self):
        ifs = []
        for i in range(160):
            ifs += [card(f"    IF WS-FLAG = 'A' OR WS-FLAG = 'B'"), card("        CONTINUE"), card("    END-IF")]
        proc = [card("0000-MAIN.")] + ifs + [card("    GOBACK.")]
        self.repo.write("app/cbl/CBCPX01C.cbl", program("CBCPX01C", proc))
        hits = self.rules_hit()
        self.assertIn("paragraph-complexity", hits)
        self.assertIn("program-complexity", hits)

    def test_complexity_below_threshold(self):
        proc = [card("0000-MAIN."), card("    IF WS-FLAG = 'A' AND WS-FLAG NOT = 'B'"),
                card("        CONTINUE"), card("    END-IF"), card("    GOBACK.")]
        self.repo.write("app/cbl/CBCPX02C.cbl", program("CBCPX02C", proc))
        self.assertNoRule("paragraph-complexity")

    def test_source_size(self):
        filler = [card("    CONTINUE") for _ in range(3100)]
        proc = [card("0000-MAIN.")] + filler + [card("    GOBACK.")]
        self.repo.write("app/cbl/CBBIG01C.cbl", program("CBBIG01C", proc))
        self.assertRule("source-size")

    def test_large_file(self):
        self.repo.write("app/data/huge.bin", b"\0" * (1024 * 1024 + 1))
        self.repo.write("app/cbl/CBGOOD1C.cbl", program("CBGOOD1C", GOOD_MAIN))
        out = self.repo.run("lint", "--no-baseline").stdout
        self.assertIn("app/data/huge.bin:1: error [large-file]", out)

    # -- dead code
    def test_unreferenced_paragraph(self):
        proc = GOOD_MAIN + [card("9000-NEVER-CALLED."), card("    MOVE 'Z' TO WS-FLAG.")]
        self.repo.write("app/cbl/CBDED01C.cbl", program("CBDED01C", proc))
        hits = [l for l in self.repo.findings() if "[unreferenced-paragraph]" in l]
        self.assertEqual(len(hits), 1, hits)
        self.assertIn("9000-NEVER-CALLED", hits[0])

    def test_fall_through_and_thru_ranges_are_reached(self):
        proc = [card("0000-MAIN."), card("    PERFORM 1000-A THRU 1000-EXIT"), card("    MOVE 'X' TO WS-FLAG."),
                card("0100-NEXT."), card("    GOBACK."),
                card("1000-A."), card("    CONTINUE."), card("1000-EXIT."), card("    EXIT.")]
        self.repo.write("app/cbl/CBDED02C.cbl", program("CBDED02C", proc))
        self.assertNoRule("unreferenced-paragraph")

    def test_unreachable_code(self):
        proc = [card("0000-MAIN."), card("    GOBACK"), card("    MOVE 'Y' TO WS-FLAG.")]
        self.repo.write("app/cbl/CBUNR01C.cbl", program("CBUNR01C", proc))
        self.assertRule("unreachable-code")

    def test_conditional_transfer_is_not_unreachable(self):
        ws = [card("01  WS-FLAG PIC X."), card("01  WS-MSG PIC X(20).")]
        proc = [card("0000-MAIN."),
                card("    IF WS-FLAG = 'Y'"),
                card("       STRING 'A' DELIMITED BY SIZE INTO WS-MSG"),
                card("       END-STRING"),
                card("       READ-LIKE-NAME-IS-NOT-A-VERB"),
                card("       GO TO 0000-EXIT"),
                card("    END-IF"),
                card("    MOVE 'N' TO WS-FLAG."),
                card("0000-EXIT."), card("    GOBACK.")]
        self.repo.write("app/cbl/CBUNR02C.cbl", program("CBUNR02C", proc, ws=ws))
        self.assertNoRule("unreachable-code")

    # -- duplicates
    def test_duplicate_block(self):
        body = [card(f"    MOVE {i} TO WS-COUNT-{i}") for i in range(14)]
        for name in ("CBDUP11C", "CBDUP12C"):
            self.repo.write(f"app/cbl/{name}.cbl", program(name, [card("0000-MAIN.")] + body + [card("    GOBACK.")]))
        hits = [l for l in self.repo.findings() if "[duplicate-block]" in l]
        self.assertEqual(len(hits), 1, hits)
        self.assertIn("CBDUP12C", hits[0])
        self.assertIn("duplicate app/cbl/CBDUP11C.cbl", hits[0])

    # -- modularity and dependencies
    def test_batch_uses_cics_copybook(self):
        self.repo.write("app/bms/COMAP01.bms", "dummy\n")
        self.repo.write("app/cpy-bms/COMAP01.CPY", card("01  COMAP01I PIC X.") + "\n")
        ws = [card("COPY DFHAID."), card("COPY COMAP01.")]
        self.repo.write("app/cbl/CBBAT01C.cbl", program("CBBAT01C", GOOD_MAIN, ws=ws))
        hits = [l for l in self.repo.findings() if "[batch-uses-cics-copybook]" in l]
        self.assertEqual(len(hits), 2, hits)

    def test_module_boundary(self):
        self.repo.write("app/app-ext-a/cpy/CVEXT01Y.cpy", card("01  EXT-REC PIC X.") + "\n")
        self.repo.write("app/cbl/CBMOD01C.cbl", program("CBMOD01C", GOOD_MAIN, ws=[card("COPY CVEXT01Y.")]))
        self.repo.write("app/app-ext-b/cbl/CBMOD02C.cbl", program("CBMOD02C", GOOD_MAIN, ws=[card("COPY CVEXT01Y.")]))
        self.repo.write("app/app-ext-a/cbl/CBMOD03C.cbl", program("CBMOD03C", GOOD_MAIN, ws=[card("COPY CVEXT01Y.")]))
        hits = [l for l in self.repo.findings() if "[module-boundary]" in l]
        self.assertEqual(sorted(h.split(":")[0] for h in hits),
                         ["app/app-ext-b/cbl/CBMOD02C.cbl", "app/cbl/CBMOD01C.cbl"], hits)

    def test_unused_copybook(self):
        self.repo.write("app/cpy/CVUSED1Y.cpy", card("01  A PIC X.") + "\n")
        self.repo.write("app/cpy/CVIDLE1Y.cpy", card("01  B PIC X.") + "\n")
        self.repo.write("app/cbl/CBCPY01C.cbl", program("CBCPY01C", GOOD_MAIN, ws=[card("COPY CVUSED1Y.")]))
        hits = [l for l in self.repo.findings() if "[unused-copybook]" in l]
        self.assertEqual(len(hits), 1, hits)
        self.assertIn("CVIDLE1Y", hits[0])

    # -- tech debt
    def test_tech_debt_marker(self):
        proc = GOOD_MAIN + [card(" TODO handle the error", ind="*"), card(" TODO(#42) tracked", ind="*"),
                            card(" FIXME(CARD-17) tracked", ind="*"), card(" format xxx-xx-xxxx / PIC XXX", ind="*")]
        self.repo.write("app/cbl/CBTDM01C.cbl", program("CBTDM01C", proc))
        hits = [l for l in self.repo.findings() if "[tech-debt-marker]" in l]
        self.assertEqual(len(hits), 1, hits)
        self.assertIn("TODO without an issue reference", hits[0])

    # -- performance
    def test_n_plus_one(self):
        proc = [card("0000-MAIN."),
                card("    PERFORM 1000-NEXT-ROW UNTIL WS-FLAG = 'Y'"),
                card("    GOBACK."),
                card("1000-NEXT-ROW."),
                card("    EXEC SQL FETCH C1 INTO :WS-FLAG END-EXEC"),
                card("    PERFORM 2000-LOOKUP."),
                card("2000-LOOKUP."),
                card("    EXEC SQL SELECT A INTO :WS-FLAG FROM T END-EXEC.")]
        self.repo.write("app/cbl/CBNPO01C.cbl", program("CBNPO01C", proc))
        hits = [l for l in self.repo.findings() if "[n-plus-one]" in l]
        self.assertEqual(len(hits), 1, hits)
        self.assertIn("EXEC SQL SELECT", hits[0])

    def test_inline_loop_n_plus_one_and_no_loop(self):
        proc = [card("0000-MAIN."),
                card("    PERFORM UNTIL WS-FLAG = 'Y'"),
                card("       EXEC DLI GN USING PCB(1) END-EXEC"),
                card("       EXEC DLI GU USING PCB(2) END-EXEC"),
                card("    END-PERFORM"),
                card("    EXEC SQL SELECT A INTO :WS-FLAG FROM T END-EXEC"),
                card("    GOBACK.")]
        self.repo.write("app/cbl/CBNPO02C.cbl", program("CBNPO02C", proc))
        hits = [l for l in self.repo.findings() if "[n-plus-one]" in l]
        self.assertEqual(len(hits), 1, hits)
        self.assertIn("EXEC DLI GU", hits[0])

    # -- data handling
    def test_sensitive_display(self):
        self.repo.write("app/cpy/CVCRD99Y.cpy", "\n".join([
            card("01  CARD-RECORD."), card("    05  CARD-NUM        PIC X(16)."),
            card("    05  CARD-ACCT-ID    PIC 9(11)."), card("    05  CARD-CVV-CD     PIC 9(03).")]) + "\n")
        ws = [card("COPY CVCRD99Y."), card("01  WS-COUNT PIC 9(4).")]
        proc = [card("0000-MAIN."), card("    DISPLAY 'COUNT: ' WS-COUNT"),
                card("    DISPLAY 'ACCT: ' CARD-ACCT-ID"),
                card("    DISPLAY CARD-RECORD"), card("    DISPLAY 'CVV ' CARD-CVV-CD"), card("    GOBACK.")]
        src = program("CBSEN01C", proc, ws=ws)
        self.repo.write("app/cbl/CBSEN01C.cbl", src)
        lines = src.splitlines()
        expected = [str(lines.index(card(c)) + 1) for c in ("    DISPLAY CARD-RECORD", "    DISPLAY 'CVV ' CARD-CVV-CD")]
        hits = [l for l in self.repo.findings() if "[sensitive-display]" in l]
        self.assertEqual([h.split(":")[1] for h in hits], expected, hits)

    def test_tabbed_copybook_is_analysed_with_tab_width(self):
        self.repo.write("app/cpy/CUTAB01Y.cpy", "\n".join([
            card("01  CUSTOMER-RECORD."), "\t\t     05  CUST-SSN        PIC 9(09)."]) + "\n")
        proc = [card("0000-MAIN."), card("    DISPLAY CUST-SSN"), card("    GOBACK.")]
        self.repo.write("app/cbl/CBTAB02C.cbl", program("CBTAB02C", proc, ws=[card("COPY CUTAB01Y.")]))
        hits = self.rules_hit()
        self.assertIn("tab-character", hits)
        self.assertIn("sensitive-display", hits)

    # -- JCL
    def test_jcl_rules(self):
        jcl = "\n".join([
            "//CBJOB01 JOB (ACCT),'TEST',CLASS=A",
            "//STEP01   EXEC PGM=IEBGENER",
            "//SYSIN    DD *",
            "  THIS IS IN-STREAM DATA AND NOT A STATEMENT",
            "/*",
            "  STRAY TEXT",
            "//STEPNAME12 EXEC PGM=IEFBR14",
        ]) + "\n"
        self.repo.write("app/jcl/CBJOB01.jcl", jcl)
        hits = self.repo.findings()
        fmt = [h for h in hits if "[jcl-statement-format]" in h]
        names = [h for h in hits if "[jcl-name-field]" in h]
        self.assertEqual([h.split(":")[1] for h in fmt], ["6"], hits)
        self.assertEqual([h.split(":")[1] for h in names], ["7"], hits)
        self.repo.write("app/jcl/TOOLONGJCL.jcl", "//CBJOB02 JOB (ACCT),'TEST'\n")
        self.assertIn("app/jcl/TOOLONGJCL.jcl:1: error [jcl-name-field]", self.repo.run("lint", "--no-baseline").stdout)

    # -- cobc
    @unittest.skipUnless(HAVE_COBC, "cobc not installed")
    def test_cobc_syntax_and_skip(self):
        bad = program("CBSYN01C", [card("0000-MAIN."), card("    MOVE UNDEFINED-ITEM TO WS-FLAG"), card("    GOBACK.")])
        bad_line = bad.splitlines().index(card("    MOVE UNDEFINED-ITEM TO WS-FLAG")) + 1
        self.repo.write("app/cbl/CBSYN01C.cbl", bad)
        self.repo.write("app/cbl/COSYN02C.cbl", program("COSYN02C", [card("0000-MAIN."), card("    EXEC CICS RETURN END-EXEC.")]))
        r = self.repo.run("syntax", "--no-baseline")
        self.assertEqual(r.returncode, 1, r.stdout + r.stderr)
        self.assertIn(f"app/cbl/CBSYN01C.cbl:{bad_line}: error [cobc-syntax] 'UNDEFINED-ITEM' is not defined", r.stdout)
        self.assertIn("skipped app/cbl/COSYN02C.cbl: contains EXEC CICS", r.stdout)

    @unittest.skipUnless(HAVE_COBC, "cobc not installed")
    def test_cobc_clean_program(self):
        self.repo.write("app/cbl/CBGOOD1C.cbl", program("CBGOOD1C", GOOD_MAIN))
        r = self.repo.run("check")
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        self.assertIn("1 programs compiled with -fsyntax-only, 0 skipped", r.stdout)


class WorkflowTests(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.repo = LintRepo(Path(self._tmp.name))

    def tearDown(self):
        self._tmp.cleanup()

    def test_baseline_only_fails_on_new_findings(self):
        proc = GOOD_MAIN + [card("9000-DEAD."), card("    CONTINUE.")]
        self.repo.write("app/cbl/CBBAS01C.cbl", program("CBBAS01C", proc))
        self.assertEqual(self.repo.run("lint").returncode, 1)
        if HAVE_COBC:
            self.assertEqual(self.repo.run("baseline").returncode, 0)
        else:
            self.skipTest("baseline needs cobc")
        r = self.repo.run("lint")
        self.assertEqual(r.returncode, 0, r.stdout)
        self.assertIn("unreferenced-paragraph", r.stdout)  # still counted in the summary
        # moving the known finding to another line keeps it baselined
        self.repo.write("app/cbl/CBBAS01C.cbl", program("CBBAS01C", [card("* moved", ind="*")] + proc))
        self.assertEqual(self.repo.run("lint").returncode, 0)
        # a new finding fails
        self.repo.write("app/cbl/CBBAS01C.cbl", program("CBBAS01C", proc + [card("9100-DEAD-TOO."), card("    CONTINUE.")]))
        r = self.repo.run("lint")
        self.assertEqual(r.returncode, 1)
        self.assertIn("9100-DEAD-TOO", r.stdout)
        self.assertNotIn("9000-DEAD is never", r.stdout)

    def test_fix_normalises_and_is_idempotent(self):
        lines = program("CBFIX01C", GOOD_MAIN).splitlines()
        lines[1] = lines[1] + "   "
        lines[2] = lines[2].ljust(80)
        lines[5] = "\t" + lines[5].lstrip()
        lines.append(card("    DISPLAY 'A\tB'."))
        raw = ("\r\n".join(lines) + "\r\n").encode()
        p = self.repo.write("app/cbl/CBFIX01C.cbl", raw)
        r = self.repo.run("fix", "--check", str(p))
        self.assertEqual(r.returncode, 1)
        self.assertEqual(p.read_bytes(), raw, "--check must not modify the file")
        r = self.repo.run("fix", str(p))
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn("tab inside a literal left for manual review", r.stdout)
        fixed = p.read_bytes().decode("latin-1")
        self.assertNotIn("\r", fixed)
        out = fixed.split("\n")
        self.assertEqual(out[1], lines[1].rstrip())
        self.assertEqual(len(out[2]), 80, "record padding to 80 columns is kept")
        self.assertEqual(out[5], " " * 4 + lines[5].lstrip("\t"))
        self.assertIn("\t", out[-2], "a tab inside a literal is data and is not expanded")
        self.assertEqual(self.repo.run("fix", "--check", str(p)).returncode, 0, "second run changes nothing")
        hits = [h for h in self.repo.findings() if "CBFIX01C" in h]
        self.assertEqual([h for h in hits if "[crlf-line-ending]" in h or "[trailing-whitespace]" in h], [])

    def test_staged_mode(self):
        if shutil.which("git") is None:
            self.skipTest("git not installed")
        root = self.repo.root
        env = {**os.environ, "GIT_AUTHOR_NAME": "t", "GIT_AUTHOR_EMAIL": "t@example.com",
               "GIT_COMMITTER_NAME": "t", "GIT_COMMITTER_EMAIL": "t@example.com"}
        subprocess.run(["git", "init", "-q"], cwd=root, check=True)
        self.repo.write("app/cbl/CBSTG01C.cbl", program("CBSTG01C", GOOD_MAIN + [card("9000-DEAD."), card("    CONTINUE.")]))
        self.repo.write("app/cbl/CBSTG02C.cbl", program("CBSTG02C", GOOD_MAIN))
        subprocess.run(["git", "add", "app/cbl/CBSTG02C.cbl"], cwd=root, check=True, env=env)
        r = self.repo.run("check", "--staged", "--allow-missing-cobc")
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)  # the unstaged file's finding is not reported
        subprocess.run(["git", "add", "app/cbl/CBSTG01C.cbl"], cwd=root, check=True, env=env)
        r = self.repo.run("check", "--staged", "--allow-missing-cobc")
        self.assertEqual(r.returncode, 1)
        self.assertIn("9000-DEAD", r.stdout)
        self.repo.write("app/data/huge.bin", b"\0" * (1024 * 1024 + 1))
        subprocess.run(["git", "rm", "-q", "--cached", "app/cbl/CBSTG01C.cbl"], cwd=root, check=True, env=env)
        subprocess.run(["git", "add", "app/data/huge.bin"], cwd=root, check=True, env=env)
        r = self.repo.run("check", "--staged", "--allow-missing-cobc")
        self.assertEqual(r.returncode, 1)
        self.assertIn("app/data/huge.bin:1: error [large-file]", r.stdout)

    def test_config_must_list_every_rule(self):
        cfg = json.loads((self.repo.root / "cobol-lint.json").read_text())
        del cfg["rules"]["n-plus-one"]
        (self.repo.root / "cobol-lint.json").write_text(json.dumps(cfg))
        r = self.repo.run("lint")
        self.assertEqual(r.returncode, 2)
        self.assertIn("missing: n-plus-one", r.stderr)


if __name__ == "__main__":
    unittest.main()
