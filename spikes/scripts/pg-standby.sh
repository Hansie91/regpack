#!/usr/bin/env bash
# Usage: spikes/scripts/pg-standby.sh stop|start|drop
#   stop   stops the synchronous standby (sp-standby); the replica keeps running
#   start  starts it again
#   drop   removes the standby and the replica and their data directories to free the disk (the
#          restore runs of L3). The synchronous setting is reset first: with it left in place every
#          commit on the primary would wait for a standby that no longer exists. A run after a
#          drop has no standby, so it never counts for L-1 to L-4 and L-13.
# Touches only /mnt/spike-pg and the containers sp-standby, sp-replica and sp-primary (settings and slots).
set -euo pipefail
if [ "${GITHUB_ACTIONS:-}" != "true" ] || [ "${RUNNER_ENVIRONMENT:-}" != "github-hosted" ]; then echo "refusing: runs only on a GitHub-hosted runner" >&2; exit 1; fi

readonly ROOT=/mnt/spike-pg
ACTION="${1:?usage: pg-standby.sh stop|start|drop}"

case "$ACTION" in
  stop)
    docker stop sp-standby
    ;;
  start)
    docker start sp-standby
    ;;
  drop)
    docker exec sp-primary psql -h 127.0.0.1 -p 5432 -U postgres -c "ALTER SYSTEM RESET synchronous_standby_names" -c "SELECT pg_reload_conf()"
    docker rm -f sp-standby sp-replica
    sudo rm -rf "$ROOT/standby" "$ROOT/replica"
    # the walsenders end with their containers; a slot that is still active is retried for up to 30 s
    for i in $(seq 1 30); do
      docker exec sp-primary psql -h 127.0.0.1 -p 5432 -U postgres -Atqc "SELECT pg_drop_replication_slot(slot_name) FROM pg_replication_slots WHERE NOT active AND slot_name IN ('standby1_slot', 'replica1_slot')" > /dev/null
      LEFT="$(docker exec sp-primary psql -h 127.0.0.1 -p 5432 -U postgres -Atqc "SELECT count(*) FROM pg_replication_slots WHERE slot_name IN ('standby1_slot', 'replica1_slot')")"
      if [ "$LEFT" = "0" ]; then break; fi
      sleep 1
    done
    if [ "$LEFT" != "0" ]; then
      echo "replication slots still present after 30 s" >&2
      exit 1
    fi
    ;;
  *)
    echo "unknown action: $ACTION" >&2
    exit 2
    ;;
esac
