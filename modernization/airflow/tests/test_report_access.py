"""transaction-service's Airflow account (carddemo_airflow.auth and the
stack's Airflow settings): the token flow and the trigger of the report DAG
through Airflow's real API app (SQLite metadata, no server, no port), and
every other call refused."""

from __future__ import annotations

import importlib.util
import json
import os
import secrets
import stat
import sys
import types

import pytest

from conftest import AIRFLOW_DIR

REPORT_USER = "transaction-service"
# Generated per run, so no credential is written into the repository.
REPORT_PASSWORD = secrets.token_urlsafe(24)
ADMIN_PASSWORD = secrets.token_urlsafe(24)
JWT_SECRET = secrets.token_urlsafe(64)
REPORT_DAG = "carddemo_transaction_report"


@pytest.fixture
def manager(monkeypatch):
    from carddemo_airflow.auth import CarddemoAuthManager

    monkeypatch.setenv("CARDDEMO_AIRFLOW_REPORT_USER", REPORT_USER)
    return CarddemoAuthManager()


def user(name, role):
    from airflow.api_fastapi.auth.managers.simple.user import SimpleAuthManagerUser

    return SimpleAuthManagerUser(username=name, role=role)


def dag(dag_id):
    from airflow.api_fastapi.auth.managers.models.resource_details import DagDetails

    return DagDetails(id=dag_id)


def test_report_account_may_only_trigger_the_report(manager):
    from airflow.api_fastapi.auth.managers.models.resource_details import DagAccessEntity

    account = user(REPORT_USER, "viewer")
    assert manager.is_authorized_dag(method="POST", user=account, access_entity=DagAccessEntity.RUN,
                                     details=dag(REPORT_DAG))
    refused = [
        ("POST", DagAccessEntity.RUN, "carddemo_daily_cycle"),
        ("POST", DagAccessEntity.RUN, None),
        ("GET", DagAccessEntity.RUN, REPORT_DAG),
        ("GET", None, REPORT_DAG),
        ("PUT", None, REPORT_DAG),
        ("DELETE", DagAccessEntity.RUN, REPORT_DAG),
        ("POST", DagAccessEntity.TASK_INSTANCE, REPORT_DAG),
    ]
    for method, entity, dag_id in refused:
        assert not manager.is_authorized_dag(method=method, user=account, access_entity=entity,
                                             details=dag(dag_id) if dag_id else None), (method, entity, dag_id)


@pytest.mark.parametrize("role", ["viewer", "user", "op", "admin"])
def test_report_account_is_refused_everything_else_whatever_its_role(manager, role):
    from airflow.api_fastapi.auth.managers.models.resource_details import AccessView

    account = user(REPORT_USER, role)
    assert not manager.is_authorized_configuration(method="GET", user=account)
    assert not manager.is_authorized_connection(method="GET", user=account)
    assert not manager.is_authorized_variable(method="GET", user=account)
    assert not manager.is_authorized_pool(method="GET", user=account)
    assert not manager.is_authorized_asset(method="GET", user=account)
    assert not manager.is_authorized_view(access_view=AccessView.PLUGINS, user=account)
    assert not manager.is_authorized_custom_view(method="GET", resource_name="x", user=account)
    assert not manager.is_authorized_hitl_task(assigned_users={REPORT_USER}, user=account)
    assert manager.filter_authorized_menu_items([object()], user=account) == []


def test_other_users_keep_the_simple_rules(manager):
    from airflow.api_fastapi.auth.managers.models.resource_details import DagAccessEntity

    admin, viewer = user("admin", "admin"), user("someone", "viewer")
    assert manager.is_authorized_connection(method="POST", user=admin)
    assert manager.is_authorized_dag(method="DELETE", user=admin, details=dag("carddemo_daily_cycle"))
    assert manager.is_authorized_dag(method="GET", user=viewer, details=dag(REPORT_DAG))
    assert not manager.is_authorized_dag(method="POST", user=viewer, access_entity=DagAccessEntity.RUN,
                                         details=dag(REPORT_DAG))


def test_without_a_report_user_nothing_is_restricted(monkeypatch):
    from carddemo_airflow.auth import CarddemoAuthManager

    monkeypatch.delenv("CARDDEMO_AIRFLOW_REPORT_USER", raising=False)
    assert CarddemoAuthManager().is_authorized_dag(method="GET", user=user(REPORT_USER, "viewer"),
                                                   details=dag(REPORT_DAG))


@pytest.fixture(scope="module")
def api(tmp_path_factory):
    """Airflow's core API app with the stack's auth settings, on the test
    metadata database, with this checkout's DAGs serialized into it."""
    home = tmp_path_factory.mktemp("auth")
    passwords = home / "passwords.json"
    passwords.write_text(json.dumps({"admin": ADMIN_PASSWORD, REPORT_USER: REPORT_PASSWORD}))
    settings = {
        "AIRFLOW__CORE__AUTH_MANAGER": "carddemo_airflow.auth.CarddemoAuthManager",
        "AIRFLOW__CORE__SIMPLE_AUTH_MANAGER_USERS": f"admin:admin,{REPORT_USER}:viewer",
        "AIRFLOW__CORE__SIMPLE_AUTH_MANAGER_PASSWORDS_FILE": str(passwords),
        "AIRFLOW__CORE__SIMPLE_AUTH_MANAGER_ALL_ADMINS": "False",
        "AIRFLOW__API_AUTH__JWT_SECRET": JWT_SECRET,
        "CARDDEMO_AIRFLOW_REPORT_USER": REPORT_USER,
    }
    saved = {k: os.environ.get(k) for k in settings}
    os.environ.update(settings)
    try:
        from airflow.api_fastapi.app import create_app, purge_cached_app
        from airflow.dag_processing.bundles.manager import DagBundlesManager
        from airflow.dag_processing.dagbag import DagBag, sync_bag_to_db
        from airflow.utils import db
        from fastapi.testclient import TestClient

        db.initdb()
        DagBundlesManager().sync_bundles_to_db()
        bag = DagBag(dag_folder=os.path.join(AIRFLOW_DIR, "dags"))
        sync_bag_to_db(bag, "dags-folder", None)
        purge_cached_app()
        yield TestClient(create_app("core"))
        purge_cached_app()
    finally:
        for k, v in saved.items():
            if v is None:
                os.environ.pop(k, None)
            else:
                os.environ[k] = v


def token(api, name, password):
    response = api.post("/auth/token", json={"username": name, "password": password})
    assert response.status_code == 201, response.status_code
    return {"Authorization": f"Bearer {response.json()['access_token']}"}


def report_run(run_id):
    return {"dag_run_id": f"tranrept-{run_id}", "logical_date": None,
            "conf": {"runId": run_id, "startDate": "2022-01-01", "endDate": "2022-07-06",
                     "requestedBy": "USER0001"}}


def test_wrong_password_gets_no_token(api):
    assert api.post("/auth/token", json={"username": REPORT_USER, "password": "wrong"}).status_code == 401


def test_report_account_triggers_the_report_once_per_run_id(api):
    headers = token(api, REPORT_USER, REPORT_PASSWORD)
    first = api.post(f"/api/v2/dags/{REPORT_DAG}/dagRuns", json=report_run(7), headers=headers)
    assert first.status_code == 200, first.text
    assert first.json()["dag_run_id"] == "tranrept-7"
    assert first.json()["conf"]["runId"] == 7
    # transaction-service counts this as success (the run exists).
    again = api.post(f"/api/v2/dags/{REPORT_DAG}/dagRuns", json=report_run(7), headers=headers)
    assert again.status_code == 409


@pytest.mark.parametrize("method,path", [
    ("POST", "/api/v2/dags/carddemo_daily_cycle/dagRuns"),
    ("POST", "/api/v2/dags/carddemo_data_load/dagRuns"),
    ("GET", "/api/v2/dags"),
    ("GET", f"/api/v2/dags/{REPORT_DAG}"),
    ("GET", f"/api/v2/dags/{REPORT_DAG}/dagRuns"),
    ("PATCH", f"/api/v2/dags/{REPORT_DAG}"),
    ("DELETE", f"/api/v2/dags/{REPORT_DAG}"),
    ("GET", "/api/v2/connections"),
    ("GET", "/api/v2/variables"),
    ("GET", "/api/v2/pools"),
    ("GET", "/api/v2/config"),
])
def test_report_account_is_refused_other_calls(api, method, path):
    headers = token(api, REPORT_USER, REPORT_PASSWORD)
    body = {"logical_date": None} if method == "POST" else {"is_paused": True} if method == "PATCH" else None
    assert api.request(method, path, json=body, headers=headers).status_code == 403


def test_admin_keeps_full_access(api):
    headers = token(api, "admin", ADMIN_PASSWORD)
    response = api.get("/api/v2/dags", headers=headers)
    assert response.status_code == 200
    assert response.json()["total_entries"] == 7


# Calls that need no permission. /api/v2/auth/logout is left out of the
# walk: it revokes the token it is called with.
OPEN_TO_EVERY_SIGNED_ON_USER = {
    "/openapi.json", "/docs", "/docs/oauth2-redirect", "/redoc", "/api/v2/monitor/health", "/api/v2/version",
    "/api/v2/auth/login", "/ui/auth/me", "/ui/auth/menus", "/ui/config"}


def test_report_account_reaches_only_what_needs_no_permission(api):
    headers = token(api, REPORT_USER, REPORT_PASSWORD)
    reached = set()
    for route in api.app.routes:
        path, methods = getattr(route, "path", ""), getattr(route, "methods", None) or set()
        if "GET" in methods and "{" not in path and path != "/api/v2/auth/logout":
            if 200 <= api.get(path, headers=headers).status_code < 300:
                reached.add(path)
    assert reached == OPEN_TO_EVERY_SIGNED_ON_USER
    assert api.get("/ui/auth/me", headers=headers).json() == {"id": REPORT_USER, "username": REPORT_USER}
    assert api.get("/ui/auth/menus", headers=headers).json() == {"authorized_menu_items": [],
                                                                  "extra_menu_items": []}


@pytest.mark.parametrize("path", ["/api/v2/backfills", "/api/v2/backfills/dry_run"])
def test_report_account_cannot_backfill(api, dagbag, path):
    # The auth manager sees a backfill as a POST on the DAG's runs, like the
    # trigger; the report DAG's missing schedule is what refuses it.
    assert dagbag.get_dag(REPORT_DAG).schedule is None
    headers = token(api, REPORT_USER, REPORT_PASSWORD)
    body = {"dag_id": REPORT_DAG, "from_date": "2022-07-01T00:00:00Z", "to_date": "2022-07-02T00:00:00Z",
            "run_backwards": False, "dag_run_conf": {}, "reprocess_behavior": "none", "max_active_runs": 1}
    own = api.post(path, json=body, headers=headers)
    assert not 200 <= own.status_code < 300, own.text
    assert api.post(path, json={**body, "dag_id": "carddemo_daily_cycle"}, headers=headers).status_code == 403

# scripts/stack.py writes these settings for the real Airflow processes.
def load_stack(monkeypatch, env_file: dict, tmp_path):
    path = os.path.join(os.path.dirname(AIRFLOW_DIR), "scripts", "stack.py")
    spec = importlib.util.spec_from_file_location("stack_under_test", path)
    stack = importlib.util.module_from_spec(spec)
    monkeypatch.setenv("CARDDEMO_VAR_DIR", str(tmp_path / "var"))
    monkeypatch.setenv("CARDDEMO_ENV_FILE", str(tmp_path / "missing.env"))
    for key in ("CARDDEMO_AIRFLOW_REPORT_USER", "CARDDEMO_AIRFLOW_REPORT_PASSWORD", "AIRFLOW_ADMIN_USER",
                "CARDDEMO_AIRFLOW_URL", "CARDDEMO_REPORTS_DIR", "CARDDEMO_TRANREPT_DIR",
                "AIRFLOW__CORE__AUTH_MANAGER", "AIRFLOW__CORE__SIMPLE_AUTH_MANAGER_USERS",
                "AIRFLOW__CORE__SIMPLE_AUTH_MANAGER_PASSWORDS_FILE",
                "AIRFLOW__CORE__SIMPLE_AUTH_MANAGER_ALL_ADMINS"):
        monkeypatch.delenv(key, raising=False)
    spec.loader.exec_module(stack)
    monkeypatch.setattr(stack, "read_env", lambda _path: dict(env_file))
    monkeypatch.setattr(stack, "node_bin", lambda: "")
    monkeypatch.setattr(stack, "airflow_venv", lambda: sys.prefix)
    return stack


SECRETS = {k: secrets.token_urlsafe(16) for k in ("AIRFLOW_DB_PASSWORD", "AIRFLOW_ADMIN_PASSWORD", "AIRFLOW_API_JWT_SECRET",
                            "AIRFLOW_API_SECRET", "AIRFLOW_FERNET_KEY", "CARDDEMO_BATCH_INTERNAL_TOKEN")}


def test_stack_gives_airflow_the_report_account(monkeypatch, tmp_path):
    env_file = {**SECRETS, "AIRFLOW_ADMIN_USER": "admin", "AIRFLOW_ADMIN_PASSWORD": ADMIN_PASSWORD,
                "CARDDEMO_AIRFLOW_REPORT_USER": REPORT_USER, "CARDDEMO_AIRFLOW_REPORT_PASSWORD": REPORT_PASSWORD}
    stack = load_stack(monkeypatch, env_file, tmp_path)
    env = stack.airflow_env(stack.child_env())
    assert env["AIRFLOW__CORE__AUTH_MANAGER"] == "carddemo_airflow.auth.CarddemoAuthManager"
    assert env["AIRFLOW__CORE__SIMPLE_AUTH_MANAGER_USERS"] == f"admin:admin,{REPORT_USER}:viewer"
    assert env["AIRFLOW__CORE__SIMPLE_AUTH_MANAGER_ALL_ADMINS"] == "False"
    assert env["CARDDEMO_AIRFLOW_URL"] == "http://127.0.0.1:28080"
    # The reports are under the output root of transaction-service's
    # database, which it finds from CARDDEMO_VAR_DIR alone.
    assert env["CARDDEMO_VAR_DIR"] == str(tmp_path / "var")
    assert "CARDDEMO_REPORTS_DIR" not in env and "CARDDEMO_TRANREPT_DIR" not in env

    monkeypatch.setattr(stack.subprocess, "run", lambda *a, **k: types.SimpleNamespace(returncode=0))
    assert stack.prepare_airflow(env) is None
    passwords = env["AIRFLOW__CORE__SIMPLE_AUTH_MANAGER_PASSWORDS_FILE"]
    with open(passwords, encoding="utf-8") as f:
        assert json.load(f) == {"admin": ADMIN_PASSWORD, REPORT_USER: REPORT_PASSWORD}
    assert stat.S_IMODE(os.stat(passwords).st_mode) == 0o600
    assert all(c.problem is None for c in stack.airflow_components())


def test_stack_without_a_report_password_starts_only_the_admin(monkeypatch, tmp_path, capsys):
    env_file = {**SECRETS, "CARDDEMO_AIRFLOW_REPORT_USER": REPORT_USER}
    stack = load_stack(monkeypatch, env_file, tmp_path)
    env = stack.airflow_env(stack.child_env())
    assert env["AIRFLOW__CORE__SIMPLE_AUTH_MANAGER_USERS"] == "admin:admin"
    monkeypatch.setattr(stack.subprocess, "run", lambda *a, **k: types.SimpleNamespace(returncode=0))
    assert stack.prepare_airflow(env) is None
    assert "run make setup" in capsys.readouterr().out


def test_stack_refuses_a_report_user_named_like_the_admin(monkeypatch, tmp_path):
    env_file = {**SECRETS, "AIRFLOW_ADMIN_USER": "admin", "CARDDEMO_AIRFLOW_REPORT_USER": "admin",
                "CARDDEMO_AIRFLOW_REPORT_PASSWORD": REPORT_PASSWORD}
    stack = load_stack(monkeypatch, env_file, tmp_path)
    assert stack.airflow_env(stack.child_env())["AIRFLOW__CORE__SIMPLE_AUTH_MANAGER_USERS"] == "admin:admin"
    assert all("must differ from AIRFLOW_ADMIN_USER" in c.problem for c in stack.airflow_components())


def test_stack_warns_when_the_report_user_is_renamed(monkeypatch, tmp_path, capsys):
    env_file = {**SECRETS, "AIRFLOW_ADMIN_USER": "admin", "CARDDEMO_AIRFLOW_REPORT_PASSWORD": REPORT_PASSWORD}
    stack = load_stack(monkeypatch, env_file, tmp_path)
    monkeypatch.setattr(stack.subprocess, "run", lambda *a, **k: types.SimpleNamespace(returncode=0))
    starts = []
    for name in (REPORT_USER, REPORT_USER, "report-renamed", "report-renamed"):
        env_file["CARDDEMO_AIRFLOW_REPORT_USER"] = name
        assert stack.prepare_airflow(stack.airflow_env(stack.child_env())) is None
        starts.append(capsys.readouterr().out)
    assert [("rotate AIRFLOW_API_JWT_SECRET" in out) for out in starts] == [False, False, True, False]
    assert f"changed from {REPORT_USER} to report-renamed" in starts[2]
    assert REPORT_PASSWORD not in "".join(starts)
    record = tmp_path / "var" / "airflow" / "report-user"
    assert record.read_text(encoding="utf-8") == "report-renamed\n"
    assert stat.S_IMODE(os.stat(record).st_mode) == 0o600

# scripts/init_env.py (make setup) generates the key that signs these tokens.
def run_init_env(tmp_path, env_lines):
    import subprocess

    example = tmp_path / "example.env"
    example.write_text("AIRFLOW_API_JWT_SECRET=\n", encoding="utf-8")
    env = tmp_path / ".env"
    if env_lines is not None:
        env.write_text("".join(f"{line}\n" for line in env_lines), encoding="utf-8")
    script = os.path.join(os.path.dirname(AIRFLOW_DIR), "scripts", "init_env.py")
    done = subprocess.run([sys.executable, script, str(example), str(env)], capture_output=True, text=True,
                          check=True)
    value = env.read_text(encoding="utf-8").split("AIRFLOW_API_JWT_SECRET=", 1)[1].splitlines()[0]
    return done.stdout + done.stderr, value


def test_setup_generates_a_jwt_secret_long_enough_for_hs512(tmp_path):
    out, value = run_init_env(tmp_path, None)
    assert len(value.encode()) >= 64
    assert "warn" not in out and value not in out


def test_setup_keeps_a_short_jwt_secret_and_warns_without_showing_it(tmp_path):
    short = secrets.token_urlsafe(16)
    out, value = run_init_env(tmp_path, [f"AIRFLOW_API_JWT_SECRET={short}"])
    assert value == short
    assert "AIRFLOW_API_JWT_SECRET is shorter than the 64 bytes" in out
    assert short not in out
