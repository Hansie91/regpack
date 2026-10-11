#!/usr/bin/env bash
# Usage: spikes/scripts/pg-up.sh <mode> [--memory=<size>] [--shared-buffers=<size>]
#   mode: verify | sp-l-ingest | sp-l-restore | sp-l-misc
# Starts the SP-L fixture on a GitHub-hosted runner: the primary (5432), a quorum synchronous
# standby (5433, application_name standby1) and an asynchronous replica (5434, replica1), each in a
# container of the pinned PostgreSQL 18.6 image on the host network. Approval A-1 covers the pull.
# It touches only /mnt/spike-pg and the containers sp-primary, sp-standby and sp-replica. The runner
# guard below is the first command that matters: on any other machine the script stops there.
set -euo pipefail
if [ "${GITHUB_ACTIONS:-}" != "true" ] || [ "${RUNNER_ENVIRONMENT:-}" != "github-hosted" ]; then echo "refusing: runs only on a GitHub-hosted runner" >&2; exit 1; fi

readonly ROOT=/mnt/spike-pg
IMAGE='postgres:18.6-trixie@sha256:74935e72241653ca55e0414067e6d8763aceb8a810eb51b452253ec3dcfc4336'
cd "$(dirname "$0")/../.."

MODE="${1:?mode is required: verify, sp-l-ingest, sp-l-restore or sp-l-misc}"
shift
MEMORY_ARGS=()
SHARED_BUFFERS=""
for arg in "$@"; do
  case "$arg" in
    --memory=*) MEMORY_ARGS=("--memory=${arg#--memory=}") ;;
    --shared-buffers=*) SHARED_BUFFERS="${arg#--shared-buffers=}" ;;
    *) echo "unknown option: $arg" >&2; exit 2 ;;
  esac
done

case "$MODE" in
  verify) DEFAULT_SHARED_BUFFERS=256MB ;;
  sp-l-ingest|sp-l-restore|sp-l-misc) DEFAULT_SHARED_BUFFERS=2GB ;;
  *) echo "unknown mode: $MODE" >&2; exit 2 ;;
esac
if [ -z "$SHARED_BUFFERS" ]; then SHARED_BUFFERS="$DEFAULT_SHARED_BUFFERS"; fi

# Spec 5.3 recovery configuration: WAL archiving is part of the SP-L measurements, not of verify.
ARCHIVE_ARGS=()
case "$MODE" in
  sp-l-ingest|sp-l-restore|sp-l-misc)
    ARCHIVE_ARGS=(-c archive_mode=on -c "archive_command=test ! -f /archive/%f && cp %p /archive/%f")
    ;;
esac

# psql_at <port> <sql>: runs inside the primary container, over TCP, so one client serves all three servers
psql_at() {
  docker exec sp-primary psql -h 127.0.0.1 -p "$1" -U postgres -Atqc "$2"
}

# wait_tcp <port>: a TCP pg_isready once a second for at most 60 s; the temporary server that the
# entrypoint runs while it initialises listens on the socket only, so it never counts as ready
wait_tcp() {
  local port="$1" i out=""
  for i in $(seq 1 60); do
    if out="$(docker exec sp-primary pg_isready -h 127.0.0.1 -p "$port" 2>&1)"; then
      return 0
    fi
    sleep 1
  done
  echo "timeout after 60 s: pg_isready on port $port; last output: $out" >&2
  exit 1
}

# wait_query <port> <what> <sql> <expected>: polls the query once a second for at most 60 s until its output equals <expected>
wait_query() {
  local port="$1" what="$2" sql="$3" expected="$4" i out=""
  for i in $(seq 1 60); do
    if out="$(psql_at "$port" "$sql" 2>&1)" && [ "$out" = "$expected" ]; then
      return 0
    fi
    sleep 1
  done
  echo "timeout after 60 s waiting for: $what (port $port); last output: $out" >&2
  exit 1
}

EXISTING="$(docker ps -a --format '{{.Names}}' | grep '^sp-' || true)"
if [ -n "$EXISTING" ]; then
  echo "refusing: containers of an earlier fixture exist (use pg-restart.sh): $EXISTING" >&2
  exit 1
fi

# 1. the image, by digest (approval A-1)
docker pull "$IMAGE"

# 2. data paths (RF12): the 18 image keeps the cluster at /var/lib/postgresql/18/docker and declares
# VOLUME /var/lib/postgresql, so each server mounts its directory there, never at .../data
sudo install -d -m 755 "$ROOT"
for role in primary standby replica archive backup; do
  sudo install -d -o 999 -g 999 -m 700 "$ROOT/$role"
done
# the DISK canary of L4 writes its scratch file here, on the same volume as the clusters; pg-restart.sh
# wipes only the five role directories, so the directory survives a restart
sudo install -d -m 777 "$ROOT/scratch"

AVAIL_BYTES="$(df -B1 --output=avail /mnt/spike-pg | tail -n 1 | tr -d ' ')"
if [ "$AVAIL_BYTES" -lt $((40 * 1024 * 1024 * 1024)) ]; then MAX_WAL_SIZE=2GB; else MAX_WAL_SIZE=8GB; fi

PG_SETTINGS=(-c listen_addresses=127.0.0.1 -c port=5432 -c wal_level=replica -c max_wal_senders=10 -c max_replication_slots=10 -c synchronous_commit=on -c fsync=on -c full_page_writes=on -c "shared_buffers=$SHARED_BUFFERS" -c "max_wal_size=$MAX_WAL_SIZE" -c checkpoint_timeout=15min -c track_io_timing=on -c track_wal_io_timing=on -c hot_standby=on)

# 3. the primary
docker run -d --shm-size=1g --name sp-primary --network host -e POSTGRES_HOST_AUTH_METHOD=trust -v "$ROOT/primary:/var/lib/postgresql" -v "$ROOT/archive:/archive" -v "$PWD/spikes/scripts/pg-init-replication.sh:/docker-entrypoint-initdb.d/10-replication.sh:ro" "${MEMORY_ARGS[@]}" "$IMAGE" "${PG_SETTINGS[@]}" "${ARCHIVE_ARGS[@]}"
wait_tcp 5432

# 4. the standby (quorum synchronous once step 5 has run) and the asynchronous replica
docker run --rm --network host --user 999:999 -v "$ROOT/standby:/var/lib/postgresql" "$IMAGE" pg_basebackup -h 127.0.0.1 -p 5432 -U postgres -D /var/lib/postgresql/18/docker -R -X stream -C -S standby1_slot -d "host=127.0.0.1 port=5432 user=postgres application_name=standby1"
docker run -d --shm-size=1g --name sp-standby --network host -v "$ROOT/standby:/var/lib/postgresql" "$IMAGE" -c listen_addresses=127.0.0.1 -c port=5433 -c hot_standby=on
wait_tcp 5433
docker run --rm --network host --user 999:999 -v "$ROOT/replica:/var/lib/postgresql" "$IMAGE" pg_basebackup -h 127.0.0.1 -p 5432 -U postgres -D /var/lib/postgresql/18/docker -R -X stream -C -S replica1_slot -d "host=127.0.0.1 port=5432 user=postgres application_name=replica1"
docker run -d --shm-size=1g --name sp-replica --network host -v "$ROOT/replica:/var/lib/postgresql" "$IMAGE" -c listen_addresses=127.0.0.1 -c port=5434 -c hot_standby=on
wait_tcp 5434

wait_query 5432 "standby1 and replica1 streaming" "SELECT count(*) FROM pg_stat_replication WHERE state = 'streaming' AND application_name IN ('standby1', 'replica1')" 2
wait_query 5433 "standby1 in recovery" "SELECT pg_is_in_recovery()" t
wait_query 5434 "replica1 in recovery" "SELECT pg_is_in_recovery()" t

# 5. only now make the standby synchronous: set at start-up it would block every commit of the
# entrypoint's temporary server until a standby exists
docker exec sp-primary psql -h 127.0.0.1 -p 5432 -U postgres -c "ALTER SYSTEM SET synchronous_standby_names = 'ANY 1 (standby1)'" -c "SELECT pg_reload_conf()"
wait_query 5432 "standby1 quorum and streaming" "SELECT count(*) FROM pg_stat_replication WHERE application_name = 'standby1' AND sync_state = 'quorum' AND state = 'streaming'" 1

# hard checks: exit 1 on any failure
DATA_DIRECTORY="$(psql_at 5432 "SHOW data_directory")"
if [ "$DATA_DIRECTORY" != "/var/lib/postgresql/18/docker" ]; then
  echo "data_directory is $DATA_DIRECTORY, expected /var/lib/postgresql/18/docker (RF12)" >&2
  exit 1
fi
if ! sudo test -f "$ROOT/primary/18/docker/PG_VERSION"; then
  echo "no PG_VERSION at $ROOT/primary/18/docker: the cluster is not on the spike disk (RF12)" >&2
  exit 1
fi
echo "data_directory $DATA_DIRECTORY"
psql_at 5432 "SELECT application_name, state, sync_state FROM pg_stat_replication ORDER BY application_name"
psql_at 5432 "SHOW server_version"
psql_at 5432 "SHOW ALL"
df -P /mnt/spike-pg
echo "pg-up: mode $MODE ready"
