#!/usr/bin/env bash
# scripts/deploy.sh — pull, build, restart, verify.
#
# Run this every time you push to main. Idempotent and crash-safe:
# build failures abort before touching the running service, so a bad
# commit can't take prod down between `git pull` and `systemctl restart`.
#
# Steps:
#   1. git pull (fail loud on dirty working tree — server should never
#      have local edits; if it does, the operator made a mistake)
#   2. mvn package (skip tests; CI gates tests, redoing on the box wastes
#      ~3 minutes and risks flakiness from missing Docker)
#   3. cd frontend && npm ci && npm run build
#   4. systemctl restart zhitu-agent
#   5. poll /actuator/health until UP or timeout (60s)
#   6. tail recent logs so the operator sees startup state
#
# What this does NOT do:
#   - Run tests (CI's job)
#   - Touch infra/cloud/ middleware (separate `cd infra/cloud && docker
#     compose pull && docker compose up -d` if you need to upgrade)
#   - Touch nginx (re-run install.sh if you edited the template)
#   - Roll back on failure (manual: git reset --hard HEAD~1 && deploy.sh)

set -euo pipefail

# ---- configuration ----------------------------------------------------
APP_DIR="${APP_DIR:-$(cd "$(dirname "$0")/.." && pwd)}"
APP_PORT="${APP_PORT:-8080}"
SERVICE_NAME="${SERVICE_NAME:-zhitu-agent}"
SKIP_GIT="${SKIP_GIT:-false}"             # set true to deploy local changes
SKIP_FRONTEND="${SKIP_FRONTEND:-false}"   # set true for backend-only pushes
HEALTH_TIMEOUT="${HEALTH_TIMEOUT:-60}"    # seconds to wait for /actuator/health

# ---- helpers ----------------------------------------------------------
log()  { printf '\033[1;34m[deploy]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[deploy]\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31m[deploy]\033[0m %s\n' "$*" >&2; exit 1; }

cd "$APP_DIR"

# ---- preflight --------------------------------------------------------
[[ -f .env ]] || die ".env missing at $APP_DIR/.env — populate it first"
command -v java >/dev/null || die "java not on PATH"
command -v mvn >/dev/null  || die "mvn not on PATH"

if [[ "$SKIP_FRONTEND" != "true" ]]; then
    command -v node >/dev/null || die "node not on PATH (set SKIP_FRONTEND=true to bypass)"
    command -v npm >/dev/null  || die "npm not on PATH"
fi

# Capture the pre-deploy SHA so we can show what's changing (and roll
# back via `git reset --hard $PREV_SHA` if something explodes).
PREV_SHA=$(git rev-parse --short HEAD 2>/dev/null || echo "unknown")
log "current HEAD: $PREV_SHA"

# ---- step 1: git pull -------------------------------------------------
if [[ "$SKIP_GIT" != "true" ]]; then
    if ! git diff --quiet HEAD 2>/dev/null; then
        die "working tree dirty — refusing to pull. either commit, stash, or set SKIP_GIT=true"
    fi
    log "pulling latest"
    git pull --ff-only
    NEW_SHA=$(git rev-parse --short HEAD)
    if [[ "$NEW_SHA" == "$PREV_SHA" ]]; then
        log "already at $NEW_SHA — nothing to deploy unless local artefacts are stale"
    else
        log "advancing $PREV_SHA → $NEW_SHA"
        git --no-pager log --oneline "$PREV_SHA..$NEW_SHA" | head -20
    fi
fi

# ---- step 2: frontend build (BEFORE backend) --------------------------
# Vite writes its bundle straight into src/main/resources/static (see
# frontend/vite.config.ts). That directory is then packaged into the
# fat jar by mvn — so the order MUST be npm build → mvn package, or
# you ship yesterday's frontend with today's backend code.
if [[ "$SKIP_FRONTEND" == "true" ]]; then
    log "skipping frontend (SKIP_FRONTEND=true)"
elif [[ ! -d "$APP_DIR/frontend" ]]; then
    warn "frontend/ missing — skipping"
else
    log "building frontend (npm ci + vite build → src/main/resources/static)"
    pushd "$APP_DIR/frontend" >/dev/null
    # ci over install: deterministic, fails on lockfile drift instead of
    # silently mutating it. Matches what CI does.
    npm ci --no-audit --no-fund
    npm run build
    popd >/dev/null
    STATIC_DIR="$APP_DIR/src/main/resources/static"
    if [[ -f "$STATIC_DIR/index.html" ]]; then
        log "frontend bundle in place: $(du -sh "$STATIC_DIR" | cut -f1)"
    else
        die "vite build finished but $STATIC_DIR/index.html missing — check frontend/vite.config.ts outDir"
    fi
fi

# ---- step 3: backend build (jar absorbs the static bundle above) ------
log "building backend (mvn package, tests skipped — CI gates them)"
# -o (offline) is intentional: every dep should already be in ~/.m2 from
# CI or a prior deploy. If not, fall through to online build.
if ! mvn -o -B clean package -DskipTests >/tmp/zhitu-mvn.log 2>&1; then
    warn "offline build failed; retrying online (slower)"
    mvn -B clean package -DskipTests
fi

JAR_PATH=$(ls -t "$APP_DIR"/target/zhitu-agent-java-*.jar 2>/dev/null | head -1 || true)
[[ -n "$JAR_PATH" && -f "$JAR_PATH" ]] || die "no jar produced at target/zhitu-agent-java-*.jar"
log "built: $(basename "$JAR_PATH") ($(du -h "$JAR_PATH" | cut -f1))"

# ---- step 4: restart service ------------------------------------------
log "restarting $SERVICE_NAME"
sudo systemctl restart "$SERVICE_NAME"

# ---- step 5: health check ---------------------------------------------
log "waiting for /actuator/health (timeout ${HEALTH_TIMEOUT}s)"
deadline=$(( $(date +%s) + HEALTH_TIMEOUT ))
healthy=false
while [[ $(date +%s) -lt $deadline ]]; do
    # /actuator/health returns 200 only when all configured indicators
    # (es, redis, kafka if enabled) are UP. For a softer gate use
    # /actuator/health/liveness instead.
    if curl -sf "http://127.0.0.1:$APP_PORT/actuator/health" \
         | grep -q '"status":"UP"'; then
        healthy=true
        break
    fi
    sleep 2
done

if [[ "$healthy" != "true" ]]; then
    warn "service did not report UP within ${HEALTH_TIMEOUT}s"
    warn "recent logs:"
    journalctl -u "$SERVICE_NAME" -n 60 --no-pager || true
    die "deploy aborted — investigate, then either retry or:"
    # (the next line is dead code but documents the rollback recipe
    #  for the operator who reads this script)
    : "git reset --hard $PREV_SHA && scripts/deploy.sh"
fi

# ---- step 6: post-deploy summary --------------------------------------
log "service is UP"
journalctl -u "$SERVICE_NAME" -n 20 --no-pager | sed 's/^/  /'

cat <<EOF

\033[1;32m[deploy] success.\033[0m  HEAD=$(git rev-parse --short HEAD)

verify:
  curl http://127.0.0.1:$APP_PORT/actuator/health
  curl -F file=@docs/m2-smoke-sample.txt http://127.0.0.1:$APP_PORT/api/files/upload
  journalctl -u $SERVICE_NAME -f

rollback (if anything is wrong):
  git reset --hard $PREV_SHA && scripts/deploy.sh SKIP_GIT=true
EOF
