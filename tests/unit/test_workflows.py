"""The GitHub workflows pin their actions and call targets that exist."""
from __future__ import annotations

import os
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
WORKFLOWS = ROOT / ".github" / "workflows"


def make_targets() -> set[str]:
    return set(re.findall(r"^([a-z][a-z-]*):", (ROOT / "Makefile").read_text(), re.M))


def job_ids(text: str) -> list[str]:
    jobs = text[text.index("\njobs:\n"):]
    return re.findall(r"^  ([a-z][a-z0-9-]*):$", jobs, re.M)


class WorkflowsTest(unittest.TestCase):
    def test_every_action_is_pinned_to_a_commit_with_its_version(self):
        for wf in sorted(WORKFLOWS.glob("*.yml")):
            uses = re.findall(r"uses: (\S+)(.*)$", wf.read_text(), re.M)
            self.assertTrue(uses, wf.name)
            for ref, comment in uses:
                with self.subTest(workflow=wf.name, action=ref):
                    self.assertRegex(ref, r"^[\w.-]+/[\w.-]+@[0-9a-f]{40}$")
                    self.assertRegex(comment, r"^ # v\d+\.\d+\.\d+$")

    def test_same_action_uses_the_same_pin_everywhere(self):
        pins: dict[str, set[str]] = {}
        for wf in WORKFLOWS.glob("*.yml"):
            for name, sha in re.findall(r"uses: ([\w.-]+/[\w.-]+)@([0-9a-f]{40})", wf.read_text()):
                pins.setdefault(name, set()).add(sha)
        self.assertEqual({k: v for k, v in pins.items() if len(v) > 1}, {})

    def test_jobs_and_least_privilege(self):
        ci = (WORKFLOWS / "ci.yml").read_text()
        release = (WORKFLOWS / "release.yml").read_text()
        self.assertEqual(job_ids(ci), ["lint", "docs-check", "build-and-test"])
        self.assertEqual(job_ids(release), ["release-build", "publish"])
        for text in (ci, release):
            self.assertIn("\npermissions:\n  contents: read\n", text)
        self.assertEqual(ci.count("contents: write"), 0)
        self.assertEqual(release.count("contents: write"), 1)

    def test_workflows_call_existing_make_targets_and_scripts(self):
        targets = make_targets()
        for wf in WORKFLOWS.glob("*.yml"):
            text = wf.read_text()
            for target in re.findall(r"(?:^|run: |ci-run\.sh )make ([a-z][a-z-]*)", text, re.M):
                with self.subTest(workflow=wf.name, target=target):
                    self.assertIn(target, targets)
            for script in re.findall(r"((?:tools|scripts)/[\w.-]+\.(?:py|sh))", text):
                with self.subTest(workflow=wf.name, script=script):
                    self.assertTrue((ROOT / script).is_file(), script)
        self.assertTrue(os.access(ROOT / "tools" / "ci-run.sh", os.X_OK))

    def test_ci_runs_the_same_steps_as_make_check(self):
        ci = (WORKFLOWS / "ci.yml").read_text()
        check = re.search(r"^check:.*\n\t@\$\(PYTHON\) tools/check\.py (.+)$",
                          (ROOT / "Makefile").read_text(), re.M).group(1).split()
        for target in check:
            with self.subTest(target=target):
                self.assertRegex(ci, rf"make {target}\b")


if __name__ == "__main__":
    unittest.main()
