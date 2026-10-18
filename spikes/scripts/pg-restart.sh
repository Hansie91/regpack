#!/usr/bin/env bash
# Usage: spikes/scripts/pg-restart.sh <mode> [--memory=<size>] [--shared-buffers=<size>]
# A down without logs, a wipe of every data directory of the fixture and an up (pg-up.sh), so that
# each measured run starts on a freshly initialised cluster. Touches only /mnt/spike-pg and the
# containers sp-primary, sp-standby and sp-replica.
set -euo pipefail
if [ "${GITHUB_ACTIONS:-}" != "true" ] || [ "${RUNNER_ENVIRONMENT:-}" != "github-hosted" ]; then echo "refusing: runs only on a GitHub-hosted runner" >&2; exit 1; fi

readonly ROOT=/mnt/spike-pg
HERE="$(cd "$(dirname "$0")" && pwd)"
: "${1:?usage: pg-restart.sh <mode> [--memory=<size>] [--shared-buffers=<size>]}"

for name in sp-primary sp-standby sp-replica; do
  docker rm -f "$name" > /dev/null 2>&1 || true
done
for role in primary standby replica archive backup; do
  sudo rm -rf "$ROOT/$role"
done
exec "$HERE/pg-up.sh" "$@"
