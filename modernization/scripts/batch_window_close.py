#!/usr/bin/env python3
"""Close the batch window of one holder after a failure, so that the online
services accept writes again (make -C modernization batch-window-close).

Give exactly one holder, in the environment (the make variables):
  CYCLE=<cycleId>       a daily cycle's window. The close sends complete:
                        false: the cycle stays OPEN and the next DAG run
                        resumes it. This never completes a cycle.
  RUN_KEY=<dag_id>/<run_id>   another DAG's (or make load's) window; the
                        close sends no complete, since it has no cycle.

POST http://127.0.0.1:$CARDDEMO_BATCH_PORT/internal/batch-window/close
(default port 28090) with the bearer CARDDEMO_BATCH_INTERNAL_TOKEN. Prints
batch-service's answer: the closed window on 200 (also when it was closed
already), else the HTTP status, code and message, with the IDs of the
running executions on 409 and the wait on 503. Exit status 0 on 200, 1 on
any other answer or when batch-service is not reachable, 2 for a usage
error, which is found before any request. The token is never printed.

Usage: CYCLE=<id> | RUN_KEY=<key> batch_window_close.py
"""

import json
import os
import re
import sys
import urllib.error
import urllib.request

NAME = "batch-window-close"
DEFAULT_PORT = "28090"
MAX_KEY_LENGTH = 250
# The close waits for a launch of its holder up to batch-service's lock wait
# (30 s by default) before it answers 503.
TIMEOUT = 120


def fail(message: str, status: int = 1) -> int:
    print(f"{NAME}: FAILED ({message})", file=sys.stderr)
    return status


def request_body(env: dict) -> dict:
    cycle = env.get("CYCLE", "").strip()
    run_key = env.get("RUN_KEY", "")
    if bool(cycle) == bool(run_key):
        raise ValueError("give exactly one of CYCLE=<cycleId> or RUN_KEY=<dag_id>/<run_id>")
    if cycle:
        if not re.fullmatch(r"[1-9][0-9]{0,18}", cycle):
            raise ValueError("CYCLE must be a cycle ID, a positive whole number")
        return {"cycleId": int(cycle), "complete": False}
    if not 1 <= len(run_key) <= MAX_KEY_LENGTH:
        raise ValueError(f"RUN_KEY has {len(run_key)} characters; batch-service takes 1 to {MAX_KEY_LENGTH}")
    return {"windowRunKey": run_key}


def describe_failure(status: int, body: dict, retry_after: str | None) -> str:
    code = body.get("code")
    text = f"HTTP {status}"
    if code:
        text += f" {code}"
    if body.get("message"):
        text += f": {body['message']}"
    if status == 400 and body.get("field"):
        text += f" (field {body['field']})"
    if body.get("executionIds"):
        text += f"; running executions {', '.join(str(i) for i in body['executionIds'])}"
    if status == 503:
        text += f"; try again in {retry_after} s" if retry_after else "; try again later"
    return text


def describe_window(body: dict) -> str:
    holder = f"cycle {body['cycleId']}" if body.get("cycleId") is not None else f"run key {body.get('windowRunKey')}"
    text = f"window {body.get('windowId')} of {holder} closed at {body.get('closedAt')}"
    cycle = body.get("cycle")
    if isinstance(cycle, dict):
        text += f"; cycle {cycle.get('cycleId')} (attempt {cycle.get('attempt')}) is {cycle.get('status')}"
    return text


def main(argv: list) -> int:
    if len(argv) > 1:
        return fail("takes no arguments; give CYCLE=<cycleId> or RUN_KEY=<dag_id>/<run_id>", 2)
    try:
        payload = request_body(os.environ)
    except ValueError as e:
        return fail(str(e), 2)
    port = os.environ.get("CARDDEMO_BATCH_PORT") or DEFAULT_PORT
    if not re.fullmatch(r"[0-9]{1,5}", port) or not 0 < int(port) < 65536:
        return fail("CARDDEMO_BATCH_PORT is not a port number", 2)
    token = os.environ.get("CARDDEMO_BATCH_INTERNAL_TOKEN", "")
    if not token:
        return fail("CARDDEMO_BATCH_INTERNAL_TOKEN is not set; run make setup", 2)

    url = f"http://127.0.0.1:{port}/internal/batch-window/close"
    request = urllib.request.Request(
        url, data=json.dumps(payload).encode(), method="POST",
        headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json",
                 "Accept": "application/json"})
    # batch-service is on loopback: no proxy, whatever the environment says.
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        with opener.open(request, timeout=TIMEOUT) as response:
            status, raw, retry_after = response.status, response.read(), None
    except urllib.error.HTTPError as e:
        status, raw, retry_after = e.code, e.read(), e.headers.get("Retry-After")
    except (urllib.error.URLError, OSError) as e:
        reason = getattr(e, "reason", e)
        return fail(f"batch-service not reachable at 127.0.0.1:{port} ({type(reason).__name__}); "
                    f"is it running (make up)?")
    try:
        body = json.loads(raw) if raw else {}
    except ValueError:
        body = {}
    if not isinstance(body, dict):
        body = {}
    if status != 200:
        return fail(describe_failure(status, body, retry_after))
    print(f"{NAME}: OK ({describe_window(body)})")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
