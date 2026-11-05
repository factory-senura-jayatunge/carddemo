"""PARMRUN, the driver that passes a JCL PARM to a CALLed program."""
from __future__ import annotations

import subprocess

from tests.support import CardDemoTestCase


class ParmRunTest(CardDemoTestCase):
    def parmrun(self, *args: str) -> subprocess.CompletedProcess:
        work = self.new_work()
        return self._run([str(work.layout.bin / "PARMRUN"), *args], "", work, None)

    def test_without_arguments_it_prints_usage_and_returns_16(self):
        proc = self.parmrun()
        self.assertEqual(proc.returncode, 16)
        self.assertIn("usage: PARMRUN <program> [<parm>]", proc.stderr)

    def test_an_unknown_program_returns_16(self):
        proc = self.parmrun("NOSUCHPG", "2022071800")
        self.assertEqual(proc.returncode, 16)
        self.assertIn("program NOSUCHPG not found in COB_LIBRARY_PATH", proc.stderr)
