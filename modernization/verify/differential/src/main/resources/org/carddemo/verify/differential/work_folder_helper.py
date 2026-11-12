"""The refusal mode's read-only check of the statement folders.

verify-differential's refusal mode starts this text as
``<python> -I -B -S -c <text>``, with an empty environment and ``/`` as the
working folder; nothing else runs it, apart from its own unit tests. It reads
one JSON object from stdin, opens the instance root from it one
descriptor-relative, no-follow step at a time, and prints exactly one line:

* ``facts final=<true|false> work=<absent|empty|nonempty|not-a-directory>``,
  exit status 0;
* or ``refuse <code>``, exit status 3, with a code of HELPER_CODES.

It writes nothing, and its line never carries a path, a name, an input value
or an error text. Only ENOENT on the one relative call of a step means absent;
every other error refuses.
"""
import base64
import errno
import hashlib
import json
import os
import stat
import sys

HELPER_CODES = (
    "work-folder-unsupported",
    "work-folder-input",
    "instance-root-refused",
    "work-folder-refused",
)
UNSUPPORTED, INPUT, ROOT_REFUSED, WALK_REFUSED = HELPER_CODES

KEYS = ("var_dir", "database", "instance_id", "cycle_id", "attempt", "job_execution_id")
INPUT_MAX = 8192
ID_MAX = 2**63 - 1
FACTS_EXIT = 0
REFUSE_EXIT = 3
HEX_DIGITS = "0123456789abcdef"
UUID_DASHES = (8, 13, 18, 23)
UUID_LENGTH = 36
MARKER_HEADER = b"carddemo-output-root 1\n"
GROUP_OR_OTHER = 0o077
GROUP_OR_OTHER_WRITE = 0o022
WORK_STATES = ("absent", "empty", "nonempty", "not-a-directory")


class Refusal(Exception):
    """One code of HELPER_CODES, and nothing else."""

    def __init__(self, code):
        super().__init__()
        self.code = code


def supported(version_info, flags, osmod):
    """Every capability the walk needs, probed on each start; no fallback exists."""
    try:
        return bool(
            tuple(version_info[:2]) >= (3, 11)
            and flags.isolated == 1
            and flags.no_site == 1
            and flags.dont_write_bytecode == 1
            and osmod.open in osmod.supports_dir_fd
            and osmod.stat in osmod.supports_dir_fd
            and osmod.stat in osmod.supports_follow_symlinks
            and osmod.scandir in osmod.supports_fd
            and hasattr(osmod, "O_NOFOLLOW")
            and hasattr(osmod, "O_DIRECTORY")
            and hasattr(osmod, "O_NONBLOCK")
            and hasattr(osmod, "O_CLOEXEC")
        )
    except Exception:
        return False


def _this_interpreter():
    return supported(sys.version_info, sys.flags, os)


def _no_duplicates(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise Refusal(INPUT)
        result[key] = value
    return result


def _no_constant(_name):
    raise Refusal(INPUT)


def _identifier(value):
    if type(value) is not int or not 0 < value <= ID_MAX:
        raise Refusal(INPUT)
    return value


def _text(value):
    if type(value) is not str or not value or "\0" in value:
        raise Refusal(INPUT)
    return value


def _instance_id(value):
    if type(value) is not str or len(value) != UUID_LENGTH:
        raise Refusal(INPUT)
    for index, char in enumerate(value):
        if index in UUID_DASHES:
            if char != "-":
                raise Refusal(INPUT)
        elif char not in HEX_DIGITS:
            raise Refusal(INPUT)
    return value


def read_input(stream):
    """The six inputs, from at most INPUT_MAX bytes that end the stream."""
    data = stream.read(INPUT_MAX + 1)
    if type(data) is not bytes or len(data) > INPUT_MAX:
        raise Refusal(INPUT)
    try:
        value = json.loads(data.decode("utf-8"), object_pairs_hook=_no_duplicates, parse_constant=_no_constant)
    except Refusal:
        raise
    except Exception:
        raise Refusal(INPUT) from None
    if type(value) is not dict or set(value) != set(KEYS) or len(value) != len(KEYS):
        raise Refusal(INPUT)
    var_dir = _text(value["var_dir"])
    if not var_dir.startswith("/"):
        raise Refusal(INPUT)
    database = _text(value["database"])
    try:
        os.fsencode(var_dir)
        database_bytes = database.encode("utf-8")
    except Exception:
        raise Refusal(INPUT) from None
    return (
        var_dir,
        database_bytes,
        _instance_id(value["instance_id"]),
        _identifier(value["cycle_id"]),
        _identifier(value["attempt"]),
        _identifier(value["job_execution_id"]),
    )


def marker(database_bytes, instance_id):
    """The exact bytes of the root's .instance marker for this database and instance."""
    return (
        MARKER_HEADER
        + b"database="
        + base64.urlsafe_b64encode(database_bytes).rstrip(b"=")
        + b"\ninstance="
        + instance_id.encode("ascii")
        + b"\n"
    )


def _open_folder(name, dir_fd):
    if dir_fd is None:
        return os.open(name, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_NONBLOCK | os.O_CLOEXEC)
    return os.open(name, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_NONBLOCK | os.O_CLOEXEC, dir_fd=dir_fd)


def _open_marker(dir_fd):
    return os.open(".instance", os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK | os.O_CLOEXEC, dir_fd=dir_fd)


def _check(fd, euid, forbidden, is_type, code):
    info = os.fstat(fd)
    if not is_type(info.st_mode) or info.st_uid != euid or info.st_mode & forbidden:
        raise Refusal(code)
    return info


def _read_at_most(fd, limit):
    data = b""
    while len(data) < limit:
        chunk = os.read(fd, limit - len(data))
        if not chunk:
            break
        data += chunk
    return data


def _anchor(var_dir, database_bytes, instance_id, euid, fds):
    """<var>, out, <sha256>, the root and its marker, from the inputs alone; any failure refuses."""
    try:
        var = _open_folder(var_dir, None)
        fds.append(var)
        _check(var, euid, GROUP_OR_OTHER_WRITE, stat.S_ISDIR, ROOT_REFUSED)
        out = _open_folder("out", var)
        fds.append(out)
        _check(out, euid, GROUP_OR_OTHER, stat.S_ISDIR, ROOT_REFUSED)
        component = _open_folder(hashlib.sha256(database_bytes).hexdigest(), out)
        fds.append(component)
        _check(component, euid, GROUP_OR_OTHER, stat.S_ISDIR, ROOT_REFUSED)
        root = _open_folder(instance_id, component)
        fds.append(root)
        _check(root, euid, GROUP_OR_OTHER, stat.S_ISDIR, ROOT_REFUSED)
        mark = _open_marker(root)
        fds.append(mark)
        _check(mark, euid, GROUP_OR_OTHER, stat.S_ISREG, ROOT_REFUSED)
        expected = marker(database_bytes, instance_id)
        if _read_at_most(mark, len(expected) + 1) != expected:
            raise Refusal(ROOT_REFUSED)
    except OSError:
        raise Refusal(ROOT_REFUSED) from None
    return root


def _walk(root, cycle_id, attempt, execution_id, euid, fds):
    """statements, the final folder, .work and the target, below the checked root."""
    try:
        try:
            statements = _open_folder("statements", root)
        except OSError as error:
            if error.errno == errno.ENOENT:
                return False, "absent"
            raise
        fds.append(statements)
        _check(statements, euid, GROUP_OR_OTHER_WRITE, stat.S_ISDIR, WALK_REFUSED)
        try:
            os.stat("%d-%d" % (cycle_id, attempt), dir_fd=statements, follow_symlinks=False)
            final = True
        except OSError as error:
            if error.errno != errno.ENOENT:
                raise
            final = False
        try:
            work = _open_folder(".work", statements)
        except OSError as error:
            if error.errno == errno.ENOENT:
                return final, "absent"
            raise
        fds.append(work)
        _check(work, euid, GROUP_OR_OTHER_WRITE, stat.S_ISDIR, WALK_REFUSED)
        name = "%d-%d.%d" % (cycle_id, attempt, execution_id)
        try:
            before = os.stat(name, dir_fd=work, follow_symlinks=False)
        except OSError as error:
            if error.errno == errno.ENOENT:
                return final, "absent"
            raise
        if not stat.S_ISDIR(before.st_mode):
            return final, "not-a-directory"
        target = _open_folder(name, work)
        fds.append(target)
        opened = _check(target, euid, GROUP_OR_OTHER_WRITE, stat.S_ISDIR, WALK_REFUSED)
        if (opened.st_dev, opened.st_ino) != (before.st_dev, before.st_ino):
            raise Refusal(WALK_REFUSED)
        with os.scandir(target) as entries:
            for _entry in entries:
                return final, "nonempty"
        return final, "empty"
    except OSError:
        raise Refusal(WALK_REFUSED) from None


def run(stream, probe=None):
    """The fact line for the inputs on stream; a refusal raises Refusal."""
    if not (probe or _this_interpreter)():
        raise Refusal(UNSUPPORTED)
    var_dir, database_bytes, instance_id, cycle_id, attempt, execution_id = read_input(stream)
    euid = os.geteuid()
    fds = []
    try:
        root = _anchor(var_dir, database_bytes, instance_id, euid, fds)
        final, work = _walk(root, cycle_id, attempt, execution_id, euid, fds)
    finally:
        for fd in reversed(fds):
            try:
                os.close(fd)
            except OSError:
                pass
    return "facts final=%s work=%s\n" % ("true" if final else "false", work)


def answer(stream, probe=None):
    """The exit status and the one line; nothing leaves as a traceback."""
    try:
        return FACTS_EXIT, run(stream, probe).encode("ascii")
    except Refusal as refusal:
        code = refusal.code if refusal.code in HELPER_CODES else WALK_REFUSED
    except BaseException:
        code = WALK_REFUSED
    return REFUSE_EXIT, ("refuse %s\n" % code).encode("ascii")


def main():
    status, line = answer(sys.stdin.buffer)
    try:
        sys.stdout.buffer.write(line)
        sys.stdout.buffer.flush()
    except BaseException:
        status = REFUSE_EXIT
    # os._exit: a failed flush at interpreter exit would otherwise print to stderr.
    os._exit(status)


if __name__ == "__main__":
    main()
