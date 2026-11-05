#!/bin/sh
# Single entry point for the COBOL/JCL lint and cobc syntax check (used by CI and documented in README.md).
# Arguments are passed through, e.g. `scripts/lint.sh --no-baseline` or `scripts/lint.sh app/cbl/CBACT01C.cbl`.
set -eu
cd "$(dirname "$0")/.."
exec python3 scripts/cobol_lint.py check "$@"
