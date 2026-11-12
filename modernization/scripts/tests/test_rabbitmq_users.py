"""Tests of the RabbitMQ provisioning: python3 -m unittest discover -s modernization/scripts/tests

A fake management API (http.server on a free loopback port) stands in for
the broker, so the shared RabbitMQ is never asked. stack.py runs as its own
process against a scratch .env, compose file and var directory, with a fake
docker first on PATH."""
from __future__ import annotations

import base64
import http.server
import json
import os
import re
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import unittest
import urllib.parse

SCRIPTS = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, SCRIPTS)
import init_env  # noqa: E402
import rabbitmq_users  # noqa: E402

ADMIN, ADMIN_PW = "carddemo", "Fake0admin0for0tests"
SVC_PW = "Fake0svc0for0tests0only"
OTHER_PW = "Fake0other0broker0value"
WANTED = rabbitmq_users.USERS[0]["permissions"]


class FakeBroker:
    def __init__(self):
        self.vhosts, self.users, self.perms = {"/"}, {ADMIN: ADMIN_PW}, {}
        self.calls = []
        self.overview = [200]
        broker = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self, *a):
                pass

            def answer(self, status, body=None):
                data = b"" if body is None else json.dumps(body).encode()
                self.send_response(status)
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

            def handle_any(self, method):
                parts = [urllib.parse.unquote(p) for p in self.path.split("/")[2:]]
                length = int(self.headers.get("Content-Length") or 0)
                body = json.loads(self.rfile.read(length)) if length else None
                broker.calls.append((method, tuple(parts), body))
                auth = self.headers.get("Authorization", "")
                if auth != "Basic " + base64.b64encode(f"{ADMIN}:{broker.users.get(ADMIN)}".encode()).decode():
                    return self.answer(401, {"error": "not_authorised"})
                if parts == ["overview"]:
                    status = broker.overview.pop(0) if len(broker.overview) > 1 else broker.overview[0]
                    return self.answer(status, {} if status == 200 else None)
                kind, rest = parts[0], tuple(parts[1:])
                if kind == "vhosts" and len(rest) == 1:
                    if method == "GET":
                        return self.answer(200, {"name": rest[0]}) if rest[0] in broker.vhosts else self.answer(404)
                    existed = rest[0] in broker.vhosts
                    broker.vhosts.add(rest[0])
                    if not existed:
                        # As RabbitMQ does: the creating user gets full permissions on it.
                        broker.perms[(rest[0], ADMIN)] = {"configure": ".*", "write": ".*", "read": ".*"}
                    return self.answer(204 if existed else 201)
                if kind == "users" and len(rest) == 1:
                    if method == "GET":
                        return self.answer(200, {"name": rest[0], "tags": []}) if rest[0] in broker.users \
                            else self.answer(404)
                    existed = rest[0] in broker.users
                    broker.users[rest[0]] = body["password"]
                    return self.answer(204 if existed else 201)
                if kind == "permissions" and len(rest) == 2:
                    if rest[0] not in broker.vhosts or rest[1] not in broker.users:
                        return self.answer(404) if method == "GET" else self.answer(400)
                    if method == "GET":
                        p = broker.perms.get(rest)
                        return self.answer(200, {"user": rest[1], "vhost": rest[0], **p}) if p else self.answer(404)
                    existed = rest in broker.perms
                    broker.perms[rest] = body
                    return self.answer(204 if existed else 201)
                return self.answer(405)

            def do_GET(self):
                self.handle_any("GET")

            def do_PUT(self):
                self.handle_any("PUT")

        self.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.port = self.server.server_address[1]
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def close(self):
        self.server.shutdown()
        self.server.server_close()

    def puts(self):
        return [c for c in self.calls if c[0] == "PUT"]


class ProvisionTest(unittest.TestCase):
    def setUp(self):
        self.broker = FakeBroker()
        self.addCleanup(self.broker.close)
        self.lines = []

    def say(self, status, name, detail):
        self.lines.append(f"{status} {name} {detail}")

    def env(self, **over):
        e = {"RABBITMQ_DEFAULT_USER": ADMIN, "RABBITMQ_DEFAULT_PASS": ADMIN_PW,
             "RABBITMQ_MGMT_HOST_PORT": str(self.broker.port),
             "CARDDEMO_RABBITMQ_AUTHORIZATION_PASSWORD": SVC_PW}
        e.update(over)
        return {k: v for k, v in e.items() if v is not None}

    def run_it(self, required=False, wait=5, **over):
        self.lines = []
        rc = rabbitmq_users.provision(self.env(**over), self.say, required=required, wait=wait)
        out = "\n".join(self.lines)
        for secret in (ADMIN_PW, SVC_PW, OTHER_PW):
            self.assertNotIn(secret, out)
        return rc, out

    def test_a_fresh_broker_gets_the_vhost_the_user_and_its_permissions(self):
        rc, out = self.run_it()
        self.assertEqual(rc, 0, out)
        self.assertIn("carddemo", self.broker.vhosts)
        self.assertEqual(self.broker.users["authorization_svc"], SVC_PW)
        self.assertEqual(self.broker.perms[("carddemo", "authorization_svc")], WANTED)
        self.assertEqual(set(self.broker.perms), {("carddemo", ADMIN), ("carddemo", "authorization_svc")})
        self.assertIn("virtual host carddemo: created", out)
        self.assertIn("created with CARDDEMO_RABBITMQ_AUTHORIZATION_PASSWORD from .env", out)
        self.assertIn("permissions of authorization_svc on carddemo: set", out)
        user_put = [c for c in self.broker.puts() if c[1][0] == "users"]
        self.assertEqual(user_put[0][2], {"password": SVC_PW, "tags": ""})

    def test_a_second_run_changes_nothing(self):
        self.assertEqual(self.run_it()[0], 0)
        before = len(self.broker.puts())
        rc, out = self.run_it()
        self.assertEqual(rc, 0, out)
        self.assertEqual(len(self.broker.puts()), before)
        self.assertIn("virtual host carddemo: kept", out)
        self.assertIn("kept (exists; its password is not changed)", out)
        self.assertIn("on carddemo: kept", out)

    def test_an_existing_user_keeps_the_brokers_password(self):
        self.broker.vhosts.add("carddemo")
        self.broker.users["authorization_svc"] = OTHER_PW
        rc, out = self.run_it()
        self.assertEqual(rc, 0, out)
        self.assertEqual(self.broker.users["authorization_svc"], OTHER_PW)
        self.assertFalse([c for c in self.broker.puts() if c[1][0] in ("users", "vhosts")])

    def test_other_permissions_are_brought_to_the_narrow_ones(self):
        self.broker.vhosts.add("carddemo")
        self.broker.users["authorization_svc"] = OTHER_PW
        self.broker.perms[("carddemo", "authorization_svc")] = {"configure": ".*", "write": ".*", "read": ".*"}
        rc, out = self.run_it()
        self.assertEqual(rc, 0, out)
        self.assertEqual(self.broker.perms[("carddemo", "authorization_svc")], WANTED)
        self.assertIn("on carddemo: updated", out)

    def test_a_refused_admin_login_fails_and_writes_nothing(self):
        rc, out = self.run_it(RABBITMQ_DEFAULT_PASS="Wrong0admin0value")
        self.assertEqual(rc, 1)
        self.assertIn("admin login RABBITMQ_DEFAULT_USER was refused (401)", out)
        self.assertEqual(self.broker.puts(), [])

    def test_a_missing_password_skips_the_user_or_fails_when_required(self):
        rc, out = self.run_it(CARDDEMO_RABBITMQ_AUTHORIZATION_PASSWORD="")
        self.assertEqual(rc, 0, out)
        self.assertIn("warn rabbitmq user authorization_svc skipped", out)
        self.assertIn("carddemo", self.broker.vhosts)
        self.assertNotIn("authorization_svc", self.broker.users)
        rc, out = self.run_it(required=True, CARDDEMO_RABBITMQ_AUTHORIZATION_PASSWORD=None)
        self.assertEqual(rc, 1)
        self.assertIn("CARDDEMO_RABBITMQ_AUTHORIZATION_PASSWORD is not set in .env", out)
        self.assertNotIn("authorization_svc", self.broker.users)

    def test_the_service_user_must_not_be_the_admin(self):
        rc, out = self.run_it(CARDDEMO_RABBITMQ_AUTHORIZATION_USER=ADMIN)
        self.assertEqual(rc, 1)
        self.assertIn("must differ from RABBITMQ_DEFAULT_USER", out)
        self.assertEqual(self.broker.calls, [])

    def test_names_from_env_are_used_and_quoted(self):
        rc, out = self.run_it(CARDDEMO_RABBITMQ_VHOST="cd/test", CARDDEMO_RABBITMQ_AUTHORIZATION_USER="authz svc")
        self.assertEqual(rc, 0, out)
        self.assertIn("cd/test", self.broker.vhosts)
        self.assertIn(("cd/test", "authz svc"), self.broker.perms)

    def test_a_management_api_that_is_starting_is_waited_for(self):
        self.broker.overview = [503, 503, 200]
        rc, out = self.run_it()
        self.assertEqual(rc, 0, out)

    def test_an_unreachable_api_fails_without_the_login(self):
        with socket.socket() as s:
            s.bind(("127.0.0.1", 0))
            port = s.getsockname()[1]
        rc, out = self.run_it(wait=0, RABBITMQ_MGMT_HOST_PORT=str(port))
        self.assertEqual(rc, 1)
        self.assertIn(f"management API on 127.0.0.1:{port} unreachable", out)


class PermissionsTest(unittest.TestCase):
    """The patterns grant the service's own names and nothing else."""

    def allowed(self, kind):
        # The broker matches like re.search: the anchors make it a full match.
        return {n for n in NAMES if re.search(WANTED[kind], n)}

    def test_each_permission_covers_exactly_what_the_service_does(self):
        own = {"carddemo.pauth", "carddemo.pauth.request", "carddemo.pauth.reply", "carddemo.pauth.request.dlq",
               "carddemo.dlx"}
        self.assertEqual(self.allowed("configure"), own)
        self.assertEqual(self.allowed("write"), own | {"amq.default"})
        self.assertEqual(self.allowed("read"), own - {"carddemo.pauth.request.dlq"})

    def test_patterns_are_anchored(self):
        for kind in ("configure", "write", "read"):
            for name in ("xcarddemo.pauth", "carddemo.pauthx", "carddemo.pauth.request.dlq.x", "carddemo-pauth",
                         "carddemo.pauth\nx", ""):
                self.assertIsNone(re.search(WANTED[kind], name), (kind, name))


NAMES = ["carddemo.pauth", "carddemo.pauth.request", "carddemo.pauth.reply", "carddemo.pauth.request.dlq",
         "carddemo.dlx", "amq.default", "amq.direct", "amq.topic", "carddemo.billpay", "carddemo.inquiry.request"]

FAKE_DOCKER = f"""#!{sys.executable}
import sys
args = sys.argv[1:]
if args[:1] == ["compose"]:
    sys.exit(0)
sys.exit(2)
"""


class StackRabbitmqTest(unittest.TestCase):
    def setUp(self):
        self.broker = FakeBroker()
        self.addCleanup(self.broker.close)
        self.dir = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.dir, True)
        self.bin = os.path.join(self.dir, "bin")
        os.makedirs(self.bin)
        with open(os.path.join(self.bin, "docker"), "w", encoding="utf-8") as f:
            f.write(FAKE_DOCKER)
        os.chmod(os.path.join(self.bin, "docker"), 0o755)
        self.infra = os.path.join(self.dir, "infra")
        os.makedirs(self.infra)
        with open(os.path.join(self.infra, "docker-compose.yml"), "w", encoding="utf-8") as f:
            f.write("services:\n  rabbitmq:\n    image: rabbitmq\n")
        self.env = os.path.join(self.dir, ".env")
        self.write_env(SVC_PW)

    def write_env(self, password):
        with open(self.env, "w", encoding="utf-8") as f:
            f.write(f"MSSQL_SA_PASSWORD=Fake0sa0value\nRABBITMQ_DEFAULT_USER={ADMIN}\nRABBITMQ_DEFAULT_PASS={ADMIN_PW}\n"
                    f"RABBITMQ_MGMT_HOST_PORT={self.broker.port}\n"
                    f"CARDDEMO_RABBITMQ_AUTHORIZATION_PASSWORD={password}\n"
                    # The stack's own folder, so sa-secret writes into it.
                    f"CARDDEMO_VAR_DIR={os.path.join(self.dir, 'var')}\n")

    def stack(self, *args, **env):
        e = {k: v for k, v in os.environ.items()
             if not k.startswith(("CARDDEMO_", "RABBITMQ_")) and k != "RECREATE"}
        # A proxy must not see the admin login.
        e.update(CARDDEMO_ENV_FILE=self.env, CARDDEMO_INFRA_DIR=self.infra,
                 CARDDEMO_VAR_DIR=os.path.join(self.dir, "var"), HTTP_PROXY="http://127.0.0.1:9",
                 http_proxy="http://127.0.0.1:9", PATH=self.bin + os.pathsep + os.environ["PATH"], **env)
        r = subprocess.run([sys.executable, os.path.join(SCRIPTS, "stack.py"), *args], capture_output=True,
                           text=True, env=e, timeout=60)
        self.out = r.stdout + r.stderr
        for secret in (ADMIN_PW, SVC_PW):
            self.assertNotIn(secret, self.out)
        return r.returncode

    def test_rabbitmq_users_creates_them(self):
        self.assertEqual(self.stack("rabbitmq-users"), 0, self.out)
        self.assertTrue(self.out.rstrip().endswith("rabbitmq-users: OK"), self.out)
        self.assertEqual(self.broker.users["authorization_svc"], SVC_PW)
        self.assertEqual(self.broker.perms[("carddemo", "authorization_svc")], WANTED)

    def test_rabbitmq_users_fails_without_the_password(self):
        self.write_env("")
        self.assertEqual(self.stack("rabbitmq-users"), 1, self.out)
        self.assertIn("rabbitmq-users: FAILED", self.out)
        self.assertEqual(self.broker.puts(), [])

    def test_infra_up_provisions_and_ensure_infra_does_not(self):
        self.assertEqual(self.stack("ensure-infra", "db-reset"), 0, self.out)
        self.assertEqual(self.broker.calls, [])
        self.assertEqual(self.stack("infra-up"), 0, self.out)
        self.assertIn("infra-up: OK", self.out)
        self.assertIn("virtual host carddemo: created", self.out)
        self.assertIn(("carddemo", "authorization_svc"), self.broker.perms)

    def test_infra_up_fails_when_the_broker_refuses_the_admin(self):
        self.broker.users[ADMIN] = "Fake0changed0on0broker"
        self.assertEqual(self.stack("infra-up"), 1, self.out)
        self.assertIn("infra-up: FAILED (RabbitMQ virtual host or users; see above)", self.out)

    def test_the_environment_wins_over_env(self):
        self.assertEqual(self.stack("rabbitmq-users", CARDDEMO_RABBITMQ_AUTHORIZATION_USER="authz_env"), 0, self.out)
        self.assertIn("authz_env", self.broker.users)
        self.assertNotIn("authorization_svc", self.broker.users)


class EnvExampleTest(unittest.TestCase):
    """setup adds the keys to an existing .env and generates the password only when it is absent."""

    def test_setup_adds_the_keys_and_keeps_a_value(self):
        d = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, d, True)
        example = os.path.join(os.path.dirname(SCRIPTS), ".env.example")
        env = os.path.join(d, ".env")
        with open(env, "w", encoding="utf-8") as f:
            f.write("RABBITMQ_DEFAULT_PASS=Fake0kept0admin\n")
        with open(os.devnull, "w") as null:
            sys_stdout, sys.stdout = sys.stdout, null
            try:
                self.assertEqual(init_env.main(["init_env.py", example, env]), 0)
                with open(env, encoding="utf-8") as f:
                    first = dict(line.split("=", 1) for line in f.read().splitlines() if "=" in line)
                self.assertEqual(init_env.main(["init_env.py", example, env]), 0)
            finally:
                sys.stdout = sys_stdout
        with open(env, encoding="utf-8") as f:
            second = dict(line.split("=", 1) for line in f.read().splitlines() if "=" in line)
        self.assertEqual(first["RABBITMQ_DEFAULT_PASS"], "Fake0kept0admin")
        self.assertEqual(first["CARDDEMO_RABBITMQ_VHOST"], "carddemo")
        self.assertEqual(first["CARDDEMO_RABBITMQ_AUTHORIZATION_USER"], "authorization_svc")
        self.assertRegex(first["CARDDEMO_RABBITMQ_AUTHORIZATION_PASSWORD"], r"^[A-Za-z0-9]{32}$")
        self.assertEqual(second["CARDDEMO_RABBITMQ_AUTHORIZATION_PASSWORD"],
                         first["CARDDEMO_RABBITMQ_AUTHORIZATION_PASSWORD"])


if __name__ == "__main__":
    unittest.main()
