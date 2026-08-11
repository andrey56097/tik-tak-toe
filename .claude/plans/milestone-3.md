# [MILESTONE-3] Game Session Service

Branch: `milestone-3`. Delivers the second service from `task.md`: session
management plus automated gameplay driven against the Game Engine.

This is the record of what was built, stage by stage, and of the decisions that
still bind future work. It replaces the five per-dispatch briefs the milestone
was originally planned with (`3a-session-domain`, `3b-orchestrator`,
`3b-fix-sync-guard`, `3c-controller-wiring`, `3-production-readiness`) — those
were instructions for agents that have already run; git holds what they produced.

Endpoints delivered, per `task.md` (no `/api` prefix, same precedent as Engine):

| Endpoint | Status | Returns |
|---|---|---|
| `POST /sessions` | 201 | new `SessionRecord` (`CREATED`, no game state yet) |
| `POST /sessions/{sessionId}/simulate` | 202 | empty — accepted for background processing |
| `GET /sessions/{sessionId}` | 200 | the session's record: status, latest game state, move history |

Errors: 404 unknown session, 409 session not startable, 405/404 from Spring's own
mappings, 500 generic — all with the shared `ErrorResponse` body.

---

## Stage A — Domain & move strategy

`domain/`: `SessionStatus` (`CREATED`/`RUNNING`/`COMPLETED`/`FAILED`),
`MoveHistoryEntry` (player, row, col), `SessionRecord` (sessionId, status,
nullable `GameState`, move history).

`SessionRecord` is an immutable record. A `ConcurrentHashMap` handles concurrent
*replacement* of a value natively, so internal mutability would buy nothing and
cost thread-safety reasoning.

`strategy/`: `MoveStrategy` (one method, `decideMove(gameId, currentState)`) and
`RandomMoveStrategy` — picks a uniformly random empty cell for
`currentState.nextTurn()`. A board with no empty cells throws
`IllegalStateException`: the orchestrator never calls it on a finished game, but
the method must not silently misbehave if it ever is.

The small interface is the seam for a future `MinimaxMoveStrategy`.

## Stage B — Engine client & orchestrator

`client/GameEngineClient` exposes only `makeMove`. **No separate "create game"
call**: Engine's `POST /games/{gameId}/move` is upsert-capable, and `sessionId`
doubles as `gameId`. The very first `decideMove` runs against a locally
synthesized fresh board (`GameStateFactory.empty`, in `common`) — calling `GET
/games/{gameId}` first would 404 on a brand-new id and defeat the upsert.

`client/RestGameEngineClient` — thin REST adapter. `@Retryable`, 3 attempts,
exponential backoff from ~500ms, **only** for transient failures
(`ResourceAccessException`, `HttpServerErrorException`). 4xx is never retried:
retrying a rejected move cannot succeed. Exhausted retries propagate, and the
session goes `FAILED` with the cause logged.

`orchestrator/GameSessionOrchestrator` — creates sessions (UUID), serves records,
and starts simulations. `simulate` is two lines:

```java
store.claimForRunning(sessionId);   // synchronous, on the caller's thread
runner.run(sessionId);              // @Async
```

**Why the split.** The `CREATED`-only guard originally lived inside the
`@Async` method, so its `SessionNotFoundException` /`SessionConflictException`
were thrown on a background thread and never reached the HTTP caller — 404/409
could not be returned at all. The claim must happen on the caller's thread. This
was found while writing Stage C's controller tests.

`service/SessionSimulationRunner` — the `@Async` move loop, a separate bean.
`@Async` works through a proxy, so a self-invoked annotated method is silently
ignored; an earlier `@Autowired setSelf(@Lazy ...)` workaround was removed in
favour of a real second bean. The loop is **bounded to 9 moves** (a 3×3 board
cannot hold more) — a terminal status ends it `COMPLETED`, an Engine failure or
loop overflow ends it `FAILED`, and nothing propagates to the caller.

`store/SessionStore` + `InMemorySessionStore` — `ConcurrentHashMap`, per
`task.md` ("In-memory storage … for session and move history"). The interface
exists because `CLAUDE.md` requires persistence to be swappable by design.
`claimForRunning` uses `compute`, so check-and-transition is one atomic step per
key: two concurrent `simulate` calls produce exactly one run and one 409.

## Stage C — Controller & wiring

`controller/SessionController` — thin; all business logic in the orchestrator,
all exception→status mapping in the advice. OpenAPI annotations via springdoc.
Responses are `dto/SessionResponse` + `dto/MoveHistoryDto`, never the store's
`SessionRecord` / `MoveHistoryEntry`: the Spring & Web Production Standards name
those two store value types explicitly as types that must not appear in a REST
contract. Both shapes are identical today — which is when the seam is cheapest
to keep, since the point is that the stored shape can move without dragging the
published one with it.

`config/AsyncConfig` — `@EnableAsync` + `@EnableRetry`, plus a `RetryListener`
bean so retries against a struggling Engine are visible in the log.

`config/RestClientConfig` — `@LoadBalanced RestClient.Builder` (Eureka resolves
`GAME-ENGINE-SERVICE`) and the Engine client with connect/read timeouts, so a
dead Engine surfaces as a fast `ResourceAccessException` instead of a hang. The
request factory is pinned to `SimpleClientHttpRequestFactory` deliberately:
Apache HttpClient 5 is on the runtime classpath for Eureka, and Spring would
otherwise pick a factory whose own retry executor multiplies attempts underneath
`@Retryable`.

`exception/SessionExceptionHandler` — `ErrorResponse` bodies for the four 4xx
this API can actually produce (unknown session, conflicting state, unknown URL,
unsupported method) plus a catch-all that logs server-side and returns a generic
500. Scope is deliberate: the endpoints take no request body and their only path
variable is a String, so the rest of Spring MVC's exceptions are unreachable.

`application.yml` — port 8082, Eureka, `engine.client.*`, `session.simulation.
move-delay-ms: 1000`, actuator (`health,info`), and
`spring.threads.virtual.enabled: true`. The auto-play loop is a blocked thread
for its whole life (~9 × delay plus round-trips); on the default 8-thread pool
with an unbounded queue the 9th concurrent session would queue invisibly.

## Stage D — Production-readiness pass

This stage implements `CLAUDE.md`'s **Spring & Web Production Standards** and
section 6 of the `code-quality` skill, both codified on `main` in `fdf1802`
after a production-readiness audit of Milestones 1 and 3. Every rule there is
mandatory; the audit's findings are the concrete violations they prevent.

An audit of A–C produced these corrections: `WebClient` + `.block()` on a servlet
stack → `RestClient` with timeouts; `@Retryable` narrowed off 4xx (it had been
retrying rejected moves); the move loop bounded; session state moved behind the
store seam with the atomic claim; the shared `ErrorResponse` contract; the
`@Lazy` self-injection removed.

The same pass also over-shot, and the excess was reverted — see below.

---

## Tried and reverted

Recorded so it is not re-introduced by the next reader:

- **`Sleeper` interface + `ThreadSleepSleeper` bean.** Production indirection
  introduced solely to make one `Thread.sleep` observable to a test.
- **`SessionExceptionHandler extends ResponseEntityExceptionHandler`.** Doubled
  the class to cover ~20 framework exceptions no request to this API can reach;
  one of its branches was only coverable by a test that constructed the
  exception itself.
- **`isNotNull()` tests over `@Bean` methods** and reflection into Spring's
  private timeout fields. They moved the mutation score and caught nothing.

## Testing

46 tests, all green. Unit tests for the strategy, store, runner and orchestrator;
`@WebMvcTest` slice for the error advice; a `@SpringJUnitConfig` + MockWebServer
test proving the retry policy against a real HTTP endpoint (4xx once, 5xx three
times, connect-refused three times — order-dependent by construction, since the
connect-refused case kills the shared server, hence `@TestMethodOrder`).

`SessionAutoPlayIntegrationTest` covers what `task.md` asks for directly —
"Session Creation ➔ Move Simulation ➔ Game Outcome" — with every session bean
real and only `GameEngineClient` mocked, and asserts the Engine call lands on a
different thread than the request (the `@Async` proof).

### Mutation testing

Pitest gates at 80; the module is at 100% (36/36). The rule that keeps that
number honest: **a surviving mutant is answered by a test that asserts real
behavior, or by deleting the code it lives in — never by adding production
indirection to make it observable.**

`com.flamingo.tiktaktoe.session.config.*` is excluded from `targetClasses`:
wiring classes only yield "return null instead of the bean" mutants, killable
only by asserting a bean is non-null. The wiring is proven by
`SessionAutoPlayIntegrationTest` booting the real context instead.

## Deferred

- **Engine adopts the shared contract** — it still returns plain-text error
  bodies, and still builds its own starting board rather than
  `GameStateFactory.empty` (which it cannot use as-is: the factory returns an
  immutable board, Engine updates cells in place).
- **Timeout recovery.** A read timeout is retried, so a move Engine did apply can
  be submitted twice. Engine's turn check rejects the duplicate with a 409, so
  the board stays correct — but the session ends `FAILED`. Turning that 409 into
  recovery (re-read the game, resume from Engine's state) closes the gap.
- **`MoveRequest` row/col bounds**, config profiles.
- **Session eviction** — `InMemorySessionStore` keeps every session forever.
- **Load-balancing and timeouts proven against a live Engine** (Milestone 7 /
  WireMock).
