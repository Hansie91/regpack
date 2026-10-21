#!/usr/bin/env bash
# Runs inside the primary container as an init script of the image (mounted by pg-quorum-up.sh at
# /docker-entrypoint-initdb.d/10-replication.sh, read-only). It has no runner guard on purpose: the
# container has no runner variables, and the script contains no docker, sudo, rm, cp, mount or
# install command. It changes only the pg_hba.conf of the cluster that is being created, because
# the database wildcard "all" in a host line does not cover replication connections. Copied from
# spikes/scripts/pg-init-replication.sh (the P1a spike), whose three commands it keeps unchanged.
set -euo pipefail
# outside the container PGDATA is unset and the append below would go to /pg_hba.conf
: "${PGDATA:?PGDATA is not set: this script runs only inside the postgres container}"
echo "host replication all 127.0.0.1/32 trust" >> "$PGDATA/pg_hba.conf"
