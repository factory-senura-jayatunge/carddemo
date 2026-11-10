#!/usr/bin/env python3
"""Start, stop and inspect the local CardDemo stack.

The compose project (SQL Server, RabbitMQ, Airflow's Postgres) is shared by
every checkout on the machine. The application processes run on the host:
each converted service from its executable jar, Airflow's four processes
from the Airflow venv, and the Vite dev server.
A component that has not been converted yet (its directory does not exist)
is skipped. up prepares the others first: it builds the service jars that
are missing or older than their sources (Maven, tests skipped) and runs npm
ci when web/node_modules is missing or older than package-lock.json, except
for a component that is already running, whose files are left alone. A
component whose preparation fails does not start, so a missing piece never
looks like a pass.

PIDs go to $CARDDEMO_VAR_DIR/run/<name>.pid, logs to $CARDDEMO_VAR_DIR/log/.
Each service also records the database it was started against
(<name>.db, CARDDEMO_DB_NAME); up refuses to reuse a running service that
uses another database, so changing CARDDEMO_DB_NAME never leaves a service on
the old one.
Every command ends with one line, `<target>: OK` or `<target>: FAILED (...)`.

Usage: stack.py infra-up | infra-down | up [--only NAME,...] | stop | down | status
       (--only airflow means the four Airflow processes)
       stack.py ensure-infra TARGET   (infra-up for another target; prints only on failure)
"""

import fcntl
import glob
import os
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
from with_env import default_env_file, main_modernization, read_env  # noqa: E402

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
# The same defaults as the Makefile, so stack.py run on its own drives the
# shared compose project instead of planning to recreate it from this checkout.
INFRA_DIR = os.environ.get("CARDDEMO_INFRA_DIR") or main_modernization()
ENV_FILE = os.environ.get("CARDDEMO_ENV_FILE") or default_env_file()
VAR_DIR = os.environ.get("CARDDEMO_VAR_DIR") or os.path.join(main_modernization(), "var")
RUN_DIR = os.path.join(VAR_DIR, "run")
LOG_DIR = os.path.join(VAR_DIR, "log")
UP_TIMEOUT = int(os.environ.get("UP_TIMEOUT", "300"))
STOP_TIMEOUT = 30

# Ports and heap caps: docs/modernization/architecture/overview.md#deployables.
# batch-service runs one-shot jobs (make load) and is not started by up.
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
    `prepare` what up must do first (build or npm ci) and why."""

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


def web_problem(web: str):
    """Why npm ci must run first, or None when node_modules matches the lock file."""
    installed = os.path.join(web, "node_modules", ".package-lock.json")
    if not os.path.exists(installed):
        return "no node_modules"
    lock = os.path.join(web, "package-lock.json")
    if os.path.exists(lock) and os.path.getmtime(lock) > os.path.getmtime(installed):
        return "node_modules older than package-lock.json"
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
        if missing:
            problem = f"{', '.join(missing)} not set in {ENV_FILE}; run make setup"
    return [Component(name, port, airflow_dir, [airflow, *args], AIRFLOW_HEALTH, f"airflow {args[0]}",
                      problem, health_key=key)
            for name, args, port, key in AIRFLOW_PROCESSES]


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
        "AIRFLOW__CORE__SIMPLE_AUTH_MANAGER_USERS": f"{env.get('AIRFLOW_ADMIN_USER', 'admin')}:admin",
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


def prepare_airflow(env: dict):
    """Migrates the metadata database, writes the admin password file and
    imports the pools before the processes start; returns an error or None."""
    home = env["AIRFLOW_HOME"]
    os.makedirs(home, exist_ok=True)
    os.makedirs(LOG_DIR, exist_ok=True)
    passwords = env["AIRFLOW__CORE__SIMPLE_AUTH_MANAGER_PASSWORDS_FILE"]
    fd = os.open(passwords, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as f:
        json.dump({env.get("AIRFLOW_ADMIN_USER", "admin"): env.get("AIRFLOW_ADMIN_PASSWORD", "")}, f)
    os.chmod(passwords, 0o600)
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
    out = subprocess.run(["ps", "-o", "command=", "-p", str(pid)],
                         capture_output=True, text=True).stdout
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


def running_containers() -> set:
    out = subprocess.run(compose("ps", "--status", "running", "--format", "{{.Name}}"),
                         capture_output=True, text=True)
    return set(out.stdout.split())


def would_recreate() -> list:
    """Running containers that `compose up` would recreate (restart), found
    with a dry run, which changes nothing."""
    out = subprocess.run(compose("--dry-run", "up", "--detach"), capture_output=True, text=True)
    if out.returncode != 0:
        last = out.stderr.strip().splitlines()[-1:] or ["no output"]
        raise RuntimeError("compose dry run failed: " + last[0])
    planned = {parts[1] for parts in (line.split() for line in (out.stdout + out.stderr).splitlines())
               if len(parts) == 3 and parts[0] == "Container" and parts[2] == "Recreate"}
    return sorted(planned & running_containers())


def infra_up(target: str) -> int:
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
                         capture_output=quiet, text=True)
    rc = res.returncode
    if not quiet:
        subprocess.run(compose("ps", "--format", "table {{.Service}}\t{{.Status}}\t{{.Ports}}"))
    elif rc != 0:
        print(res.stderr.strip())
    if rc != 0:
        print(f"{target}: FAILED (containers not healthy within {UP_TIMEOUT} s; see make logs)")
        return 1
    if target == "infra-up":
        print(f"{target}: OK")
    return 0


def heavy_holder():
    path = os.path.join(RUN_DIR, "heavy.lock")
    if not os.path.exists(path):
        return None
    fd = os.open(path, os.O_RDONLY)
    try:
        fcntl.flock(fd, fcntl.LOCK_SH | fcntl.LOCK_NB)
        return None
    except BlockingIOError:
        return os.pread(fd, 512, 0).decode("utf-8", "replace").strip() or "unknown"
    finally:
        os.close(fd)


def infra_down(target: str, force: bool = False) -> int:
    holder = heavy_holder()
    if holder and not force and os.environ.get("FORCE") != "1":
        print(f"{target}: FAILED (a heavy target is running: {holder}; rerun with FORCE=1 to stop anyway)")
        return 1
    running = sorted(running_containers())
    if running:
        say("WARN", "compose", "stopping the shared containers for every checkout: " + ", ".join(running))
    rc = subprocess.run(compose("down", "--remove-orphans")).returncode
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


def prepare(comps, env) -> list:
    """Builds the missing or stale service jars and installs the web
    dependencies, for the components that are not running; returns the
    components after preparation."""
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
        say("..", "web", f"npm ci ({web.prepare})")
        if run_logged([web.argv[0], "ci"], web.cwd, env, "up-npm-ci.log", "npm ci"):
            say("ok", "web", "npm ci")
        else:
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
                         capture_output=True, text=True)
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
    if not os.path.exists(ENV_FILE):
        print(f"{argv[1] if len(argv) > 1 else 'stack'}: FAILED ({ENV_FILE} is missing; run make setup in the main checkout)")
        return 1
    cmd = argv[1] if len(argv) > 1 else ""
    if cmd == "infra-up":
        return infra_up("infra-up")
    if cmd == "ensure-infra" and len(argv) == 3:
        return infra_up(argv[2])
    if cmd == "infra-down":
        return infra_down("infra-down")
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
