#!/usr/bin/env python3
"""Generate CHANGELOG.md, or one release's notes, from the git history.

    python3 tools/changelog.py                # rewrite CHANGELOG.md
    python3 tools/changelog.py --stdout       # print it instead
    python3 tools/changelog.py --notes v1.2.0 # notes for one release (used by release.yml)

Releases are the v* tags, newest first; commits after the newest tag are
"Unreleased". Merge commits are skipped. Each commit lands in a section
by its Conventional Commits type ("fix(batch): ...") or, for plain
subjects, by its first word ("Add ...", "Fix ..."). Commits up to
UPSTREAM_BASE, the AWS CardDemo import this work starts from, are left
out. Standard library only.
"""
from __future__ import annotations

import argparse
import re
import subprocess
import sys
from dataclasses import dataclass, field
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUTPUT = ROOT / "CHANGELOG.md"
UPSTREAM_BASE = "59cc6c2fd7ebd7ef7925cad552a01a4b8b6e4d5e"

SECTIONS = ["Added", "Changed", "Fixed", "Removed", "Security", "Documentation", "Build and CI", "Tests"]
TYPES = {"feat": "Added", "fix": "Fixed", "docs": "Documentation", "build": "Build and CI",
         "ci": "Build and CI", "test": "Tests", "refactor": "Changed", "perf": "Changed",
         "style": "Changed", "chore": "Changed", "revert": "Changed", "security": "Security"}
VERBS = {"add": "Added", "added": "Added", "adding": "Added", "create": "Added", "introduce": "Added",
         "fix": "Fixed", "fixed": "Fixed", "fixes": "Fixed", "correct": "Fixed",
         "remove": "Removed", "removed": "Removed", "delete": "Removed", "drop": "Removed",
         "document": "Documentation", "documented": "Documentation", "docs": "Documentation"}
CONVENTIONAL = re.compile(r"^(?P<type>[a-z]+)(\((?P<scope>[^)]+)\))?(?P<bang>!)?: (?P<desc>.+)$")
FIELD, RECORD = "\x1f", "\x1e"


@dataclass
class Commit:
    sha: str
    subject: str

    def entry(self) -> tuple[str, str]:
        """(section, Markdown line) for this commit."""
        m = CONVENTIONAL.match(self.subject)
        if m:
            section = TYPES.get(m["type"], "Changed")
            text = m["desc"]
            if m["scope"]:
                text = f"{m['scope']}: {text}"
            if m["bang"]:
                text = f"**Breaking:** {text}"
        else:
            first = self.subject.split(maxsplit=1)[0].lower().strip(":,") if self.subject else ""
            section, text = VERBS.get(first, "Changed"), self.subject
        return section, f"- {text} ({self.sha[:7]})"


@dataclass
class Release:
    name: str
    date: str | None
    commits: list[Commit] = field(default_factory=list)

    def body(self) -> str:
        grouped: dict[str, list[str]] = {}
        for c in self.commits:
            section, line = c.entry()
            grouped.setdefault(section, []).append(line)
        if not grouped:
            return "No changes.\n"
        parts = []
        for section in SECTIONS:
            if section in grouped:
                parts.append(f"### {section}\n\n" + "\n".join(grouped[section]) + "\n")
        return "\n".join(parts)

    def heading(self) -> str:
        return f"## [{self.name}]" + (f" - {self.date}" if self.date else "")


def git(root: Path, *args: str) -> str:
    return subprocess.run(["git", "-C", str(root), *args], capture_output=True, text=True,
                          check=True).stdout


def parse_log(text: str) -> list[Commit]:
    commits = []
    for rec in text.split(RECORD):
        rec = rec.strip("\n")
        if rec:
            sha, subject = rec.split(FIELD, 1)
            commits.append(Commit(sha, subject.strip()))
    return commits


def has_commit(root: Path, rev: str, run=git) -> bool:
    try:
        run(root, "cat-file", "-e", f"{rev}^{{commit}}")
        return True
    except subprocess.CalledProcessError:
        return False


def collect(root: Path, head: str = "HEAD", run=git) -> list[Release]:
    """Releases newest first, starting with Unreleased (dropped when empty)."""
    tags = run(root, "tag", "--list", "v*", "--merged", head, "--sort=-creatordate").split()
    # A shallow clone may not contain the upstream base; then everything is listed.
    exclude = [f"^{UPSTREAM_BASE}"] if has_commit(root, UPSTREAM_BASE, run) else []
    revs = [head, *tags]
    releases = []
    for i, rev in enumerate(revs):
        older = [f"^{revs[i + 1]}"] if i + 1 < len(revs) else []
        commits = parse_log(run(root, "log", "--no-merges", f"--format=%H{FIELD}%s{RECORD}",
                                rev, *older, *exclude))
        if i == 0:
            if commits:
                releases.append(Release("Unreleased", None, commits))
            continue
        date = run(root, "log", "-1", "--format=%ad", "--date=short", rev).strip()
        releases.append(Release(rev, date, commits))
    return releases


def render(releases: list[Release]) -> str:
    out = ["# Changelog", "",
           "Notable changes, newest first. This file is generated from the git history",
           "by `python3 tools/changelog.py` (`make changelog`): change commit messages,",
           f"not this file. History up to {UPSTREAM_BASE[:7]}, the upstream AWS CardDemo",
           "import, is not listed.", ""]
    for r in releases:
        out += [r.heading(), "", r.body()]
    return "\n".join(out).rstrip("\n") + "\n"


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    group = ap.add_mutually_exclusive_group()
    group.add_argument("--stdout", action="store_true", help="print the changelog instead of writing it")
    group.add_argument("--notes", metavar="TAG", help="print the notes of one release")
    args = ap.parse_args(argv)
    releases = collect(ROOT)
    if args.notes:
        match = [r for r in releases if r.name == args.notes]
        if not match:
            print(f"no release {args.notes!r}; known: {[r.name for r in releases]}", file=sys.stderr)
            return 1
        print(match[0].body(), end="")
        return 0
    text = render(releases)
    if args.stdout:
        print(text, end="")
    else:
        OUTPUT.write_text(text)
        print(f"wrote {OUTPUT.relative_to(ROOT)} ({sum(len(r.commits) for r in releases)} commits)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
