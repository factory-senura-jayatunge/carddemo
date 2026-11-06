#!/usr/bin/env python3
"""make smoke: read-only check of the running Bill Pay slice.

Checks, against the services on 127.0.0.1: both health endpoints, sign-on as
USER0001 with CARDDEMO_SEED_USER_PASSWORD, GET /api/auth/me, GET
/api/features, a Bill Pay preview of account 00000000001, the JWKS, the web
app when it is converted, then signs off. It counts the rows of every table
in CARDDEMO_DB_NAME before and after: only the sign-on bookkeeping tables
(iam.session, iam.audit_event) may change. The whole run must take under 60
seconds. No secret, token or cookie value is printed.

Ends with "smoke: OK" or "smoke: FAILED (<reason>)".
Usage: smoke.py   (run through with_env.py, as make smoke does)
"""

import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request

IDENTITY = "http://127.0.0.1:28081"
BILLPAY = "http://127.0.0.1:28082"
WEB = "http://127.0.0.1:25173"
HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LIMIT_S = 60
ACCOUNT = "00000000001"
SEED_USER = "USER0001"
WRITABLE = {"iam.session", "iam.audit_event"}

COUNT_SQL = (
    "SET NOCOUNT ON; DECLARE @q nvarchar(max) = (SELECT STRING_AGG(CAST("
    "'SELECT ''' + s.name + '.' + t.name + ''' + ''|'' + CAST(COUNT_BIG(*) AS varchar(20)) FROM '"
    " + QUOTENAME(s.name) + '.' + QUOTENAME(t.name) AS nvarchar(max)), ' UNION ALL ')"
    " FROM sys.tables t JOIN sys.schemas s ON s.schema_id = t.schema_id WHERE t.is_ms_shipped = 0);"
    " IF @q IS NOT NULL EXEC (@q);"
)


class Client:
    """Minimal cookie handling: the services set host-only cookies on
    127.0.0.1, which http.cookiejar treats inconsistently."""

    def __init__(self):
        self.cookies = {}

    def call(self, method, url, body=None, csrf=False):
        headers = {"Accept": "application/json"}
        data = None
        if body is not None:
            data = json.dumps(body).encode()
            headers["Content-Type"] = "application/json"
        if self.cookies:
            headers["Cookie"] = "; ".join(f"{k}={v}" for k, v in self.cookies.items())
        if csrf and "XSRF-TOKEN" in self.cookies:
            headers["X-XSRF-TOKEN"] = self.cookies["XSRF-TOKEN"]
        req = urllib.request.Request(url, data=data, method=method, headers=headers)
        try:
            with urllib.request.urlopen(req, timeout=10) as r:
                status, raw, hdrs = r.status, r.read(), r.headers
        except urllib.error.HTTPError as e:
            status, raw, hdrs = e.code, e.read(), e.headers
        for cookie in hdrs.get_all("Set-Cookie") or []:
            name, _, rest = cookie.partition("=")
            value = rest.split(";", 1)[0]
            if value and "max-age=0" not in cookie.lower():
                self.cookies[name.strip()] = value
            else:
                self.cookies.pop(name.strip(), None)
        try:
            payload = json.loads(raw) if raw else None
        except ValueError:
            payload = None
        return status, payload


def row_counts(db):
    cmd = ["docker", "compose", "--project-directory", os.environ.get("CARDDEMO_INFRA_DIR", HERE),
           "-f", os.path.join(os.environ.get("CARDDEMO_INFRA_DIR", HERE), "docker-compose.yml"),
           "--env-file", os.environ["CARDDEMO_ENV_FILE"],
           "exec", "-T", "mssql", "sh", "-c",
           'SQLCMDPASSWORD="$MSSQL_SA_PASSWORD" /opt/mssql-tools18/bin/sqlcmd -S localhost -U sa -C -b '
           f'-h -1 -W -d "{db}" -Q "$1"', "sh", COUNT_SQL]
    out = subprocess.run(cmd, capture_output=True, text=True)
    if out.returncode != 0:
        raise RuntimeError(f"cannot count rows in {db}: {out.stdout.strip() or out.stderr.strip()}"[:300])
    counts = {}
    for line in out.stdout.splitlines():
        name, sep, n = line.strip().rpartition("|")
        if sep:
            counts[name] = int(n)
    return counts


def main() -> int:
    start = time.monotonic()
    db = os.environ.get("CARDDEMO_DB_NAME") or "carddemo"
    password = os.environ.get("CARDDEMO_SEED_USER_PASSWORD")
    failures = []

    def check(name, ok, detail):
        print(f"{'ok' if ok else 'FAIL':5} {name:16} {detail}", flush=True)
        if not ok:
            failures.append(name)
        return ok

    if not password:
        print("smoke: FAILED (CARDDEMO_SEED_USER_PASSWORD is not set; run through with_env.py)")
        return 1
    try:
        before = row_counts(db)
    except RuntimeError as e:
        print(f"smoke: FAILED ({e})")
        return 1
    check("database", bool(before), f"{db}: {len(before)} tables")

    c = Client()
    for name, base in (("identity health", IDENTITY), ("billpay health", BILLPAY)):
        status, body = c.call("GET", base + "/actuator/health")
        check(name, status == 200 and (body or {}).get("status") == "UP", f"HTTP {status} {(body or {}).get('status', '')}")

    status, body = c.call("POST", IDENTITY + "/api/auth/sign-on", {"userId": SEED_USER, "password": password})
    signed_on = check("sign-on", status == 200 and (body or {}).get("userId") == SEED_USER and "cd_at" in c.cookies,
                      f"HTTP {status} {SEED_USER} role {(body or {}).get('role')} "
                      f"cookies {sorted(c.cookies)}" if status == 200 else f"HTTP {status} {(body or {}).get('code', '')}")
    if signed_on:
        status, body = c.call("GET", IDENTITY + "/api/auth/me")
        check("me", status == 200 and (body or {}).get("userId") == SEED_USER, f"HTTP {status}")
        status, body = c.call("GET", IDENTITY + "/api/features")
        check("features", status == 200 and isinstance((body or {}).get("modules"), dict),
              f"HTTP {status} converted {(body or {}).get('converted')}")
        status, body = c.call("GET", f"{BILLPAY}/api/billpay/accounts/{ACCOUNT}")
        hint = " (no sample data: run make load)" if status == 404 else ""
        check("preview", status == 200 and (body or {}).get("acctId") == ACCOUNT,
              f"HTTP {status} {ACCOUNT} payable {(body or {}).get('payable')} {(body or {}).get('code', '')}{hint}")
    status, body = c.call("GET", IDENTITY + "/api/auth/.well-known/jwks.json")
    keys = (body or {}).get("keys") or []
    check("jwks", status == 200 and bool(keys) and all(k.get("kty") == "RSA" for k in keys),
          f"HTTP {status} {len(keys)} key(s)")
    if os.path.exists(os.path.join(HERE, "web", "package.json")):
        status, _ = c.call("GET", WEB + "/")
        check("web", status == 200, f"HTTP {status} {WEB}/")
    if signed_on:
        status, body = c.call("POST", IDENTITY + "/api/auth/sign-off", csrf=True)
        check("sign-off", status == 200, f"HTTP {status}")

    after = row_counts(db)
    changed = sorted(t for t in set(before) | set(after)
                     if before.get(t) != after.get(t) and t not in WRITABLE)
    check("row counts", not changed,
          "unchanged outside " + ", ".join(sorted(WRITABLE)) if not changed
          else "changed: " + ", ".join(f"{t} {before.get(t)}->{after.get(t)}" for t in changed))
    elapsed = time.monotonic() - start
    check("duration", elapsed < LIMIT_S, f"{elapsed:.1f} s (limit {LIMIT_S} s)")
    if failures:
        print(f"smoke: FAILED ({', '.join(failures)})")
        return 1
    print("smoke: OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
