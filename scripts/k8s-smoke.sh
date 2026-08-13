#!/usr/bin/env bash
#
# Proves the Kubernetes manifests actually play a game.
#
# Loads the five images into the cluster, applies k8s/, waits for every
# Deployment to become available, and plays a full game through the gateway. A
# non-zero exit means the manifests are broken. Everything is deleted on every
# exit path unless KEEP_UP=true.
#
#   ./scripts/k8s-smoke.sh              # load, apply, play, delete
#   KEEP_UP=true ./scripts/k8s-smoke.sh # leave it up to poke at by hand
#   BUILD=always ./scripts/k8s-smoke.sh # rebuild the images first
#
# It reaches the gateway through `kubectl port-forward`, not through the Ingress,
# for one reason: `minikube tunnel` needs sudo, and a verification script that
# prompts for a password is a verification script nobody runs. The Ingress path —
# and with it the SSE-through-nginx behaviour — is therefore a manual check,
# documented in the README and in .claude/plans/milestone-11-kubernetes.md.
#
set -euo pipefail

NAMESPACE="${NAMESPACE:-tik-tak-toe}"
# 18080 rather than 8080 so a compose stack or an IDE run already holding the
# usual port does not make this script fail for the wrong reason.
PORT="${PORT:-18080}"
BASE="http://localhost:$PORT"
KEEP_UP="${KEEP_UP:-false}"
# auto: build only if an image is missing. always: rebuild first. never: fail if
# anything is missing rather than spending ten minutes on a build.
BUILD="${BUILD:-auto}"
ROLLOUT_TIMEOUT="${ROLLOUT_TIMEOUT:-300s}"

SERVICES=(eureka-server game-engine-service game-session-service ui-service gateway)

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# shellcheck source=lib/game-smoke.sh
. "$ROOT_DIR/scripts/lib/game-smoke.sh"

PF_PID=""

dump_logs() {
  echo "--- pods"
  kubectl -n "$NAMESPACE" get pods -o wide || true
  echo "--- recent events"
  kubectl -n "$NAMESPACE" get events --sort-by=.lastTimestamp | tail -20 || true
  echo "--- last 50 log lines per service"
  for svc in "${SERVICES[@]}"; do
    echo "=== $svc"
    kubectl -n "$NAMESPACE" logs "deploy/$svc" --tail 50 || true
  done
}

cleanup() {
  local exit_code=$?
  # Killed first either way: a backgrounded port-forward outlives this shell and
  # would silently hold the port against the next run.
  [ -n "$PF_PID" ] && kill "$PF_PID" 2>/dev/null || true
  if [ "$KEEP_UP" = "true" ]; then
    echo "--- KEEP_UP=true: leaving the stack running ('kubectl delete -k $ROOT_DIR/k8s/' to remove it)"
    exit "$exit_code"
  fi
  echo "--- kubectl delete -k k8s/"
  # `|| true` on purpose: a teardown that fails must not turn a game that was
  # played successfully into a red run. The verdict comes from the game.
  kubectl delete -k "$ROOT_DIR/k8s/" --ignore-not-found || true
  exit "$exit_code"
}
trap cleanup EXIT
# Without these, an interrupted run still cleans up but exits 0 on SIGINT —
# indistinguishable from a pass to anything reading the exit code.
trap 'exit 130' INT
trap 'exit 143' TERM

for tool in kubectl minikube docker python3 curl; do
  command -v "$tool" >/dev/null || { echo "FAIL: $tool is not on PATH"; exit 1; }
done
kubectl cluster-info >/dev/null 2>&1 || {
  echo "FAIL: no reachable cluster. Start one with 'minikube start'."
  exit 1
}

case "$BUILD" in
  auto | always | never) ;;
  # Without this, a typo silently takes neither branch below and the run tests
  # whatever images happen to be lying around.
  *) echo "FAIL: BUILD must be auto, always or never (got '$BUILD')"; exit 1 ;;
esac

missing=()
for svc in "${SERVICES[@]}"; do
  docker image inspect "tiktaktoe/$svc:dev" >/dev/null 2>&1 || missing+=("$svc")
done

if [ "$BUILD" = "always" ] || { [ "$BUILD" = "auto" ] && [ ${#missing[@]} -gt 0 ]; }; then
  echo "--- docker compose build (a cold build is ~10 minutes; the images are built one at a time)"
  docker compose -f "$ROOT_DIR/docker-compose.yml" build
elif [ ${#missing[@]} -gt 0 ]; then
  echo "FAIL: missing images: ${missing[*]}. Run 'docker compose build' or set BUILD=auto."
  exit 1
fi

# Loaded unconditionally rather than skipped when the name already exists in the
# node: a matching name says nothing about matching content, and silently testing
# yesterday's image is the failure mode this script exists to prevent.
echo "--- minikube image load (five images, ~600 MB each)"
for svc in "${SERVICES[@]}"; do
  echo "    $svc"
  minikube image load "tiktaktoe/$svc:dev"
done

echo "--- kubectl apply -k k8s/"
kubectl apply -k "$ROOT_DIR/k8s/"

echo "--- waiting for every Deployment to become available"
# Deliberately not ordered: nothing sequences these Pods, and if one of them
# needed another to be up first, this loop is where that would show up as a
# timeout rather than as a comment nobody tested.
for svc in "${SERVICES[@]}"; do
  kubectl -n "$NAMESPACE" rollout status "deploy/$svc" --timeout="$ROLLOUT_TIMEOUT" || {
    echo "FAIL: $svc never became available"
    dump_logs
    exit 1
  }
done

echo "--- kubectl port-forward svc/gateway $PORT:8080"
kubectl -n "$NAMESPACE" port-forward svc/gateway "$PORT:8080" >/dev/null 2>&1 &
PF_PID=$!

# port-forward returns before it is listening, so the first request would race it.
for _ in $(seq 1 30); do
  curl -fsS -o /dev/null "$BASE/" 2>/dev/null && break
  sleep 1
done
curl -fsS -o /dev/null "$BASE/" || {
  echo "FAIL: the gateway never answered through port-forward"
  dump_logs
  exit 1
}

play_game "$BASE"
