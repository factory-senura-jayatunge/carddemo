"""scripts/init_env.py --rotate: one key's value replaced, every other byte
of .env kept, a private copy of the previous file, and no value printed."""

from __future__ import annotations

import importlib.util
import os
import secrets
import stat
import subprocess
import sys

import pytest

from conftest import AIRFLOW_DIR

SCRIPT = os.path.join(os.path.dirname(AIRFLOW_DIR), "scripts", "init_env.py")
KEY = "AIRFLOW_API_JWT_SECRET"


def env_bytes(old: str, others: dict) -> bytes:
    # Mixed line endings, a comment, an empty line, a value with spaces and
    # no newline at the end: all must survive byte for byte.
    return (b"# shared settings\r\n"
            + f"AIRFLOW_ADMIN_PASSWORD={others['admin']}\n".encode()
            + b"\n"
            + f"{KEY}={old}\r\n".encode()
            + f"CARDDEMO_BATCH_INTERNAL_TOKEN={others['token']}  \n".encode()
            + f"AIRFLOW_FERNET_KEY={others['fernet']}".encode())


def rotate(env, backup, key=KEY):
    done = subprocess.run([sys.executable, SCRIPT, "--rotate", key, str(env), "--backup-dir", str(backup)],
                          capture_output=True, text=True, timeout=60)
    return done.returncode, done.stdout + done.stderr


@pytest.fixture
def files(tmp_path):
    old = secrets.token_urlsafe(16)
    others = {k: secrets.token_urlsafe(20) for k in ("admin", "token", "fernet")}
    env = tmp_path / ".env"
    env.write_bytes(env_bytes(old, others))
    os.chmod(env, 0o600)
    return env, tmp_path / "private" / "backup", old, others


def test_only_the_key_changes(files):
    env, backup, old, others = files
    original = env.read_bytes()
    rc, out = rotate(env, backup)
    assert rc == 0, out
    before, after = original.splitlines(keepends=True), env.read_bytes().splitlines(keepends=True)
    assert len(before) == len(after)
    changed = [i for i, (a, b) in enumerate(zip(before, after)) if a != b]
    assert changed == [3]
    line = after[3]
    assert line.startswith(f"{KEY}=".encode()) and line.endswith(b"\r\n")
    new = line[len(KEY) + 1:-2].decode()
    assert len(new.encode()) >= 64 and new.isalnum() and new != old
    assert env.read_bytes() == original.replace(f"{KEY}={old}".encode(), f"{KEY}={new}".encode())
    assert stat.S_IMODE(os.stat(env).st_mode) == 0o600
    for value in (old, new, *others.values()):
        assert value not in out
    assert "replaced AIRFLOW_API_JWT_SECRET only (1 of 6 lines changed" in out


def test_the_previous_file_is_kept_privately(files):
    env, backup, _old, _others = files
    original = env.read_bytes()
    assert rotate(env, backup)[0] == 0
    assert stat.S_IMODE(os.stat(backup).st_mode) == 0o700
    [copy] = list(backup.iterdir())
    assert copy.read_bytes() == original
    assert stat.S_IMODE(os.stat(copy).st_mode) == 0o600
    assert not [p for p in env.parent.iterdir() if p.name.startswith(".") and ".rotate." in p.name]


def test_two_rotations_give_two_values_and_two_copies(files):
    env, backup, _old, _others = files
    assert rotate(env, backup)[0] == 0
    first = env.read_bytes()
    assert rotate(env, backup)[0] == 0
    assert env.read_bytes() != first
    assert len(list(backup.iterdir())) == 2


@pytest.mark.parametrize("key", ["AIRFLOW_ADMIN_PASSWORD", "CARDDEMO_BATCH_INTERNAL_TOKEN", "OTHER"])
def test_other_keys_are_refused(files, key):
    env, backup, _old, _others = files
    original = env.read_bytes()
    rc, out = rotate(env, backup, key)
    assert rc == 2 and "only AIRFLOW_API_JWT_SECRET can be rotated" in out
    assert env.read_bytes() == original and not backup.exists()


@pytest.mark.parametrize("count", [0, 2])
def test_missing_or_repeated_key_changes_nothing(tmp_path, count):
    env = tmp_path / ".env"
    env.write_bytes(b"A=1\n" + b"".join(f"{KEY}={secrets.token_urlsafe(8)}\n".encode() for _ in range(count)))
    original = env.read_bytes()
    rc, out = rotate(env, tmp_path / "backup")
    assert rc == 1 and f"{count} lines set {KEY}, expected exactly one; nothing changed" in out
    assert env.read_bytes() == original and not (tmp_path / "backup").exists()


def test_a_commented_or_prefixed_key_is_not_the_key(tmp_path):
    env = tmp_path / ".env"
    env.write_bytes(f"# {KEY}=x\nX_{KEY}=y\n{KEY}X=z\n".encode())
    assert rotate(env, tmp_path / "backup")[0] == 1


def test_a_symlinked_env_is_refused(files, tmp_path):
    env, backup, _old, _others = files
    link = tmp_path / "link.env"
    link.symlink_to(env)
    original = env.read_bytes()
    assert rotate(link, backup)[0] == 2
    assert env.read_bytes() == original and link.is_symlink()


def load_init_env():
    spec = importlib.util.spec_from_file_location("init_env_under_test", SCRIPT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def test_a_file_changed_meanwhile_is_not_replaced(files, monkeypatch, capsys):
    env, backup, _old, _others = files
    module = load_init_env()
    generate = module.GENERATORS[KEY]

    def generate_while_someone_writes():
        with open(env, "ab") as f:
            f.write(b"\nNEW_KEY=1\n")
        return generate()

    monkeypatch.setitem(module.GENERATORS, KEY, generate_while_someone_writes)
    changed = env.read_bytes() + b"\nNEW_KEY=1\n"
    assert module.rotate(KEY, str(env), str(backup)) == 1
    assert "changed while AIRFLOW_API_JWT_SECRET was rotated; nothing replaced" in capsys.readouterr().err
    assert env.read_bytes() == changed


def test_setup_still_completes_an_env_without_rotating(tmp_path):
    example = tmp_path / "example.env"
    example.write_text(f"{KEY}=\nA=1\n", encoding="utf-8")
    env = tmp_path / ".env"
    kept = secrets.token_urlsafe(64)
    env.write_text(f"{KEY}={kept}\n", encoding="utf-8")
    subprocess.run([sys.executable, SCRIPT, str(example), str(env)], check=True, capture_output=True)
    assert env.read_text(encoding="utf-8") == f"{KEY}={kept}\nA=1\n"
