#!/usr/bin/env bash
# Usage: spikes/t.sh <spike module> <surefire -Dtest selector> [extra maven args]
# Refreshes the installed release modules the spikes use first (a stale install is the failure mode).
# SPIKES_SKIP_INSTALL=1 skips the refresh.
set -euo pipefail
cd "$(dirname "$0")/.."
MVN="${REGPACK_MVN:-./mvnw}"
if [ -z "${SPIKES_SKIP_INSTALL:-}" ]; then
  "$MVN" -B -ntp -q -pl regpack-bench,regpack-engine,regpack-inmemory -am install -DskipTests -Dspotless.check.skip=true
fi
exec "$MVN" -B -ntp -f spikes/pom.xml -pl "$1" -am test "-Dtest=$2" -Dsurefire.failIfNoSpecifiedTests=false "${@:3}"
