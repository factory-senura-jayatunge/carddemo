#!/usr/bin/env python3
"""Run a heavy target's command while holding the machine-wide heavy lock.

Heavy targets (Testcontainers, the running stack, browsers) must not run in
parallel on a laptop. The lock is an flock on $CARDDEMO_VAR_DIR/run/heavy.lock,
held for the whole run by this process and the command it starts, so the
kernel releases it when the run ends, also after a crash or kill; there are
no stale locks to clean up.
A second heavy target fails at once and names the holder, unless
HEAVY_WAIT=1, which makes it wait.

Usage: heavy.py NAME [--] COMMAND [ARG...]
"""

import fcntl
import os
import signal
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def lock_path() -> str:
    var_dir = os.environ.get("CARDDEMO_VAR_DIR") or os.path.join(HERE, "var")
    return os.path.join(var_dir, "run", "heavy.lock")


def main(argv: list) -> int:
    args = argv[1:]
    if len(args) < 2:
        print(__doc__.strip().splitlines()[-1], file=sys.stderr)
        return 2
    name, command = args[0], args[1:]
    if command[:1] == ["--"]:
        command = command[1:]
    path = lock_path()
    os.makedirs(os.path.dirname(path), exist_ok=True)
    fd = os.open(path, os.O_RDWR | os.O_CREAT, 0o644)
    try:
        fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        holder = os.pread(fd, 512, 0).decode("utf-8", "replace").strip() or "unknown"
        if os.environ.get("HEAVY_WAIT") != "1":
            print(f"{name}: FAILED (heavy lock held by {holder}; rerun later or with HEAVY_WAIT=1)",
                  file=sys.stderr)
            return 3
        print(f"{name}: waiting for the heavy lock held by {holder}", file=sys.stderr)
        fcntl.flock(fd, fcntl.LOCK_EX)
    os.ftruncate(fd, 0)
    os.pwrite(fd, f"{name} pid {os.getpid()} since {time.strftime('%H:%M:%S')} in {os.getcwd()}\n".encode(), 0)

    # The child inherits the locked descriptor: if this wrapper is killed,
    # the lock stays held until the heavy work itself has ended.
    child = subprocess.Popen(command, pass_fds=(fd,))
    for sig in (signal.SIGINT, signal.SIGTERM, signal.SIGHUP):
        signal.signal(sig, lambda s, _f: child.send_signal(s))
    rc = child.wait()
    os.ftruncate(fd, 0)
    return rc if rc >= 0 else 128 - rc


if __name__ == "__main__":
    sys.exit(main(sys.argv))
