"""A static audit of the refusal mode's folder helper (PH-12, D-8).

python3 -m unittest discover -s modernization/verify/differential/tests

It reads the helper's source with ast and never runs it: the imports, the
os.open flag sets, and the calls and names a read-only, single-line helper
must never use.
"""
from __future__ import annotations

import ast
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
HELPER = (HERE.parent / "src" / "main" / "resources" / "org" / "carddemo" / "verify" / "differential"
          / "work_folder_helper.py")

ALLOWED_IMPORTS = {"base64", "errno", "hashlib", "json", "os", "stat", "sys"}
FOLDER_FLAGS = frozenset({"O_RDONLY", "O_DIRECTORY", "O_NOFOLLOW", "O_NONBLOCK", "O_CLOEXEC"})
MARKER_FLAGS = frozenset({"O_RDONLY", "O_NOFOLLOW", "O_NONBLOCK", "O_CLOEXEC"})
CHANGES = {"write", "writelines", "remove", "unlink", "rename", "renames", "replace", "mkdir", "makedirs",
           "rmdir", "removedirs", "chmod", "fchmod", "lchmod", "chown", "fchown", "lchown", "utime",
           "symlink", "link", "truncate", "ftruncate", "mkfifo", "mknod"}
PROCESSES = {"kill", "killpg", "system", "popen", "fork", "forkpty", "spawnl", "spawnle", "spawnlp",
             "spawnlpe", "spawnv", "spawnve", "spawnvp", "spawnvpe", "posix_spawn", "posix_spawnp",
             "execl", "execle", "execlp", "execlpe", "execv", "execve", "execvp", "execvpe", "fdopen",
             "getenv", "getenvb", "putenv", "unsetenv", "environ", "environb", "argv", "orig_argv"}
BUILTINS = {"open", "print", "input", "eval", "exec", "compile", "__import__", "getattr", "setattr",
            "delattr", "globals", "locals", "vars", "breakpoint"}
MODULES = {"subprocess", "socket", "signal", "io", "shutil", "pathlib", "importlib", "ctypes", "tempfile",
           "multiprocessing", "threading", "pty", "fcntl", "resource"}
#: The one write the helper makes: its single line, to its own stdout.
STDOUT_WRITE = "sys.stdout.buffer.write"


def tree() -> ast.Module:
    return ast.parse(HELPER.read_bytes(), str(HELPER))


def dotted(node: ast.AST) -> str | None:
    parts = []
    while isinstance(node, ast.Attribute):
        parts.append(node.attr)
        node = node.value
    if not isinstance(node, ast.Name):
        return None
    parts.append(node.id)
    return ".".join(reversed(parts))


def flag_names(node: ast.AST) -> list[str] | None:
    """The os.O_* names of a pure `|` chain, or None for anything else."""
    if isinstance(node, ast.BinOp) and isinstance(node.op, ast.BitOr):
        left, right = flag_names(node.left), flag_names(node.right)
        return None if left is None or right is None else left + right
    name = dotted(node)
    if name is not None and name.startswith("os.O_") and name.count(".") == 1:
        return [name[3:]]
    return None


class HelperAuditTest(unittest.TestCase):

    def setUp(self) -> None:
        self.tree = tree()
        self.nodes = list(ast.walk(self.tree))

    def test_imports_only_the_listed_standard_modules(self) -> None:
        imported = set()
        for node in self.nodes:
            if isinstance(node, ast.Import):
                imported.update(alias.name for alias in node.names)
                self.assertTrue(all(alias.asname is None for alias in node.names), ast.dump(node))
            elif isinstance(node, ast.ImportFrom):
                self.fail(f"from-import at line {node.lineno}")
        self.assertEqual(imported, ALLOWED_IMPORTS)

    def test_no_builtin_or_io_open_and_no_dynamic_lookup(self) -> None:
        called = {id(n.func) for n in self.nodes if isinstance(n, ast.Call)}
        for node in self.nodes:
            if isinstance(node, ast.Name):
                self.assertNotIn(node.id, BUILTINS, f"line {node.lineno}")
                self.assertNotIn(node.id, MODULES, f"line {node.lineno}")
            if isinstance(node, ast.Attribute):
                self.assertFalse(node.attr.startswith("__") and node.attr != "__init__",
                                 f"line {node.lineno}: {node.attr}")
                if node.attr == "open" and dotted(node) != "os.open":
                    # supported() probes osmod.open's dir_fd support; it never calls it.
                    self.assertEqual(dotted(node), "osmod.open", f"line {node.lineno}")
                    self.assertNotIn(id(node), called, f"line {node.lineno}")

    def test_os_open_uses_only_the_two_flag_sets(self) -> None:
        calls = [n for n in self.nodes if isinstance(n, ast.Call) and dotted(n.func) == "os.open"]
        self.assertTrue(calls)
        seen = set()
        for call in calls:
            self.assertEqual(len(call.args), 2, f"line {call.lineno}")
            self.assertTrue(all(k.arg == "dir_fd" for k in call.keywords), f"line {call.lineno}")
            names = flag_names(call.args[1])
            self.assertIsNotNone(names, f"line {call.lineno}: flags are not a literal os.O_* chain")
            self.assertEqual(len(names), len(set(names)), f"line {call.lineno}")
            self.assertIn(frozenset(names), {FOLDER_FLAGS, MARKER_FLAGS}, f"line {call.lineno}")
            seen.add(frozenset(names))
        self.assertEqual(seen, {FOLDER_FLAGS, MARKER_FLAGS})

    def test_no_environment_argv_process_or_signal_use(self) -> None:
        for node in self.nodes:
            if isinstance(node, ast.Attribute):
                self.assertNotIn(node.attr, PROCESSES, f"line {node.lineno}: {dotted(node)}")
            if isinstance(node, ast.Name):
                self.assertNotIn(node.id, PROCESSES, f"line {node.lineno}")

    def test_no_change_to_the_file_system_and_one_stdout_write(self) -> None:
        writes = []
        for node in self.nodes:
            if isinstance(node, ast.Attribute) and node.attr in CHANGES:
                self.assertEqual((node.attr, dotted(node)), ("write", STDOUT_WRITE), f"line {node.lineno}")
                writes.append(node.lineno)
            if isinstance(node, ast.Name):
                self.assertNotIn(node.id, CHANGES, f"line {node.lineno}")
        self.assertEqual(len(writes), 1)

    def test_hasattr_names_only_literal_open_flags(self) -> None:
        for node in self.nodes:
            if isinstance(node, ast.Call) and isinstance(node.func, ast.Name) and node.func.id == "hasattr":
                self.assertEqual(len(node.args), 2, f"line {node.lineno}")
                name = node.args[1]
                self.assertIsInstance(name, ast.Constant, f"line {node.lineno}")
                self.assertIn(name.value, FOLDER_FLAGS | MARKER_FLAGS, f"line {node.lineno}")

    def test_the_audit_sees_a_violation(self) -> None:
        # The checks above pass on an empty tree too; prove they look at real nodes.
        bad = ast.parse("import os\nos.open('x', os.O_RDONLY)\nos.environ.get('X')\nopen('y')\n")
        nodes = list(ast.walk(bad))
        call = next(n for n in nodes if isinstance(n, ast.Call) and dotted(n.func) == "os.open")
        self.assertNotIn(frozenset(flag_names(call.args[1])), {FOLDER_FLAGS, MARKER_FLAGS})
        self.assertTrue(any(isinstance(n, ast.Attribute) and n.attr in PROCESSES for n in nodes))
        self.assertTrue(any(isinstance(n, ast.Name) and n.id in BUILTINS for n in nodes))


if __name__ == "__main__":
    unittest.main()
