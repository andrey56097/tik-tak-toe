# shellcheck shell=bash
#
# The half of a smoke test that is about the *system* rather than about how it
# was deployed: create a session, start the simulation, wait for a terminal
# status. Sourced by both scripts/smoke.sh (docker compose) and
# scripts/k8s-smoke.sh (Kubernetes), because a game played through the gateway
# looks identical either way — which is the property both scripts exist to prove.
#
# What stays with each caller is everything deployment-shaped: bringing the stack
# up, tearing it down, and what "show me the logs" means. The caller supplies the
# last one by defining `dump_logs`; the default below does nothing, so forgetting
# it costs diagnostics, not a broken run.

# Reads one named value out of a JSON document on stdin. The field is a fixed
# name rather than an expression, so no shell quoting ever reaches Python.
#
# The program goes in as an argument, never as a heredoc: `python3 - <<'PY'`
# would take the program from stdin, which is exactly where the piped JSON has
# to arrive. Only double quotes inside, so the whole thing survives the single
# quotes that wrap it.
json() {
  python3 -c '
import json, sys

doc = json.load(sys.stdin)
field = sys.argv[1]
if field == "sessionId":
    print(doc["sessionId"])
elif field == "status":
    print(doc["status"])
elif field == "summary":
    game = doc.get("gameState") or {}
    moves = len(doc.get("moveHistory", []))
    print(game.get("status"), "winner=" + str(game.get("winner")), "moves=" + str(moves))
else:
    sys.exit("unknown field: " + field)
' "$1"
}

# Overridden by the caller to print whatever its deployment calls logs. Guarded,
# so that a caller which defines it *before* sourcing this file keeps its own
# version — an unguarded definition would replace it with this no-op and leave a
# failing run with no diagnostics at all.
declare -F dump_logs >/dev/null || dump_logs() { :; }

# play_game BASE_URL — returns 0 when a game completes, 1 otherwise.
#
# Returns rather than exits: the caller's EXIT trap owns teardown, and a library
# that exits out from under it would skip the cleanup it never knew about.
#
# A game is 5-9 moves one second apart, plus the engine round trips. The default
# budget is comfortably above that and still fails fast if the stack is wedged.
play_game() {
  local base="$1"
  local attempts="${POLL_ATTEMPTS:-60}"
  local interval="${POLL_INTERVAL:-2}"

  echo "--- POST $base/sessions"
  local session_id
  session_id="$(curl -fsS -X POST "$base/sessions" | json sessionId)"
  echo "sessionId=$session_id"

  echo "--- POST $base/sessions/$session_id/simulate"
  curl -fsS -X POST "$base/sessions/$session_id/simulate" -o /dev/null -w 'accepted: HTTP %{http_code}\n'

  echo "--- waiting for a terminal session status"
  local status="" body=""
  local _
  for _ in $(seq 1 "$attempts"); do
    body="$(curl -fsS "$base/sessions/$session_id")"
    status="$(printf '%s' "$body" | json status)"
    case "$status" in
      COMPLETED)
        echo "PASS: session COMPLETED — $(printf '%s' "$body" | json summary)"
        return 0
        ;;
      FAILED)
        echo "FAIL: the session ended in FAILED"
        printf '%s\n' "$body"
        dump_logs
        return 1
        ;;
    esac
    sleep "$interval"
  done

  echo "FAIL: no terminal status within $((attempts * interval))s (last seen: ${status:-none})"
  dump_logs
  return 1
}
