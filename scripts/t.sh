#!/usr/bin/env bash
# Usage: scripts/t.sh <module> <surefire -Dtest selector> [extra maven args]
set -euo pipefail
cd "$(dirname "$0")/.."
MVN="${REGPACK_MVN:-./mvnw}"
exec "$MVN" -B -ntp -pl "$1" -am test "-Dtest=$2" -Dsurefire.failIfNoSpecifiedTests=false "${@:3}"
