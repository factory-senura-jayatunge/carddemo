#!/bin/sh
# Finds and checks the toolchain of the modernized stack without changing any
# global setting (no relinking, no default JDK or Node switch).
#
#   toolchain.sh java-home   print the home of a JDK 21; exit 1 if none is found
#   toolchain.sh node-bin    print a directory holding Node 24's node and npm
#   toolchain.sh check       check every prerequisite; exit 1 if one is missing
#
# Overrides: CARDDEMO_JAVA_HOME, CARDDEMO_NODE_BIN (used as given, never
# second-guessed) and AIRFLOW_VENV (default ~/.venvs/carddemo-airflow).
# CARDDEMO_SETUP_SKIP lists checks a job does not need (java node docker
# airflow), e.g. in CI jobs that don't use that tool; they print "skip".
set -u

HERE=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)
JAVA_MAJOR=$(sed -n '1s/[^0-9].*//p' "$HERE/.java-version" 2>/dev/null)
NODE_MAJOR=$(sed -n '1s/[^0-9].*//p' "$HERE/.nvmrc" 2>/dev/null)
JAVA_MAJOR=${JAVA_MAJOR:-21}
NODE_MAJOR=${NODE_MAJOR:-24}
AIRFLOW_MAJOR=3
AIRFLOW_VENV=${AIRFLOW_VENV:-$HOME/.venvs/carddemo-airflow}

java_major() {
    [ -n "$1" ] && [ -x "$1/bin/java" ] || return 1
    "$1/bin/java" -XshowSettings:properties -version 2>&1 |
        sed -n 's/^ *java\.specification\.version = *//p'
}

java_candidates() {
    printf '%s\n' "${JAVA_HOME:-}" "${JAVA_HOME_21_X64:-}" "${JAVA_HOME_21_ARM64:-}"
    if [ -x /usr/libexec/java_home ]; then
        /usr/libexec/java_home -v "$JAVA_MAJOR" 2>/dev/null
    fi
    for p in \
        "/opt/homebrew/opt/openjdk@$JAVA_MAJOR/libexec/openjdk.jdk/Contents/Home" \
        "/usr/local/opt/openjdk@$JAVA_MAJOR/libexec/openjdk.jdk/Contents/Home" \
        /usr/lib/jvm/java-"$JAVA_MAJOR"-openjdk* \
        /usr/lib/jvm/temurin-"$JAVA_MAJOR"* \
        /usr/lib/jvm/msopenjdk-"$JAVA_MAJOR"* \
        /usr/lib/jvm/java-"$JAVA_MAJOR"* \
        "$HOME"/.sdkman/candidates/java/"$JAVA_MAJOR".*; do
        printf '%s\n' "$p"
    done
    if command -v java >/dev/null 2>&1; then
        java -XshowSettings:properties -version 2>&1 | sed -n 's/^ *java\.home = *//p'
    fi
}

find_java_home() {
    if [ -n "${CARDDEMO_JAVA_HOME:-}" ]; then
        [ "$(java_major "$CARDDEMO_JAVA_HOME")" = "$JAVA_MAJOR" ] || return 1
        printf '%s\n' "$CARDDEMO_JAVA_HOME"
        return 0
    fi
    java_candidates | while IFS= read -r home; do
        if [ "$(java_major "$home")" = "$JAVA_MAJOR" ]; then
            printf '%s\n' "$home"
            break
        fi
    done | grep . || return 1
}

node_major() {
    [ -n "$1" ] && [ -x "$1/node" ] || return 1
    "$1/node" -p 'process.versions.node.split(".")[0]' 2>/dev/null
}

node_candidates() {
    if command -v node >/dev/null 2>&1; then
        dirname -- "$(command -v node)"
    fi
    nvm_dir=${NVM_DIR:-$HOME/.nvm}
    fnm_dir=${FNM_DIR:-$HOME/.local/share/fnm}
    for p in \
        "/opt/homebrew/opt/node@$NODE_MAJOR/bin" \
        "/usr/local/opt/node@$NODE_MAJOR/bin" \
        "$nvm_dir"/versions/node/v"$NODE_MAJOR".*/bin \
        "$fnm_dir"/node-versions/v"$NODE_MAJOR".*/installation/bin \
        "$HOME"/.volta/tools/image/node/"$NODE_MAJOR".*/bin \
        /usr/local/bin /usr/bin; do
        printf '%s\n' "$p"
    done
}

find_node_bin() {
    if [ -n "${CARDDEMO_NODE_BIN:-}" ]; then
        [ "$(node_major "$CARDDEMO_NODE_BIN")" = "$NODE_MAJOR" ] || return 1
        printf '%s\n' "$CARDDEMO_NODE_BIN"
        return 0
    fi
    node_candidates | while IFS= read -r dir; do
        if [ "$(node_major "$dir")" = "$NODE_MAJOR" ]; then
            printf '%s\n' "$dir"
            break
        fi
    done | grep . || return 1
}

failures=0
ok() { printf 'ok    %-16s %s\n' "$1" "$2"; }
warn() { printf 'warn  %-16s %s\n' "$1" "$2"; }
fail() {
    printf 'FAIL  %-16s %s\n' "$1" "$2"
    failures=$((failures + 1))
}

skip() {
    case " ${CARDDEMO_SETUP_SKIP:-} " in
        *" $1 "*)
            printf 'skip  %-16s %s\n' "$2" 'not needed here (CARDDEMO_SETUP_SKIP)'
            return 0
            ;;
    esac
    return 1
}

check() {
    os=$(uname -s)

    if skip java "JDK $JAVA_MAJOR"; then
        :
    elif home=$(find_java_home); then
        ok "JDK $JAVA_MAJOR" "$("$home/bin/java" -version 2>&1 | head -n 1) at $home"
    elif [ -n "${CARDDEMO_JAVA_HOME:-}" ]; then
        fail "JDK $JAVA_MAJOR" "CARDDEMO_JAVA_HOME=$CARDDEMO_JAVA_HOME is not a JDK $JAVA_MAJOR"
    elif [ "$os" = Darwin ]; then
        fail "JDK $JAVA_MAJOR" "not found; brew install openjdk@$JAVA_MAJOR (no linking needed)"
    else
        fail "JDK $JAVA_MAJOR" "not found; apt-get install openjdk-$JAVA_MAJOR-jdk-headless, or actions/setup-java in CI"
    fi

    if skip node "Node $NODE_MAJOR"; then
        :
    elif bin=$(find_node_bin); then
        if [ -x "$bin/npm" ]; then
            ok "Node $NODE_MAJOR" "$("$bin/node" -v), npm $("$bin/npm" -v 2>/dev/null) in $bin"
        else
            fail "Node $NODE_MAJOR" "node $("$bin/node" -v) in $bin has no npm next to it"
        fi
    elif [ -n "${CARDDEMO_NODE_BIN:-}" ]; then
        fail "Node $NODE_MAJOR" "CARDDEMO_NODE_BIN=$CARDDEMO_NODE_BIN has no Node $NODE_MAJOR"
    elif [ "$os" = Darwin ]; then
        fail "Node $NODE_MAJOR" "not found; brew install node@$NODE_MAJOR (keg-only, used by full path)"
    else
        fail "Node $NODE_MAJOR" "not found; nvm install $NODE_MAJOR, or actions/setup-node with node-version-file: modernization/.nvmrc in CI"
    fi

    if skip docker docker; then
        :
    elif ! command -v docker >/dev/null 2>&1; then
        if [ "$os" = Darwin ]; then
            fail docker "CLI not found; brew install colima docker docker-compose"
        else
            fail docker "CLI not found; install Docker Engine with the compose plugin"
        fi
    else
        compose=$(docker compose version --short 2>/dev/null)
        case $compose in
            [2-9].* | [1-9][0-9]*) ok "docker compose" "$compose" ;;
            *) fail "docker compose" "docker compose v2 or newer is required (got '${compose:-none}')" ;;
        esac
        if server=$(docker info --format '{{.ServerVersion}} {{.OSType}}/{{.Architecture}}' 2>/dev/null); then
            endpoint=${DOCKER_HOST:-context $(docker context show 2>/dev/null)}
            ok "docker engine" "$server via $endpoint"
            case $server in
                *aarch64* | *arm64*)
                    rosetta=""
                    case $endpoint in
                        *colima*)
                            if grep -Eq '^[[:space:]]*rosetta:[[:space:]]*true' "$HOME/.colima/default/colima.yaml" 2>/dev/null; then
                                rosetta=1
                            fi
                            ;;
                    esac
                    if [ -n "$rosetta" ]; then
                        ok "amd64 emulation" "Colima Rosetta is on (SQL Server is amd64 only)"
                    else
                        warn "amd64 emulation" "arm64 engine: SQL Server needs amd64 emulation (colima start --vm-type vz --vz-rosetta)"
                    fi
                    ;;
            esac
        elif [ "$os" = Darwin ] && command -v colima >/dev/null 2>&1; then
            fail "docker engine" "not reachable; colima start (first time: colima start --vm-type vz --vz-rosetta --cpus 4 --memory 8 --disk 60)"
        else
            fail "docker engine" "not reachable; start the Docker daemon"
        fi
    fi

    if [ "$os" = Darwin ]; then
        if command -v colima >/dev/null 2>&1; then
            ok colima "$(colima version 2>/dev/null | head -n 1)"
        else
            warn colima "not installed; any Docker engine works, Colima is the tested one"
        fi
    fi

    if skip airflow "Airflow venv"; then
        :
    elif [ -x "$AIRFLOW_VENV/bin/python" ]; then
        af=$("$AIRFLOW_VENV/bin/python" -c 'import importlib.metadata as m; print(m.version("apache-airflow"))' 2>/dev/null)
        case $af in
            "$AIRFLOW_MAJOR".*) ok "Airflow venv" "apache-airflow $af in $AIRFLOW_VENV" ;;
            "") fail "Airflow venv" "$AIRFLOW_VENV has no apache-airflow; see README.md, Prerequisites" ;;
            *) fail "Airflow venv" "apache-airflow $af in $AIRFLOW_VENV, need $AIRFLOW_MAJOR.x" ;;
        esac
    else
        fail "Airflow venv" "$AIRFLOW_VENV not found; see README.md, Prerequisites (or set AIRFLOW_VENV)"
    fi

    if command -v python3 >/dev/null 2>&1; then
        ok python3 "$(python3 -V 2>&1)"
    else
        fail python3 "not found"
    fi

    if [ "$failures" -gt 0 ]; then
        printf 'toolchain: %s prerequisite(s) missing\n' "$failures"
        return 1
    fi
    printf 'toolchain: OK\n'
}

case ${1:-} in
    java-home) find_java_home ;;
    node-bin) find_node_bin ;;
    check) check ;;
    *)
        printf 'usage: %s java-home|node-bin|check\n' "$0" >&2
        exit 2
        ;;
esac
