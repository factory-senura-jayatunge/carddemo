#!/bin/sh
# Installs the Airflow requirements (airflow/requirements.txt with the
# official constraints for the venv's Python) and carddemo_airflow, in
# editable mode, into AIRFLOW_VENV. Already-satisfied requirements need no
# network. Prints one setup line: ok, skip or FAIL.
#
# Usage: airflow_venv.sh

set -u
HERE=$(cd "$(dirname "$0")/.." && pwd)
AF="$HERE/airflow"
VENV=${AIRFLOW_VENV:-$HOME/.venvs/carddemo-airflow}
NAME=carddemo_airflow
AIRFLOW_VERSION=3.3.2

case " ${CARDDEMO_SETUP_SKIP:-} " in
    *" airflow "*) printf 'skip  %-16s %s\n' "$NAME" 'not needed here (CARDDEMO_SETUP_SKIP)'; exit 0 ;;
esac
if [ ! -x "$VENV/bin/python" ]; then
    printf 'FAIL  %-16s %s\n' "$NAME" "no Airflow venv at $VENV; see README.md, Prerequisites"
    exit 1
fi
py=$("$VENV/bin/python" -c 'import sys; print("%d.%d" % sys.version_info[:2])')
constraints="$AF/constraints-$AIRFLOW_VERSION-py$py.txt"
if [ ! -f "$constraints" ]; then
    printf 'FAIL  %-16s %s\n' "$NAME" "the venv has Python $py, but airflow/ has no constraints-$AIRFLOW_VERSION-py$py.txt; see README.md, Prerequisites"
    exit 1
fi
log=$(mktemp)
trap 'rm -f "$log"' EXIT
pip() { "$VENV/bin/python" -m pip --disable-pip-version-check -q "$@" >>"$log" 2>&1; }
if pip install -r "$AF/requirements.txt" -c "$constraints" \
    && pip install --no-build-isolation --no-deps -e "$AF"; then
    printf 'ok    %-16s %s\n' "$NAME" "requirements and $AF (editable) in $VENV"
else
    printf 'FAIL  %-16s %s\n' "$NAME" "pip install failed; last lines:"
    tail -n 15 "$log"
    exit 1
fi
