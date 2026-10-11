#!/usr/bin/env bash
# Usage: spikes/scripts/pg-basebackup.sh /mnt/spike-pg/backup
# Takes a plain-format base backup (the conservative reading: uncompressed) of the running primary
# into /mnt/spike-pg/backup, with the WAL streamed into it, then flushes it to the disk so that the
# restore reads it cold. The directory is emptied first because pg_basebackup refuses a directory
# that is not empty. It touches only /mnt/spike-pg/backup and one short-lived container. The runner
# guard below is the first command that matters: on any other machine the script stops there.
set -euo pipefail
if [ "${GITHUB_ACTIONS:-}" != "true" ] || [ "${RUNNER_ENVIRONMENT:-}" != "github-hosted" ]; then echo "refusing: runs only on a GitHub-hosted runner" >&2; exit 1; fi

readonly ROOT=/mnt/spike-pg
IMAGE='postgres:18.6-trixie@sha256:74935e72241653ca55e0414067e6d8763aceb8a810eb51b452253ec3dcfc4336'

BACKUP="$(realpath -m "${1:?usage: pg-basebackup.sh /mnt/spike-pg/backup}")"
[ "$BACKUP" = "$ROOT/backup" ] || { echo "refusing: the backup directory is $ROOT/backup, not $BACKUP" >&2; exit 1; }

sudo rm -rf "$ROOT/backup"
sudo install -d -o 999 -g 999 -m 700 "$ROOT/backup"
docker run --rm --network host --user 999:999 -v "$ROOT/backup:/backup" "$IMAGE" pg_basebackup -h 127.0.0.1 -p 5432 -U postgres -D /backup -X stream -c fast
sync
sudo du -sb "$ROOT/backup"
df -P /mnt/spike-pg
echo "pg-basebackup: done"
