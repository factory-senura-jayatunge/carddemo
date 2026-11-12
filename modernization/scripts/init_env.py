#!/usr/bin/env python3
"""Create or complete modernization/.env from .env.example.

Copies the example when .env is missing, adds keys that the example has and
.env lacks, and fills every empty *_PASSWORD, *_PASS, *_SECRET and *_TOKEN
value with a random one (CARDDEMO_SEED_USER_PASSWORD: 8 upper-case letters
and digits; AIRFLOW_FERNET_KEY: a Fernet key; AIRFLOW_API_JWT_SECRET: 64
letters and digits).
Existing values are never changed, and no secret is printed. An empty
CARDDEMO_VAR_DIR gets the absolute default <main checkout>/modernization/var,
which is printed; a relative one, in .env or the environment, prints
"FAIL  CARDDEMO_VAR_DIR must be an absolute path" and exits 1, and so does
each removed output setting that is still set (by name, never its value).
A kept AIRFLOW_API_JWT_SECRET shorter than 64 bytes gets a warning:
replacing it signs every Airflow session out, so that is the user's
decision.

--rotate replaces one key's value with a new generated one and changes
nothing else: every other byte of ENV stays as it was, line endings
included. Only AIRFLOW_API_JWT_SECRET can be rotated. The previous file goes
to BACKUP_DIR (mode 700) as a file of mode 600 first; the new file replaces
ENV atomically, only if ENV did not change meanwhile, and is then compared
with the previous one: exactly that line must differ, or the previous file
is put back. No value is printed.

--sa-secret copies MSSQL_SA_PASSWORD of ENV to SECRET_FILE, the file that
the mssql service gets as its compose secret, when it differs. The file is
rewritten in place (mode 644, directory mode 700). No value is printed.

Usage: init_env.py EXAMPLE ENV
       init_env.py --rotate KEY ENV --backup-dir BACKUP_DIR
       init_env.py --sa-secret ENV SECRET_FILE
"""

import base64
import os
import re
import secrets
import stat
import string
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from with_env import VAR_DIR_KEY, default_var_dir, legacy_output_settings  # noqa: E402
KEY_RE = re.compile(r"^([A-Za-z_][A-Za-z0-9_]*)=(.*)$")
SECRET_RE = re.compile(r"_(PASSWORD|PASS|SECRET|TOKEN)$")
ALPHABET = string.ascii_letters + string.digits


def password(length: int = 32) -> str:
    # Letters and digits only: valid for the SQL Server complexity policy
    # (three of four character classes), and safe in .env, make and URLs.
    while True:
        value = "".join(secrets.choice(ALPHABET) for _ in range(length))
        if (any(c.isupper() for c in value) and any(c.islower() for c in value)
                and any(c.isdigit() for c in value)):
            return value


def seed_password() -> str:
    # 8 upper-case letters and digits: fits the 8-character legacy password
    # field, survives legacy upper-casing, and meets the fixed-mode minimum.
    alphabet = string.ascii_uppercase + string.digits
    while True:
        value = "".join(secrets.choice(alphabet) for _ in range(8))
        if any(c.isalpha() for c in value) and any(c.isdigit() for c in value):
            return value


def fernet_key() -> str:
    # What cryptography's Fernet.generate_key() returns: 32 random bytes,
    # URL-safe base64.
    return base64.urlsafe_b64encode(secrets.token_bytes(32)).decode()


GENERATORS = {"CARDDEMO_SEED_USER_PASSWORD": seed_password, "AIRFLOW_FERNET_KEY": fernet_key,
              # Airflow signs its API tokens with HS512, which wants a key of
              # at least 64 bytes (RFC 7518, section 3.2).
              "AIRFLOW_API_JWT_SECRET": lambda: password(64)}
MIN_BYTES = {"AIRFLOW_API_JWT_SECRET": 64}
ROTATABLE = ("AIRFLOW_API_JWT_SECRET",)


def write_new(path: str, data: bytes) -> None:
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "wb") as f:
        f.write(data)
        f.flush()
        os.fsync(f.fileno())


def fsync_dir(path: str) -> None:
    fd = os.open(path, os.O_RDONLY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def key_lines(lines: list, key: str) -> list:
    prefix = key.encode() + b"="
    return [i for i, line in enumerate(lines) if line.startswith(prefix)]


def rotate(key: str, env_path: str, backup_dir: str) -> int:
    label = f"{'.env':16} {env_path}"
    if key not in ROTATABLE:
        print(f"fail  {label}: only {', '.join(ROTATABLE)} can be rotated, not {key}", file=sys.stderr)
        return 2
    if os.path.islink(env_path) or not os.path.isfile(env_path):
        print(f"fail  {label}: not a regular file; nothing changed", file=sys.stderr)
        return 2
    if os.path.islink(backup_dir):
        print(f"fail  {label}: the backup directory {backup_dir} is a symlink; nothing changed", file=sys.stderr)
        return 2
    with open(env_path, "rb") as f:
        original = f.read()
    before = os.stat(env_path)
    lines = original.splitlines(keepends=True)
    hits = key_lines(lines, key)
    if len(hits) != 1:
        print(f"fail  {label}: {len(hits)} lines set {key}, expected exactly one; nothing changed", file=sys.stderr)
        return 1
    i = hits[0]
    body = lines[i].rstrip(b"\r\n")
    prefix = key.encode() + b"="
    old_value = body[len(prefix):]
    new_value = old_value
    while new_value == old_value:
        new_value = GENERATORS[key]().encode()
    if len(new_value) < MIN_BYTES[key]:
        raise AssertionError(f"the generator of {key} is too short")
    updated_lines = list(lines)
    updated_lines[i] = prefix + new_value + lines[i][len(body):]
    updated = b"".join(updated_lines)

    old_umask = os.umask(0o077)
    try:
        os.makedirs(backup_dir, mode=0o700, exist_ok=True)
        os.chmod(backup_dir, 0o700)
        backup = os.path.join(backup_dir, f"{os.path.basename(env_path)}.{time.strftime('%Y%m%dT%H%M%S')}"
                                          f".{os.getpid()}")
        write_new(backup, original)
        fsync_dir(backup_dir)
        directory = os.path.dirname(os.path.abspath(env_path))
        tmp = os.path.join(directory, f".{os.path.basename(env_path)}.rotate.{os.getpid()}")
        write_new(tmp, updated)
        now = os.stat(env_path)
        with open(env_path, "rb") as f:
            unchanged = f.read() == original
        if not unchanged or (now.st_ino, now.st_size, now.st_mtime_ns) != (before.st_ino, before.st_size,
                                                                         before.st_mtime_ns):
            os.remove(tmp)
            print(f"fail  {label}: changed while {key} was rotated; nothing replaced (copy in {backup})",
                  file=sys.stderr)
            return 1
        os.replace(tmp, env_path)
        fsync_dir(directory)

        with open(env_path, "rb") as f:
            after = f.read().splitlines(keepends=True)
        differ = [j for j, (a, b) in enumerate(zip(lines, after)) if a != b]
        if len(after) != len(lines) or differ != [i] or after[i] != updated_lines[i]:
            restore = os.path.join(directory, f".{os.path.basename(env_path)}.restore.{os.getpid()}")
            write_new(restore, original)
            os.replace(restore, env_path)
            fsync_dir(directory)
            print(f"fail  {label}: the new file differs in more than {key}; the previous file is back",
                  file=sys.stderr)
            return 1
    finally:
        os.umask(old_umask)
    mode = stat.S_IMODE(os.stat(env_path).st_mode)
    print(f"ok    {label}: replaced {key} only (1 of {len(lines)} lines changed, at least "
          f"{MIN_BYTES[key]} bytes, mode {mode:o}); previous file in {backup} (mode 600)")
    return 0


def sa_secret(env_path: str, secret_path: str, verbose: bool = True) -> int:
    """Copy MSSQL_SA_PASSWORD of env_path to secret_path, the mssql service's
    compose secret: no newline, mode 644, in a directory of mode 700."""
    label = f"{'sa-secret':16} {secret_path}"
    try:
        with open(env_path, encoding="utf-8") as f:
            values = {m.group(1): m.group(2) for m in map(KEY_RE.match, f.read().splitlines()) if m}
    except OSError:
        print(f"fail  {label}: {env_path} is missing; run make setup", file=sys.stderr)
        return 1
    value = values.get("MSSQL_SA_PASSWORD", "").strip()
    if not value:
        print(f"fail  {label}: MSSQL_SA_PASSWORD is empty in {env_path}; run make setup", file=sys.stderr)
        return 1
    if os.path.islink(secret_path) or (os.path.exists(secret_path) and not os.path.isfile(secret_path)):
        print(f"fail  {label}: not a regular file; nothing changed", file=sys.stderr)
        return 1
    directory = os.path.dirname(os.path.abspath(secret_path))
    os.makedirs(directory, mode=0o700, exist_ok=True)
    os.chmod(directory, 0o700)
    data = value.encode()
    try:
        with open(secret_path, "rb") as f:
            current = f.read()
    except FileNotFoundError:
        current = None
    if current != data:
        # Rewritten in place, not replaced by a rename: the running container
        # bind-mounts this inode, so only then does its health check see a
        # changed password without a restart.
        fd = os.open(secret_path, os.O_WRONLY | os.O_CREAT | getattr(os, "O_NOFOLLOW", 0), 0o644)
        with os.fdopen(fd, "wb") as f:
            f.write(data)
            f.truncate(len(data))
            f.flush()
            os.fsync(f.fileno())
    os.chmod(secret_path, 0o644)
    if verbose or current != data:
        state = "kept" if current == data else "written" if current is None else "updated"
        print(f"ok    {label}: {state} from MSSQL_SA_PASSWORD (mode 644, directory mode 700)")
    return 0


def main(argv: list) -> int:
    if len(argv) == 6 and argv[1] == "--rotate" and argv[4] == "--backup-dir":
        return rotate(argv[2], argv[3], argv[5])
    if len(argv) == 4 and argv[1] == "--sa-secret":
        return sa_secret(argv[2], argv[3])
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
        if m and (SECRET_RE.search(m.group(1)) or m.group(1) in GENERATORS) and not m.group(2).strip():
            lines[i] = f"{m.group(1)}={GENERATORS.get(m.group(1), password)()}"
            filled.append(m.group(1))

    # CARDDEMO_VAR_DIR is a path, not a secret: an empty value gets the
    # absolute default and is printed; a set value is never changed.
    failures, var_written = [], None
    for i, line in enumerate(lines):
        m = KEY_RE.match(line)
        if m and m.group(1) == VAR_DIR_KEY:
            if not m.group(2).strip():
                var_written = default_var_dir()
                lines[i] = f"{VAR_DIR_KEY}={var_written}"
            elif not os.path.isabs(m.group(2).strip()):
                failures.append(f"{VAR_DIR_KEY} must be an absolute path")
    env_var = (os.environ.get(VAR_DIR_KEY) or "").strip()
    if env_var and not os.path.isabs(env_var) and f"{VAR_DIR_KEY} must be an absolute path" not in failures:
        failures.append(f"{VAR_DIR_KEY} must be an absolute path")
    file_values = {m.group(1): m.group(2) for m in map(KEY_RE.match, lines) if m}
    for name in legacy_output_settings(os.environ, file_values):
        failures.append(f"{name} is no longer read; remove it (outputs are under {VAR_DIR_KEY}/out/)")

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
    if name.startswith(".."):
        name = os.path.abspath(env_path)
    parts = ["created from " + os.path.basename(example_path)] if created else ["kept"]
    if added:
        parts.append("added " + ", ".join(added))
    if filled:
        parts.append("generated " + ", ".join(filled))
    print(f"ok    {'.env':16} {name}: {'; '.join(parts)} (mode 600)")
    if var_written:
        print(f"ok    {VAR_DIR_KEY:16} {var_written} (written to .env)")
    for failure in failures:
        print(f"FAIL  {failure}")
    for line in lines:
        m = KEY_RE.match(line)
        if m and m.group(1) in MIN_BYTES and m.group(1) not in filled:
            minimum = MIN_BYTES[m.group(1)]
            if len(m.group(2).strip().encode()) < minimum:
                print(f"warn  {'.env':16} {m.group(1)} is shorter than the {minimum} bytes HS512 needs "
                      f"(RFC 7518); Airflow still accepts it, with a warning per token. To replace it, "
                      f"empty the value and run make setup; everyone signed in to Airflow signs in again.")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
