#!/usr/bin/env bash
# Usage: spikes/scripts/pg-down.sh <directory under results/>
# Saves the gzipped docker logs of the three servers into <directory>/pg/ and removes the containers.
# Touches only the containers sp-primary, sp-standby and sp-replica and a directory under the
# repository results/ directory. Data directories stay (pg-restart.sh wipes them).
set -euo pipefail
if [ "${GITHUB_ACTIONS:-}" != "true" ] || [ "${RUNNER_ENVIRONMENT:-}" != "github-hosted" ]; then echo "refusing: runs only on a GitHub-hosted runner" >&2; exit 1; fi

REPO="$(cd "$(dirname "$0")/../.." && pwd)"
RESOLVED="$(realpath -m "${1:?usage: pg-down.sh <directory under results/>}")"
case "$RESOLVED" in
  "$REPO"/results/*) ;;
  *) echo "refusing: $RESOLVED is not under $REPO/results/" >&2; exit 1 ;;
esac
mkdir -p "$RESOLVED/pg"

for name in sp-primary sp-standby sp-replica; do
  PRESENT="$(docker ps -a --format '{{.Names}}' | grep -x "$name" || true)"
  if [ -n "$PRESENT" ]; then
    docker logs "$name" 2>&1 | gzip > "$RESOLVED/pg/$name.log.gz"
    docker rm -f "$name"
  fi
done
echo "pg-down: logs in $RESOLVED/pg"
