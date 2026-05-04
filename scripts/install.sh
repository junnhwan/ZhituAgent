#!/usr/bin/env bash
# scripts/install.sh — one-time server bootstrap for zhitu-agent-java.
#
# Idempotent: re-run safely after editing the unit/nginx templates or
# bumping APP_USER. Splits cleanly from deploy.sh so day-to-day pushes
# never touch /etc/.
#
# What this does:
#   1. Validate prereqs (java 21, mvn, node 20, nginx, systemctl)
#   2. Create service user + log dir
#   3. Materialize systemd unit from template (substitute APP_DIR, APP_USER)
#      and symlink into /etc/systemd/system/
#   4. Materialize nginx site from template (substitute SERVER_NAME,
#      APP_DIR, APP_PORT) and symlink into /etc/nginx/sites-enabled/
#   5. systemctl daemon-reload + enable (does NOT start — first start
#      should happen via deploy.sh after a successful build)
#   6. nginx -t + reload
#
# What this does NOT do:
#   - Pull code (git clone is your responsibility, this script lives in
#     the repo so you've already got it)
#   - Build artefacts (deploy.sh's job)
#   - Configure TLS (run certbot --nginx -d $SERVER_NAME after this)
#   - Touch infra/cloud/ middleware compose (separate lifecycle)

set -euo pipefail

# ---- configuration (edit before first run) ----------------------------
APP_USER="${APP_USER:-zhitu}"
APP_DIR="${APP_DIR:-$(cd "$(dirname "$0")/.." && pwd)}"   # repo root
APP_PORT="${APP_PORT:-8080}"
SERVER_NAME="${SERVER_NAME:-_}"   # nginx default_server catch-all; set to your domain
LOG_DIR="${LOG_DIR:-/var/log/zhitu-agent}"
# Set SKIP_NGINX=true to deploy backend-only (Spring Boot exposed
# directly on $APP_PORT). Useful for early demos before TLS is set up,
# or when the box has its own reverse proxy upstream. Re-running with
# SKIP_NGINX=false later will install the nginx site cleanly without
# touching systemd.
SKIP_NGINX="${SKIP_NGINX:-false}"
# Set SKIP_FRONTEND_CHECK=true if you also pass SKIP_FRONTEND=true to
# deploy.sh — install.sh otherwise insists node/npm exist so a future
# `scripts/deploy.sh` won't surprise-fail mid-build.
SKIP_FRONTEND_CHECK="${SKIP_FRONTEND_CHECK:-$SKIP_NGINX}"

# ---- helpers ----------------------------------------------------------
log()  { printf '\033[1;34m[install]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[install]\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31m[install]\033[0m %s\n' "$*" >&2; exit 1; }

require_root() {
    if [[ $EUID -ne 0 ]]; then
        die "must run as root (sudo $0)"
    fi
}

require_cmd() {
    command -v "$1" >/dev/null 2>&1 || die "missing required command: $1"
}

# ---- prereq checks ----------------------------------------------------
require_root

log "checking prerequisites"
require_cmd java
require_cmd mvn
require_cmd systemctl
if [[ "$SKIP_FRONTEND_CHECK" != "true" ]]; then
    require_cmd node
    require_cmd npm
else
    log "skipping node/npm check (SKIP_FRONTEND_CHECK=true)"
fi
if [[ "$SKIP_NGINX" != "true" ]]; then
    require_cmd nginx
else
    log "skipping nginx check (SKIP_NGINX=true) — backend will serve on :$APP_PORT directly"
fi

java_version=$(java -version 2>&1 | head -1 | awk -F\" '{print $2}' | cut -d. -f1)
[[ "$java_version" -ge 21 ]] || die "java >= 21 required, found $java_version"

node_version=$(node -v | sed 's/v//' | cut -d. -f1)
[[ "$node_version" -ge 20 ]] || warn "node >= 20 recommended, found $node_version"

# ---- service user -----------------------------------------------------
if id "$APP_USER" >/dev/null 2>&1; then
    log "user $APP_USER already exists"
else
    log "creating service user $APP_USER"
    useradd --system --no-create-home --shell /usr/sbin/nologin "$APP_USER"
fi

# ---- directories ------------------------------------------------------
log "preparing directories"
mkdir -p "$LOG_DIR" "$APP_DIR/logs"
chown -R "$APP_USER:$APP_USER" "$LOG_DIR" "$APP_DIR/logs"
# App user needs read access to repo (built jars + frontend/dist) but
# never write — keep deploy as a privileged user, app as unprivileged.
# We don't chown the whole repo to APP_USER on purpose.
chmod 755 "$APP_DIR"

# ---- .env sanity check ------------------------------------------------
if [[ ! -f "$APP_DIR/.env" ]]; then
    warn ".env missing at $APP_DIR/.env — service will fail to start"
    warn "copy from .env.example or your password manager before running deploy.sh"
fi

# ---- systemd unit -----------------------------------------------------
UNIT_TEMPLATE="$APP_DIR/infra/systemd/zhitu-agent.service"
UNIT_RENDERED="$APP_DIR/infra/systemd/zhitu-agent.rendered.service"
UNIT_TARGET="/etc/systemd/system/zhitu-agent.service"

[[ -f "$UNIT_TEMPLATE" ]] || die "unit template missing: $UNIT_TEMPLATE"

log "rendering systemd unit"
sed \
    -e "s|__APP_DIR__|$APP_DIR|g" \
    -e "s|__APP_USER__|$APP_USER|g" \
    "$UNIT_TEMPLATE" > "$UNIT_RENDERED"

# Replace any prior install (could be a regular file from earlier hand
# install, or a stale symlink pointing at a moved repo).
log "installing unit at $UNIT_TARGET"
ln -sf "$UNIT_RENDERED" "$UNIT_TARGET"

systemctl daemon-reload
systemctl enable zhitu-agent.service
log "systemd unit enabled (not started — run scripts/deploy.sh first)"

# ---- nginx site -------------------------------------------------------
if [[ "$SKIP_NGINX" == "true" ]]; then
    log "skipping nginx site install (SKIP_NGINX=true)"
    log "Spring Boot will be reachable directly at http://<host>:$APP_PORT"
    log "to add nginx later: apt install nginx, then re-run this script"
else
    NGINX_TEMPLATE="$APP_DIR/infra/nginx/zhitu-agent.conf"
    NGINX_RENDERED="$APP_DIR/infra/nginx/zhitu-agent.rendered.conf"
    NGINX_AVAILABLE="/etc/nginx/sites-available/zhitu-agent.conf"
    NGINX_ENABLED="/etc/nginx/sites-enabled/zhitu-agent.conf"

    [[ -f "$NGINX_TEMPLATE" ]] || die "nginx template missing: $NGINX_TEMPLATE"

    log "rendering nginx site (server_name=$SERVER_NAME, app_port=$APP_PORT)"
    sed \
        -e "s|__APP_DIR__|$APP_DIR|g" \
        -e "s|__APP_PORT__|$APP_PORT|g" \
        -e "s|__SERVER_NAME__|$SERVER_NAME|g" \
        "$NGINX_TEMPLATE" > "$NGINX_RENDERED"

    # Some distros use conf.d, others sites-available — handle both.
    if [[ -d /etc/nginx/sites-available && -d /etc/nginx/sites-enabled ]]; then
        ln -sf "$NGINX_RENDERED" "$NGINX_AVAILABLE"
        ln -sf "$NGINX_AVAILABLE" "$NGINX_ENABLED"
        log "installed at $NGINX_ENABLED"
    else
        ln -sf "$NGINX_RENDERED" /etc/nginx/conf.d/zhitu-agent.conf
        log "installed at /etc/nginx/conf.d/zhitu-agent.conf"
    fi

    log "validating nginx config"
    nginx -t

    log "reloading nginx"
    systemctl reload nginx
fi

# ---- summary ----------------------------------------------------------
if [[ "$SKIP_NGINX" == "true" ]]; then
    ACCESS_URL="http://<host>:$APP_PORT (Spring Boot direct, no reverse proxy)"
    NGINX_LINE="nginx:  (skipped — backend on :$APP_PORT directly)"
else
    ACCESS_URL="http://$SERVER_NAME or via certbot for HTTPS"
    NGINX_LINE="nginx:  /etc/nginx/sites-enabled/zhitu-agent.conf"
fi

cat <<EOF

\033[1;32m[install] done.\033[0m

next steps:
  1. ensure $APP_DIR/.env is populated (LLM keys, ES password, etc.)
  2. run scripts/deploy.sh to build + start the service
     (pass SKIP_FRONTEND=true if you skipped nginx — no point building
      a frontend that has nowhere to be served)
  3. access: $ACCESS_URL
  4. tail logs:
       journalctl -u zhitu-agent -f
       tail -f $LOG_DIR/app.log

paths:
  unit:   $UNIT_TARGET -> $UNIT_RENDERED
  $NGINX_LINE
  logs:   $LOG_DIR
  user:   $APP_USER
EOF
