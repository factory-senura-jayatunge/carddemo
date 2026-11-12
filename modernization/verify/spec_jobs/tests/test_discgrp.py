from pathlib import Path
import unittest

from modernization.verify.spec_jobs import discgrp
from modernization.verify.spec_jobs.layouts import ROOT


class DisclosureTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = (ROOT / "app/data/ASCII/discgrp.txt").read_bytes()
        cls.rows = discgrp.parse(cls.source)

    def test_source_count_and_own_roundtrip(self):
        self.assertEqual(len(self.rows), 51)
        for row, line in zip(self.rows, discgrp.lines(self.source)):
            self.assertEqual(discgrp.encode(row), line)

    def test_crlf(self):
        self.assertEqual(discgrp.parse(self.source), discgrp.parse(self.source.replace(b"\n", b"\r\n")))

    def test_invalid_inputs(self):
        lines = discgrp.lines(self.source)
        mutations = [lines[1:] + lines[:1], [lines[0]] + lines, [line for line in lines if not line.startswith(b"DEFAULT")],
                     [lines[0][:-1]] + lines[1:], [lines[0][:16] + b"badval" + lines[0][22:]] + lines[1:],
                     [lines[0][:-1] + b"!"] + lines[1:]]
        for mutation in mutations:
            with self.assertRaises(discgrp.InputError):
                discgrp.parse(b"\n".join(mutation) + b"\n")

    def test_field_and_missing_extra_mutations(self):
        for field, value in (("group_id", "OTHER"), ("type_cd", "99"), ("cat_cd", "9999"), ("int_rate", "1.23")):
            mutant = [{**self.rows[0], field: value}] + self.rows[1:]
            self.assertTrue(discgrp.validate("sample", self.source, mutant, status="COMPLETED", source_after=self.source))
        for mutant in (self.rows[:-1], self.rows + self.rows[:1]):
            self.assertTrue(discgrp.validate("sample", self.source, mutant, status="COMPLETED", source_after=self.source))

    def test_snapshot_and_source_mutations(self):
        self.assertFalse(discgrp.validate("sample", self.source, self.rows, status="COMPLETED", source_after=self.source))
        self.assertTrue(discgrp.validate("snapshot", self.source, self.rows, status="COMPLETED", source_after=self.source))
        self.assertTrue(discgrp.validate("snapshot", self.source, self.rows, status="COMPLETED", source_after=self.source,
                                        old_hashes={1: "old"}, new_hashes={2: "new"}, observations=({1: "partial"},)))
        self.assertTrue(discgrp.validate("sample", self.source, self.rows, status="COMPLETED", source_after=b""))

    def test_failure_mutations(self):
        self.assertFalse(discgrp.validate_rejected("duplicate", status="FAILED", before={1: "a"}, after={1: "a"}))
        self.assertTrue(discgrp.validate_rejected("duplicate", status="FAILED", before={1: "a"}, after={}))
        self.assertTrue(discgrp.validate_rejected("missing", status="FAILED", before={}, after={}))
        self.assertTrue(discgrp.validate_rejected("no_window", status="COMPLETED", before={}, after={}))


if __name__ == "__main__":
    unittest.main()
