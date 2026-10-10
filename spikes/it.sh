#!/usr/bin/env bash
# Usage: spikes/it.sh <spike module> <failsafe -Dit.test selector> [extra maven args]
# Refreshes the installed release modules the spikes use first. SPIKES_SKIP_INSTALL=1 skips the refresh.
set -euo pipefail
cd "$(dirname "$0")/.."
MVN="${REGPACK_MVN:-./mvnw}"
if [ -z "${SPIKES_SKIP_INSTALL:-}" ]; then
  "$MVN" -B -ntp -q -pl regpack-bench,regpack-engine,regpack-inmemory -am install -DskipTests -Dspotless.check.skip=true
fi
# -Dspotless.check.skip=true: freshly hand-written code is not formatted yet; the full verify and CI still enforce the check
exec "$MVN" -B -ntp -f spikes/pom.xml -pl "$1" -am verify -Dspotless.check.skip=true "-Dit.test=$2" -Dfailsafe.failIfNoSpecifiedTests=false \
  -Dtest=NoUnitTestsInThisRun -Dsurefire.failIfNoSpecifiedTests=false "${@:3}"
