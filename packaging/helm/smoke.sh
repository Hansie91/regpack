#!/usr/bin/env bash
# Kubernetes smoke (plan P0): kind cluster, Helm install, health, toy pipeline, clean stop.
# Usage: packaging/helm/smoke.sh <locally built image, for example regpack:ci> [helm binary] [kind binary]
set -euo pipefail
cd "$(dirname "$0")/../.."
IMAGE="${1:?usage: smoke.sh <image> [helm] [kind]}"
HELM="${2:-helm}"
KIND="${3:-kind}"
NODE_IMAGE='kindest/node:v1.36.4@sha256:099e049362a1526b2db71494e1947aae99bd16290d7c895f2b7ea312e3cbfaed'
CLUSTER=regpack-smoke
NS=regpack
REPO="${IMAGE%%:*}"
TAG="${IMAGE##*:}"

CREATED=0
cleanup() {
  # Only delete a cluster this script created; never touch a developer's own cluster.
  if [ "$CREATED" = 1 ]; then
    "$KIND" delete cluster --name "$CLUSTER" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

existing=$("$KIND" get clusters 2>/dev/null) || { echo "smoke: cannot list kind clusters; refusing to continue" >&2; exit 1; }
if printf '%s\n' "$existing" | grep -qxF "$CLUSTER"; then
  echo "smoke: a kind cluster named $CLUSTER already exists; refusing to touch it. Delete it yourself and rerun." >&2
  exit 1
fi
CREATED=1
"$KIND" create cluster --name "$CLUSTER" --image "$NODE_IMAGE" --wait 180s
"$KIND" load docker-image "$IMAGE" --name "$CLUSTER"
kubectl create namespace "$NS"
"$HELM" install regpack packaging/helm/regpack --namespace "$NS" \
  --set image.repository="$REPO" --set image.tag="$TAG" --wait --timeout 180s
kubectl -n "$NS" rollout status deploy/regpack --timeout=120s

POD=$(kubectl -n "$NS" get pod -l app.kubernetes.io/name=regpack -o jsonpath='{.items[0].metadata.name}')
kubectl -n "$NS" exec "$POD" -- java -jar /opt/regpack/lib/regpack-app.jar smoke --health-url http://127.0.0.1:8080

# Clean stop: SIGTERM to PID 1 is the signal the kubelet sends. The container restarts (restartPolicy Always),
# and the previous container's exit code and log are the evidence.
kubectl -n "$NS" exec "$POD" -- sh -c 'kill -TERM 1'
rc=0
for _ in $(seq 1 60); do
  rc=$(kubectl -n "$NS" get pod "$POD" -o jsonpath='{.status.containerStatuses[0].restartCount}')
  [ "$rc" = 1 ] && break
  sleep 1
done
[ "$rc" = 1 ] || { kubectl -n "$NS" describe pod "$POD"; echo "smoke: container did not restart after SIGTERM"; exit 1; }
code=$(kubectl -n "$NS" get pod "$POD" -o jsonpath='{.status.containerStatuses[0].lastState.terminated.exitCode}')
[ "$code" = 143 ] || { echo "smoke: exit code $code, expected 143"; exit 1; }
kubectl -n "$NS" logs "$POD" --previous | grep -q 'regpack lifecycle: stopped cleanly' \
  || { kubectl -n "$NS" logs "$POD" --previous; echo "smoke: no clean-stop marker"; exit 1; }
echo "smoke: kubernetes OK"
