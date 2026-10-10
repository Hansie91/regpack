#!/usr/bin/env bash
# Smoke run of the regpack distribution as a systemd service (plan P0; spec 5.2 and 14.5).
# Usage: sudo env "REGPACK_JAVA_HOME=/path/to/jdk-25" bash packaging/systemd/smoke.sh <path to regpack-*-dist.tar.gz> --destructive
# The JDK is passed under its own name through env, after sudo. A plain JAVA_HOME does not survive sudo on
# Ubuntu: PAM re-reads /etc/environment and resets it to the image default (Java 17 on the CI runner).
set -euo pipefail

DIST="${1:?usage: smoke.sh <dist .tar.gz> --destructive}"
PORT="${REGPACK_SMOKE_PORT:-18080}"
APP_HOME=/opt/regpack
UNIT=/etc/systemd/system/regpack.service

[ "$(id -u)" -eq 0 ] || { echo "smoke: run as root"; exit 1; }
[ "$(ps -p 1 -o comm=)" = "systemd" ] || { echo "smoke: PID 1 is not systemd"; exit 1; }
[ "${2:-}" = "--destructive" ] || { echo "smoke: this script deletes ${APP_HOME}, /etc/regpack/regpack.env and ${UNIT}; pass --destructive as the second argument on a disposable host"; exit 1; }
JDK="${REGPACK_JAVA_HOME:?REGPACK_JAVA_HOME must point at a JDK 25 (pass it with: sudo env REGPACK_JAVA_HOME=... bash smoke.sh ...)}"
jdk_version=$("$JDK/bin/java" -version 2>&1) || { echo "smoke: cannot run $JDK/bin/java"; exit 1; }
jdk_first_line=$(printf '%s\n' "$jdk_version" | head -n 1)
case "$jdk_first_line" in
  *'version "25'*) ;;
  *) echo "smoke: REGPACK_JAVA_HOME=$JDK is not a Java 25 runtime: $jdk_first_line"; exit 1 ;;
esac
# sudo resets JAVA_HOME (see above), so the script's own foreground commands, such as the launcher's
# smoke run below, must be given the verified JDK too.
export JAVA_HOME="$JDK"

diagnose() {
  # Printed only when the smoke fails, so a red CI run carries its own evidence.
  echo "=== smoke diagnostics ==="
  echo "-- REGPACK_JAVA_HOME=${JDK}"
  ls -ld "$JDK" "$JDK/bin/java" 2>&1 || true
  "$JDK/bin/java" -version 2>&1 || true
  echo "-- default java on PATH: $(readlink -f "$(command -v java)" 2>&1 || true)"
  echo "-- /etc/regpack/regpack.env:"; cat /etc/regpack/regpack.env 2>&1 || true
  echo "-- unit properties:"
  systemctl show regpack -p Environment -p EnvironmentFiles -p ExecMainStatus -p Result 2>&1 || true
  echo "-- the same sandbox run by hand:"
  systemd-run --quiet --wait --collect --pipe -p DynamicUser=yes -p ProtectSystem=strict -p ProtectHome=yes \
    -p EnvironmentFile=/etc/regpack/regpack.env /bin/sh -c 'echo "JAVA_HOME=$JAVA_HOME"; "$JAVA_HOME/bin/java" -version' 2>&1 || true
}

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
printf 'JAVA_HOME=%s\nREGPACK_HTTP_BIND=127.0.0.1\nREGPACK_HTTP_PORT=%s\n' "$JDK" "$PORT" >> /etc/regpack/regpack.env
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
[ "$ready" = yes ] || { journalctl -u regpack --no-pager | tail -50; diagnose; echo "smoke: not ready after 60 s"; exit 1; }

# 3. the toy pipeline from the installed build, plus the health assertions
"$APP_HOME/bin/regpack" smoke --health-url "http://127.0.0.1:${PORT}"

# 4. stop and assert a clean stop
systemctl stop regpack
result=$(systemctl show regpack -p Result --value)
[ "$result" = "success" ] || { journalctl -u regpack --no-pager | tail -50; echo "smoke: unit Result=$result"; exit 1; }
marker=no
for _ in $(seq 1 20); do
  # journald is asynchronous: give the last line up to 10 s to become readable.
  if journalctl -u regpack --no-pager | grep -q 'regpack lifecycle: stopped cleanly'; then marker=yes; break; fi
  sleep 0.5
done
[ "$marker" = yes ] || { journalctl -u regpack --no-pager | tail -50; echo "smoke: no clean-stop marker"; exit 1; }
echo "smoke: linux systemd OK"
