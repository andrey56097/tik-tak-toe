# [MILESTONE-5] SSE push Session → UI

Branch: `milestone-5`. Replaces the browser's polling loop with a real push
channel: the board updates the instant a move is applied, and the page stops
asking for news it usually does not have.

Decisions below were grilled with the user and are final — do not re-litigate.

`task.md` marks this optional (line 62 lists WebSockets/SSE as the *mechanism*;
the required *effect* — a live board — is already met by Milestone 4's polling).
It is built anyway because it is the milestone where the Observer / Pub-Sub seam
stops being a diagram [README.md](../README.md)and becomes code.

---

## Decisions (grilled)

**1. Publication hangs off a `SessionStore` decorator, not off the runner.**
Every state transition in this service already funnels through one method —
`store.save(...)` — called seven times: once in `createSession`, once when the
session is claimed `RUNNING`, five times in `SessionSimulationRunner`. So
`PublishingSessionStore implements SessionStore` delegates the save, then
publishes. One place instead of seven, and a future transition cannot forget to
notify. `SessionSimulationRunner` and `GameSessionOrchestrator` do not change by
a single line. *Rejected:* explicit `publisher.publish(...)` calls in the runner
(the plan's original wording) — seven call sites, and it hands the runner a
second responsibility on top of driving the game. *Rejected:* publishing from
inside `InMemorySessionStore` — that welds notification to storage, so swapping
the store for a database would drag SSE along with it.

Consequence, accepted deliberately: an event is emitted for *every* save,
including `CREATED` and the `RUNNING` claim, not only for applied moves. That is
correct — each one is a real state change, and each carries the full state a
client renders from.

**2. No replay buffer. Event ids are set anyway.** The plan's checklist asks for
`Last-Event-ID` replay, which requires the server to keep per-session event
history. It is not built, because it would send data the client is guaranteed to
discard: every event carries the complete `SessionResponse`, and `render(state)`
is idempotent, so a client that missed five events needs only the sixth. On
(re)connect the server sends the current state as the first event — that *is*
the replay. Ids are still set (the move count, monotonic per session) because
they cost nothing and make an event line up with a log line during diagnosis.
*Rejected:* full replay (memory and code for superseded data). *Rejected:*
omitting ids (loses log correlation, and the browser stops sending
`Last-Event-ID` at all).

**3. The browser's polling code is deleted.** `pollUntilDone`,
`POLL_INTERVAL_MS` and `MAX_POLLS` go. The page gets exactly one way to learn
about a change. `GET /sessions/{id}` stays on the server — `task.md` requires it
and it remains the documented alternative — so restoring polling is one line
against a live endpoint. *Rejected:* automatic fallback to polling when the
stream fails (two live update paths in the client, both needing tests, for a
failure mode — a proxy that buffers — that Milestone 6 already has a checklist
item to verify). *Rejected:* both paths behind a flag (one branch is dead code
on every run).

**4. The browser subscribes *before* it starts the game.** Order becomes
create → open stream → `POST /simulate`, not create → simulate → open stream.
Nothing is lost either way — full-state events mean a late subscriber still sees
the whole board and history — but with `move-delay-ms` set low the game can
finish before a late subscriber attaches, and the page would then show the
result without ever showing the play. Costs one reordered step in
`startSimulation`.

**5. A dropped connection reads as "Reconnecting", not as an error.**
`EventSource` reconnects on its own and reports *every* drop, including ones it
recovers from immediately, so routing that into the red error banner would flash
alarm on a hiccup. Instead a quiet notice appears next to the status and clears
itself on the next delivered event: a blip shows briefly, a genuinely dead
service leaves it standing, which is what explains a frozen board. The red
banner stays for real failures — no such session, could not create one.
*Rejected:* showing nothing (a dead service is indistinguishable from a stalled
game). *Rejected:* reusing the error banner (cries wolf on every reconnect).

---

## Settled by the plan, not re-opened

- **New endpoint** `GET /sessions/{sessionId}/stream`, `produces = text/event-stream`,
  returning `SseEmitter`. A separate path rather than content negotiation on
  `GET /sessions/{id}`: a stream and a snapshot need different timeouts,
  buffering, caching and metrics, and two operations on one path+method render
  badly in Swagger UI. Full rationale in the plan.
- **Unknown session id** → `SessionNotFoundException` → the same 404
  `ErrorResponse` as every other endpoint.
- **Multiple subscribers per session** — the registry is `sessionId` →
  collection of emitters.
- **Termination is explicit**: a named `done` event, then the emitter completes.
  `EventSource` auto-reconnects when a stream closes, so completing silently
  would make the browser reopen in a loop; the client calls `close()` on `done`.
- **Eviction** on completion, timeout, error and client disconnect
  (`onCompletion` / `onTimeout` / `onError`) — otherwise the registry leaks.
- **`GameUpdatePublisher` is the port**; `SseGameUpdatePublisher` is the
  adapter. A WebSocket implementation would swap in without touching anything
  upstream.

## To decide while implementing (no user input needed)

- Emitter timeout: configurable (`session.stream.timeout-ms`), set comfortably
  above the worst-case game — 9 moves × `move-delay-ms` plus Engine round-trips
  *and* `@Retryable` retries at 5s read timeout each.
- Subscribing to an already-terminal session: send the current state, then
  `done`, then complete — no special-casing on the client.

## Testing (mandatory, per CLAUDE.md)

Tests first, written by a separate subagent from the implementer.

- Publisher registry: several subscribers on one session all receive an event;
  eviction on completion / timeout / error; publishing to a session with no
  subscribers is a no-op, not a failure.
- Decorator: `save` delegates to the wrapped store *and* publishes exactly once,
  and a publisher failure must not lose the save.
- Endpoint: subscribing yields the current state as the first event; a full
  simulation delivers one event per move and terminates with `done`; unknown id
  → 404 `ErrorResponse`.
- Engine status codes: one test per row of the table above, asserting the status
  *and* that the body is the shared `ErrorResponse` — plus tests pinning the
  three behaviours that already work, so the fix cannot regress them. These are
  the kind of mutants Pitest kills easily; the threshold applies.
- These classes live outside `...session.config.*`, so unlike the configuration
  classes they **are** mutation-tested — Pitest's 80% threshold applies.

---

## Also in this milestone: Engine returns 500 where it owes 400 / 404 / 405

Found by probing the API surface while auditing it against `task.md`, not by a
failing test — nobody had knocked on Engine with a malformed request before.
Carried here rather than onto a separate branch at the user's request.

`GameExceptionHandler` handles the domain exceptions and then falls through to
`@ExceptionHandler(Exception.class)`, which by design answers 500. Three
framework exceptions have no handler of their own, so every one of them becomes
a 500. Measured against the running service:

| Request | Now | Owes |
|---|---|---|
| `POST /games/{id}/move` with `{"player":"Z",…}` | 500 | 400 |
| `POST /games/{id}/move` with a missing field | 500 | 400 |
| `POST /games/{id}/move` with malformed JSON | 500 | 400 |
| `POST /games/{id}/move` with `{"row":"a"}` | 500 | 400 |
| `GET /no-such-path` | 500 | 404 |
| `DELETE /games/{id}` | 500 | 405 |

The first four are one cause: `HttpMessageNotReadableException`, thrown before
the controller is reached whenever Jackson cannot bind the body. The last two
are `NoResourceFoundException` and `HttpRequestMethodNotSupportedException`.

What already works and must keep working: `GET /games/{unknown}` → 404,
`POST /games/{id}/move` with valid enums and out-of-range coordinates → 400,
a legal move → 200.

**Why it belongs to correctness, not polish.** `task.md` requires "robust error
handling for invalid moves" of the Engine; a move naming an unknown player
symbol is exactly an invalid move. CLAUDE.md requires "400 invalid input, 404
unknown resource" and "never bare 500s". The endpoint's own `@ApiResponse`
already documents `400 — Malformed body`, so the service contradicts its
published contract. And `SessionExceptionHandler` in the sibling service already
handles `NoResourceFoundException` (404) and `HttpRequestMethodNotSupportedException`
(405) — Engine is simply behind, so this is also a consistency fix.

**Not in scope here:** the H2 console. `spring.h2.console.enabled: true` in
Engine's `application.yml` is dead configuration — no Spring Boot 4.1.0 module
on the classpath ships H2 console autoconfiguration (verified by scanning the
jars), which is why `/h2-console` 500s like any other unmapped path. Once the
handler fix lands it will honestly 404. Deciding between adding the module and
dropping the claim from the plan is a separate call.

## Landing before this milestone: the game-status wording

The status line now leads with the status itself (`Win — X won the game`), so
`task.md`'s "show the current game status" is unmistakable rather than implied
by "X wins". It ships **ahead of this milestone** on `fix/game-status-wording`,
not on this branch.

Consequence to expect, not to be surprised by: this milestone edits the same
file — `app.js` loses `pollUntilDone`, `POLL_INTERVAL_MS` and `MAX_POLLS` — so
branch `milestone-5` must be cut *after* that fix is merged, or rebased onto it.
`describeStatus` itself is untouched by the transport change.

## Out of scope

- Browser-side tests (`app.js`, board geometry). Still no JS test
  infrastructure; Playwright remains the agreed, deferred direction.
- Gateway streaming behaviour — Milestone 6 owns "confirm the route streams
  rather than buffers".

## Documentation follow-up (goes to `main`, not this branch)

- README: SSE vs WebSocket + STOMP as a design comparison — `task.md` line 89
  invites a discussion of alternative designs, so the comparison is itself a
  deliverable.
- README: polling recorded as the documented fallback.
