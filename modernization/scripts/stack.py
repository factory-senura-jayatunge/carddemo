#!/usr/bin/env python3
"""Start, stop and inspect the local CardDemo stack.

The compose project (SQL Server, RabbitMQ, Airflow's Postgres) is shared by
every checkout on the machine. The application processes run on the host:
each converted service from its executable jar, and the Vite dev server.
A component that has not been converted yet (its directory does not exist)
is skipped; one that exists but is not built fails, so a missing piece never
looks like a pass.

PIDs go to $CARDDEMO_VAR_DIR/run/<name>.pid, logs to $CARDDEMO_VAR_DIR/log/.
Every command ends with one line, `<target>: OK` or `<target>: FAILED (...)`.

Usage: stack.py infra-up | infra-down | up [--only NAME,...] | down | status
       stack.py ensure-infra TARGET   (infra-up for another target; prints only on failure)
"""

import fcntl
import glob
import os
import signal
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from with_env import default_env_file, read_env  # noqa: E402

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
INFRA_DIR = os.environ.get("CARDDEMO_INFRA_DIR") or HERE
ENV_FILE = os.environ.get("CARDDEMO_ENV_FILE") or default_env_file()
VAR_DIR = os.environ.get("CARDDEMO_VAR_DIR") or os.path.join(HERE, "var")
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


def service_args(name: str) -> list:
    # identity-service reads its signing key from CARDDEMO_JWT_KEY_FILE,
    # which the Makefile exports.
    return [f"--spring.profiles.active={SPRING_PROFILE}"]
WEB_PORT = 25173
HEALTH_PATH = "/actuator/health"


def say(status: str, name: str, detail: str) -> None:
    print(f"{status:5} {name:21} {detail}", flush=True)


class Component:
    """One host process; `problem` says why a converted one can't start."""

    def __init__(self, name, port, cwd, argv, health_url, marker, problem=None):
        self.name, self.port, self.cwd, self.argv = name, port, cwd, argv
        self.health_url, self.marker, self.problem = health_url, marker, problem

    @property
    def pid_file(self):
        return os.path.join(RUN_DIR, f"{self.name}.pid")

    @property
    def log_file(self):
        return os.path.join(LOG_DIR, f"{self.name}.log")


def java_bin() -> str:
    home = os.environ.get("JAVA_HOME")
    return os.path.join(home, "bin", "java") if home else "java"


def find_jar(module_dir: str, artifact: str):
    jars = sorted(glob.glob(os.path.join(module_dir, "target", f"{artifact}-*-exec.jar")))
    return jars[-1] if jars else None


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
                               f"http://127.0.0.1:{port}{HEALTH_PATH}", jar or name, problem))
    web = os.path.join(HERE, "web")
    if os.path.exists(os.path.join(web, "package.json")):
        problem = None
        if not os.path.isdir(os.path.join(web, "node_modules")):
            problem = "dependencies missing: run npm ci in modernization/web"
        # vite.config.ts pins 127.0.0.1:25173 with strictPort.
        found.append(Component("web", WEB_PORT, web, ["npm", "run", "dev"],
                               f"http://127.0.0.1:{WEB_PORT}/", "vite", problem))
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


def healthy(url) -> bool:
    try:
        with urllib.request.urlopen(url, timeout=3) as r:
            return 200 <= r.status < 300
    except (urllib.error.URLError, OSError, ValueError):
        return False


def port_busy(port) -> bool:
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
    return "started"


def stop(comp_name, pid, marker) -> bool:
    pid_file = os.path.join(RUN_DIR, f"{comp_name}.pid")
    if not alive(pid, marker):
        if os.path.exists(pid_file):
            os.remove(pid_file)
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
    return True


def recorded() -> list:
    """(name, pid, marker) for every pid file, also of components that don't
    exist in this checkout."""
    names = (os.path.basename(p)[:-4] for p in sorted(glob.glob(os.path.join(RUN_DIR, "*.pid"))))
    return [(name, *read_pid_marker(name)) for name in names]


def up(only) -> int:
    if infra_up("up") != 0:
        return 1
    comps = components()
    if only:
        unknown = only - {c.name for c in comps}
        if unknown:
            print(f"up: FAILED (not in this checkout: {', '.join(sorted(unknown))})")
            return 1
        comps = [c for c in comps if c.name in only]
    if not comps:
        print("up: OK (infrastructure only; no application component is converted yet)")
        return 0
    failures = []
    for c in comps:
        if c.problem:
            say("FAIL", c.name, c.problem)
            failures.append(c.name)
    env = child_env()
    waiting = []
    for c in comps:
        if c.problem:
            continue
        result = start(c, env)
        if result in ("started", "running") or result.startswith("running from"):
            say("..", c.name, f"{result}, port {c.port}, log {c.log_file}")
            waiting.append(c)
        else:
            say("FAIL", c.name, result)
            failures.append(c.name)
    deadline = time.time() + UP_TIMEOUT
    while waiting and time.time() < deadline:
        for c in list(waiting):
            if healthy(c.health_url):
                say("ok", c.name, f"healthy at {c.health_url}")
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


def down() -> int:
    holder = heavy_holder()
    if holder and os.environ.get("FORCE") != "1":
        print(f"down: FAILED (a heavy target is running: {holder}; rerun with FORCE=1 to stop anyway)")
        return 1
    left = []
    for name, pid, marker in reversed(recorded()):
        if stop(name, pid, marker):
            say("ok", name, "stopped")
        else:
            say("FAIL", name, f"pid {pid} did not stop")
            left.append(name)
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
            if c and not healthy(c.health_url):
                say("FAIL", name, f"pid {pid} running but not healthy at {c.health_url}")
                problems.append(name)
            else:
                say("ok", name, f"pid {pid}" + (f", port {c.port}" if c else ""))
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
    if cmd == "status":
        return status()
    print(__doc__.strip().splitlines()[-1], file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv))
