"""The dev container, its package list and .env.example stay consistent with the build."""
from __future__ import annotations

import json
import re
import tomllib
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def env_example() -> dict[str, str]:
    """Commented-out VAR=value lines of .env.example."""
    out = {}
    for line in (ROOT / ".env.example").read_text().splitlines():
        m = re.fullmatch(r"# ([A-Z][A-Z0-9_]*)=(.*)", line)
        if m:
            out[m.group(1)] = m.group(2)
    return out


class DevEnvironmentTest(unittest.TestCase):
    def test_dockerfile_base_image_is_pinned_by_digest(self):
        froms = [l for l in (ROOT / ".devcontainer" / "Dockerfile").read_text().splitlines()
                 if l.startswith("FROM ")]
        self.assertEqual(len(froms), 1)
        self.assertRegex(froms[0], r"^FROM [\w./-]+:[\w.-]+@sha256:[0-9a-f]{64}$")

    def test_devcontainer_builds_the_dockerfile_without_features(self):
        cfg = json.loads((ROOT / ".devcontainer" / "devcontainer.json").read_text())
        self.assertEqual(cfg["build"], {"dockerfile": "Dockerfile", "context": ".."})
        self.assertNotIn("features", cfg)
        self.assertNotIn("image", cfg)

    def test_package_list_pins_the_required_gnucobol(self):
        want = tomllib.loads((ROOT / "tools" / "batch-programs.toml").read_text())["toolchain"]["cobc_version"]
        pkgs = [l.strip() for l in (ROOT / "tools" / "apt-packages.txt").read_text().splitlines()
                if l.strip() and not l.startswith("#")]
        self.assertIn("python3", pkgs)
        self.assertIn("make", pkgs)
        cobol = [p for p in pkgs if p.startswith("gnucobol")]
        self.assertEqual(len(cobol), 1)
        self.assertRegex(cobol[0], rf"^gnucobol3={re.escape(want)}-\d+$")

    def test_env_example_changes_nothing_until_edited(self):
        for line in (ROOT / ".env.example").read_text().splitlines():
            self.assertTrue(line == "" or line.startswith("#"), line)

    def test_env_example_lists_variables_that_are_read_with_their_defaults(self):
        sources = "\n".join(p.read_text() for p in [ROOT / "Makefile", *ROOT.glob("tools/*.py"),
                                                    *ROOT.glob("tests/*.py")])
        config = tomllib.loads((ROOT / "tests" / "test-config.toml").read_text())
        defaults = {
            "CARDDEMO_TEST_RETRIES": str(config["run"]["retries"]),
            "CARDDEMO_TEST_TIMEOUT": str(config["run"]["program_timeout_seconds"]),
            "CARDDEMO_COVERAGE_MIN": str(config["coverage"]["min_percent"]),
            "CARDDEMO_BUSINESS_DATE": re.search(r'DEFAULT_BUSINESS_DATE = "([^"]+)"', sources).group(1),
        }
        documented = env_example()
        self.assertGreaterEqual(len(documented), 10)
        for name, value in documented.items():
            with self.subTest(var=name):
                self.assertIn(name, sources)
                if name in defaults:
                    self.assertEqual(value, defaults[name])


if __name__ == "__main__":
    unittest.main()
