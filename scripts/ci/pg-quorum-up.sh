#!/usr/bin/env bash
# Usage: bash scripts/ci/pg-quorum-up.sh
# Starts the fixture of the job ledger-durability on a GitHub-hosted runner: a primary (port 5432)
# and one hot standby (port 5433, application_name standby1), each in a container of the
# digest-pinned PostgreSQL 18.6 image on the host network, listening on 127.0.0.1 only, with trust
# authentication and no credential. When the script ends the standby is a quorum synchronous
# standby of the primary (synchronous_standby_names = 'ANY 1 (standby1)'). The job then runs the
# durability tests with the standby up, stops the standby and runs them again.
# It touches only /mnt/regpack-pg and the containers regpack-primary and regpack-standby. Approval
# AP-6 covers exactly the docker verbs pull, run and exec used here (the job adds stop, logs and rm)
# and the two sudo install lines below. The pattern is that of the P1a spike script
# spikes/scripts/pg-up.sh, which this script copies and does not edit. The runner guard is the first
# command that matters: on any other machine the script stops there.
set -euo pipefail
if [ "${GITHUB_ACTIONS:-}" != "true" ] || [ "${RUNNER_ENVIRONMENT:-}" != "github-hosted" ]; then echo "refusing: runs only on a GitHub-hosted runner" >&2; exit 1; fi

IMAGE='postgres:18.6-trixie@sha256:74935e72241653ca55e0414067e6d8763aceb8a810eb51b452253ec3dcfc4336'
cd "$(dirname "$0")/../.."

# psql_at <port> <sql>: runs inside the primary container, over TCP, so one client serves both servers
psql_at() {
  docker exec regpack-primary psql -h 127.0.0.1 -p "$1" -U postgres -Atqc "$2"
}

# wait_tcp <port>: a TCP pg_isready once a second for at most 60 s; the temporary server that the
# entrypoint runs while it initialises listens on the socket only, so it never counts as ready
wait_tcp() {
  local port="$1" i out=""
  for i in $(seq 1 60); do
    if out="$(docker exec regpack-primary pg_isready -h 127.0.0.1 -p "$port" 2>&1)"; then
      return 0
    fi
    sleep 1
  done
  out="${out//$'\n'/ }"
  echo "::error::timeout after 60 s: pg_isready on port $port; last output: ${out//\%/%25}"
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
  out="${out//$'\n'/ }"
  echo "::error::timeout after 60 s waiting for: $what (port $port); last output: ${out//\%/%25}"
  exit 1
}

# 1. the image, by digest (AP-6)
docker pull "$IMAGE"

# 2. data paths: the 18 image keeps the cluster at /var/lib/postgresql/18/docker and declares
# VOLUME /var/lib/postgresql, so each server mounts its directory there, never at .../data
sudo install -d -m 755 /mnt/regpack-pg
sudo install -d -o 999 -g 999 -m 700 /mnt/regpack-pg/primary /mnt/regpack-pg/standby

# 3. the primary; synchronous_standby_names stays unset until step 5
docker run -d --name regpack-primary --network host --shm-size=256m -e POSTGRES_HOST_AUTH_METHOD=trust -v /mnt/regpack-pg/primary:/var/lib/postgresql -v "$PWD/scripts/ci/pg-init-replication.sh:/docker-entrypoint-initdb.d/10-replication.sh:ro" "$IMAGE" -c listen_addresses=127.0.0.1 -c port=5432 -c wal_level=replica -c max_wal_senders=4 -c max_replication_slots=4 -c hot_standby=on
wait_tcp 5432

# 4. the standby: a base backup over trust on 127.0.0.1, then the hot standby on port 5433
docker run --rm --network host --user 999:999 -v /mnt/regpack-pg/standby:/var/lib/postgresql "$IMAGE" pg_basebackup -h 127.0.0.1 -p 5432 -U postgres -D /var/lib/postgresql/18/docker -R -X stream -c fast -C -S standby1_slot -d "host=127.0.0.1 port=5432 user=postgres application_name=standby1"
docker run -d --name regpack-standby --network host --shm-size=256m -v /mnt/regpack-pg/standby:/var/lib/postgresql "$IMAGE" -c listen_addresses=127.0.0.1 -c port=5433 -c hot_standby=on
wait_tcp 5433

# 5. only now make the standby synchronous: set at start-up it would block every commit of the
# entrypoint's temporary server until a standby exists
docker exec regpack-primary psql -h 127.0.0.1 -p 5432 -U postgres -v ON_ERROR_STOP=1 -c "ALTER SYSTEM SET synchronous_standby_names = 'ANY 1 (standby1)'" -c "SELECT pg_reload_conf()"
wait_query 5432 "standby1 quorum and streaming" "SELECT count(*) FROM pg_stat_replication WHERE application_name = 'standby1' AND sync_state = 'quorum' AND state = 'streaming'" 1

psql_at 5432 "SHOW synchronous_standby_names"
echo "pg-quorum-up: ready"
