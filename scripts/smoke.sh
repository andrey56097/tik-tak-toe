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
set -euo pipefail

BASE="${BASE:-http://localhost:8080}"
KEEP_UP="${KEEP_UP:-false}"
# A game is 5-9 moves one second apart, plus the engine round trips. The default
# budget is comfortably above that and still fails fast if the stack is wedged.
POLL_ATTEMPTS="${POLL_ATTEMPTS:-60}"
POLL_INTERVAL="${POLL_INTERVAL:-2}"
# A cold run builds five images, and they are built one at a time (see
# docker/Dockerfile on why the Gradle cache mount is locked).
UP_TIMEOUT="${UP_TIMEOUT:-900}"

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE=(docker compose -f "$ROOT_DIR/docker-compose.yml")

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

# Reads one value out of a JSON document on stdin. Kept to a fixed set of names
# so no shell quoting ever reaches Python.
json() {
  python3 - "$1" <<'PY'
import json, sys

doc = json.load(sys.stdin)
field = sys.argv[1]
if field == "sessionId":
    print(doc["sessionId"])
elif field == "status":
    print(doc["status"])
elif field == "summary":
    game = doc.get("gameState") or {}
    print(f"{game.get('status')} winner={game.get('winner')} moves={len(doc.get('moveHistory', []))}")
else:
    raise SystemExit(f"unknown field: {field}")
PY
}

echo "--- docker compose up (a cold run builds five images; expect ~10 minutes)"
"${COMPOSE[@]}" up --build --detach --wait --wait-timeout "$UP_TIMEOUT"

echo "--- POST $BASE/sessions"
SESSION_ID="$(curl -fsS -X POST "$BASE/sessions" | json sessionId)"
echo "sessionId=$SESSION_ID"

echo "--- POST $BASE/sessions/$SESSION_ID/simulate"
curl -fsS -X POST "$BASE/sessions/$SESSION_ID/simulate" -o /dev/null -w 'accepted: HTTP %{http_code}\n'

echo "--- waiting for a terminal session status"
STATUS=""
for _ in $(seq 1 "$POLL_ATTEMPTS"); do
  BODY="$(curl -fsS "$BASE/sessions/$SESSION_ID")"
  STATUS="$(printf '%s' "$BODY" | json status)"
  case "$STATUS" in
    COMPLETED)
      echo "PASS: session COMPLETED — $(printf '%s' "$BODY" | json summary)"
      exit 0
      ;;
    FAILED)
      echo "FAIL: the session ended in FAILED"
      printf '%s\n' "$BODY"
      echo "--- last 50 log lines per service"
      "${COMPOSE[@]}" logs --tail 50
      exit 1
      ;;
  esac
  sleep "$POLL_INTERVAL"
done

echo "FAIL: no terminal status within $((POLL_ATTEMPTS * POLL_INTERVAL))s (last seen: ${STATUS:-none})"
"${COMPOSE[@]}" logs --tail 50
exit 1
