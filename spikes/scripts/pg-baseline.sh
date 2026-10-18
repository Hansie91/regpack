#!/usr/bin/env bash
# Usage: spikes/scripts/pg-baseline.sh
# Runs pg_test_fsync (5 seconds per test) from the image, inside the primary container, against a
# scratch file on the volume of the clusters (/mnt/spike-pg/primary through the mount at
# /var/lib/postgresql), and prints its output: the fsync baseline of the fixture that the SP-L report
# prints beside the L-2 and L-11 results. It removes the scratch file. It touches only that file and
# the container sp-primary. The runner guard below is the first command that matters: on any other
# machine the script stops there.
set -euo pipefail
if [ "${GITHUB_ACTIONS:-}" != "true" ] || [ "${RUNNER_ENVIRONMENT:-}" != "github-hosted" ]; then echo "refusing: runs only on a GitHub-hosted runner" >&2; exit 1; fi

STATUS=0
docker exec sp-primary pg_test_fsync -s 5 -f /var/lib/postgresql/pg_test_fsync.tmp || STATUS=$?
docker exec sp-primary rm -f /var/lib/postgresql/pg_test_fsync.tmp
exit "$STATUS"
