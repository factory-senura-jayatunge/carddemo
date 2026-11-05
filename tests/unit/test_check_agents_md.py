"""scripts/check_agents_md.py: drift between AGENTS.md or a skill and the tree it describes."""
from __future__ import annotations

import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

CHECKER = Path(__file__).resolve().parents[2] / "scripts" / "check_agents_md.py"

MAKEFILE = """\
PYTHON ?= python3
PROGRAM ?= POSTTRAN
TEST_ARGS ?=

help: ## List the targets
\t@echo targets

run-batch: ## Run one job: make run-batch PROGRAM=POSTTRAN
\t@$(PYTHON) tools/runner.py run $(PROGRAM)

test-unit: ## Unit tests without the coverage minimum
\t@$(PYTHON) tests/run_tests.py --suite unit --coverage-min 0 $(TEST_ARGS)

docs-check: ## Fail if the generated docs are stale
\t@$(PYTHON) scripts/gen.py --check

clean: ## Remove build output
\trm -rf build
"""

AGENTS_MD = """\
# AGENTS.md

Read [the guide](docs/guide.md), in particular [naming](docs/guide.md#naming-conventions).

| Command | What it does |
|:--|:--|
| `make help` | Lists the targets. |
| `make run-batch PROGRAM=POSTTRAN` | Runs one job. |
| `make test-unit TEST_ARGS="-k posttran"` | Only the matching unit tests. |
| `make docs-check` | Fails when the docs are stale. |
| `make clean` | Removes `build/`. |

```sh
python3 tools/runner.py run POSTTRAN --workdir /tmp/posttran   # a private scratch directory
python3 tests/run_tests.py --suite unit --coverage-min 0
python3 scripts/gen.py --check
scripts/lint.sh --no-baseline
python3 scripts/lint.py rules
python3 tools/plain.py
python3 - <<'EOF'
import missing.heredoc_module  # inside a heredoc: never checked
EOF
```

Settings are in `config/settings.toml` (`settings.toml` for short); runs write to `build/run/<JOB>`.
"""

SKILL_MD = """\
---
name: demo
description: >-
  Use when linting the demo settings.
---

```sh
python3 scripts/lint.py check config/settings.toml --no-baseline
```
"""

RUNNER = """\
import argparse

parser = argparse.ArgumentParser()
sub = parser.add_subparsers(dest="command", required=True)
run = sub.add_parser("run")
run.add_argument("--workdir")
run.add_argument("name")
sub.add_parser("list")
parser.parse_args()
"""

RUN_TESTS = """\
import argparse

parser = argparse.ArgumentParser()
parser.add_argument("--suite", choices=["unit", "integration", "all"], default="all")
parser.add_argument("--coverage-min", type=float)
parser.add_argument("-k", dest="pattern")
parser.parse_args()
"""

GEN = """\
import argparse

parser = argparse.ArgumentParser()
parser.add_argument("--check", action="store_true")
parser.parse_args()
"""

LINT = """\
import argparse

parser = argparse.ArgumentParser()
sub = parser.add_subparsers(dest="command", required=True)
check = sub.add_parser("check")
check.add_argument("--no-baseline", action="store_true")
check.add_argument("paths", nargs="*")
sub.add_parser("rules")
parser.parse_args()
"""

LINT_SH = """\
#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
exec python3 scripts/lint.py check "$@"
"""

# No option parser: the checker must only confirm it exists, never run it.
PLAIN = """\
from pathlib import Path

Path(__file__).with_name("ran.marker").write_text("ran")
"""

GUIDE = "# Guide\n\n## Naming conventions\n\nEight characters at most.\n"

SKILL = ".factory/skills/demo/SKILL.md"
TREE = {
    "Makefile": MAKEFILE,
    "AGENTS.md": AGENTS_MD,
    SKILL: SKILL_MD,
    ".gitignore": "build/\n",
    "tools/runner.py": RUNNER,
    "tools/plain.py": PLAIN,
    "tests/run_tests.py": RUN_TESTS,
    "scripts/gen.py": GEN,
    "scripts/lint.py": LINT,
    "scripts/lint.sh": LINT_SH,
    "docs/guide.md": GUIDE,
    "config/settings.toml": "business_date = 2022-07-06\n",
}


def line_of(text: str, needle: str) -> int:
    return next(n for n, line in enumerate(text.splitlines(), 1) if needle in line)


@unittest.skipUnless(shutil.which("make") and shutil.which("git"), "needs make and git")
class CheckAgentsMdTest(unittest.TestCase):
    def make_tree(self, edits=()) -> Path:
        """A small repository; each edit is (path, old, new) applied to the files above."""
        root = Path(tempfile.mkdtemp(prefix="check-agents-md-"))
        self.addCleanup(shutil.rmtree, root)
        files = dict(TREE)
        for path, old, new in edits:
            self.assertIn(old, files[path], "fixture edit must change something")
            files[path] = files[path].replace(old, new)
        for path, text in files.items():
            (root / path).parent.mkdir(parents=True, exist_ok=True)
            (root / path).write_text(text)
        subprocess.run(["git", "init", "-q"], cwd=root, check=True)
        return root

    @staticmethod
    def run_checker(root: Path, *files: str) -> subprocess.CompletedProcess:
        # A subprocess, not main() with redirect_stdout: run_tests.py runs tests
        # in threads, and swapping sys.stdout would capture other tests' output.
        return subprocess.run([sys.executable, str(CHECKER), "--root", str(root),
                               *(str(root / f) for f in files)],
                              capture_output=True, text=True, timeout=120)

    def check(self, root: Path, *files: str) -> tuple[int, list[str], str]:
        proc = self.run_checker(root, *files)
        lines = proc.stdout.splitlines()
        self.assertTrue(lines, proc.stderr)
        return proc.returncode, lines[:-1], lines[-1]

    def test_consistent_tree_passes(self):
        root = self.make_tree()
        rc, problems, summary = self.check(root)
        self.assertEqual((rc, problems), (0, []))
        self.assertRegex(summary, r"^check_agents_md: 2 file\(s\), \d+ commands, \d+ paths, 2 links: OK$")

    def test_only_scripts_with_an_option_parser_are_run(self):
        root = self.make_tree()
        self.assertEqual(self.check(root)[0], 0)
        self.assertTrue((root / "tools" / "plain.py").is_file())
        self.assertFalse((root / "tools" / "ran.marker").exists(), "plain.py was executed")

    def assert_drift(self, edits, expected):
        """Apply the edits, then expect exactly these problems.

        Each expected problem is (text on the reported line, message); a line of
        None means a problem reported for AGENTS.md as a whole.
        """
        root = self.make_tree(edits)
        doc = edits[0][0] if edits[0][0].endswith(".md") else "AGENTS.md"
        text = (root / doc).read_text()
        want = [f"{doc}:{line_of(text, needle)}: {msg}" if needle else f"AGENTS.md: {msg}"
                for needle, msg in expected]
        rc, problems, summary = self.check(root)
        self.assertEqual(rc, 1)
        self.assertEqual(sorted(problems), sorted(want))
        self.assertTrue(summary.endswith(f": {len(want)} problem(s)"), summary)

    # One test per kind of drift, so the runner can run them in parallel.
    def test_unknown_make_target(self):
        self.assert_drift([("AGENTS.md", "`make docs-check`", "`make docs-chek`")],
                          [("make docs-chek", "make target 'docs-chek' is not defined in the Makefile"),
                           (None, "make target 'docs-check' (Makefile) is not documented")])

    def test_make_variable_the_makefile_does_not_use(self):
        self.assert_drift([("AGENTS.md", "PROGRAM=POSTTRAN` |", "PROG=POSTTRAN` |")],
                          [("PROG=POSTTRAN", "make variable PROG is not used by the Makefile")])

    def test_unknown_option_passed_through_a_make_variable(self):
        self.assert_drift([("AGENTS.md", 'TEST_ARGS="-k posttran"', 'TEST_ARGS="-x posttran"')],
                          [("-x posttran", "option -x is not in `python3 tests/run_tests.py --help` "
                                           "(run by `make test-unit 'TEST_ARGS=-x posttran'`)")])

    def test_unknown_option(self):
        self.assert_drift([("AGENTS.md", "scripts/gen.py --check", "scripts/gen.py --verify")],
                          [("--verify", "option --verify is not in `python3 scripts/gen.py --help`")])

    def test_unknown_subcommand(self):
        self.assert_drift([("AGENTS.md", "scripts/lint.py rules", "scripts/lint.py rule")],
                          [("lint.py rule", "subcommand 'rule' is not in `python3 scripts/lint.py --help` "
                                            "(check, rules)")])

    def test_unknown_option_through_a_shell_wrapper(self):
        self.assert_drift([("AGENTS.md", "lint.sh --no-baseline", "lint.sh --no-baselines")],
                          [("--no-baselines",
                            "option --no-baselines is not in `python3 scripts/lint.py check --help`")])

    def test_missing_script(self):
        self.assert_drift([("AGENTS.md", "tools/runner.py run", "tools/runer.py run")],
                          [("tools/runer.py", "script tools/runer.py does not exist")])

    def test_missing_path(self):
        self.assert_drift([("AGENTS.md", "`config/settings.toml`", "`config/setings.toml`")],
                          [("config/setings.toml", "path config/setings.toml does not exist")])

    def test_missing_link_target(self):
        self.assert_drift([("AGENTS.md", "(docs/guide.md)", "(docs/gide.md)")],
                          [("docs/gide.md", "link target docs/gide.md does not exist")])

    def test_missing_link_anchor(self):
        self.assert_drift([("AGENTS.md", "#naming-conventions", "#naming")],
                          [("#naming)", "link anchor #naming is not a heading in docs/guide.md")])

    def test_makefile_target_missing_from_agents_md(self):
        self.assert_drift([("AGENTS.md", "| `make clean` | Removes `build/`. |\n", "")],
                          [(None, "make target 'clean' (Makefile) is not documented")])

    def test_missing_path_in_a_skill(self):
        self.assert_drift([(SKILL, "check config/settings.toml", "check config/missing.toml")],
                          [("config/missing.toml", "path config/missing.toml does not exist")])

    def test_option_renamed_in_the_script(self):
        self.assert_drift([("tests/run_tests.py", '"--coverage-min"', '"--min-coverage"')],
                          [("make test-unit", "option --coverage-min is not in `python3 tests/run_tests.py "
                                              "--help` (run by `make test-unit 'TEST_ARGS=-k posttran'`)"),
                           ("run_tests.py --suite",
                            "option --coverage-min is not in `python3 tests/run_tests.py --help`")])
    def test_git_ignored_output_counts_as_present(self):
        root = self.make_tree([(".gitignore", "build/\n", "")])
        rc, problems, _ = self.check(root)
        self.assertEqual(rc, 1)
        line = line_of(AGENTS_MD, "build/run/<JOB>")
        self.assertEqual(problems, [f"AGENTS.md:{line_of(AGENTS_MD, '`make clean`')}: path build/ does not exist",
                                    f"AGENTS.md:{line}: path build/run/<JOB> does not exist"])

    def test_named_files_only(self):
        root = self.make_tree([("AGENTS.md", "`make docs-check`", "`make docs-chek`")])
        rc, problems, summary = self.check(root, SKILL)
        self.assertEqual((rc, problems), (0, []))
        self.assertIn(": 1 file(s), ", summary)

    def test_missing_file_is_a_usage_error(self):
        root = self.make_tree()
        proc = self.run_checker(root, "NOPE.md")
        self.assertEqual(proc.returncode, 2)
        self.assertIn("NOPE.md does not exist", proc.stderr)


if __name__ == "__main__":
    unittest.main()
