#!/usr/bin/env bash
# Usage: scripts/it.sh <module> <failsafe -Dit.test selector> [extra maven args]
set -euo pipefail
cd "$(dirname "$0")/.."
MVN="${REGPACK_MVN:-./mvnw}"
# -Dspotless.check.skip=true: freshly hand-written code is not formatted yet; the full verify and CI still enforce the check
exec "$MVN" -B -ntp -pl "$1" -am verify -Dspotless.check.skip=true "-Dit.test=$2" -Dfailsafe.failIfNoSpecifiedTests=false \
  -Dtest=NoUnitTestsInThisRun -Dsurefire.failIfNoSpecifiedTests=false "${@:3}"
