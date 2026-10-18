#!/usr/bin/env bash
# Usage: spikes/scripts/it-pg.sh <ingest|restore|misc>
# Runs the SP-L integration tests of one part against the fixture that pg-up.sh started and copies
# their failsafe reports into results/sp-l-<part>/it/ (the smoke dispatch only; the reports travel
# in the sealed artefact). The release modules the spike uses must be installed already.
set -euo pipefail
if [ "${GITHUB_ACTIONS:-}" != "true" ] || [ "${RUNNER_ENVIRONMENT:-}" != "github-hosted" ]; then echo "refusing: runs only on a GitHub-hosted runner" >&2; exit 1; fi

cd "$(dirname "$0")/../.."
PART="${1:?usage: it-pg.sh <ingest|restore|misc>}"
case "$PART" in
  ingest) TESTS="PgFixtureIT,BurstRunIT,CommitPathIT" ;;
  misc) TESTS="ConstraintChecksIT,RowSizeIT,PartitionIT" ;;
  restore) TESTS="RestoreIT,RebuildIT" ;;
  *) echo "unknown part: $PART" >&2; exit 2 ;;
esac

RC=0
./mvnw -B -ntp -f spikes/pom.xml -pl spike-l verify -Dspotless.check.skip=true -Dspike.pg.url='jdbc:postgresql://127.0.0.1:5432/postgres?user=postgres' "-Dit.test=$TESTS" -Dtest=NoUnitTestsInThisRun -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false || RC=$?

mkdir -p "results/sp-l-$PART/it/"
if [ -d spikes/spike-l/target/failsafe-reports ]; then
  cp -r spikes/spike-l/target/failsafe-reports/. "results/sp-l-$PART/it/"
fi
exit "$RC"
