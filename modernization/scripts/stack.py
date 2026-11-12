#!/usr/bin/env python3
"""Start, stop and inspect the local CardDemo stack.

The compose project (SQL Server, RabbitMQ, Airflow's Postgres) is shared by
every checkout on the machine. The application processes run on the host:
each converted service from its executable jar, Airflow's four processes
from the Airflow venv, and the Vite dev server.
A component that has not been converted yet (its directory does not exist)
is skipped. up prepares the others first: it builds the service jars that
are missing or older than their sources (Maven, tests skipped), runs npm ci
when web/node_modules is missing or older than package-lock.json and then
web/scripts/gen-validation.mjs, which also runs alone when only its
git-ignored output is missing (web_generate), except for a component that
is already running, whose files are left alone. A component whose
preparation fails does not start, so a missing piece never looks like a pass.
An existing output is not regenerated when platform/validation's rules
change; npm run gen:validation in web/ or make test-billpay does that.

PIDs go to $CARDDEMO_VAR_DIR/run/<name>.pid, logs to $CARDDEMO_VAR_DIR/log/.
Each service also records the database it was started against
(<name>.db, CARDDEMO_DB_NAME); up refuses to reuse a running service that
uses another database, so changing CARDDEMO_DB_NAME never leaves a service on
the old one.
While scripts/e2e_stack.py runs the slice on its own database it holds
~/.cache/carddemo/slice.lock (machine-wide, CARDDEMO_SLICE_LOCK overrides);
up, stop and down then fail (see guard_slice).
Every command ends with one line, `<target>: OK` or `<target>: FAILED (...)`.

Usage: stack.py infra-up | infra-down | up [--only NAME,...] | stop | down | status
       (--only airflow means the four Airflow processes)
       stack.py ensure-infra TARGET   (infra-up for another target; prints only on failure)
       stack.py sa-secret             (the SA secret file, when asked for or needed; see sync_sa_secret)
       stack.py rabbitmq-users        (the virtual host and service users; infra-up and up do it too)
"""

import fcntl
import glob
import os
import re
import signal
import socket
import subprocess
import sys
import json
import time
import urllib.error
import urllib.parse
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import init_env  # noqa: E402
import rabbitmq_users  # noqa: E402
from with_env import (LEGACY_OUTPUT_SETTINGS, VAR_DIR_KEY, VAR_DIR_PRIVATE, VarDirError,  # noqa: E402
                      default_env_file, is_private_var_dir, legacy_output_settings, main_modernization, read_env,
                      resolve_var_dir)

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
# The same defaults as the Makefile, so stack.py run on its own drives the
# shared compose project instead of planning to recreate it from this checkout.
INFRA_DIR = os.environ.get("CARDDEMO_INFRA_DIR") or main_modernization()
ENV_FILE = os.environ.get("CARDDEMO_ENV_FILE") or default_env_file()
# Absolute, from the environment, .env or the main checkout (with_env.py);
# a relative value leaves it empty and main() refuses every command. So does
# a harness's private folder: the stack's run/, log/ and secrets/ are shared.
try:
    VAR_DIR, VAR_DIR_ERROR = resolve_var_dir(os.environ, None), None
    if is_private_var_dir(VAR_DIR):
        VAR_DIR_ERROR = VAR_DIR_PRIVATE
except VarDirError as e:
    VAR_DIR, VAR_DIR_ERROR = "", str(e)
# The SA password's secret file is written only when it is asked for (the
# variable set by the caller; the Makefile exports it only then) or needed
# (see sync_sa_secret). docker-compose.yml reads the path from the environment,
# which compose_env() gives the compose commands only. The default is never put
# into os.environ: a harness that imports this module would pass it on to its
# make children, whose stack.py would then take it as asked for.
SA_SECRET_EXPLICIT = bool(os.environ.get("CARDDEMO_MSSQL_SA_PASSWORD_FILE"))
SA_SECRET_FILE = (os.environ.get("CARDDEMO_MSSQL_SA_PASSWORD_FILE")
                  or (os.path.join(VAR_DIR, "secrets", "mssql-sa-password") if VAR_DIR else ""))
SA_SECRET_MOUNT = "/run/secrets/mssql_sa_password"
RUN_DIR = os.path.join(VAR_DIR, "run")
LOG_DIR = os.path.join(VAR_DIR, "log")
UP_TIMEOUT = int(os.environ.get("UP_TIMEOUT", "300"))
STOP_TIMEOUT = 30

# Ports and heap caps: docs/modernization/architecture/overview.md#deployables.
JVM_SERVICES = [
    ("identity-service", "services/identity-service", 28081, "256m"),
    ("billpay-service", "services/billpay-service", 28082, "256m"),
    ("account-service", "services/account-service", 28083, "384m"),
    ("transaction-service", "services/transaction-service", 28084, "384m"),
    ("reference-service", "services/reference-service", 28085, "256m"),
    ("authorization-service", "services/authorization-service", 28086, "384m"),
    ("inquiry-service", "services/inquiry-service", 28087, "256m"),
]
SPRING_PROFILE = os.environ.get("CARDDEMO_SPRING_PROFILE", "demo")
# batch-service in server mode (batch-execution-contract.md): the internal API
# that the DAGs drive, on loopback. No job starts by itself, and a launch
# answers 202 at once. make load still runs its one-shot JVM.
BATCH_SERVICE = ("batch-service", "batch/batch-service", "1g")
BATCH_DEFAULT_PORT = 28090

# Airflow (architecture: batch.md, repo-build-ci.md): four processes from the
# host venv, LocalExecutor, metadata in the compose Postgres. All four report
# through the API server's health endpoint.
AIRFLOW_PORT = 28080
AIRFLOW_URL = f"http://127.0.0.1:{AIRFLOW_PORT}"
AIRFLOW_HEALTH = f"{AIRFLOW_URL}/api/v2/monitor/health"
# --skip-serve-logs: Airflow's log servers bind every interface, and on one
# host the API server reads the task logs from the files directly.
AIRFLOW_PROCESSES = [
    ("airflow-api-server", ["api-server"], AIRFLOW_PORT, "metadatabase"),
    ("airflow-scheduler", ["scheduler", "--skip-serve-logs"], None, "scheduler"),
    ("airflow-dag-processor", ["dag-processor"], None, "dag_processor"),
    ("airflow-triggerer", ["triggerer", "--skip-serve-logs"], None, "triggerer"),
]
AIRFLOW_SECRETS = ("AIRFLOW_DB_PASSWORD", "AIRFLOW_ADMIN_PASSWORD", "AIRFLOW_API_JWT_SECRET",
                   "AIRFLOW_API_SECRET", "AIRFLOW_FERNET_KEY", "CARDDEMO_BATCH_INTERNAL_TOKEN")


def service_args(name: str) -> list:
    # identity-service reads its signing key from CARDDEMO_JWT_KEY_FILE,
    # which the Makefile exports.
    return [f"--spring.profiles.active={SPRING_PROFILE}"]
WEB_PORT = 25173
HEALTH_PATH = "/actuator/health"


def say(status: str, name: str, detail: str) -> None:
    print(f"{status:5} {name:21} {detail}", flush=True)


class Component:
    """One host process; `problem` says why a converted one can't start,
    `prepare` what up must do first (build, npm ci or gen-validation) and why."""

    def __init__(self, name, port, cwd, argv, health_url, marker, problem=None, health_key=None,
                 prepare=None, module=None):
        self.name, self.port, self.cwd, self.argv = name, port, cwd, argv
        self.health_url, self.marker, self.problem = health_url, marker, problem
        # Set for the Airflow processes: the part of the health answer
        # (scheduler, triggerer, ...) that must say "healthy".
        self.health_key = health_key
        self.prepare = prepare
        # The Maven module (relative to modernization/) of a service.
        self.module = module

    @property
    def is_airflow(self):
        return self.health_key is not None

    @property
    def where(self):
        return f"port {self.port}" if self.port else "no port"


    @property
    def pid_file(self):
        return os.path.join(RUN_DIR, f"{self.name}.pid")

    @property
    def log_file(self):
        return os.path.join(LOG_DIR, f"{self.name}.log")


def toolchain(what: str) -> str:
    """JDK 21 home or Node 24 bin directory from scripts/toolchain.sh, so the
    stack also uses the pinned versions when stack.py runs outside make."""
    out = subprocess.run([os.path.join(HERE, "scripts", "toolchain.sh"), what],
                         capture_output=True, text=True)
    return out.stdout.strip() if out.returncode == 0 else ""


def java_bin() -> str:
    home = os.environ.get("JAVA_HOME") or toolchain("java-home")
    return os.path.join(home, "bin", "java") if home else "java"


def node_bin() -> str:
    return toolchain("node-bin")


def find_jar(module_dir: str, artifact: str):
    jars = sorted(glob.glob(os.path.join(module_dir, "target", f"{artifact}-*-exec.jar")))
    return jars[-1] if jars else None


# What a service jar is built from, besides its own module: the reactor's
# poms and the platform libraries every service depends on.
BUILD_INPUTS = ("pom.xml", ".mvn", "platform")
SKIP_DIRS = {"target", "node_modules", ".git", "__pycache__"}


def newest_source(paths) -> float:
    newest = 0.0
    for path in paths:
        if os.path.isfile(path):
            newest = max(newest, os.path.getmtime(path))
            continue
        for root, dirs, files in os.walk(path):
            dirs[:] = [d for d in dirs if d not in SKIP_DIRS]
            for f in files:
                try:
                    newest = max(newest, os.path.getmtime(os.path.join(root, f)))
                except OSError:
                    pass
    return newest


def jar_problem(module: str, jar):
    """Why the service jar must be built first, or None when it is current."""
    if not jar:
        return "no jar"
    inputs = [os.path.join(HERE, p) for p in BUILD_INPUTS] + [module]
    if newest_source(inputs) > os.path.getmtime(jar):
        return "jar older than its sources"
    return None


# Relative to web/. src/api/generated is git-ignored and only the script writes it; the
# sign-on rules in WEB_RULES are imported on the path every web entry point loads.
GEN_VALIDATION = os.path.join("scripts", "gen-validation.mjs")
WEB_RULES = os.path.join("src", "api", "generated", "validation-forms", "auth.json")
WEB_RULES_MISSING = "no " + WEB_RULES


def web_problem(web: str):
    """Why npm ci and gen-validation, or gen-validation alone, must run first, or None when
    node_modules matches the lock file and the generated rules exist."""
    installed = os.path.join(web, "node_modules", ".package-lock.json")
    if not os.path.exists(installed):
        return "no node_modules"
    lock = os.path.join(web, "package-lock.json")
    if os.path.exists(lock) and os.path.getmtime(lock) > os.path.getmtime(installed):
        return "node_modules older than package-lock.json"
    if not os.path.exists(os.path.join(web, WEB_RULES)):
        return WEB_RULES_MISSING
    return None


def db_name(env=None) -> str:
    """The application database the services use: CARDDEMO_DB_NAME from the
    environment, else .env, else carddemo (as in the services' settings)."""
    env = os.environ if env is None else env
    return env.get("CARDDEMO_DB_NAME") or read_env(ENV_FILE).get("CARDDEMO_DB_NAME") or "carddemo"


def db_file(name: str) -> str:
    return os.path.join(RUN_DIR, f"{name}.db")


def recorded_db(name: str):
    try:
        with open(db_file(name), encoding="utf-8") as f:
            return f.read().strip() or None
    except OSError:
        return None


def batch_port(env=None) -> int:
    env = os.environ if env is None else env
    value = env.get("CARDDEMO_BATCH_PORT") or read_env(ENV_FILE).get("CARDDEMO_BATCH_PORT")
    return int(value) if value else BATCH_DEFAULT_PORT


def batch_component():
    """batch-service's server, when this checkout has its module."""
    name, rel, heap = BATCH_SERVICE
    module = os.path.join(HERE, rel)
    if not os.path.exists(os.path.join(module, "pom.xml")):
        return None
    port = batch_port()
    jar = find_jar(module, name)
    argv, problem = None, None
    if jar:
        # java itself, not a shell: the recorded pid is the JVM's, so stop
        # signals the process that holds the execution leases.
        argv = [java_bin(), f"-Xmx{heap}", "-XX:MaxMetaspaceSize=128m", "-Xss512k",
                "-jar", jar, "--spring.profiles.active=server", f"--server.port={port}",
                "--server.address=127.0.0.1"]
    else:
        problem = f"not built: no {rel}/target/{name}-*-exec.jar (run make build)"
    return Component(name, port, module, argv, f"http://127.0.0.1:{port}{HEALTH_PATH}", jar or name, problem,
                     prepare=jar_problem(module, jar), module=rel)


def airflow_venv() -> str:
    return (os.environ.get("AIRFLOW_VENV") or read_env(ENV_FILE).get("AIRFLOW_VENV")
            or os.path.join(os.path.expanduser("~"), ".venvs", "carddemo-airflow"))


def airflow_components() -> list:
    """The four Airflow processes, when this checkout has DAGs."""
    airflow_dir = os.path.join(HERE, "airflow")
    if not os.path.isdir(os.path.join(airflow_dir, "dags")):
        return []
    airflow = os.path.join(airflow_venv(), "bin", "airflow")
    problem = None
    if not os.access(airflow, os.X_OK):
        problem = f"no {airflow}; see README.md, Prerequisites (AIRFLOW_VENV)"
    else:
        env = read_env(ENV_FILE)
        missing = [k for k in AIRFLOW_SECRETS if not (os.environ.get(k) or env.get(k))]
        merged = {**env, **os.environ}
        if missing:
            problem = f"{', '.join(missing)} not set in {ENV_FILE}; run make setup"
        elif (merged.get("CARDDEMO_AIRFLOW_REPORT_USER", "").strip()
              == merged.get("AIRFLOW_ADMIN_USER", "admin")):
            # carddemo_airflow.auth restricts this name, so the admin would lose its rights.
            problem = "CARDDEMO_AIRFLOW_REPORT_USER must differ from AIRFLOW_ADMIN_USER"
    return [Component(name, port, airflow_dir, [airflow, *args], AIRFLOW_HEALTH, f"airflow {args[0]}",
                      problem, health_key=key)
            for name, args, port, key in AIRFLOW_PROCESSES]


def airflow_users(env: dict) -> dict:
    """Name -> (role, password) of the simple auth manager's users: the
    admin, and transaction-service's report account once setup has given it
    a password. The account gets the lowest role; carddemo_airflow.auth
    narrows it to triggering carddemo_transaction_report."""
    admin = env.get("AIRFLOW_ADMIN_USER", "admin")
    users = {admin: ("admin", env.get("AIRFLOW_ADMIN_PASSWORD", ""))}
    report = env.get("CARDDEMO_AIRFLOW_REPORT_USER", "").strip()
    password = env.get("CARDDEMO_AIRFLOW_REPORT_PASSWORD", "")
    if report and password and report != admin:
        users[report] = ("viewer", password)
    return users


def airflow_env(base: dict) -> dict:
    """The environment of the Airflow processes: home, logs and DAGs under
    the shared var directory and this checkout, metadata in the compose
    Postgres, and the shared secrets, so all four processes agree. An
    AIRFLOW__* variable already in the environment wins."""
    env = dict(base)
    home = os.path.join(VAR_DIR, "airflow")
    db = "postgresql+psycopg2://{}:{}@127.0.0.1:{}/{}".format(
        urllib.parse.quote(env.get("AIRFLOW_DB_USER", "airflow"), safe=""),
        urllib.parse.quote(env.get("AIRFLOW_DB_PASSWORD", ""), safe=""),
        env.get("AIRFLOW_DB_HOST_PORT", "25432"),
        urllib.parse.quote(env.get("AIRFLOW_DB_NAME", "airflow"), safe=""))
    settings = {
        "AIRFLOW__CORE__DAGS_FOLDER": os.path.join(HERE, "airflow", "dags"),
        "AIRFLOW__CORE__LOAD_EXAMPLES": "False",
        "AIRFLOW__CORE__EXECUTOR": "LocalExecutor",
        "AIRFLOW__CORE__PARALLELISM": "4",
        # The cycle changes data: a DAG runs only after someone unpauses it.
        "AIRFLOW__CORE__DAGS_ARE_PAUSED_AT_CREATION": "True",
        "AIRFLOW__CORE__FERNET_KEY": env.get("AIRFLOW_FERNET_KEY", ""),
        "AIRFLOW__CORE__AUTH_MANAGER": "carddemo_airflow.auth.CarddemoAuthManager",
        "AIRFLOW__CORE__SIMPLE_AUTH_MANAGER_USERS": ",".join(
            f"{name}:{role}" for name, (role, _) in airflow_users(env).items()),
        "AIRFLOW__CORE__SIMPLE_AUTH_MANAGER_ALL_ADMINS": "False",
        "AIRFLOW__CORE__SIMPLE_AUTH_MANAGER_PASSWORDS_FILE": os.path.join(home, "passwords.json"),
        "AIRFLOW__CORE__EXECUTION_API_SERVER_URL": f"{AIRFLOW_URL}/execution/",
        "AIRFLOW__DATABASE__SQL_ALCHEMY_CONN": db,
        "AIRFLOW__API__HOST": "127.0.0.1",
        "AIRFLOW__API__PORT": str(AIRFLOW_PORT),
        "AIRFLOW__API__WORKERS": "1",
        "AIRFLOW__API__BASE_URL": AIRFLOW_URL,
        "AIRFLOW__API__SECRET_KEY": env.get("AIRFLOW_API_SECRET", ""),
        "AIRFLOW__API_AUTH__JWT_SECRET": env.get("AIRFLOW_API_JWT_SECRET", ""),
        "AIRFLOW__DAG_PROCESSOR__PARSING_PROCESSES": "1",
        "AIRFLOW__LOGGING__BASE_LOG_FOLDER": os.path.join(LOG_DIR, "airflow"),
    }
    for key, value in settings.items():
        if key not in os.environ:
            env[key] = value
    if sys.platform == "darwin":
        # The DAG processor and the triggerer fork without exec; Apple's
        # frameworks then abort (Objective-C fork safety) or crash in the
        # system proxy lookup unless a proxy variable is set.
        env.setdefault("OBJC_DISABLE_INITIALIZE_FORK_SAFETY", "YES")
        env.setdefault("no_proxy", "127.0.0.1,localhost")
    env["AIRFLOW_HOME"] = home
    env["CARDDEMO_VAR_DIR"] = VAR_DIR
    # This checkout's operators, also when the venv's editable install points
    # at another checkout.
    env["PYTHONPATH"] = os.pathsep.join(p for p in (os.path.join(HERE, "airflow"), env.get("PYTHONPATH")) if p)
    return env


def note_report_user(home: str, report: str) -> None:
    """Warns when CARDDEMO_AIRFLOW_REPORT_USER changed since the last start:
    Airflow accepts a token until it expires without looking the name up,
    and carddemo_airflow.auth restricts only the configured name, so a token
    issued to the old name keeps its role, unrestricted."""
    path = os.path.join(home, "report-user")
    try:
        with open(path, encoding="utf-8") as f:
            previous = f.read().strip()
    except OSError:
        previous = ""
    if previous and previous != report:
        say("warn", "airflow", f"CARDDEMO_AIRFLOW_REPORT_USER changed from {previous} to {report or '(none)'}: "
                               f"tokens issued to {previous} stay valid until they expire (24 h by default), "
                               f"without the report restriction; rotate AIRFLOW_API_JWT_SECRET and restart "
                               f"Airflow (README, Airflow)")
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as f:
        f.write(report + "\n")


def prepare_airflow(env: dict):
    """Migrates the metadata database, writes the admin password file and
    imports the pools before the processes start; returns an error or None."""
    home = env["AIRFLOW_HOME"]
    os.makedirs(home, exist_ok=True)
    os.makedirs(LOG_DIR, exist_ok=True)
    passwords = env["AIRFLOW__CORE__SIMPLE_AUTH_MANAGER_PASSWORDS_FILE"]
    users = airflow_users(env)
    fd = os.open(passwords, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as f:
        json.dump({name: password for name, (_, password) in users.items()}, f)
    report = env.get("CARDDEMO_AIRFLOW_REPORT_USER", "").strip()
    if report and report not in users:
        say("warn", "airflow", f"no Airflow account {report} for transaction-service's report trigger "
                               f"(CARDDEMO_AIRFLOW_REPORT_PASSWORD not set; run make setup)")
    os.chmod(passwords, 0o600)
    note_report_user(home, report)
    airflow = os.path.join(airflow_venv(), "bin", "airflow")
    log_path = os.path.join(LOG_DIR, "airflow-prepare.log")
    steps = [("db migrate", [airflow, "db", "migrate"]),
             ("pools import", [airflow, "pools", "import", os.path.join(HERE, "airflow", "pools.json")])]
    with open(log_path, "ab") as log:
        for what, argv in steps:
            log.write(f"\n=== {time.strftime('%Y-%m-%d %H:%M:%S')} airflow {what}\n".encode())
            log.flush()
            try:
                rc = subprocess.run(argv, cwd=os.path.join(HERE, "airflow"), env=env, stdin=subprocess.DEVNULL,
                                    stdout=log, stderr=subprocess.STDOUT, timeout=UP_TIMEOUT).returncode
            except subprocess.TimeoutExpired:
                return f"airflow {what} took over {UP_TIMEOUT} s; see {log_path}"
            if rc != 0:
                return f"airflow {what} failed; see {log_path}"
    return None

def components() -> list:
    """Every component whose source exists, in start order."""
    found = []
    for name, rel, port, heap in JVM_SERVICES:
        module = os.path.join(HERE, rel)
        if not os.path.exists(os.path.join(module, "pom.xml")):
            continue
        jar = find_jar(module, name)
        argv = None
        problem = None
        if jar:
            argv = [java_bin(), f"-Xmx{heap}", "-XX:MaxMetaspaceSize=128m", "-Xss512k",
                    "-jar", jar, f"--server.port={port}", "--server.address=127.0.0.1",
                    *service_args(name)]
        else:
            problem = f"not built: no {rel}/target/{name}-*-exec.jar (run make build)"
        found.append(Component(name, port, module, argv,
                               f"http://127.0.0.1:{port}{HEALTH_PATH}", jar or name, problem,
                               prepare=jar_problem(module, jar), module=rel))
    # Before Airflow, whose tasks call it.
    batch = batch_component()
    if batch:
        found.append(batch)
    found.extend(airflow_components())
    web = os.path.join(HERE, "web")
    if os.path.exists(os.path.join(web, "package.json")):
        problem = None
        if not os.path.isdir(os.path.join(web, "node_modules")):
            problem = "dependencies missing: run npm ci in modernization/web"
        # vite.config.ts pins 127.0.0.1:25173 with strictPort.
        node = node_bin()
        npm = os.path.join(node, "npm") if node else "npm"
        found.append(Component("web", WEB_PORT, web, [npm, "run", "dev"],
                               f"http://127.0.0.1:{WEB_PORT}/", "vite", problem, prepare=web_problem(web)))
    return found


def read_pid_marker(name):
    """The pid and the marker recorded at start, which may come from another
    checkout: the processes, like the containers, are machine-wide."""
    try:
        with open(os.path.join(RUN_DIR, f"{name}.pid"), encoding="utf-8") as f:
            pid_text, marker = f.read().strip().split(" ", 1)
            return int(pid_text), marker
    except (OSError, ValueError):
        return None, ""


def command_line(pid) -> str:
    """pid's whole command line as ps prints it; "" when there is no such process.

    -ww: procps ps on Linux cuts the command column to $COLUMNS even when it
    writes to a pipe, which can cut off the marker. Twice -w lifts that limit
    there, and macOS ps accepts it too."""
    return subprocess.run(["ps", "-ww", "-o", "command=", "-p", str(pid)],
                          capture_output=True, text=True).stdout


def alive(pid, marker) -> bool:
    """True when pid runs and its command line still names our component, so
    a reused PID is never mistaken for (or killed as) a stack process."""
    if not pid:
        return False
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    except PermissionError:
        return False
    out = command_line(pid)
    if marker in out:
        return True
    # npm starts vite as a child; the leader is "npm run dev".
    return marker == "vite" and "npm" in out


def healthy(url, key=None) -> bool:
    """2xx from url; with key, also {key: {"status": "healthy"}} in the JSON."""
    try:
        with urllib.request.urlopen(url, timeout=3) as r:
            if not 200 <= r.status < 300:
                return False
            if key is None:
                return True
            part = json.loads(r.read()).get(key) or {}
            return part.get("status") == "healthy"
    except (urllib.error.URLError, OSError, ValueError, AttributeError):
        return False


def comp_healthy(c) -> bool:
    return healthy(c.health_url, c.health_key)


def port_busy(port) -> bool:
    if not port:
        return False
    with socket.socket() as s:
        s.settimeout(0.5)
        return s.connect_ex(("127.0.0.1", port)) == 0


def child_env() -> dict:
    env = dict(os.environ)
    for key, value in read_env(ENV_FILE).items():
        env.setdefault(key, value)
    # Services run with their module as working directory, so a relative
    # default would not find the checkout's ledger.
    env.setdefault("CARDDEMO_LEDGER_DIR", os.path.join(HERE, "ledger"))
    env.setdefault("CARDDEMO_JWT_KEY_FILE", os.path.join(VAR_DIR, "secrets", "identity-jwt.pem"))
    # transaction-service triggers the report DAG here.
    env.setdefault("CARDDEMO_AIRFLOW_URL", AIRFLOW_URL)
    # Every output and inbound file is under CARDDEMO_VAR_DIR (out/<database>/
    # <instance>/ and inbound/); the services and db-migrate refuse the old
    # per-folder settings by name, so none is set here, and up refuses one that
    # the environment or .env still sets.
    for key in LEGACY_OUTPUT_SETTINGS:
        env.pop(key, None)
    env[VAR_DIR_KEY] = VAR_DIR
    # batch-service runs in its module directory, where its relative defaults
    # point nowhere; the data load and the purge read these sample files.
    sample = {"CARDDEMO_LOAD_SOURCE_DIR": os.path.join("..", "app", "data", "ASCII"),
              "CARDDEMO_PAUTH_SOURCE_FILE": os.path.join("..", "app", "app-authorization-ims-db2-mq", "data",
                                                         "EBCDIC", "AWS.M2.CARDDEMO.IMSDATA.DBPAUTP0.dat")}
    for key, default in sample.items():
        env[key] = os.path.normpath(os.path.join(HERE, env.get(key) or default))

    # The Airflow tasks reach batch-service where up starts it.
    env.setdefault("CARDDEMO_BATCH_URL", f"http://127.0.0.1:{batch_port(env)}")
    # npm and vite start "node" from PATH; web's engines field rejects
    # anything but Node 24, and Homebrew's default node can be newer.
    node = node_bin()
    if node:
        env["PATH"] = node + os.pathsep + env.get("PATH", "")
    return env


def tail(path, lines=5) -> str:
    try:
        with open(path, encoding="utf-8", errors="replace") as f:
            return "".join(f.readlines()[-lines:]).rstrip()
    except OSError:
        return ""


# --- compose ---------------------------------------------------------------

def compose(*args) -> list:
    return ["docker", "compose", "--project-directory", INFRA_DIR,
            "-f", os.path.join(INFRA_DIR, "docker-compose.yml"), "--env-file", ENV_FILE, *args]


def compose_env() -> dict:
    """The environment of a compose command: the SA secret's path for the mssql
    service's compose secret."""
    env = dict(os.environ)
    if SA_SECRET_FILE:
        env["CARDDEMO_MSSQL_SA_PASSWORD_FILE"] = SA_SECRET_FILE
    return env


def running_containers() -> set:
    out = subprocess.run(compose("ps", "--status", "running", "--format", "{{.Name}}"),
                         capture_output=True, text=True, env=compose_env())
    return set(out.stdout.split())


def would_recreate() -> list:
    """Running containers that `compose up` would recreate (restart), found
    with a dry run, which changes nothing."""
    out = subprocess.run(compose("--dry-run", "up", "--detach"), capture_output=True, text=True,
                         env=compose_env())
    if out.returncode != 0:
        last = out.stderr.strip().splitlines()[-1:] or ["no output"]
        raise RuntimeError("compose dry run failed: " + last[0])
    planned = {parts[1] for parts in (line.split() for line in (out.stdout + out.stderr).splitlines())
               if len(parts) == 3 and parts[0] == "Container" and parts[2] == "Recreate"}
    return sorted(planned & running_containers())


def mssql_secret_state() -> tuple:
    """('mounted', host file) or ('not mounted', None) for the compose
    project's mssql container, ('absent', None) when there is none, ('unknown',
    None) when docker cannot say. Reads one field of docker inspect, the
    mounts' destinations and sources, never Config.Env."""
    try:
        ps = subprocess.run(compose("ps", "--all", "-q", "mssql"), capture_output=True, text=True, timeout=60,
                            env=compose_env())
        if ps.returncode != 0:
            return "unknown", None
        ids = ps.stdout.split()
        if not ids:
            return "absent", None
        out = subprocess.run(["docker", "inspect", "--format",
                              "{{range .Mounts}}{{println .Destination .Source}}{{end}}", ids[0]],
                             capture_output=True, text=True, timeout=60)
    except (OSError, subprocess.TimeoutExpired):
        return "unknown", None
    if out.returncode != 0:
        return "unknown", None
    for line in out.stdout.splitlines():
        destination, _, source = line.strip().partition(" ")
        if destination == SA_SECRET_MOUNT:
            return "mounted", source
    return "not mounted", None


def compose_declares_sa_secret() -> bool:
    try:
        with open(os.path.join(INFRA_DIR, "docker-compose.yml"), encoding="utf-8") as f:
            text = f.read()
    except OSError:
        return False
    return re.search(r"(?m)^secrets:\n(?:(?:  .*|#.*|)\n)*?  mssql_sa_password:", text) is not None


def canonical_sa_secret() -> str:
    """The stack's own secret file: under .env's CARDDEMO_VAR_DIR or the main
    checkout's var/, never under a CARDDEMO_VAR_DIR that only this process's
    environment names (a test harness's private folder)."""
    environ = {k: v for k, v in os.environ.items() if k != VAR_DIR_KEY}
    return os.path.join(resolve_var_dir(environ), "secrets", "mssql-sa-password")


def same_file(a: str, b: str) -> bool:
    return os.path.realpath(a) == os.path.realpath(b)


def mounted_from(source: str, path: str) -> bool:
    """Whether docker's mount source is path. Docker Desktop reports host
    files under a prefix of its VM (/host_mnt followed by the host path); Colima and Linux
    report the host path itself."""
    real = os.path.realpath(path)
    return bool(source) and (same_file(source, path) or source.endswith(real) or source.endswith(path))


def sync_sa_secret(verbose: bool = False) -> int:
    """Copy MSSQL_SA_PASSWORD from .env to the secret file only when the
    caller set CARDDEMO_MSSQL_SA_PASSWORD_FILE, when the mssql container
    mounts the file, or when there is no mssql container and the compose file
    gives the one up creates the secret. A container created before the
    secret existed has its password in master and does not need the file, so
    no copy of it is left on disk; a one-line note says so.

    Unless the caller named the file, it is written only at the stack's own
    path (canonical_sa_secret), and never when the container mounts the
    secret from another file: a copy elsewhere would leave the password in a
    folder nobody looks after, and compose would plan to recreate mssql."""
    if not SA_SECRET_EXPLICIT:
        try:
            canonical = canonical_sa_secret()
        except VarDirError as e:
            say("FAIL", "sa-secret", f"{e}; nothing written")
            return 1
        if not same_file(SA_SECRET_FILE, canonical):
            say("FAIL", "sa-secret", f"{SA_SECRET_FILE} is not the stack's secret file {canonical}: this "
                                     f"process's CARDDEMO_VAR_DIR is not the stack's (a harness gives db-reset its "
                                     f"own folder with RUN_VAR_DIR); nothing written")
            return 1
        state, source = mssql_secret_state()
        if not (state == "mounted" or (state == "absent" and compose_declares_sa_secret())):
            why = {"not mounted": "the mssql container does not mount the secret",
                   "absent": "no mssql container, and the compose file has no SA secret",
                   "unknown": "docker cannot say whether the mssql container mounts the secret"}[state]
            say("note", "sa-secret", f"not written ({why}); set CARDDEMO_MSSQL_SA_PASSWORD_FILE to write it")
            return 0
        if state == "mounted" and not mounted_from(source, SA_SECRET_FILE):
            say("FAIL", "sa-secret", f"the mssql container mounts the secret from {source or 'an unknown file'}, "
                                     f"not {SA_SECRET_FILE}; nothing written")
            return 1
    return init_env.sa_secret(ENV_FILE, SA_SECRET_FILE, verbose=verbose)

def rabbit_env() -> dict:
    # The environment wins over .env, as for compose and the services.
    return {**read_env(ENV_FILE), **os.environ}


def rabbit_users() -> int:
    if rabbitmq_users.provision(rabbit_env(), say, required=True) != 0:
        print("rabbitmq-users: FAILED (see above)")
        return 1
    print("rabbitmq-users: OK")
    return 0


def infra_up(target: str) -> int:
    # A changed .env value reaches the running container's health check
    # through the same file.
    if sync_sa_secret() != 0:
        print(f"{target}: FAILED (the SA password secret file; see above)")
        return 1
    try:
        recreate = would_recreate()
    except RuntimeError as e:
        print(f"{target}: FAILED ({e})")
        return 1
    if recreate:
        names = ", ".join(recreate)
        if os.environ.get("RECREATE") != "1":
            say("STOP", "compose", f"would RECREATE (restart) running shared container(s): {names}")
            say("", "", "their configuration changed; every checkout's connections and running tests drop")
            print(f"{target}: FAILED (would restart shared container(s) {names}; rerun with RECREATE=1 when nobody uses them)")
            return 1
        say("WARN", "compose", f"RECREATING (restarting) shared container(s) {names}; connections drop for every checkout")
    quiet = target not in ("infra-up", "up")
    res = subprocess.run(compose("up", "--detach", "--wait", "--wait-timeout", str(UP_TIMEOUT)),
                         capture_output=quiet, text=True, env=compose_env())
    rc = res.returncode
    if not quiet:
        subprocess.run(compose("ps", "--format", "table {{.Service}}\t{{.Status}}\t{{.Ports}}"), env=compose_env())
    elif rc != 0:
        print(res.stderr.strip())
    if rc != 0:
        print(f"{target}: FAILED (containers not healthy within {UP_TIMEOUT} s; see make logs)")
        return 1
    # Only for the targets that start the services: the others (db-reset,
    # load, ...) need SQL Server alone.
    if target in ("infra-up", "up") and rabbitmq_users.provision(rabbit_env(), say) != 0:
        print(f"{target}: FAILED (RabbitMQ virtual host or users; see above)")
        return 1
    if target == "infra-up":
        print(f"{target}: OK")
    return 0


def lock_holder(path):
    """The first line an exclusive holder of the lock file wrote, or None when
    nobody holds it exclusively."""
    if not os.path.exists(path):
        return None
    fd = os.open(path, os.O_RDONLY)
    try:
        fcntl.flock(fd, fcntl.LOCK_SH | fcntl.LOCK_NB)
        return None
    except BlockingIOError:
        text = os.pread(fd, 512, 0).decode("utf-8", "replace").strip()
        return text.splitlines()[0] if text else "unknown"
    finally:
        os.close(fd)


def heavy_holder():
    return lock_holder(os.path.join(RUN_DIR, "heavy.lock"))


# scripts/e2e_stack.py holds this lock exclusively while it runs the slice on
# its own database. up, stop and down hold it shared for their whole run, so
# they never start or stop slice processes under an isolated run, and an
# isolated run never begins while one of them is half done. The isolated run's
# own calls pass its token in CARDDEMO_E2E_SESSION and skip the lock.
# The slice ports are machine-wide, so the lock is too: one file in the
# user's home, not in var/, which a separate clone (its own .git) does not
# share with the worktrees.
SLICE_LOCK = os.environ.get("CARDDEMO_SLICE_LOCK") or os.path.join(
    os.path.expanduser("~"), ".cache", "carddemo", "slice.lock")
_slice_fd = None


def guard_slice(target: str) -> bool:
    global _slice_fd
    token = os.environ.get("CARDDEMO_E2E_SESSION")
    os.makedirs(os.path.dirname(SLICE_LOCK), exist_ok=True)
    fd = os.open(SLICE_LOCK, os.O_RDWR | os.O_CREAT, 0o644)
    if token and f"token {token}" in os.pread(fd, 512, 0).decode("utf-8", "replace").split("\n"):
        os.close(fd)
        return True
    try:
        fcntl.flock(fd, fcntl.LOCK_SH | fcntl.LOCK_NB)
    except BlockingIOError:
        text = os.pread(fd, 512, 0).decode("utf-8", "replace").strip()
        os.close(fd)
        holder = text.splitlines()[0] if text else "unknown"
        print(f"{target}: FAILED (an isolated browser run owns the slice: {holder}; "
              f"rerun when it has ended, it restores what ran before)")
        return False
    _slice_fd = fd
    return True


def infra_down(target: str, force: bool = False) -> int:
    holder = heavy_holder()
    if holder and not force and os.environ.get("FORCE") != "1":
        print(f"{target}: FAILED (a heavy target is running: {holder}; rerun with FORCE=1 to stop anyway)")
        return 1
    running = sorted(running_containers())
    if running:
        say("WARN", "compose", "stopping the shared containers for every checkout: " + ", ".join(running))
    rc = subprocess.run(compose("down", "--remove-orphans"), env=compose_env()).returncode
    if rc != 0:
        print(f"{target}: FAILED (docker compose down exited {rc})")
        return 1
    if target == "infra-down":
        print(f"{target}: OK (data volumes kept)")
    return 0


# --- host processes --------------------------------------------------------

def start(comp, env) -> str:
    """Starts comp unless it already runs; returns started, running or an error."""
    pid, marker = read_pid_marker(comp.name)
    if alive(pid, marker):
        return "running" if marker == comp.marker else f"running from {marker}"
    if port_busy(comp.port):
        return f"port {comp.port} is already in use by another process"
    os.makedirs(RUN_DIR, exist_ok=True)
    os.makedirs(LOG_DIR, exist_ok=True)
    log = open(comp.log_file, "ab")
    log.write(f"\n=== {time.strftime('%Y-%m-%d %H:%M:%S')} start {comp.name}\n".encode())
    log.flush()
    try:
        proc = subprocess.Popen(comp.argv, cwd=comp.cwd, env=env, stdin=subprocess.DEVNULL,
                                stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
    except OSError as e:
        return f"cannot start: {e.strerror}"
    finally:
        log.close()
    with open(comp.pid_file, "w", encoding="utf-8") as f:
        f.write(f"{proc.pid} {comp.marker}\n")
    if comp.module:
        with open(db_file(comp.name), "w", encoding="utf-8") as f:
            f.write(db_name(env) + "\n")
    return "started"


def stop(comp_name, pid, marker) -> bool:
    pid_file = os.path.join(RUN_DIR, f"{comp_name}.pid")
    if not alive(pid, marker):
        for path in (pid_file, db_file(comp_name)):
            if os.path.exists(path):
                os.remove(path)
        return True
    try:
        os.killpg(pid, signal.SIGTERM)
    except ProcessLookupError:
        pass
    deadline = time.time() + STOP_TIMEOUT
    while time.time() < deadline and alive(pid, marker):
        time.sleep(0.5)
    if alive(pid, marker):
        say("WARN", comp_name, f"still running after {STOP_TIMEOUT} s; killing")
        try:
            os.killpg(pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        time.sleep(1)
    if alive(pid, marker):
        return False
    os.remove(pid_file)
    if os.path.exists(db_file(comp_name)):
        os.remove(db_file(comp_name))
    return True


def recorded() -> list:
    """(name, pid, marker) for every pid file, also of components that don't
    exist in this checkout."""
    names = (os.path.basename(p)[:-4] for p in sorted(glob.glob(os.path.join(RUN_DIR, "*.pid"))))
    return [(name, *read_pid_marker(name)) for name in names]


def own_process_runs(c) -> bool:
    """c runs from this checkout's files (its jar, or a web dev server)."""
    pid, marker = read_pid_marker(c.name)
    return alive(pid, marker) and (marker == c.marker or c.module is None)


def run_logged(argv, cwd, env, log_name, what) -> bool:
    log_path = os.path.join(LOG_DIR, log_name)
    os.makedirs(LOG_DIR, exist_ok=True)
    with open(log_path, "ab") as log:
        log.write(f"\n=== {time.strftime('%Y-%m-%d %H:%M:%S')} {what} in {cwd}\n".encode())
        log.flush()
        rc = subprocess.run(argv, cwd=cwd, env=env, stdin=subprocess.DEVNULL, stdout=log,
                            stderr=subprocess.STDOUT).returncode
    if rc != 0:
        say("FAIL", what.split()[0], f"exited {rc}; last lines of {log_path}:")
        print(tail(log_path, 20), flush=True)
    return rc == 0


def web_generate(web, env) -> bool:
    """Runs GEN_VALIDATION in web.cwd with node, not npm run. Never while this checkout's dev
    server runs: the script deletes and rewrites folders that Vite serves."""
    if own_process_runs(web):
        say("FAIL", "web", "its dev server runs, so gen-validation was not run; make stop first")
        return False
    node = node_bin()
    argv = [os.path.join(node, "node") if node else "node", GEN_VALIDATION]
    say("..", "web", f"node {GEN_VALIDATION}")
    if not run_logged(argv, web.cwd, env, "up-gen-validation.log", "gen-validation"):
        return False
    say("ok", "web", "gen-validation")
    return True


def prepare(comps, env) -> list:
    """Builds the missing or stale service jars, installs the web
    dependencies and generates the web app's rules, for the components that
    are not running; returns the components after preparation."""
    names = [c.name for c in comps]
    todo = [c for c in comps if c.prepare]
    for c in todo:
        if own_process_runs(c):
            say("warn", c.name, f"running, so left as it is ({c.prepare}); make stop, then make up, "
                                f"restarts it on a fresh build")
    todo = [c for c in todo if not own_process_runs(c)]
    builds = [c for c in todo if c.module]
    failed = set()
    if builds:
        modules = ",".join(c.module for c in builds)
        say("..", "build", f"{modules} ({'; '.join(sorted({c.prepare for c in builds}))}): "
                           f"Maven package, tests skipped")
        build_env = dict(env)
        build_env.setdefault("JAVA_HOME", toolchain("java-home"))
        argv = [os.path.join(HERE, "mvnw"), "-q", "-B", "-pl", modules, "-am", "package", "-DskipTests"]
        if run_logged(argv, HERE, build_env, "up-build.log", "build " + modules):
            say("ok", "build", modules)
        else:
            failed |= {c.name for c in builds}
    web = next((c for c in todo if c.name == "web"), None)
    if web:
        installed = True
        if web.prepare != WEB_RULES_MISSING:
            say("..", "web", f"npm ci ({web.prepare})")
            installed = run_logged([web.argv[0], "ci"], web.cwd, env, "up-npm-ci.log", "npm ci")
            if installed:
                say("ok", "web", "npm ci")
        if not (installed and web_generate(web, env)):
            failed.add("web")
    fresh = {c.name: c for c in components()}
    result = []
    for name in names:
        c = fresh.get(name) or next(x for x in comps if x.name == name)
        if name in failed:
            c.problem = "preparation failed, see above"
        result.append(c)
    return result


def up(only) -> int:
    if infra_up("up") != 0:
        return 1
    comps = components()
    if "airflow" in only:
        only = (only - {"airflow"}) | {name for name, *_ in AIRFLOW_PROCESSES}
    if only:
        unknown = only - {c.name for c in comps}
        if unknown:
            print(f"up: FAILED (not in this checkout: {', '.join(sorted(unknown))})")
            return 1
        comps = [c for c in comps if c.name in only]
    if not comps:
        print("up: OK (infrastructure only; no application component is converted yet)")
        return 0
    env = child_env()
    comps = prepare(comps, env)
    database = db_name(env)
    failures = []
    for c in comps:
        if c.problem:
            say("FAIL", c.name, c.problem)
            failures.append(c.name)
        elif c.module and alive(*read_pid_marker(c.name)):
            used = recorded_db(c.name)
            if used and used != database:
                c.problem = f"running against database {used}, not {database}; make stop first"
                say("FAIL", c.name, c.problem)
                failures.append(c.name)
            elif not used:
                say("warn", c.name, f"running, database not recorded; make stop, then make up, to be sure it "
                                    f"uses {database}")
    af_env = airflow_env(env)
    to_start = [c for c in comps if c.is_airflow and not c.problem and not alive(*read_pid_marker(c.name))]
    if to_start:
        say("..", "airflow", "db migrate and pools import (first run: about a minute)")
        error = prepare_airflow(af_env)
        if error:
            for c in comps:
                if c.is_airflow and not c.problem:
                    c.problem = error
                    say("FAIL", c.name, error)
                    failures.append(c.name)
    waiting = []
    for c in comps:
        if c.problem:
            continue
        result = start(c, af_env if c.is_airflow else env)
        if result in ("started", "running") or result.startswith("running from"):
            db = f", database {recorded_db(c.name)}" if c.module and recorded_db(c.name) else ""
            say("..", c.name, f"{result}, {c.where}{db}, log {c.log_file}")
            waiting.append(c)
        else:
            say("FAIL", c.name, result)
            failures.append(c.name)
    deadline = time.time() + UP_TIMEOUT
    while waiting and time.time() < deadline:
        for c in list(waiting):
            if comp_healthy(c):
                say("ok", c.name, f"healthy at {c.health_url}" + (f" ({c.health_key})" if c.health_key else ""))
                waiting.remove(c)
            elif not alive(*read_pid_marker(c.name)):
                say("FAIL", c.name, f"exited during start; last lines of {c.log_file}:")
                print(tail(c.log_file), flush=True)
                failures.append(c.name)
                waiting.remove(c)
        if waiting:
            time.sleep(1)
    for c in waiting:
        say("FAIL", c.name, f"not healthy within {UP_TIMEOUT} s at {c.health_url}; see {c.log_file}")
        failures.append(c.name)
    if failures:
        print(f"up: FAILED ({', '.join(failures)})")
        return 1
    print(f"up: OK ({len(comps)} application component(s) healthy)")
    return 0


def stop_processes() -> list:
    """Stops every recorded process; returns the names that kept running."""
    left = []
    for name, pid, marker in reversed(recorded()):
        if stop(name, pid, marker):
            say("ok", name, "stopped")
        else:
            say("FAIL", name, f"pid {pid} did not stop")
            left.append(name)
    return left


def stop_only() -> int:
    left = stop_processes()
    if left:
        print(f"stop: FAILED (still running: {', '.join(left)})")
        return 1
    print("stop: OK (containers left running)")
    return 0


def down() -> int:
    holder = heavy_holder()
    if holder and os.environ.get("FORCE") != "1":
        print(f"down: FAILED (a heavy target is running: {holder}; rerun with FORCE=1 to stop anyway)")
        return 1
    left = stop_processes()
    if infra_down("down", force=True) != 0:
        left.append("containers")
    if left:
        print(f"down: FAILED (still running: {', '.join(left)})")
        return 1
    print("down: OK (data volumes kept)")
    return 0


def status() -> int:
    problems = []
    out = subprocess.run(compose("ps", "--all", "--format", "{{.Service}}\t{{.State}}\t{{.Health}}\t{{.Ports}}"),
                         capture_output=True, text=True, env=compose_env())
    if out.returncode != 0:
        print(f"status: FAILED (docker compose ps: {out.stderr.strip()})")
        return 1
    rows = [line.split("\t") for line in out.stdout.splitlines() if line.strip()]
    for service, state, health, *ports in rows:
        ok = state == "running" and health in ("", "healthy")
        say("ok" if ok else "FAIL", service, f"{state} {health} {ports[0] if ports else ''}".strip())
        if not ok:
            problems.append(service)
    if not rows:
        say("--", "containers", "none (run make up)")
    comps = {c.name: c for c in components()}
    pids = {name: (pid, marker) for name, pid, marker in recorded()}
    for name in list(comps) + [n for n in pids if n not in comps]:
        c = comps.get(name)
        pid, marker = pids.get(name, (None, c.marker if c else ""))
        if alive(pid, marker):
            if c and not comp_healthy(c):
                say("FAIL", name, f"pid {pid} running but not healthy at {c.health_url}"
                    + (f" ({c.health_key})" if c.health_key else ""))
                problems.append(name)
            else:
                db = recorded_db(name)
                say("ok", name, f"pid {pid}" + (f", {c.where}" if c else "") + (f", database {db}" if db else ""))
        elif name in pids:
            say("FAIL", name, f"recorded pid {pid} is gone; see {os.path.join(LOG_DIR, name + '.log')}")
            problems.append(name)
        else:
            say("--", name, c.problem or "stopped")
    if problems:
        print(f"status: FAILED ({', '.join(problems)})")
        return 1
    print("status: OK")
    return 0


def main(argv) -> int:
    if VAR_DIR_ERROR:
        print(f"{argv[1] if len(argv) > 1 else 'stack'}: FAILED ({VAR_DIR_ERROR})")
        return 2
    if not os.path.exists(ENV_FILE):
        print(f"{argv[1] if len(argv) > 1 else 'stack'}: FAILED ({ENV_FILE} is missing; run make setup in the main checkout)")
        return 1
    cmd = argv[1] if len(argv) > 1 else ""
    if cmd == "infra-up":
        return infra_up("infra-up")
    if cmd == "ensure-infra" and len(argv) == 3:
        return infra_up(argv[2])
    if cmd == "sa-secret":
        return sync_sa_secret(verbose=True)
    if cmd == "rabbitmq-users":
        return rabbit_users()
    if cmd == "infra-down":
        return infra_down("infra-down")
    if cmd == "up":
        legacy = legacy_output_settings(os.environ, read_env(ENV_FILE))
        if legacy:
            one = len(legacy) == 1
            print(f"up: FAILED ({', '.join(legacy)} {'is' if one else 'are'} no longer read; remove "
                  f"{'it' if one else 'them'}: every output is under {VAR_DIR_KEY}/out/)")
            return 1
    if cmd in ("up", "stop", "down") and not guard_slice(cmd):
        return 1
    if cmd == "up":
        only = set()
        if len(argv) == 4 and argv[2] == "--only":
            only = {n for n in argv[3].split(",") if n}
        return up(only)
    if cmd == "down":
        return down()
    if cmd == "stop":
        return stop_only()
    if cmd == "status":
        return status()
    print(__doc__.strip().splitlines()[-1], file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv))
