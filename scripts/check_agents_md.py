#!/usr/bin/env python3
"""Check that the commands and paths in AGENTS.md and the project skills still exist.

Agents follow AGENTS.md and .factory/skills/*/SKILL.md literally, so a renamed
make target, script, option or path there sends them down a dead end. This
reads the inline code spans, Markdown links and shell code blocks of those
files and checks that:

  * every repository path exists, or is git-ignored output such as build/;
  * `make TARGET VAR=value` names a Makefile target and variables the Makefile
    uses, and `make -n` accepts it; the Python commands that make would run are
    then checked like direct commands;
  * `python3 SCRIPT ...` names an existing script; for argparse scripts the
    subcommand and every option appear in `SCRIPT --help` (and in
    `SCRIPT SUBCOMMAND --help`); a shell wrapper that execs a Python script is
    checked through that script;
  * every make target with a `## ` help comment is documented in AGENTS.md.

Nothing is built or run except `make -n` and `--help` of argparse scripts.

Usage:
    python3 scripts/check_agents_md.py            # AGENTS.md and every skill
    python3 scripts/check_agents_md.py FILE...    # only these Markdown files

Exit codes: 0 = everything resolves, 1 = problems found, 2 = usage error.
"""

from __future__ import annotations

import argparse
import fnmatch
import os
import re
import shlex
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SKILLS_GLOB = ".factory/skills/*/SKILL.md"
SHELL_FENCES = {"", "sh", "bash", "shell", "console"}
INTERPRETERS = {"python3": "python", "python": "python", "sh": "sh", "bash": "sh"}
OPERATORS = {"&&", "||", "|", ";"}
FILE_EXT = (r"md|py|sh|toml|json|ya?ml|txt|cbl|cpy|jcl|prc|proc|bms|csd|awk|asm|mac|ctl"
            r"|template|example|cfg|ini|xml|csv")
FILE_RE = re.compile(r"[\w*<>.-]+\.(?:" + FILE_EXT + r")", re.I)
ENV_ASSIGN_RE = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*=")
MAKE_ARG_RE = re.compile(r"^(?:[a-z][\w-]*|[A-Za-z_][A-Za-z0-9_]*=.*)$")
SKIP_DIRS = {".git", "build", "dist", "__pycache__", "node_modules"}


def make_targets(makefile: str) -> set[str]:
    return set(re.findall(r"^([A-Za-z][\w.-]*)\s*:(?!=)", makefile, re.M))


def documented_targets(makefile: str) -> list[str]:
    return re.findall(r"^([A-Za-z][\w-]*):[^=\n]*## ", makefile, re.M)


def heading_slugs(text: str) -> set[str]:
    """GitHub's anchors for the Markdown headings of a file."""
    slugs: set[str] = set()
    seen: dict[str, int] = {}
    in_fence = False
    for line in text.splitlines():
        if re.match(r"^\s*(```|~~~)", line):
            in_fence = not in_fence
        m = None if in_fence else re.match(r"^#{1,6}\s+(.*?)\s*#*\s*$", line)
        if not m:
            continue
        slug = re.sub(r"[^\w\- ]", "", m.group(1).strip().lower()).replace(" ", "-")
        n = seen.get(slug, 0)
        seen[slug] = n + 1
        slugs.add(slug if n == 0 else f"{slug}-{n}")
    return slugs


def scan(text: str):
    """Yield (line number, kind, text) for each code span, link and shell command line."""
    fence = None
    lang = ""
    heredoc = None
    for lineno, line in enumerate(text.splitlines(), 1):
        stripped = line.strip()
        if fence is None:
            m = re.match(r"^\s*(`{3,}|~{3,})\s*([\w+-]*)", line)
            if m:
                fence, lang, heredoc = m.group(1), m.group(2).lower(), None
                continue
            for span in re.findall(r"`([^`]+)`", line):
                yield lineno, "span", span.strip()
            for target in re.findall(r"\]\(([^)\s]+)\)", re.sub(r"`[^`]*`", "", line)):
                yield lineno, "link", target
            continue
        if stripped.startswith(fence) and not stripped.strip(fence[0]):
            fence = None
            continue
        if lang not in SHELL_FENCES:
            continue
        if heredoc is not None:
            if stripped == heredoc:
                heredoc = None
            continue
        if stripped and not stripped.startswith("#"):
            yield lineno, "command", stripped
        m = re.search(r"<<-?\s*['\"]?(\w+)['\"]?", line)
        if m:
            heredoc = m.group(1)


def split_commands(line: str) -> list[list[str]]:
    try:
        tokens = shlex.split(line, comments=True)
    except ValueError:
        return []
    commands: list[list[str]] = [[]]
    for tok in tokens:
        if tok in OPERATORS:
            commands.append([])
        else:
            commands[-1].append(tok)
    return [strip_env(c) for c in commands if strip_env(c)]


def strip_env(tokens: list[str]) -> list[str]:
    i = 0
    while i < len(tokens) and ENV_ASSIGN_RE.match(tokens[i]):
        i += 1
    return tokens[i:]


def looks_like_path(token: str, dotfiles: bool = False) -> bool:
    if not token or token.startswith(("-", "/", "~", "$")) or "://" in token:
        return False
    if re.search(r"[\s=()\[\]{}\"'`,;:|]", token):
        return False
    if "/" in token:
        return True
    if dotfiles and re.fullmatch(r"\.[\w-][\w.-]*", token):
        return True
    return bool(FILE_RE.fullmatch(token))


class Checker:
    def __init__(self, root: Path = ROOT) -> None:
        self.root = root.resolve()
        self.agents_md = self.root / "AGENTS.md"
        self.makefile = (self.root / "Makefile").read_text()
        self.targets = make_targets(self.makefile)
        self.errors: list[str] = []
        self.counts = {"commands": 0, "paths": 0, "links": 0}
        self.make_documented: set[str] = set()
        self._memo: dict[tuple, list[str]] = {}
        self._help: dict[tuple, tuple[int, str]] = {}
        self._tree: list[str] | None = None

    # -- reporting -------------------------------------------------------
    def memo(self, where: str, key: tuple, fn) -> None:
        if key not in self._memo:
            self._memo[key] = fn()
        self.errors.extend(f"{where}: {msg}" for msg in self._memo[key])

    # -- documents -------------------------------------------------------
    def check_doc(self, doc: Path) -> None:
        rel = doc.relative_to(self.root).as_posix() if doc.is_relative_to(self.root) else str(doc)
        is_agents = doc == self.agents_md
        for lineno, kind, text in scan(doc.read_text()):
            where = f"{rel}:{lineno}"
            if kind == "link":
                self.counts["links"] += 1
                self.memo(where, ("link", doc, text), lambda: self.link_problems(doc, text))
            elif kind == "command":
                for tokens in split_commands(text):
                    self.check_command(where, tokens, is_agents)
            elif self.is_command_span(text):
                for tokens in split_commands(text):
                    self.check_command(where, tokens, is_agents)
            elif looks_like_path(text):
                self.counts["paths"] += 1
                self.memo(where, ("path", text), lambda: self.path_problems(text))

    @staticmethod
    def is_command_span(span: str) -> bool:
        cmds = split_commands(span)
        if len(cmds) != 1:
            return False
        tokens = cmds[0]
        if tokens[0] == "make":
            return len(tokens) > 1 and all(MAKE_ARG_RE.match(t) for t in tokens[1:])
        if tokens[0] in INTERPRETERS:
            return len(tokens) > 1
        return tokens[0].endswith((".py", ".sh")) and len(tokens) > 1

    # -- commands --------------------------------------------------------
    def check_command(self, where: str, tokens: list[str], is_agents: bool) -> None:
        self.counts["commands"] += 1
        head, args = tokens[0], tokens[1:]
        if head == "make":
            if is_agents:
                self.make_documented.update(a for a in args if "=" not in a and not a.startswith("-"))
            self.memo(where, ("make", *args), lambda: self.make_problems(args))
        elif head in INTERPRETERS or head.endswith((".py", ".sh")):
            self.memo(where, ("run", *tokens), lambda: self.interpreter_problems(tokens))
        else:
            for arg in args:
                if looks_like_path(arg, dotfiles=True):
                    self.counts["paths"] += 1
                    self.memo(where, ("path", arg), lambda arg=arg: self.path_problems(arg))

    def make_problems(self, args: list[str]) -> list[str]:
        problems = []
        targets = [a for a in args if "=" not in a and not a.startswith("-")]
        for target in targets:
            if target not in self.targets:
                problems.append(f"make target '{target}' is not defined in the Makefile")
        for var in (a.split("=", 1)[0] for a in args if "=" in a):
            if not re.search(r"\$[({]" + re.escape(var) + r"[)}]", self.makefile):
                problems.append(f"make variable {var} is not used by the Makefile")
        if problems:
            return problems
        env = {k: v for k, v in os.environ.items() if k not in ("MAKEFLAGS", "MAKELEVEL", "MFLAGS")}
        proc = subprocess.run(["make", "-n", *args], cwd=self.root, env=env,
                              capture_output=True, text=True, timeout=60)
        cmd = "make -n " + shlex.join(args)
        if proc.returncode != 0:
            last = (proc.stderr.strip().splitlines() or ["(no output)"])[-1]
            return [f"`{cmd}` failed: {last}"]
        for line in proc.stdout.splitlines():
            for tokens in split_commands(line):
                if tokens[0] in INTERPRETERS or tokens[0].endswith((".py", ".sh")):
                    problems += [f"{p} (run by `make {shlex.join(args)}`)"
                                 for p in self.interpreter_problems(tokens)]
        return problems

    def interpreter_problems(self, tokens: list[str]) -> list[str]:
        head, args = tokens[0], tokens[1:]
        if head in INTERPRETERS:
            if not args or args[0] in ("-", "-c"):
                return []
            if args[0] == "-m":
                return [p for a in args[2:] if looks_like_path(a) for p in self.path_problems(a)]
            kind, script, args = INTERPRETERS[head], args[0], args[1:]
        else:
            kind, script = ("python" if head.endswith(".py") else "sh"), head
        path = self.resolve(script)
        if path is None:
            return [f"script {script} does not exist"]
        source = path.read_text(errors="replace")
        if kind == "sh":
            m = re.search(r'^\s*exec\s+python3?\s+(\S+\.py)((?:\s+[\w-]+)*)\s+"\$@"', source, re.M)
            if not m:
                return []
            return self.interpreter_problems(["python3", m.group(1), *m.group(2).split(), *args])
        if "argparse" not in source:
            return []
        return self.arg_problems(path, [], args)

    def help_text(self, path: Path, sub: list[str]) -> tuple[int, str]:
        key = (path, *sub)
        if key not in self._help:
            proc = subprocess.run([sys.executable, str(path), *sub, "--help"], cwd=self.root,
                                  capture_output=True, text=True, timeout=60)
            self._help[key] = (proc.returncode, proc.stdout + proc.stderr)
        return self._help[key]

    def arg_problems(self, path: Path, sub: list[str], args: list[str]) -> list[str]:
        rel = path.relative_to(self.root).as_posix()
        shown = " ".join([rel, *sub, "--help"])
        rc, text = self.help_text(path, sub)
        if rc != 0:
            return [f"`python3 {shown}` exited {rc}"]
        usage = text.split("\n\n", 1)[0]
        bare = usage
        while True:
            reduced = re.sub(r"\[[^\[\]]*\]", "", bare)
            if reduced == bare:
                break
            bare = reduced
        m = re.search(r"\{([\w,-]+)\}", bare)
        subcommands = m.group(1).split(",") if m else []
        takes_value = set(re.findall(r"\[(-{1,2}[\w-]+) [^\[\]]+\]", usage))
        problems = []
        i = 0
        while i < len(args):
            arg = args[i]
            if arg.startswith("-") and arg != "-":
                flag = arg.split("=", 1)[0]
                if not re.search(r"(?<![\w-])" + re.escape(flag) + r"(?![\w-])", text):
                    problems.append(f"option {flag} is not in `python3 {shown}`")
                if flag in takes_value and "=" not in arg:
                    i += 1
            elif subcommands:
                if arg not in subcommands:
                    return problems + [f"subcommand '{arg}' is not in `python3 {shown}` "
                                       f"({', '.join(subcommands)})"]
                return problems + self.arg_problems(path, [*sub, arg], args[i + 1:])
            elif looks_like_path(arg):
                problems += self.path_problems(arg)
            i += 1
        return problems

    # -- paths -----------------------------------------------------------
    def tree(self) -> list[str]:
        if self._tree is None:
            entries = []
            for dirpath, dirnames, filenames in os.walk(self.root):
                dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
                base = Path(dirpath).relative_to(self.root).as_posix()
                for name in dirnames + filenames:
                    entries.append(name if base == "." else f"{base}/{name}")
            self._tree = entries
        return self._tree

    def resolve(self, name: str) -> Path | None:
        if (self.root / name).is_file():
            return self.root / name
        if "/" not in name:
            hits = [e for e in self.tree() if e.endswith("/" + name) and (self.root / e).is_file()]
            if len(hits) == 1:
                return self.root / hits[0]
        return None

    def exists(self, rel: str) -> bool:
        pattern = re.sub(r"<[^>]*>", "*", rel).rstrip("/")
        want_dir = rel.endswith("/")
        top = pattern.split("/", 1)[0]
        if "*" in top or not (self.root / top).exists():
            # A bare name or a path relative to a directory named in the text
            # (e.g. `unit/cobol/` under tests/): it must exist somewhere.
            hits = [e for e in self.tree()
                    if fnmatch.fnmatchcase(e, pattern) or fnmatch.fnmatchcase(e, "*/" + pattern)]
        elif "*" in pattern:
            hits = [p.relative_to(self.root).as_posix() for p in self.root.glob(pattern)]
        else:
            hits = [pattern] if (self.root / pattern).exists() else []
        return any((self.root / h).is_dir() for h in hits) if want_dir else bool(hits)

    def git_ignored(self, rel: str) -> bool:
        concrete = re.sub(r"<[^>]*>", "x", rel)
        try:
            proc = subprocess.run(["git", "check-ignore", "-q", "--no-index", "--", concrete],
                                  cwd=self.root, capture_output=True, timeout=30)
        except OSError:
            return False
        return proc.returncode == 0

    def path_problems(self, rel: str) -> list[str]:
        if self.exists(rel) or self.git_ignored(rel):
            return []
        return [f"path {rel} does not exist"]

    def link_problems(self, doc: Path, target: str) -> list[str]:
        if re.match(r"^[a-z][a-z0-9+.-]*:", target, re.I):
            return []
        file_part, _, anchor = target.partition("#")
        dest = (doc.parent / file_part).resolve() if file_part else doc
        if not dest.exists():
            return [f"link target {target} does not exist"]
        if anchor and dest.suffix.lower() == ".md" and anchor not in heading_slugs(dest.read_text()):
            return [f"link anchor #{anchor} is not a heading in {file_part or doc.name}"]
        return []

    # -- Makefile coverage -----------------------------------------------
    def check_targets_documented(self) -> None:
        for target in documented_targets(self.makefile):
            if target not in self.make_documented:
                self.errors.append(f"AGENTS.md: make target '{target}' (Makefile) is not documented")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Check that the commands and paths in AGENTS.md and the project skills exist.")
    parser.add_argument("files", nargs="*", type=Path,
                        help=f"Markdown files to check (default: AGENTS.md and {SKILLS_GLOB})")
    parser.add_argument("--root", type=Path, default=ROOT,
                        help="repository root (default: this script's repository)")
    args = parser.parse_args(argv)

    docs = [f if f.is_absolute() else Path.cwd() / f for f in args.files] or \
        [args.root / "AGENTS.md", *sorted(args.root.glob(SKILLS_GLOB))]
    checker = Checker(args.root)
    for doc in docs:
        if not doc.is_file():
            print(f"check_agents_md: {doc} does not exist", file=sys.stderr)
            return 2
        checker.check_doc(doc.resolve())
    if checker.agents_md in {d.resolve() for d in docs}:
        checker.check_targets_documented()

    for error in checker.errors:
        print(error)
    c = checker.counts
    status = f"{len(checker.errors)} problem(s)" if checker.errors else "OK"
    print(f"check_agents_md: {len(docs)} file(s), {c['commands']} commands, {c['paths']} paths, "
          f"{c['links']} links: {status}")
    return 1 if checker.errors else 0


if __name__ == "__main__":
    sys.exit(main())
