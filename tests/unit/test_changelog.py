"""tools/changelog.py: commit classification, release ranges and rendering."""
from __future__ import annotations

import importlib.util
import subprocess
import sys
import unittest
from pathlib import Path

_spec = importlib.util.spec_from_file_location(
    "changelog", Path(__file__).resolve().parents[2] / "tools" / "changelog.py")
cl = importlib.util.module_from_spec(_spec)
sys.modules[_spec.name] = cl
_spec.loader.exec_module(cl)

F, R = cl.FIELD, cl.RECORD


def fake_git(tags: list[str], logs: dict[tuple[str, ...], list[tuple[str, str]]], base_present=True):
    calls = []

    def run(root, *args):
        calls.append(args)
        if args[0] == "tag":
            return "\n".join(tags) + "\n"
        if args[0] == "cat-file":
            if not base_present:
                raise subprocess.CalledProcessError(128, "git")
            return ""
        if args[:2] == ("log", "-1"):
            return "2026-01-02\n"
        if args[0] == "log":
            rng = tuple(a for a in args[3:])
            return "".join(f"{sha}{F}{subject}{R}\n" for sha, subject in logs.get(rng, []))
        raise AssertionError(args)

    return run, calls


class ClassifyTest(unittest.TestCase):
    def entry(self, subject):
        return cl.Commit("abcdef1234567", subject).entry()

    def test_conventional_types_and_scopes(self):
        self.assertEqual(self.entry("fix(batch): CBTRN02C overlimit check"),
                         ("Fixed", "- batch: CBTRN02C overlimit check (abcdef1)"))
        self.assertEqual(self.entry("build: add Makefile"), ("Build and CI", "- add Makefile (abcdef1)"))
        self.assertEqual(self.entry("ci: cache the image"), ("Build and CI", "- cache the image (abcdef1)"))
        self.assertEqual(self.entry("test: suite"), ("Tests", "- suite (abcdef1)"))
        self.assertEqual(self.entry("feat!: new format"), ("Added", "- **Breaking:** new format (abcdef1)"))
        self.assertEqual(self.entry("wip: thing"), ("Changed", "- thing (abcdef1)"))

    def test_plain_subjects_use_their_first_word(self):
        self.assertEqual(self.entry("Add runbooks for the batch cycle")[0], "Added")
        self.assertEqual(self.entry("Fix linter false positives")[0], "Fixed")
        self.assertEqual(self.entry("Document linting: README section")[0], "Documentation")
        self.assertEqual(self.entry("Remove old scripts")[0], "Removed")
        self.assertEqual(self.entry("Extend .gitignore for env files"),
                         ("Changed", "- Extend .gitignore for env files (abcdef1)"))


class CollectTest(unittest.TestCase):
    def test_commits_are_split_at_tags_and_upstream_is_excluded(self):
        base = f"^{cl.UPSTREAM_BASE}"
        run, calls = fake_git(["v0.2.0", "v0.1.0"], {
            ("HEAD", "^v0.2.0", base): [("c3", "fix: three")],
            ("v0.2.0", "^v0.1.0", base): [("c2", "Add two")],
            ("v0.1.0", base): [("c1", "build: one")],
        })
        releases = cl.collect(Path("."), run=run)
        self.assertEqual([(r.name, r.date, [c.sha for c in r.commits]) for r in releases],
                         [("Unreleased", None, ["c3"]), ("v0.2.0", "2026-01-02", ["c2"]),
                          ("v0.1.0", "2026-01-02", ["c1"])])
        self.assertIn(("tag", "--list", "v*", "--merged", "HEAD", "--sort=-creatordate"), calls)

    def test_empty_unreleased_is_dropped_and_shallow_clones_list_everything(self):
        run, _ = fake_git(["v1.0.0"], {("v1.0.0",): [("c1", "fix: one")]}, base_present=False)
        releases = cl.collect(Path("."), run=run)
        self.assertEqual([r.name for r in releases], ["v1.0.0"])

    def test_render_groups_sections_in_order(self):
        rel = cl.Release("v1.0.0", "2026-01-02", [cl.Commit("1111111aaa", "test: t"),
                                                  cl.Commit("2222222bbb", "fix: f"),
                                                  cl.Commit("3333333ccc", "Add a")])
        text = cl.render([rel])
        self.assertTrue(text.startswith("# Changelog\n"))
        body = text[text.index("## [v1.0.0] - 2026-01-02"):]
        self.assertEqual(body, "## [v1.0.0] - 2026-01-02\n\n### Added\n\n- Add a (3333333)\n\n"
                               "### Fixed\n\n- f (2222222)\n\n### Tests\n\n- t (1111111)\n")

    def test_runs_against_this_repository(self):
        # Works in a shallow CI checkout too (the upstream base may be absent).
        releases = cl.collect(cl.ROOT)
        self.assertTrue(releases and releases[0].commits)
        self.assertTrue(all(len(c.sha) == 40 for r in releases for c in r.commits))


if __name__ == "__main__":
    unittest.main()
