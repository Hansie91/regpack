#!/usr/bin/env bash
# Usage: spikes/scripts/pg-drop-caches.sh
# Flushes the dirty pages and drops the page cache, dentries and inodes of the runner, so that the
# next read comes from the disk (the cold-cache reading of the rebuild and the restore). Prints
# cache_dropped=1 when it worked and cache_dropped=0 when sudo or the write failed: a point that
# reports 0 is INCONCLUSIVE, never PASS. It touches nothing but the kernel cache. The runner guard
# below is the first command that matters: on any other machine the script stops there.
set -euo pipefail
if [ "${GITHUB_ACTIONS:-}" != "true" ] || [ "${RUNNER_ENVIRONMENT:-}" != "github-hosted" ]; then echo "refusing: runs only on a GitHub-hosted runner" >&2; exit 1; fi

CACHE_DROPPED=0
sync
if echo 3 | sudo tee /proc/sys/vm/drop_caches > /dev/null; then CACHE_DROPPED=1; fi
echo "cache_dropped=$CACHE_DROPPED"
