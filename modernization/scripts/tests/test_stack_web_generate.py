"""Tests of stack.py's web preparation: npm ci, then scripts/gen-validation.mjs (web_generate).
python3 -m unittest discover -s modernization/scripts/tests

Nothing is installed, generated or started: run_logged, own_process_runs, node_bin, components and
say are replaced, and web_problem reads a scratch folder."""
from __future__ import annotations

import os
import sys
import tempfile
import unittest
import unittest.mock

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import stack  # noqa: E402

RULES = "src/api/generated/validation-forms/auth.json"
NODE = "/pinned/node/bin"
GEN = [NODE + "/node", "scripts/gen-validation.mjs"]
NPM_CI = [NODE + "/npm", "ci"]


def web_component(prepare):
    return stack.Component("web", stack.WEB_PORT, "/scratch/web", [NODE + "/npm", "run", "dev"],
                           None, "vite", prepare=prepare)


class WebProblemTest(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.web = tmp.name

    def touch(self, rel, mtime):
        path = os.path.join(self.web, rel)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        open(path, "w").close()
        os.utime(path, (mtime, mtime))

    def test_no_node_modules_comes_first(self):
        self.touch("package-lock.json", 100)
        self.assertEqual(stack.web_problem(self.web), "no node_modules")

    def test_a_stale_install_comes_before_the_rules(self):
        self.touch("package-lock.json", 200)
        self.touch("node_modules/.package-lock.json", 100)
        self.assertEqual(stack.web_problem(self.web), "node_modules older than package-lock.json")

    def test_missing_rules_are_a_reason(self):
        self.touch("package-lock.json", 100)
        self.touch("node_modules/.package-lock.json", 200)
        self.assertEqual(stack.web_problem(self.web), "no " + RULES)

    def test_installed_with_rules_needs_nothing(self):
        self.touch("package-lock.json", 100)
        self.touch("node_modules/.package-lock.json", 200)
        self.touch(RULES, 300)
        self.assertIsNone(stack.web_problem(self.web))


class PrepareWebTest(unittest.TestCase):
    def prepare(self, why, results=(), running=False, node=NODE):
        """stack.prepare([web]) with run_logged answering from results in order; returns (web, calls)."""
        calls, answers = [], list(results)

        def run_logged(argv, cwd, env, log_name, what):
            calls.append((argv, cwd))
            return answers.pop(0)

        fresh = web_component(None)
        with unittest.mock.patch.multiple(stack, run_logged=run_logged, own_process_runs=lambda c: running,
                                          node_bin=lambda: node, components=lambda: [fresh],
                                          say=lambda *a: None):
            [web] = stack.prepare([web_component(why)], {})
        return web, calls

    def test_npm_ci_then_the_generator(self):
        web, calls = self.prepare("no node_modules", [True, True])
        self.assertEqual(calls, [(NPM_CI, "/scratch/web"), (GEN, "/scratch/web")])
        self.assertIsNone(web.problem)

    def test_a_stale_install_also_runs_the_generator(self):
        _, calls = self.prepare("node_modules older than package-lock.json", [True, True])
        self.assertEqual([argv for argv, _ in calls], [NPM_CI, GEN])

    def test_missing_rules_run_the_generator_alone(self):
        web, calls = self.prepare("no " + RULES, [True])
        self.assertEqual(calls, [(GEN, "/scratch/web")])
        self.assertIsNone(web.problem)

    def test_a_failed_npm_ci_skips_the_generator_and_fails_web(self):
        web, calls = self.prepare("no node_modules", [False])
        self.assertEqual([argv for argv, _ in calls], [NPM_CI])
        self.assertEqual(web.problem, "preparation failed, see above")

    def test_a_failed_generator_fails_web(self):
        web, _ = self.prepare("no " + RULES, [False])
        self.assertEqual(web.problem, "preparation failed, see above")

    def test_a_running_dev_server_is_left_alone(self):
        web, calls = self.prepare("no " + RULES, running=True)
        self.assertEqual(calls, [])
        self.assertIsNone(web.problem)

    def test_without_a_pinned_node_the_generator_runs_node_from_path(self):
        _, calls = self.prepare("no " + RULES, [True], node="")
        self.assertEqual(calls, [(["node", "scripts/gen-validation.mjs"], "/scratch/web")])


class WebGenerateTest(unittest.TestCase):
    def test_refuses_while_this_checkouts_dev_server_runs(self):
        calls = []
        with unittest.mock.patch.multiple(stack, run_logged=lambda *a: calls.append(a) or True,
                                          own_process_runs=lambda c: True, say=lambda *a: None):
            self.assertFalse(stack.web_generate(web_component("no " + RULES), {}))
        self.assertEqual(calls, [])


if __name__ == "__main__":
    unittest.main()
