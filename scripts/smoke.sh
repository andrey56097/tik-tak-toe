#!/usr/bin/env bash
#
# Proves the containerised stack actually plays a game.
#
# Brings the compose stack up, creates a session, starts the simulation and waits
# for a terminal state — everything through the single published port, exactly as
# the browser does. A non-zero exit means the stack is broken. The stack is torn
# down on every exit path unless KEEP_UP=true.
#
#   ./scripts/smoke.sh              # up, play, down
#   KEEP_UP=true ./scripts/smoke.sh # leave it running to poke at :8080 by hand
#
# The game itself lives in scripts/lib/game-smoke.sh, shared with k8s-smoke.sh:
# a game through the gateway is the same game whichever way the stack was
# deployed, and that is exactly what the two scripts exist to demonstrate.
#
set -euo pipefail

BASE="${BASE:-http://localhost:8080}"
KEEP_UP="${KEEP_UP:-false}"
# A cold run builds five images, and they are built one at a time (see
# docker/Dockerfile on why the Gradle cache mount is locked).
UP_TIMEOUT="${UP_TIMEOUT:-900}"

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE=(docker compose -f "$ROOT_DIR/docker-compose.yml")

# shellcheck source=lib/game-smoke.sh
. "$ROOT_DIR/scripts/lib/game-smoke.sh"

dump_logs() {
  echo "--- last 50 log lines per service"
  "${COMPOSE[@]}" logs --tail 50
}

cleanup() {
  local exit_code=$?
  if [ "$KEEP_UP" = "true" ]; then
    echo "--- KEEP_UP=true: leaving the stack running ('${COMPOSE[*]} down' to stop it)"
    exit "$exit_code"
  fi
  echo "--- docker compose down"
  # `|| true` on purpose: a teardown that fails must not turn a game that was
  # played successfully into a red run. The verdict comes from the game.
  "${COMPOSE[@]}" down --remove-orphans || true
  exit "$exit_code"
}
trap cleanup EXIT
# Without these, an interrupted run still cleans up but exits 0 on SIGINT —
# indistinguishable from a pass to anything reading the exit code.
trap 'exit 130' INT
trap 'exit 143' TERM

echo "--- docker compose up (a cold run builds five images; expect ~10 minutes)"
"${COMPOSE[@]}" up --build --detach --wait --wait-timeout "$UP_TIMEOUT"

play_game "$BASE"
