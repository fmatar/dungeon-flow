#!/usr/bin/env bash
#
# Build Dungeon Flow into a single container image and run it locally.
#
# The whole game ships as ONE image: Quarkus serves the SvelteKit UI at / and the
# game API under /api. That means the UI has to be built and copied into the jar's
# static resources BEFORE Maven packages it - and because that copy target is
# gitignored, forgetting it produces a working API with a blank UI and no error to
# explain why. This script exists so that cannot happen.
#
# Usage:
#   scripts/run-local.sh                 build + run, auto-pick a free port
#   scripts/run-local.sh --port 9000     pin the host port
#   scripts/run-local.sh --push          also push the image to GHCR
#   scripts/run-local.sh --no-run        build the image only
#   scripts/run-local.sh --with-tests    run the Maven test suite too (slower)
#   scripts/run-local.sh --stop          stop a previously started stack
#   scripts/run-local.sh --help
#
# Native (GraalVM) builds — a tiny, fast-booting image instead of the JVM one:
#   scripts/run-local.sh --native                          host arch, ~1 min
#   scripts/run-local.sh --native --platform linux/amd64   what DataRobot needs
#
# Native does NOT cross-compile: the binary targets the build architecture. The
# build always runs inside a Linux builder container (no local GraalVM needed),
# so on an ARM Mac plain --native gives you linux/arm64 — perfect for testing
# the native path locally, but it CANNOT run on DataRobot. For the real artifact
# add --platform linux/amd64, which forces emulation and is much slower.
#
# Native images are tagged native-<arch> so they never clobber the JVM :latest.
#
# Re-exec under a real bash. Invoking this as `sh scripts/run-local.sh` bypasses the
# shebang above, and the bash-only features used below (process substitution, [[ ]],
# arrays) are then syntax errors. The first one sits inside a function, so it fails
# partway through with a bare "syntax error near unexpected token" instead of up front.
#
# Testing for BASH_VERSION is NOT sufficient: macOS /bin/sh *is* bash in POSIX mode, so
# it sets BASH_VERSION while still rejecting process substitution. Hence a sentinel -
# re-exec unconditionally, exactly once.
#
# Must stay ABOVE `set -o pipefail` (not POSIX) and use only POSIX syntax itself.
# Shells parse one command at a time, so exec-ing here means sh never parses the rest.
if [ -z "${RUN_LOCAL_REEXECED:-}" ]; then
    if command -v bash >/dev/null 2>&1; then
        RUN_LOCAL_REEXECED=1
        export RUN_LOCAL_REEXECED
        exec bash "$0" "$@"
    fi
    echo "error: this script needs bash, which was not found on PATH." >&2
    exit 1
fi

set -euo pipefail

# --- pretty output ----------------------------------------------------------
if [[ -t 1 ]]; then
    BOLD=$'\033[1m'; RED=$'\033[31m'; GRN=$'\033[32m'; YLW=$'\033[33m'; DIM=$'\033[2m'; RST=$'\033[0m'
else
    BOLD=""; RED=""; GRN=""; YLW=""; DIM=""; RST=""
fi
step()  { printf '\n%s==>%s %s%s%s\n' "$GRN" "$RST" "$BOLD" "$*" "$RST"; }
info()  { printf '    %s\n' "$*"; }
warn()  { printf '%s[warn]%s %s\n' "$YLW" "$RST" "$*" >&2; }
die()   { printf '\n%s[fail]%s %s\n' "$RED" "$RST" "$*" >&2; exit 1; }

# --- repo root (works from any cwd, follows symlinks) -----------------------
SOURCE=${BASH_SOURCE[0]}
while [[ -L $SOURCE ]]; do
    DIR=$(cd -P "$(dirname "$SOURCE")" && pwd)
    SOURCE=$(readlink "$SOURCE")
    [[ $SOURCE != /* ]] && SOURCE=$DIR/$SOURCE
done
SCRIPT_DIR=$(cd -P "$(dirname "$SOURCE")" && pwd)
REPO_ROOT=$(cd -P "$SCRIPT_DIR/.." && pwd)
cd "$REPO_ROOT"

WEB_DIR=$REPO_ROOT/web
WEB_BUILD=$WEB_DIR/build
STATIC_DIR=$REPO_ROOT/src/main/resources/META-INF/resources
COMPOSE_SERVICE=app
CONTAINER_PORT=8080

# --- args -------------------------------------------------------------------
HOST_PORT=""; DO_RUN=1; DO_PUSH=0; WITH_TESTS=0; DO_STOP=0; DO_NATIVE=0; PLATFORM=""
while [[ $# -gt 0 ]]; do
    case $1 in
        --port) HOST_PORT=${2:-}; [[ -n $HOST_PORT ]] || die "--port needs a value"; shift 2 ;;
        --port=*) HOST_PORT=${1#*=}; shift ;;
        --push) DO_PUSH=1; shift ;;
        --no-run) DO_RUN=0; shift ;;
        --with-tests) WITH_TESTS=1; shift ;;
        --stop) DO_STOP=1; shift ;;
        --native) DO_NATIVE=1; shift ;;
        --platform) PLATFORM=${2:-}; [[ -n $PLATFORM ]] || die "--platform needs a value"; shift 2 ;;
        --platform=*) PLATFORM=${1#*=}; shift ;;
        # Print the header comment block: everything after the shebang up to the
        # first non-comment line. Beats a hardcoded line range, which silently
        # rots the moment the header changes length.
        -h|--help) awk 'NR==1{next} /^#/{sub(/^# ?/,""); print; next} {exit}' "$SOURCE"; exit 0 ;;
        *) die "unknown option: $1 (try --help)" ;;
    esac
done

if [[ -n $HOST_PORT && ! $HOST_PORT =~ ^[0-9]+$ ]]; then
    die "--port must be numeric, got '$HOST_PORT'"
fi

if [[ -n $PLATFORM ]]; then
    [[ $DO_NATIVE -eq 1 ]] || die "--platform only applies to --native.
    The JVM image is built multi-arch on push and needs no platform flag."
    case $PLATFORM in
        linux/amd64|linux/arm64) ;;
        *) die "--platform must be linux/amd64 or linux/arm64, got '$PLATFORM'" ;;
    esac
fi

# Host architecture in Docker's vocabulary, used to name the image and to warn when
# a native build will be emulated.
case "$(uname -m)" in
    arm64|aarch64) HOST_PLATFORM=linux/arm64 ;;
    x86_64|amd64)  HOST_PLATFORM=linux/amd64 ;;
    *)             HOST_PLATFORM="linux/$(uname -m)" ;;
esac
: "${PLATFORM:=$HOST_PLATFORM}"

# ===========================================================================
# --stop: tear down and exit
# ===========================================================================
if [[ $DO_STOP -eq 1 ]]; then
    step "Stopping the stack"
    docker compose down --remove-orphans
    info "stopped."
    exit 0
fi

# ===========================================================================
# 0. Preflight - fail with an actionable message, never half-way through
# ===========================================================================
step "Checking prerequisites"

need() { command -v "$1" >/dev/null 2>&1 || die "$1 not found on PATH. $2"; }
need node "Install Node 20+ (https://nodejs.org)."
need npm  "Install Node 20+, which bundles npm."
need mvn  "Install Maven, or use ./mvnw if the wrapper is present."
need docker "Install Docker Desktop (https://docker.com)."

docker info >/dev/null 2>&1 || die "the Docker daemon is not responding. Start Docker Desktop and retry."
docker compose version >/dev/null 2>&1 || die "'docker compose' (v2) is unavailable. Update Docker Desktop."
[[ -f $REPO_ROOT/pom.xml ]] || die "pom.xml not found - is $REPO_ROOT really the repo root?"
[[ -f $REPO_ROOT/docker-compose.yaml ]] || die "docker-compose.yaml not found in $REPO_ROOT."
[[ -d $WEB_DIR ]] || die "web/ not found - the UI sources are missing."
info "node $(node -v), npm $(npm -v), docker $(docker version --format '{{.Server.Version}}' 2>/dev/null || echo '?')"

# --- the JDK trap: the pom demands a specific release ----------------------
# maven.compiler.release is 25 in this project. A lower JDK fails deep inside the
# build with "release version 25 not supported", which reads like a Maven bug.
REQUIRED_JDK=$(sed -n 's/.*<maven\.compiler\.release>\([0-9]*\)<\/maven\.compiler\.release>.*/\1/p' "$REPO_ROOT/pom.xml" | head -1)
: "${REQUIRED_JDK:=25}"

jdk_major() {
    # Prints the major version of the JDK at $1 (a JAVA_HOME), or nothing.
    local home=$1
    [[ -x $home/bin/java ]] || return 0
    "$home/bin/java" -version 2>&1 | sed -n '1s/.*version "\([0-9]*\).*/\1/p'
}

current_java_home() {
    if [[ -n ${JAVA_HOME:-} ]]; then printf '%s' "$JAVA_HOME"; return; fi
    local jbin; jbin=$(command -v java 2>/dev/null) || return 0
    # resolve <home>/bin/java -> <home>
    printf '%s' "$(cd -P "$(dirname "$(dirname "$jbin")")" && pwd)"
}

pick_jdk() {
    # Emit a JAVA_HOME satisfying >= REQUIRED_JDK, or nothing.
    local home major
    home=$(current_java_home)
    if [[ -n $home ]]; then
        major=$(jdk_major "$home")
        if [[ -n $major && $major -ge $REQUIRED_JDK ]]; then printf '%s' "$home"; return; fi
    fi
    # macOS
    if command -v /usr/libexec/java_home >/dev/null 2>&1; then
        home=$(/usr/libexec/java_home -v "$REQUIRED_JDK" 2>/dev/null || true)
        [[ -n $home ]] && { printf '%s' "$home"; return; }
    fi
    # SDKMAN, newest matching first
    if [[ -d $HOME/.sdkman/candidates/java ]]; then
        local c
        while IFS= read -r c; do
            major=$(jdk_major "$c")
            [[ -n $major && $major -ge $REQUIRED_JDK ]] && { printf '%s' "$c"; return; }
        done < <(find "$HOME/.sdkman/candidates/java" -maxdepth 1 -mindepth 1 -type d | sort -rV)
    fi
}

JAVA_HOME_PICKED=$(pick_jdk || true)
if [[ -z $JAVA_HOME_PICKED ]]; then
    die "this project needs JDK ${REQUIRED_JDK}+ but none was found.
    Current: $(current_java_home || echo none) (major $(jdk_major "$(current_java_home)" 2>/dev/null || echo '?'))
    Install one, e.g.:  sdk install java ${REQUIRED_JDK}.0.4-tem"
fi
export JAVA_HOME=$JAVA_HOME_PICKED
info "JDK $(jdk_major "$JAVA_HOME") at $JAVA_HOME ${DIM}(required: ${REQUIRED_JDK}+)${RST}"

# ===========================================================================
# 1. Build the UI
# ===========================================================================
step "Building the SvelteKit UI"
if [[ -f $WEB_DIR/package-lock.json ]]; then
    # npm ci is reproducible but fails if the lockfile drifts from package.json;
    # fall back rather than dying, since a stale lock is not worth blocking on.
    (cd "$WEB_DIR" && npm ci --no-audit --no-fund) || {
        warn "npm ci failed (lockfile likely out of sync) - falling back to npm install"
        (cd "$WEB_DIR" && npm install --no-audit --no-fund)
    }
else
    (cd "$WEB_DIR" && npm install --no-audit --no-fund)
fi
(cd "$WEB_DIR" && npm run build)

[[ -f $WEB_BUILD/index.html ]] || die "the UI build produced no $WEB_BUILD/index.html.
    Check the 'npm run build' output above."
info "built $(find "$WEB_BUILD" -type f | wc -l | tr -d ' ') files into web/build"

# ===========================================================================
# 2. Copy the UI into the jar's static resources
# ===========================================================================
step "Copying the UI into src/main/resources/META-INF/resources"
# Wipe first: asset filenames are content-hashed, so a plain copy would leave
# every previous build's orphaned JS/CSS behind and grow the image forever.
# Guard the path so a mangled variable can never rm something else.
case $STATIC_DIR in
    "$REPO_ROOT"/src/main/resources/META-INF/resources) rm -rf "$STATIC_DIR" ;;
    *) die "refusing to delete unexpected path: $STATIC_DIR" ;;
esac
mkdir -p "$STATIC_DIR"
cp -R "$WEB_BUILD/." "$STATIC_DIR/"

[[ -f $STATIC_DIR/index.html ]] || die "copy failed - $STATIC_DIR/index.html is missing."
info "staged $(find "$STATIC_DIR" -type f | wc -l | tr -d ' ') files"

# ===========================================================================
# 3. Build (and optionally push) the image
# ===========================================================================
MVN_ARGS=(-B clean package -Dquarkus.container-image.build=true)
if [[ $WITH_TESTS -eq 1 ]]; then
    step "Building the image (running tests)"
else
    step "Building the image (skipping tests)"
    MVN_ARGS+=(-DskipTests)
fi

NATIVE_TAG=""
if [[ $DO_NATIVE -eq 1 ]]; then
    # Tagged per-arch so a native build never overwrites the JVM :latest that
    # docker-compose and the deployed workload spec both reference by default.
    NATIVE_TAG="native-${PLATFORM##*/}"
    # -DskipITs because the `native` profile flips skipITs to false; there are no
    # *IT classes today, so leave that switch to a deliberate choice.
    MVN_ARGS+=(-Dnative -DskipITs
               -Dquarkus.native.container-build=true
               -Dquarkus.container-image.tag="$NATIVE_TAG")
    info "native build inside a Linux builder container (no local GraalVM needed)"
    info "target: $PLATFORM  ->  image tag :$NATIVE_TAG"
    if [[ $PLATFORM != "$HOST_PLATFORM" ]]; then
        # GraalVM does not cross-compile; the only way to get a foreign-arch binary
        # is to emulate the whole builder container.
        MVN_ARGS+=(-Dquarkus.native.container-runtime-options=--platform="$PLATFORM")
        warn "$PLATFORM != host $HOST_PLATFORM, so native-image runs EMULATED."
        warn "Expect many minutes, and an OOM here means the builder needs more"
        warn "memory: add -Dquarkus.native.native-image-xmx=6g (and raise Docker's RAM)."
    fi
fi

if [[ $DO_PUSH -eq 1 ]]; then
    MVN_ARGS+=(-Dquarkus.container-image.push=true)
    if [[ $DO_NATIVE -eq 1 ]]; then
        # A native image contains one architecture's binary; there is no multi-arch
        # manifest to assemble the way the JVM image gets one.
        info "push enabled: single-arch $PLATFORM -> ghcr.io :$NATIVE_TAG"
    else
        info "push enabled: multi-arch linux/amd64,linux/arm64 -> ghcr.io"
        info "${DIM}amd64 is required by the DataRobot Workload API; arm64 keeps it native on Apple Silicon${RST}"
        MVN_ARGS+=(-Dquarkus.docker.buildx.platform=linux/amd64,linux/arm64)
    fi
fi
mvn "${MVN_ARGS[@]}"

# Resolve the image name from the properties rather than hardcoding it.
prop() { sed -n "s/^$1=//p" "$REPO_ROOT/src/main/resources/application.properties" | tail -1; }
IMG_REG=$(prop 'quarkus\.container-image\.registry')
IMG_GRP=$(prop 'quarkus\.container-image\.group')
IMG_NAME=$(prop 'quarkus\.container-image\.name')
IMG_TAG=$(prop 'quarkus\.container-image\.tag')
[[ -n $NATIVE_TAG ]] && IMG_TAG=$NATIVE_TAG
IMAGE="${IMG_REG:+$IMG_REG/}${IMG_GRP:+$IMG_GRP/}${IMG_NAME:-dungeon-flow}:${IMG_TAG:-latest}"

if [[ $DO_PUSH -eq 1 ]]; then
    # A multi-arch buildx build pushes straight to the registry without ever
    # loading into the local daemon, so a local lookup would wrongly fail here.
    info "pushed $IMAGE"
else
    docker image inspect "$IMAGE" >/dev/null 2>&1 \
        || die "expected image '$IMAGE' was not created. Check the Maven output above."
    info "built $IMAGE"
fi

if [[ $DO_RUN -eq 0 ]]; then
    step "Done (--no-run)"
    if [[ -n $NATIVE_TAG ]]; then
        info "start it later with: DUNGEON_IMAGE=$IMAGE docker compose up"
    else
        info "start it later with: docker compose up"
    fi
    exit 0
fi

# ===========================================================================
# 4. Pick a host port
# ===========================================================================
step "Choosing a host port"
port_busy() {
    local p=$1
    if command -v lsof >/dev/null 2>&1; then
        lsof -nP -iTCP:"$p" -sTCP:LISTEN >/dev/null 2>&1 && return 0
    fi
    # Fallback: bash's /dev/tcp. A successful connect means something is there.
    (exec 3<>"/dev/tcp/127.0.0.1/$p") >/dev/null 2>&1 && { exec 3<&- 3>&-; return 0; }
    return 1
}

if [[ -n $HOST_PORT ]]; then
    if port_busy "$HOST_PORT"; then
        die "port $HOST_PORT is already in use. Free it, or pick another with --port."
    fi
else
    HOST_PORT=""
    for candidate in 8080 8090 8091 8092 9080 9090; do
        if ! port_busy "$candidate"; then HOST_PORT=$candidate; break; fi
    done
    [[ -n $HOST_PORT ]] || die "no free port found among 8080/8090-8092/9080/9090. Use --port."
    [[ $HOST_PORT != 8080 ]] && warn "8080 is busy (often 'mvn quarkus:dev') - using $HOST_PORT instead"
fi
info "host port: $HOST_PORT"

if ! grep -q 'DUNGEON_HOST_PORT' "$REPO_ROOT/docker-compose.yaml"; then
    warn "docker-compose.yaml does not read \$DUNGEON_HOST_PORT, so the port above may be ignored."
fi

# ===========================================================================
# 5. Run it
# ===========================================================================
step "Starting the container"
export DUNGEON_HOST_PORT=$HOST_PORT
# Tell compose which image to run: the JVM :latest by default, or the native-<arch>
# tag just built. Compose defaults this if unset, so `--stop` needs nothing.
export DUNGEON_IMAGE=$IMAGE
info "image: $IMAGE"
if [[ $DO_NATIVE -eq 1 && $PLATFORM != "$HOST_PLATFORM" ]]; then
    warn "this is a $PLATFORM image on a $HOST_PLATFORM host - Docker will emulate it,"
    warn "so local timings will NOT reflect how it performs on DataRobot."
fi
docker compose down --remove-orphans >/dev/null 2>&1 || true
docker compose up -d

# Ask Docker where it actually published, rather than trusting our own variable.
MAPPED=$(docker compose port "$COMPOSE_SERVICE" "$CONTAINER_PORT" 2>/dev/null || true)
if [[ -n $MAPPED ]]; then
    EFFECTIVE_PORT=${MAPPED##*:}
    [[ $EFFECTIVE_PORT != "$HOST_PORT" ]] && warn "compose published $EFFECTIVE_PORT, not $HOST_PORT"
else
    EFFECTIVE_PORT=$HOST_PORT
fi

# Use 127.0.0.1, NOT localhost. On macOS 'localhost' resolves to ::1 first, and if
# anything else (a Vite dev server, say) holds the IPv6 side of the same port you
# will silently probe the wrong process.
BASE="http://127.0.0.1:$EFFECTIVE_PORT"

step "Waiting for the app to come up"
DEADLINE=$((SECONDS + 120))
READY=0
while [[ $SECONDS -lt $DEADLINE ]]; do
    if ! docker compose ps --status running --format '{{.Service}}' 2>/dev/null | grep -q "^$COMPOSE_SERVICE$"; then
        printf '\n'; docker compose logs --tail 40 "$COMPOSE_SERVICE" || true
        die "the container exited. Logs are above."
    fi
    ui=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/" 2>/dev/null || echo 000)
    api=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/dungeon" 2>/dev/null || echo 000)
    if [[ $ui == 200 && $api == 200 ]]; then READY=1; break; fi
    printf '.'
    sleep 2
done
printf '\n'
if [[ $READY -eq 0 ]]; then
    docker compose logs --tail 40 "$COMPOSE_SERVICE" || true
    die "app did not become ready within 120s (UI=$ui API=$api). Logs are above."
fi
info "UI and API both responding"

# ===========================================================================
# 6. Smoke test - prove the workflow engine really runs, not just that it serves
# ===========================================================================
step "Smoke test: playing a full game"
extract() { sed -n "s/.*\"$1\":\"\([^\"]*\)\".*/\1/p"; }

ID=$(curl -s -X POST "$BASE/api/dungeon" | extract instanceId)
[[ -n $ID ]] || die "could not start a game - POST /api/dungeon returned no instanceId."
info "instance $ID"

curl -s -X POST "$BASE/api/dungeon/$ID/choice" \
     -H 'Content-Type: application/json' -d '{"direction":"left"}' -o /dev/null
curl -s -X POST "$BASE/api/dungeon/$ID/lever-a" -o /dev/null
curl -s -X POST "$BASE/api/dungeon/$ID/lever-b" -o /dev/null

# The Trap Corridor's lock is random by default, so a run can legitimately be
# thrown back to the fork before it wins. Treat any live progression as success
# and only fail if the instance breaks or never moves.
ROOM=""; STATUS=""
DEADLINE=$((SECONDS + 45))
while [[ $SECONDS -lt $DEADLINE ]]; do
    BODY=$(curl -s "$BASE/api/dungeon/$ID" || true)
    ROOM=$(printf '%s' "$BODY" | extract room)
    STATUS=$(printf '%s' "$BODY" | extract status)
    case $STATUS in
        COMPLETED) break ;;
        FAULTED)   die "the workflow FAULTED during the smoke test (room=$ROOM)." ;;
    esac
    [[ $ROOM == TREASURE_ROOM ]] && break
    sleep 2
done

if [[ $STATUS == COMPLETED || $ROOM == TREASURE_ROOM ]]; then
    info "${GRN}victory${RST} - reached $ROOM (status $STATUS)"
elif [[ -n $ROOM ]]; then
    # e.g. the lock jammed three times and the trap respawned the player.
    info "engine is live - instance sits at $ROOM (status $STATUS); the lock is random by design"
else
    die "could not read the instance state back. The API is up but not behaving."
fi

# ===========================================================================
# 7. Measured footprint - the numbers worth quoting, not estimates
# ===========================================================================
step "Measured footprint"
CID=$(docker compose ps -q "$COMPOSE_SERVICE" 2>/dev/null | head -1)
STARTUP=$(docker compose logs "$COMPOSE_SERVICE" 2>/dev/null \
    | sed -n 's/.*started in \([0-9.]*\)s.*/\1/p' | tail -1)
MEM=$(docker stats --no-stream --format '{{.MemUsage}}' "$CID" 2>/dev/null | awk '{print $1}')
IMG_SIZE=$(docker images --format '{{.Size}}' "$IMAGE" 2>/dev/null | head -1)
MODE=$([[ $DO_NATIVE -eq 1 ]] && echo "native ($PLATFORM)" || echo "JVM")
printf '    %-14s %s\n' "mode:"    "$MODE"
printf '    %-14s %s\n' "image:"   "${IMG_SIZE:-?}"
printf '    %-14s %s\n' "startup:" "${STARTUP:-?}s"
printf '    %-14s %s\n' "memory:"  "${MEM:-?}"
if [[ $DO_NATIVE -eq 0 ]]; then
    info "${DIM}compare against: scripts/run-local.sh --native${RST}"
fi

# ===========================================================================
# Done
# ===========================================================================
printf '\n%s%s Dungeon Flow is running%s\n' "$BOLD" "$GRN" "$RST"
printf '   Play:  %shttp://localhost:%s%s\n' "$BOLD" "$EFFECTIVE_PORT" "$RST"
printf '   Race:  http://localhost:%s/race\n' "$EFFECTIVE_PORT"
printf '   API:   http://localhost:%s/api/dungeon\n' "$EFFECTIVE_PORT"
printf '\n   %sLogs:%s  docker compose logs -f\n' "$DIM" "$RST"
printf '   %sStop:%s  scripts/run-local.sh --stop\n' "$DIM" "$RST"
printf '\n   %sNote: this is a production image, so there is no Quarkus Dev UI.%s\n' "$DIM" "$RST"
printf '   %sFor the workflow diagram at /q/dev-ui, run: mvn quarkus:dev%s\n\n' "$DIM" "$RST"
