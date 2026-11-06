#!/usr/bin/env python3
"""make billpay: one scripted Bill Pay payment against the running slice.

Signs on as USER0001 with CARDDEMO_SEED_USER_PASSWORD, previews account
00000000001 (or, when it has nothing to pay, the first account with a
positive balance and a card), pays it with confirm Y and a new
Idempotency-Key, and checks the success message, the stored payment
transaction (amount equal to the balance, either sign: DEF-BILL-010) and
curr_bal 0.00 afterwards, through the API and in CARDDEMO_DB_NAME. Then
signs off. No secret, token, cookie or card number is printed.

--has-data exits 0 when core.account has rows, 1 when it is empty or the
database does not exist yet, 2 when the database cannot be queried. The
make recipe uses it to decide whether to run db-reset and load.

Ends with "billpay: OK" or "billpay: FAILED (<reason>)".
Usage: billpay_e2e.py [--has-data]   (run through with_env.py)
"""

import os
import subprocess
import sys
import urllib.request
import uuid
from decimal import Decimal

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import smoke  # noqa: E402
from smoke import BILLPAY, IDENTITY, SEED_USER, WEB  # noqa: E402
from with_env import main_modernization  # noqa: E402

ACCOUNT = "00000000001"
SUCCESS = "Payment successful.  Your Transaction ID is {}."


class Client(smoke.Client):
    """smoke.Client with extra request headers (Idempotency-Key)."""

    def call(self, method, url, body=None, csrf=False, headers=None):
        if not headers:
            return super().call(method, url, body, csrf)
        original = urllib.request.Request

        def with_headers(*args, **kwargs):
            req = original(*args, **kwargs)
            for name, value in headers.items():
                req.add_header(name, value)
            return req

        urllib.request.Request = with_headers
        try:
            return super().call(method, url, body, csrf)
        finally:
            urllib.request.Request = original


def sql(db, query):
    """Rows of a sqlcmd query in db as lists of strings ("|"-separated)."""
    infra = os.environ.get("CARDDEMO_INFRA_DIR") or main_modernization()
    cmd = ["docker", "compose", "--project-directory", infra,
           "-f", os.path.join(infra, "docker-compose.yml"),
           "--env-file", os.environ["CARDDEMO_ENV_FILE"],
           "exec", "-T", "mssql", "sh", "-c",
           'SQLCMDPASSWORD="$MSSQL_SA_PASSWORD" /opt/mssql-tools18/bin/sqlcmd -S localhost -U sa -C -b '
           f'-h -1 -W -s "|" -d "{db}" -Q "$1"', "sh", "SET NOCOUNT ON; " + query]
    out = subprocess.run(cmd, capture_output=True, text=True)
    if out.returncode != 0:
        raise RuntimeError((out.stdout.strip() or out.stderr.strip())[:300])
    return [line.strip().split("|") for line in out.stdout.splitlines() if line.strip()]


def has_data(db) -> int:
    try:
        exists = sql("master", f"SELECT COUNT(*) FROM sys.databases WHERE name = N'{db}'")
        if exists[0][0] == "0":
            print(f"billpay: database {db} does not exist yet")
            return 1
        rows = sql(db, "IF OBJECT_ID(N'core.account') IS NULL SELECT -1 ELSE SELECT COUNT_BIG(*) FROM core.account")
    except (RuntimeError, IndexError) as e:
        print(f"billpay: FAILED (cannot query {db}: {e})")
        return 2
    n = int(rows[0][0])
    print(f"billpay: {db} core.account has {max(n, 0)} row(s)")
    return 0 if n > 0 else 1


def pick_account(c, db):
    """(acctId, preview) for 00000000001 when payable, else the first payable account."""
    status, body = c.call("GET", f"{BILLPAY}/api/billpay/accounts/{ACCOUNT}")
    if status == 200 and (body or {}).get("payable"):
        return ACCOUNT, body
    rows = sql(db, "SELECT TOP 1 a.acct_id FROM core.account a WHERE a.curr_bal > 0 AND EXISTS "
                   "(SELECT 1 FROM core.card_xref x WHERE x.acct_id = a.acct_id) ORDER BY a.acct_id")
    if not rows:
        return None, None
    acct = rows[0][0]
    status, body = c.call("GET", f"{BILLPAY}/api/billpay/accounts/{acct}")
    return (acct, body) if status == 200 and (body or {}).get("payable") else (None, None)


def main(argv) -> int:
    db = os.environ.get("CARDDEMO_DB_NAME") or "carddemo"
    if argv[1:] == ["--has-data"]:
        return has_data(db)
    if argv[1:]:
        print(__doc__.strip().splitlines()[-1], file=sys.stderr)
        return 2
    password = os.environ.get("CARDDEMO_SEED_USER_PASSWORD")
    if not password:
        print("billpay: FAILED (CARDDEMO_SEED_USER_PASSWORD is not set; run through with_env.py)")
        return 1
    failures = []

    def check(name, ok, detail):
        print(f"{'ok' if ok else 'FAIL':5} {name:16} {detail}", flush=True)
        if not ok:
            failures.append(name)
        return ok

    c = Client()
    status, body = c.call("POST", IDENTITY + "/api/auth/sign-on", {"userId": SEED_USER, "password": password})
    if not check("sign-on", status == 200 and "cd_at" in c.cookies, f"HTTP {status} {SEED_USER}"):
        print("billpay: FAILED (sign-on)")
        return 1
    try:
        acct, preview = pick_account(c, db)
        if not check("preview", acct is not None,
                     f"{acct} balance {preview['currentBalanceDisplay']}" if acct
                     else "no account with a positive balance (run make db-reset load)"):
            print("billpay: FAILED (nothing to pay)")
            return 1
        balance = Decimal(preview["currentBalance"])
        status, body = c.call("POST", f"{BILLPAY}/api/billpay/accounts/{acct}/payments", {"confirm": "Y"},
                              csrf=True, headers={"Idempotency-Key": str(uuid.uuid4())})
        body = body or {}
        tran_id = body.get("tranId", "")
        check("payment", status == 201 and body.get("message") == SUCCESS.format(tran_id) and len(tran_id) == 16,
              f"HTTP {status} {body.get('message') or body.get('code', '')}")
        if tran_id:
            rows = sql(db, f"SELECT t.amount, t.type_cd, t.cat_cd FROM core.[transaction] t "
                           f"JOIN core.card_xref x ON x.card_num = t.card_num "
                           f"WHERE t.tran_id = '{tran_id}' AND x.acct_id = '{acct}'")
            check("transaction", len(rows) == 1 and abs(Decimal(rows[0][0])) == balance
                  and rows[0][1:] == ["02", "0002"],
                  f"{tran_id} amount {rows[0][0]} type {rows[0][1]}/{rows[0][2]}" if rows else f"{tran_id} not stored")
        rows = sql(db, f"SELECT curr_bal FROM core.account WHERE acct_id = '{acct}'")
        check("balance", bool(rows) and Decimal(rows[0][0]) == 0, f"curr_bal {rows[0][0] if rows else '?'}")
        status, body = c.call("GET", f"{BILLPAY}/api/billpay/accounts/{acct}")
        check("re-preview", status == 200 and (body or {}).get("payable") is False,
              f"HTTP {status} {(body or {}).get('currentBalanceDisplay', '')}")
    except RuntimeError as e:
        check("database", False, str(e))
    finally:
        status, _ = c.call("POST", IDENTITY + "/api/auth/sign-off", csrf=True)
        check("sign-off", status == 200, f"HTTP {status}")
    if failures:
        print(f"billpay: FAILED ({', '.join(failures)})")
        return 1
    print(f"billpay: OK (the slice stays up at {WEB}/bill-pay)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
