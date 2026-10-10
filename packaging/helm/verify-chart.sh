#!/usr/bin/env bash
# Static assertions on the rendered chart. Usage: packaging/helm/verify-chart.sh [helm binary]
set -euo pipefail
cd "$(dirname "$0")/../.."
HELM="${1:-helm}"
CHART=packaging/helm/regpack
"$HELM" lint "$CHART"
rendered=$("$HELM" template regpack "$CHART")
need() { printf '%s\n' "$rendered" | grep -Eq "$1" || { echo "verify-chart: missing: $1"; exit 1; }; }
need '^[[:space:]]+replicas: 1$'
need '^[[:space:]]+type: Recreate$'
need 'path: /health/live'
need 'path: /health/ready'
need 'startupProbe:'
need 'livenessProbe:'
need 'readinessProbe:'
need 'readOnlyRootFilesystem: true'
need 'runAsNonRoot: true'
need 'allowPrivilegeEscalation: false'
need 'automountServiceAccountToken: false'
for bad in 0 2 3; do
  if "$HELM" template regpack "$CHART" --set "replicaCount=$bad" >/dev/null 2>&1; then
    echo "verify-chart: replicaCount=$bad must be rejected by values.schema.json"; exit 1
  fi
done
echo "verify-chart: OK"
