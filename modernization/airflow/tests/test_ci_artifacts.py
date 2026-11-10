"""The CI artifacts that the ledger job's make trace reads: the backend and
billpay jobs upload the Java tests' spec-items next to their JUnit reports,
and the ledger job downloads this run's artifacts into build/ci-results/.

The upload and download are emulated as actions/upload-artifact and
actions/download-artifact document them: several paths are rooted at their
least common ancestor, a directory path takes every file below it, `**`
matches any number of directories, hidden files are left out, and without
merge-multiple every artifact lands in a directory of its own name."""

from __future__ import annotations

import fnmatch
import json
import os
import sys
from pathlib import Path

import pytest
import yaml

MODERNIZATION = Path(__file__).resolve().parents[2]
WORKFLOW = MODERNIZATION.parent / ".github" / "workflows" / "modernization.yml"
sys.path.insert(0, str(MODERNIZATION / "verify" / "trace"))
from tracelib import reports  # noqa: E402

SPEC_ITEMS = "modernization/**/target/spec-items/"
# The reports these jobs uploaded before spec-items; they stay in the artifact.
REPORTS = {
    "backend": {"modernization/build/test-results/", "modernization/**/target/surefire-reports/",
                "modernization/**/target/failsafe-reports/"},
    "billpay": {"modernization/build/test-results/", "modernization/**/target/surefire-reports/",
                "modernization/**/target/failsafe-reports/", "modernization/web/playwright-report/",
                "modernization/var/log/"},
}
JAVA_JOBS = tuple(REPORTS)


def workflow() -> dict:
    with open(WORKFLOW, encoding="utf-8") as f:
        return yaml.safe_load(f)


def steps(job: str, action: str) -> list[dict]:
    return [s for s in workflow()["jobs"][job]["steps"] if s.get("uses", "").startswith(action + "@")]


def upload(job: str) -> tuple[str, list[str]]:
    (step,) = steps(job, "actions/upload-artifact")
    paths = [p.strip() for p in step["with"]["path"].splitlines() if p.strip()]
    return step["with"]["name"], paths


def _search_root(pattern: str) -> tuple[str, ...]:
    parts = []
    for seg in pattern.rstrip("/").split("/"):
        if any(c in seg for c in "*?["):
            break
        parts.append(seg)
    return tuple(parts)


def _match(segs: tuple[str, ...], pat: tuple[str, ...]) -> bool:
    if not pat:
        return not segs
    if pat[0] == "**":
        return any(_match(segs[i:], pat[1:]) for i in range(len(segs) + 1))
    return bool(segs) and fnmatch.fnmatchcase(segs[0], pat[0]) and _match(segs[1:], pat[1:])


def emulate_artifact(workspace: Path, paths: list[str]) -> dict[str, Path]:
    """Path in the artifact -> file in the workspace, as upload-artifact builds it."""
    roots = [_search_root(p) for p in paths]
    common = []
    for segs in zip(*roots):
        if len(set(segs)) != 1:
            break
        common.append(segs[0])
    patterns = [tuple(p.rstrip("/").split("/")) for p in paths]
    found = {}
    for dirpath, _dirs, files in os.walk(workspace):
        for name in files:
            rel = Path(dirpath, name).relative_to(workspace).parts
            if any(part.startswith(".") for part in rel):
                continue
            # A pattern names a file or a directory; a directory takes every file below it.
            if any(_match(rel[:n], pat) for pat in patterns for n in range(1, len(rel) + 1)):
                found["/".join(rel[len(common):])] = Path(dirpath, name)
    return found


def download(artifacts: dict[str, dict[str, Path]], dest: Path) -> None:
    for name, files in artifacts.items():
        for rel, src in files.items():
            target = dest / name / rel
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(src.read_bytes())


def spec_line(cls: str, item: str) -> str:
    return json.dumps({"class": cls, "method": "pays", "displayName": "pays()", "items": [item],
                       "status": "SUCCESSFUL", "mode": "FIXED"}) + "\n"


@pytest.fixture
def workspace(tmp_path):
    """A checkout after the backend and billpay jobs ran their tests."""
    files = {
        "modernization/platform/ledger-core/target/spec-items/org.carddemo.LedgerTest.jsonl":
            spec_line("org.carddemo.LedgerTest", "BR-BILL-002"),
        "modernization/services/billpay-service/target/spec-items/org.carddemo.BillPayTest.jsonl":
            spec_line("org.carddemo.BillPayTest", "SE-BILL-001"),
        "modernization/services/billpay-service/target/surefire-reports/TEST-org.carddemo.BillPayTest.xml":
            '<testsuite name="x" tests="0"/>\n',
        "modernization/build/test-results/billpay/vitest-billpay-junit.xml": '<testsuites/>\n',
        "modernization/web/playwright-report/e2e-mock-fixed.json": '{"suites": []}\n',
        "modernization/var/log/billpay-service.log": "started\n",
        # Never uploaded: the private .env, the key files, build output, sources.
        "modernization/.env": "AIRFLOW_API_JWT_SECRET=not-a-real-value\n",
        "modernization/var/secrets/identity-jwt.pem": "not a real key\n",
        "modernization/services/billpay-service/target/classes/application.yml": "server: {}\n",
        "modernization/services/billpay-service/target/billpay-service-0.1.0-exec.jar": "jar\n",
        "modernization/services/billpay-service/src/test/java/BillPayTest.java": "class BillPayTest {}\n",
    }
    for rel, body in files.items():
        p = tmp_path / "ws" / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(body, encoding="utf-8")
    return tmp_path / "ws"


@pytest.mark.parametrize("job", JAVA_JOBS)
def test_java_jobs_upload_spec_items_with_their_reports(job):
    name, paths = upload(job)
    assert name == f"test-results-{job}"
    assert SPEC_ITEMS in paths
    assert REPORTS[job] <= set(paths)


def test_the_ledger_job_runs_the_make_scripts_unit_tests():
    """The step runs every test in modernization/scripts/tests; it is no
    conditional skip, and the folder holds tests, so discover never passes empty."""
    (step,) = [s for s in workflow()["jobs"]["ledger"]["steps"]
               if s.get("run") == "python3 -m unittest discover -s modernization/scripts/tests"]
    assert not {"if", "continue-on-error"} & set(step)
    assert sorted((MODERNIZATION / "scripts" / "tests").glob("test_*.py"))


def test_ledger_downloads_only_this_runs_artifacts_one_directory_each():
    (step,) = steps("ledger", "actions/download-artifact")
    w = step["with"]
    assert w["pattern"] == "test-results-*"
    assert w["path"] == "modernization/build/ci-results/"
    # No run-id, repository or token: only this run's artifacts. Without
    # merge-multiple, same-named files of two jobs do not overwrite each other.
    assert not {"run-id", "repository", "github-token", "merge-multiple"} & set(w)


def test_make_trace_finds_the_java_citations_in_the_downloaded_artifacts(workspace, tmp_path):
    artifacts = dict(upload(job) for job in JAVA_JOBS)
    ci_results = tmp_path / "ci-results"
    download({name: emulate_artifact(workspace, paths) for name, paths in artifacts.items()}, ci_results)
    jsonl = [p for p in reports.discover(ci_results) if p.suffix == ".jsonl"]
    rels = sorted(str(p.relative_to(ci_results)) for p in jsonl)
    assert rels == [
        "test-results-backend/platform/ledger-core/target/spec-items/org.carddemo.LedgerTest.jsonl",
        "test-results-backend/services/billpay-service/target/spec-items/org.carddemo.BillPayTest.jsonl",
        "test-results-billpay/platform/ledger-core/target/spec-items/org.carddemo.LedgerTest.jsonl",
        "test-results-billpay/services/billpay-service/target/spec-items/org.carddemo.BillPayTest.jsonl",
    ]
    cited = set()
    for p in jsonl:
        results, problems = reports.read(p, ci_results)
        assert problems == []
        cited |= {(i, r.status) for r in results for i in r.items}
    assert cited == {("BR-BILL-002", "passed"), ("SE-BILL-001", "passed")}


def test_without_the_spec_items_path_the_java_citations_are_lost(workspace, tmp_path):
    """The layout of run 42907091175: surefire XML but no spec-items, so the
    ledger job saw no Java citation."""
    name, paths = upload("billpay")
    ci_results = tmp_path / "ci-results"
    download({name: emulate_artifact(workspace, [p for p in paths if p != SPEC_ITEMS])}, ci_results)
    found = reports.discover(ci_results)
    assert any("surefire-reports" in p.parts for p in found)
    assert not [p for p in found if p.suffix == ".jsonl"]


@pytest.mark.parametrize("job", JAVA_JOBS)
def test_spec_items_add_only_the_citation_files_and_nothing_private(job, workspace):
    _name, paths = upload(job)
    files = emulate_artifact(workspace, paths)
    added = set(files) - set(emulate_artifact(workspace, [p for p in paths if p != SPEC_ITEMS]))
    assert added and all("spec-items" in rel.split("/") and rel.endswith(".jsonl") for rel in added)
    for rel in files:
        assert not any(part.startswith(".") for part in rel.split("/")), rel
        assert "secrets" not in rel.split("/") and "classes" not in rel.split("/"), rel
        assert not rel.endswith((".jar", ".java", ".pem")), rel
