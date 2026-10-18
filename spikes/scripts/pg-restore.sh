#!/usr/bin/env bash
# Usage: spikes/scripts/pg-restore.sh /mnt/spike-pg/backup /mnt/spike-pg/archive
# The timed part of a restore point (RF13): the primary is stopped, its data directory removed and
# the page cache dropped BEFORE the timer; INSIDE the timer the base backup is copied in, ownership
# is restored, the copy is made durable with sync, the recovery configuration is written, the
# container is started and recovery runs to its end (WAL is fetched from the archive). Prints
#   cache_dropped=<0|1>   1 when the page cache was dropped (0: the point is INCONCLUSIVE)
#   copy_nanos=<n>        copy, ownership change and sync (it contains sync_nanos)
#   sync_nanos=<n>        the sync alone
#   replay_nanos=<n>      from the end of the sync to the end of recovery (start-up and WAL replay)
#   restore_seconds=<n>   the whole timed region
# The container sp-primary is stopped and started again, not removed: it keeps the exact settings
# and mounts that pg-up.sh gave it. It touches only /mnt/spike-pg and that container. The runner
# guard below is the first command that matters: on any other machine the script stops there.
set -euo pipefail
if [ "${GITHUB_ACTIONS:-}" != "true" ] || [ "${RUNNER_ENVIRONMENT:-}" != "github-hosted" ]; then echo "refusing: runs only on a GitHub-hosted runner" >&2; exit 1; fi

readonly ROOT=/mnt/spike-pg

BACKUP="$(realpath -m "${1:?usage: pg-restore.sh /mnt/spike-pg/backup /mnt/spike-pg/archive}")"
ARCHIVE="$(realpath -m "${2:?usage: pg-restore.sh /mnt/spike-pg/backup /mnt/spike-pg/archive}")"
[ "$BACKUP" = "$ROOT/backup" ] && [ "$ARCHIVE" = "$ROOT/archive" ] || { echo "refusing: only $ROOT/backup and $ROOT/archive are accepted" >&2; exit 1; }
sudo test -f "$ROOT/backup/PG_VERSION" || { echo "refusing: no base backup at $ROOT/backup" >&2; exit 1; }

# before the timer: stop the primary, remove its data directory, drop the page cache
docker stop -t 300 sp-primary
sudo rm -rf "$ROOT/primary/18/docker"
sudo install -d -o 999 -g 999 -m 700 "$ROOT/primary/18/docker"
CACHE_DROPPED=0
sync
if echo 3 | sudo tee /proc/sys/vm/drop_caches > /dev/null; then CACHE_DROPPED=1; fi
echo "cache_dropped=$CACHE_DROPPED"

# inside the timer
TIMER_START=$(date +%s%N)
sudo cp -a "$ROOT/backup/." "$ROOT/primary/18/docker/"
sudo chown -R 999:999 "$ROOT/primary/18/docker"
SYNC_START=$(date +%s%N)
sync
SYNC_STOP=$(date +%s%N)
echo "restore_command = 'cp /archive/%f \"%p\"'" | sudo tee -a "$ROOT/primary/18/docker/postgresql.auto.conf" > /dev/null
sudo touch "$ROOT/primary/18/docker/recovery.signal"
sudo chown 999:999 "$ROOT/primary/18/docker/recovery.signal"
docker start sp-primary > /dev/null
READY=0
for i in $(seq 1 14400); do
  if [ "$(docker inspect -f '{{.State.Running}}' sp-primary)" != "true" ]; then
    echo "sp-primary stopped during recovery" >&2
    docker logs --tail 50 sp-primary >&2
    exit 1
  fi
  # a hot standby accepts connections during recovery, so the end of recovery is the signal, not pg_isready alone
  if [ "$(docker exec sp-primary psql -h 127.0.0.1 -p 5432 -U postgres -Atqc "SELECT pg_is_in_recovery()" 2> /dev/null || true)" = "f" ] && docker exec sp-primary pg_isready -h 127.0.0.1 -p 5432 > /dev/null 2>&1; then
    READY=1
    break
  fi
  sleep 0.5
done
TIMER_STOP=$(date +%s%N)

if [ "$READY" != "1" ]; then
  echo "timeout after 2 hours: recovery did not end" >&2
  docker logs --tail 50 sp-primary >&2
  exit 1
fi
echo "copy_nanos=$((SYNC_STOP - TIMER_START))"
echo "sync_nanos=$((SYNC_STOP - SYNC_START))"
echo "replay_nanos=$((TIMER_STOP - SYNC_STOP))"
echo "restore_seconds=$(((TIMER_STOP - TIMER_START) / 1000000000))"
