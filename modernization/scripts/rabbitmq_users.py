#!/usr/bin/env python3
"""Create the RabbitMQ virtual host and the service users of the stack.

Create-only: a virtual host or user that exists is kept, and an existing
user's password is never set again, also when .env holds another one (the
broker's value wins; changing it is the operator's decision). The
permissions of each service user on the virtual host are brought to the
values in USERS, the narrowest that let the service declare its own
topology (messaging.md) and do its work.

It talks to the management API on 127.0.0.1:RABBITMQ_MGMT_HOST_PORT with
the admin login (RABBITMQ_DEFAULT_USER / RABBITMQ_DEFAULT_PASS), never
through a proxy. No password is printed; error lines carry the HTTP status
only.
"""

import base64
import json
import re
import time
import urllib.error
import urllib.parse
import urllib.request


def names(*items: str) -> str:
    return "^(" + "|".join(re.escape(i) for i in items) + ")$"


PAUTH = "carddemo.pauth"
DLX = "carddemo.dlx"
REQUEST = "carddemo.pauth.request"
REPLY = "carddemo.pauth.reply"
DLQ = "carddemo.pauth.request.dlq"
# RabbitMQ checks the default exchange under this name.
DEFAULT_EXCHANGE = "amq.default"

# What each AMQP operation needs (RabbitMQ access control, "How Permissions
# Work"): exchange.declare and queue.declare need configure on the name; a
# queue declared with a dead-letter exchange also needs write on that
# exchange and read on the queue; queue.bind needs write on the queue and
# read on the exchange; basic.publish needs write on the exchange;
# basic.consume needs read on the queue.
USERS = [
    {
        "service": "authorization-service",
        "user_key": "CARDDEMO_RABBITMQ_AUTHORIZATION_USER",
        "user_default": "authorization_svc",
        "password_key": "CARDDEMO_RABBITMQ_AUTHORIZATION_PASSWORD",
        "permissions": {
            "configure": names(PAUTH, REQUEST, REPLY, DLQ, DLX),
            # Replies to reply_to (default exchange) or to carddemo.pauth;
            # the two queues with a DLX need write on carddemo.dlx; the
            # three bindings need write on their queues.
            "write": names(DEFAULT_EXCHANGE, PAUTH, DLX, REQUEST, REPLY, DLQ),
            # Consumes the requests; the two queues with a DLX and the
            # bindings' source exchanges need read.
            "read": names(REQUEST, REPLY, PAUTH, DLX),
        },
    },
]


class ProvisionError(Exception):
    pass


class Api:
    def __init__(self, port: int, user: str, password: str, timeout: float = 10):
        self.port = port
        self.base = f"http://127.0.0.1:{port}/api"
        token = base64.b64encode(f"{user}:{password}".encode()).decode()
        self.headers = {"Authorization": "Basic " + token, "Content-Type": "application/json"}
        self.timeout = timeout
        # No proxy: the admin login must not leave the machine.
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def call(self, method: str, *parts: str, body=None):
        path = "/".join(urllib.parse.quote(p, safe="") for p in parts)
        data = None if body is None else json.dumps(body).encode()
        req = urllib.request.Request(f"{self.base}/{path}", data=data, method=method, headers=self.headers)
        try:
            with self.opener.open(req, timeout=self.timeout) as r:
                raw = r.read()
                return r.status, (json.loads(raw) if raw else None)
        except urllib.error.HTTPError as e:
            e.close()
            return e.code, None
        except (urllib.error.URLError, OSError) as e:
            reason = getattr(e, "reason", e)
            raise ProvisionError(f"management API on 127.0.0.1:{self.port} unreachable "
                                 f"({type(reason).__name__})") from None


def wait_ready(api: Api, wait: float) -> None:
    deadline = time.monotonic() + wait
    while True:
        try:
            status, _ = api.call("GET", "overview")
        except ProvisionError:
            if time.monotonic() >= deadline:
                raise
            status = None
        if status == 200:
            return
        if status in (401, 403):
            raise ProvisionError(f"the admin login RABBITMQ_DEFAULT_USER was refused ({status}); "
                                 "RABBITMQ_DEFAULT_PASS in .env differs from the broker's")
        if status is not None and status != 503:
            raise ProvisionError(f"GET /api/overview answered {status}")
        if time.monotonic() >= deadline:
            raise ProvisionError(f"the management API was not ready within {wait:.0f} s")
        time.sleep(1)


def ensure(api: Api, what: str, parts: tuple, body) -> str:
    """'kept' when the object exists, else PUT it: 'created'."""
    status, _ = api.call("GET", *parts)
    if status == 200:
        return "kept"
    if status != 404:
        raise ProvisionError(f"reading {what} answered {status}")
    status, _ = api.call("PUT", *parts, body=body)
    if status not in (201, 204):
        raise ProvisionError(f"creating {what} answered {status}")
    return "created"


def ensure_permissions(api: Api, vhost: str, user: str, wanted: dict) -> str:
    status, current = api.call("GET", "permissions", vhost, user)
    if status == 200 and {k: (current or {}).get(k) for k in wanted} == wanted:
        return "kept"
    if status not in (200, 404):
        raise ProvisionError(f"reading the permissions of {user} on {vhost} answered {status}")
    put, _ = api.call("PUT", "permissions", vhost, user, body=dict(wanted))
    if put not in (201, 204):
        raise ProvisionError(f"setting the permissions of {user} on {vhost} answered {put}")
    return "set" if status == 404 else "updated"


def provision(env: dict, say, required: bool = False, wait: float = 60) -> int:
    """0 when the virtual host and every user whose password .env has exist
    with their permissions. A user without a password is skipped with a
    warning, or fails when required (the explicit target)."""
    admin = env.get("RABBITMQ_DEFAULT_USER") or "carddemo"
    admin_password = env.get("RABBITMQ_DEFAULT_PASS", "")
    vhost = env.get("CARDDEMO_RABBITMQ_VHOST") or "carddemo"
    port = env.get("RABBITMQ_MGMT_HOST_PORT") or "35672"
    if not admin_password:
        say("fail", "rabbitmq", "RABBITMQ_DEFAULT_PASS is empty in .env; run make setup")
        return 1
    if not port.isdigit():
        say("fail", "rabbitmq", "RABBITMQ_MGMT_HOST_PORT is not a port number")
        return 1
    users = []
    for spec in USERS:
        user = env.get(spec["user_key"]) or spec["user_default"]
        if user == admin:
            say("fail", "rabbitmq", f"{spec['user_key']} must differ from RABBITMQ_DEFAULT_USER")
            return 1
        if not env.get(spec["password_key"]):
            if required:
                say("fail", "rabbitmq", f"{spec['password_key']} is not set in .env; run make setup")
                return 1
            say("warn", "rabbitmq", f"user {user} skipped: {spec['password_key']} is not set in .env; "
                                    "run make setup")
            continue
        users.append((spec, user))
    api = Api(int(port), admin, admin_password)
    try:
        wait_ready(api, wait)
        state = ensure(api, f"virtual host {vhost}", ("vhosts", vhost), {})
        say("ok", "rabbitmq", f"virtual host {vhost}: {state}")
        for spec, user in users:
            state = ensure(api, f"user {user}", ("users", user),
                           {"password": env[spec["password_key"]], "tags": ""})
            detail = (f"created with {spec['password_key']} from .env" if state == "created"
                      else "kept (exists; its password is not changed)")
            say("ok", "rabbitmq", f"user {user} ({spec['service']}): {detail}")
            state = ensure_permissions(api, vhost, user, spec["permissions"])
            say("ok", "rabbitmq", f"permissions of {user} on {vhost}: {state} (its own names only)")
    except ProvisionError as e:
        say("fail", "rabbitmq", str(e))
        return 1
    return 0
