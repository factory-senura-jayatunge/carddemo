#!/bin/sh
# Run a command inside the dev container image that CI builds from
# .devcontainer/Dockerfile (tag carddemo-dev:ci by default). The checkout is
# mounted at /workspace and the command runs with the caller's uid/gid, so
# build/ and dist/ stay owned by the CI user.
#   tools/ci-run.sh make test
set -eu
image="${CARDDEMO_IMAGE:-carddemo-dev:ci}"
exec docker run --rm \
    --user "$(id -u):$(id -g)" \
    -e HOME=/tmp \
    -e CI \
    -e CARDDEMO_TEST_SEED \
    -e CARDDEMO_FAIL_ON_FLAKY \
    -e SOURCE_DATE_EPOCH \
    -v "$PWD:/workspace" \
    -w /workspace \
    "$image" "$@"
