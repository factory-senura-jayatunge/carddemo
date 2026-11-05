"""tools/package_release.py: release archive contents and checksums."""
from __future__ import annotations

import hashlib
import importlib.util
import json
import sys
import tarfile
import tempfile
import unittest
from pathlib import Path

_spec = importlib.util.spec_from_file_location(
    "package_release", Path(__file__).resolve().parents[2] / "tools" / "package_release.py")
pkg = importlib.util.module_from_spec(_spec)
sys.modules[_spec.name] = pkg
_spec.loader.exec_module(pkg)


class PackageReleaseTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="pkg-test-"))
        self.addCleanup(lambda: __import__("shutil").rmtree(self.tmp))
        self.build = self.tmp / "build"
        (self.build / "bin").mkdir(parents=True)
        (self.build / "lib").mkdir()
        (self.build / "gen").mkdir()
        prog = self.build / "bin" / "CBTRN02C"
        prog.write_bytes(b"\x7fELF program")
        prog.chmod(0o755)
        (self.build / "lib" / "CEEDAYS.so").write_bytes(b"module")
        (self.build / "gen" / "repro-x.cbl").write_text("generated")
        (self.build / "build-flags.json").write_text(
            json.dumps({"CBTRN02C": ["-std=ibm", "-I", f"{self.tmp}/app/cpy"]}))
        self.results = self.tmp / "results"
        self.results.mkdir()
        (self.results / "summary.json").write_text('{"tests": 1}')
        (self.results / "junit.xml").write_text("<testsuites/>")

    def make(self, out: str = "dist"):
        return pkg.package("v1.2.3", self.build, self.tmp / out, self.tmp, self.results, mtime=0)

    def test_archive_holds_programs_modules_results_and_build_info(self):
        archive = self.make()
        self.assertRegex(archive.name, r"^carddemo-batch-v1\.2\.3-[a-z]+-[\w]+\.tar\.gz$")
        stem = archive.name[:-len(".tar.gz")]
        with tarfile.open(archive) as tar:
            members = {m.name: m for m in tar.getmembers()}
            self.assertEqual(sorted(members), sorted(f"{stem}/{n}" for n in [
                "BUILDINFO.json", "bin/CBTRN02C", "lib/CEEDAYS.so",
                "test-results/junit.xml", "test-results/summary.json"]))
            self.assertEqual(members[f"{stem}/bin/CBTRN02C"].mode, 0o755)
            self.assertEqual(members[f"{stem}/lib/CEEDAYS.so"].mode, 0o644)
            info = json.load(tar.extractfile(f"{stem}/BUILDINFO.json"))
        self.assertEqual(info["version"], "v1.2.3")
        self.assertEqual(info["cobc_flags"], {"CBTRN02C": ["-std=ibm", "-I", "app/cpy"]})

    def test_checksums_match_and_output_is_reproducible(self):
        first = self.make("a")
        second = self.make("b")
        self.assertEqual(first.read_bytes(), second.read_bytes())
        sums = (self.tmp / "a" / "SHA256SUMS").read_text()
        self.assertEqual(sums, f"{hashlib.sha256(first.read_bytes()).hexdigest()}  {first.name}\n")
        self.make("a")
        self.assertEqual((self.tmp / "a" / "SHA256SUMS").read_text(), sums, "re-packaging replaces the line")

    def test_rejects_bad_versions_and_missing_builds(self):
        with self.assertRaises(SystemExit):
            pkg.package("latest", self.build, self.tmp / "d", self.tmp)
        with self.assertRaises(SystemExit):
            pkg.package("v1.0.0", self.tmp / "nothing", self.tmp / "d", self.tmp)


if __name__ == "__main__":
    unittest.main()
