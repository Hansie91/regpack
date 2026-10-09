#!/usr/bin/env bash
# Smoke run of the regpack distribution as a systemd service (plan P0; spec 5.2 and 14.5).
# Usage: sudo --preserve-env=JAVA_HOME bash packaging/systemd/smoke.sh <path to regpack-*-dist.tar.gz> --destructive
set -euo pipefail

DIST="${1:?usage: smoke.sh <dist .tar.gz> --destructive}"
PORT="${REGPACK_SMOKE_PORT:-18080}"
APP_HOME=/opt/regpack
UNIT=/etc/systemd/system/regpack.service

[ "$(id -u)" -eq 0 ] || { echo "smoke: run as root"; exit 1; }
[ "$(ps -p 1 -o comm=)" = "systemd" ] || { echo "smoke: PID 1 is not systemd"; exit 1; }
[ "${2:-}" = "--destructive" ] || { echo "smoke: this script deletes ${APP_HOME}, /etc/regpack/regpack.env and ${UNIT}; pass --destructive as the second argument on a disposable host"; exit 1; }
: "${JAVA_HOME:?JAVA_HOME must point at a JDK 25 (preserve it through sudo)}"

cleanup() {
  systemctl stop regpack >/dev/null 2>&1 || true
  rm -f "$UNIT" /etc/regpack/regpack.env
  systemctl daemon-reload || true
}
trap cleanup EXIT

rm -rf "$APP_HOME"; mkdir -p "$APP_HOME"
tar -xzf "$DIST" -C "$APP_HOME" --strip-components=1 --no-same-owner
chown -R root:root "$APP_HOME"
install -d /etc/regpack
install -m 0600 -o root -g root /dev/null /etc/regpack/regpack.env
printf 'JAVA_HOME=%s\nREGPACK_HTTP_BIND=127.0.0.1\nREGPACK_HTTP_PORT=%s\n' "$JAVA_HOME" "$PORT" >> /etc/regpack/regpack.env
install -m 0644 "$APP_HOME/share/systemd/regpack.service" "$UNIT"

# 1. the unit must verify with no message at all
out=$(systemd-analyze verify "$UNIT" 2>&1) || { echo "$out"; echo "smoke: systemd-analyze verify failed"; exit 1; }
[ -z "$out" ] || { echo "$out"; echo "smoke: systemd-analyze verify printed warnings"; exit 1; }

# 2. start and wait for readiness
systemctl daemon-reload
systemctl start regpack
ready=no
for _ in $(seq 1 60); do
  if curl -fsS "http://127.0.0.1:${PORT}/health/ready" >/dev/null 2>&1; then ready=yes; break; fi
  sleep 1
done
[ "$ready" = yes ] || { journalctl -u regpack --no-pager | tail -50; echo "smoke: not ready after 60 s"; exit 1; }

# 3. the toy pipeline from the installed build, plus the health assertions
"$APP_HOME/bin/regpack" smoke --health-url "http://127.0.0.1:${PORT}"

# 4. stop and assert a clean stop
systemctl stop regpack
result=$(systemctl show regpack -p Result --value)
[ "$result" = "success" ] || { journalctl -u regpack --no-pager | tail -50; echo "smoke: unit Result=$result"; exit 1; }
journalctl -u regpack --no-pager | grep -q 'regpack lifecycle: stopped cleanly' \
  || { journalctl -u regpack --no-pager | tail -50; echo "smoke: no clean-stop marker"; exit 1; }
echo "smoke: linux systemd OK"
