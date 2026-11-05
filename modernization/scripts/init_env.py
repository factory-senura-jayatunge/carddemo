#!/usr/bin/env python3
"""Create or complete modernization/.env from .env.example.

Copies the example when .env is missing, adds keys that the example has and
.env lacks, and fills every empty *_PASSWORD / *_PASS value with a random
password. Existing values are never changed, and no value is printed.

Usage: init_env.py EXAMPLE ENV
"""

import os
import re
import secrets
import string
import sys

KEY_RE = re.compile(r"^([A-Za-z_][A-Za-z0-9_]*)=(.*)$")
SECRET_RE = re.compile(r"_(PASSWORD|PASS)$")
ALPHABET = string.ascii_letters + string.digits


def password(length: int = 32) -> str:
    # Letters and digits only: valid for the SQL Server complexity policy
    # (three of four character classes), and safe in .env, make and URLs.
    while True:
        value = "".join(secrets.choice(ALPHABET) for _ in range(length))
        if (any(c.isupper() for c in value) and any(c.islower() for c in value)
                and any(c.isdigit() for c in value)):
            return value


def main(argv: list) -> int:
    if len(argv) != 3:
        print(__doc__.strip().splitlines()[-1], file=sys.stderr)
        return 2
    example_path, env_path = argv[1], argv[2]
    with open(example_path, encoding="utf-8") as f:
        example = f.read().splitlines()
    created = not os.path.exists(env_path)
    if created:
        lines = list(example)
    else:
        with open(env_path, encoding="utf-8") as f:
            lines = f.read().splitlines()

    present = {m.group(1) for m in map(KEY_RE.match, lines) if m}
    added = []
    for line in example:
        m = KEY_RE.match(line)
        if m and m.group(1) not in present:
            lines.append(line)
            present.add(m.group(1))
            added.append(m.group(1))

    filled = []
    for i, line in enumerate(lines):
        m = KEY_RE.match(line)
        if m and SECRET_RE.search(m.group(1)) and not m.group(2).strip():
            lines[i] = f"{m.group(1)}={password()}"
            filled.append(m.group(1))

    old_umask = os.umask(0o077)
    try:
        tmp = env_path + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            f.write("\n".join(lines) + "\n")
        os.chmod(tmp, 0o600)
        os.replace(tmp, env_path)
    finally:
        os.umask(old_umask)

    name = os.path.relpath(env_path)
    parts = ["created from " + os.path.basename(example_path)] if created else ["kept"]
    if added:
        parts.append("added " + ", ".join(added))
    if filled:
        parts.append("generated " + ", ".join(filled))
    print(f"ok    {'.env':16} {name}: {'; '.join(parts)} (mode 600)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
