# Milestone 7 — Testing & Validation

**Status:** planned
**Branch:** `milestone-7` · one PR, atomic commits
**Closes:** `task.md` → «🧪 Testing & Validation» (all four items) + «Submission Checklist → Testing»

---

## What this milestone is, and what it is not

Every module already has unit and slice tests, and `./gradlew build` already gates
on them, on JaCoCo (engine) and on Pitest (engine + session). This milestone is
**not** more of that.

It is the one thing the suite cannot currently claim: that the two services
actually work **as two services**. Today every Session test that involves the
Engine replaces `GameEngineClient` with a Mockito mock. Nothing anywhere runs a
move request through a real socket into the real Engine, and nothing proves the
load-balanced client resolves `GAME-ENGINE-SERVICE` against something alive —
which the README itself lists as a known gap.

So: **real HTTP on both sides, the real Engine, the real board rules, the real
H2** — and the failure paths a live Engine cannot produce on demand, driven by a
stub endpoint.

---

## Decisions

Taken in the pre-implementation interview; each is load-bearing.

| # | Decision | Why |
|---|---|---|
| 1 | **Scope is JVM tests only.** No Playwright, no Testcontainers. | Browser tests for `app.js` are real work with their own toolchain (Node, Playwright, a CI runner change). Bundling them here would make the milestone about infrastructure instead of about the assignment's Testing section. They move to their own milestone; the README line that promises them "in the testing milestone" gets corrected. |
| 2 | **No new Gradle module.** | Rejected explicitly. A test-only module would be a sixth thing to build for tests that belong to a service that already exists. |
| 3 | **Hybrid: real Engine for truth, stub endpoint for failure.** | A hand-written stub encodes *my belief* about Engine's contract — paths, status codes, JSON shape — and drifts silently. A real Engine cannot be told to answer 503, hang past a read timeout, or refuse a connection. Each covers the other's blind spot. |
| 4 | **A dedicated `integrationTest` source set inside `game-session-service`.** | See «The classpath hazard» — this is not a style preference, it is the only option that leaves the 21 existing session test classes on an unchanged classpath. |
| 5 | **`check` depends on `integrationTest`.** | `./gradlew build` stays the entire gate — the property the CI workflow and branch protection are built on. |
| 6 | **Concurrency asserts board integrity, not a specific loser status.** | Two parallel moves can legitimately lose with 409 (optimistic lock, or wrong turn) or 400 (cell taken) depending on interleaving. Asserting one exact code produces a test that passes on a fast machine and fails on CI. `@Version` itself is already pinned by `GameRepositoryTest.secondWriteFromAStaleCopyIsRejected`. |
| 7 | **MockWebServer, not WireMock.** | It is already a dependency and already emulates Engine in `RestGameEngineClientRetryTest`. Adding a second stub library for the same job fails KISS/DRY. The README's tech table currently claims WireMock, which is used nowhere — the table gets fixed instead. |
| 8 | **Session runs on a real port too (`RANDOM_PORT`).** | MockMvc returns an SSE body only once the emitter has completed, so it can count events but cannot show them arriving *as moves happen*. A real port also puts Session's own HTTP stack in the scenario. MockMvc-based coverage of Session's contract already exists in `src/test` and stays there. |
| 9 | **Load balancing is proven across two live Engine instances.** | Resolving a service id to one instance proves resolution, not balancing. Two instances prove the thing the README says is unproven. |
| 10 | **Gateway is out of the chain.** | `task.md`'s Testing section names Session ↔ Engine. Gateway has its own tests (including a non-buffering SSE proof) and `scripts/smoke.sh` exercises it against real containers. A third context — and a second web stack — buys little. |

---

## Two hazards found while planning, and how they are handled

### 1. The classpath hazard — why a separate source set is mandatory

`game-engine-service/src/main/resources/application.yml` sets:

```yaml
spring:
  application:
    name: game-engine-service
server:
  port: 8081
```

Putting engine on session's **`test`** classpath puts that file at
`classpath:/application.yml` — the exact location session's own
`application.yml` occupies. Spring Boot resolves that location to **one**
resource, and which one wins depends on classpath ordering. The failure mode is
not a compile error; it is the session context booting under the engine's name
on the engine's port, and `spring-boot-starter-data-jpa` + H2 arriving with it
so JPA auto-configuration starts applying to every existing session test.

The `integrationTest` source set keeps that classpath entirely separate. The
existing `test` task keeps the dependencies it has today, unchanged.

Inside the new source set the collision still exists, so it is resolved
explicitly rather than by luck. **Both of the original resolutions below were
corrected during implementation — see the SDD ledger for the ruling and why:**

- `src/integrationTest/resources/application.properties` — our own file, loaded
  *alongside* this service's own `application.yml`. (The plan originally said
  `application.yml`; that would have *shadowed* Session's own file — Spring Boot
  resolves `classpath:/application.yml` to exactly one resource, and a source
  set's own resources precede jars. The `.properties` name loads instead of
  replaces.) It holds only what both contexts share
  (`eureka.client.enabled=false`).
- The **Session** context gets its properties from
  `@SpringBootTest(properties = …)` / `@DynamicPropertySource`.
- The **Engine** context is started with command-line arguments
  (`--server.port=0`, `--spring.datasource.url=…`). (The plan originally said
  `SpringApplicationBuilder.properties(…)`; that sets `defaultProperties`, the
  lowest-precedence source, so config files — here the *session's* file, which is
  what wins on this source set's classpath — would override it. Command-line
  arguments outrank config files, so they are the only form that survives.)

### 2. Two Engine instances would share one database

Engine's datasource is `jdbc:h2:mem:games;DB_CLOSE_DELAY=-1` — a **named**
in-memory database. Two instances in one JVM would share it and would be
indistinguishable, which is exactly what a load-balancing test must
distinguish.

Each embedded instance therefore gets its own database name
(`jdbc:h2:mem:engine-<n>`), and the balancing test observes distribution the
only honest way: send **N moves with N distinct `gameId`s** through the
load-balanced client, then read each instance **directly on its own port** and
assert both instances hold at least one of those games. No dependence on
round-robin's starting offset, no shared state, no sleep.

This also means the *game* tests use exactly **one** instance — a game split
across two isolated stores would be a broken game, not a test.

---

## How the Engine is reachable by service id without Eureka

Session's client is `@LoadBalanced` with `engine.client.base-url:
http://GAME-ENGINE-SERVICE`. Pointing that at `http://localhost:PORT` does not
work — the load balancer would try to resolve `localhost` *as a service id*
(the failure mode already recorded for this project). The base URL therefore
stays untouched, and the service id is made resolvable instead:

`SimpleDiscoveryClient` via properties —

```
spring.cloud.discovery.client.simple.instances.GAME-ENGINE-SERVICE[0].uri=http://localhost:${port}
```

fed by `@DynamicPropertySource` once the embedded Engine has a port.

(The service-id key is **case-sensitive**: `SimpleDiscoveryClient.getInstances`
does a plain `Map.get`, and the load balancer looks up the id as it appears in
`engine.client.base-url` — uppercase `GAME-ENGINE-SERVICE`. A lowercase key
resolves to an empty list. The plan's original lowercase example was wrong and
was corrected during implementation.)

**No `ServiceInstanceListSupplier` fallback.** `SimpleDiscoveryClient` was proven
to engage with eureka on the classpath but disabled (verified with a one-off
test), so the planned fallback would be dead code.

Whichever engages, the load-balanced `RestClient` — the production bean, with
its production timeouts and its production `@Retryable` — is what makes the
call. That is the point.

---

## Test inventory

Every row traces to a `task.md` requirement or a plan checklist line. Names are
indicative; the test subagent may sharpen them.

### A. `game-session-service/src/integrationTest` — Session ↔ Engine, real HTTP

| Test | Proves |
|---|---|
| `SessionEngineFullGameIT.playsACompleteGameAgainstTheRealEngine` | **Integration test, full loop.** `POST /sessions` → `POST /simulate` → poll `GET /sessions/{id}` to a terminal state. The final `gameState.status` is `WIN` or `DRAW`, the move history is 5–9 alternating X/O moves, and every move landed on a distinct cell. |
| `SessionEngineFullGameIT.sessionStateMatchesTheEngineSideBoard` | **State management across services.** After the session completes, `GET /games/{id}` read straight off the Engine's own port is byte-for-byte the board, status and winner that Session reports. The two stores agree. |
| `SessionEngineFullGameIT.repeatedReadsOfTheSameGameAreStable` | **State recovery.** Two `GET /games/{id}` a moment apart return identical state — nothing is lost between requests. |
| `SessionEngineContractIT.moveRequestAndGameStateSurviveTheWire` | **Serialization both ways.** A move issued by Session arrives at Engine as `{"player":"X","row":r,"col":c}` and Engine's `GameState` deserializes back with board, `status`, `nextTurn`, `winner` intact — asserted on the wire, not on objects. |
| `SessionEngineSseIT.everyMoveProducesAStreamEventAndTheStreamCloses` | **SSE delivery.** A real streaming client subscribes to `GET /sessions/{id}/stream` before the simulation starts, receives one event per move with a monotonically growing move history, then `event:done`, and the connection closes. |
| `SessionEnginePollingIT.pollingReachesTheSameTerminalStateAsTheStream` | **The polling path independently.** No SSE involved: repeated `GET /sessions/{id}` observes the move count grow and settle on a terminal status. |
| `SessionEngineLoadBalancingIT.movesAreDistributedAcrossBothEngineInstances` | **Load balancing against live instances** (README gap). Two Engines, N distinct games through the service id, both instances hold work. |

### B. `game-session-service/src/integrationTest` — failure paths (MockWebServer as Engine)

| Test | Proves |
|---|---|
| `EngineUnavailableIT.engineDown_endsTheSessionFailed_withoutCrashing` | **Connection issues.** Engine's socket refuses; the session ends `FAILED`, the service stays up and keeps answering `GET /sessions/{id}`, and the SSE stream closes rather than hanging. |
| `EngineUnavailableIT.engineReturning503_isRetriedThenFailsTheSession` | 5xx is retried per `@Retryable` and, once exhausted, ends the session `FAILED` — proven end-to-end, not just at the client. |
| `EngineUnavailableIT.engineSlowerThanTheReadTimeout_failsFast` | **Client timeouts against a live endpoint** (README gap). A response delayed past `read-timeout-ms` surfaces as a failure in bounded time — never a hang. |
| `EngineUnavailableIT.errorBodyNeverLeaksEngineInternals` | Session's own response for a failed session carries the shared `ErrorResponse` shape, no stack traces, no upstream body. |

### C. `game-engine-service/src/test` — concurrency and error contract

| Test | Proves |
|---|---|
| `ConcurrentMoveIT.twoParallelMovesOnOneGame_leaveTheBoardIntact` | **Concurrency handling** (`task.md` optional). Real HTTP on `RANDOM_PORT`, N iterations of two simultaneous moves on one `gameId`: exactly one 2xx, the other a 4xx carrying a proper `ErrorResponse` — never a 5xx — and the board ends with exactly one new mark and a consistent version. |
| `ConcurrentMoveIT.parallelMovesAcrossDistinctGames_allSucceed` | The guard is per game, not a global lock — independent games are not serialized against each other. |

Invalid-move and unknown-`gameId` behaviour (400/404/409 with `ErrorResponse`)
is already covered by `GameControllerIntegrationTest` and
`EngineErrorContractIntegrationTest`; the `task.md` line «Validate response
behavior for invalid moves» is closed by those, and the plan will say so rather
than duplicating them.

---

## Build changes

**`game-session-service/build.gradle.kts`**

- New `integrationTest` source set + `integrationTest` task (`useJUnitPlatform()`,
  `shouldRunAfter(test)`), configurations extending the test ones.
- `integrationTestImplementation(project(":game-engine-service"))` — **test scope
  only**; the production dependency graph is untouched, so no service starts
  depending on another.
- `check.dependsOn(integrationTest)`.
- JaCoCo plugin + `jacocoTestCoverageVerification` at the same **80 % line**
  floor the engine uses, with execution data merged from `test` **and**
  `integrationTest`, and `check` depending on the verification.
- Pitest keeps `testSourceSets = [test]` — mutation analysis stays on fast unit
  tests; running it against two booted contexts per mutant would be untenable.
  This is stated as a comment in the build file so it is not "fixed" later.

**`game-engine-service/build.gradle.kts`** — unchanged.

---

## Documentation (straight to `main`, separate from the code PR)

- **README «Testing Strategy»** — the diagram gains the real Session ↔ Engine
  path and the load-balancing and concurrency nodes; the text says which command
  runs what.
- **README tech table** — `WireMock` → `MockWebServer` (decision 7): it now
  describes what the repo uses.
- **README «Known gaps»** — delete «Load balancing and client timeouts are not
  proven against a live Engine» (closed here). Rewrite the browser-tests gap so
  it no longer promises Playwright "in the testing milestone" (decision 1).
- **README «Test» section** — document `./gradlew integrationTest` and that
  `build` includes it.
- **Roadmap row 7** — mark done.
- **`docs/tic-tac-toe-plan.md`** — tick the Milestone 7 checklist items and note
  where each is proven.

---

## Commit breakdown (one PR, `milestone-7`)

1. `[MILESTONE-7] Give the session service an integration test source set` —
   build wiring, the embedded-Engine support class, one walking-skeleton test
   that plays a real game. *This commit is where the SimpleDiscoveryClient
   question is settled in fact rather than on paper.*
2. `[MILESTONE-7] Prove session and engine agree on state across the wire` —
   state-management and serialization tests.
3. `[MILESTONE-7] Prove every move reaches the browser, by stream and by poll` —
   SSE and polling tests.
4. `[MILESTONE-7] Prove the session survives an engine that is down or slow` —
   MockWebServer failure suite.
5. `[MILESTONE-7] Prove the load balancer spreads work across engine instances` —
   two-instance test.
6. `[MILESTONE-7] Keep the board intact under parallel moves` — engine
   concurrency tests.
7. `[MILESTONE-7] Gate the session service on line coverage too` — JaCoCo for
   session, `check` wiring.

Docs land on `main` separately, per the repo's rule.

---

## Process

Per `CLAUDE.md`: test subagent writes the failing tests first, implementer
subagent makes them pass (here that mostly means support classes and build
wiring — production code is expected to stay unchanged), reviewer subagent
reviews both. `code-quality` checklist as acceptance. **No commit without
explicit confirmation.**

If a test fails against the real system, that is a **finding, not a bug in the
test** — it gets reported and decided on, not silently accommodated.

---

## Acceptance criteria

1. `./gradlew build` is green from a clean checkout, and it runs the new suite.
2. Every `task.md` «Testing & Validation» bullet maps to a named test in this
   document's inventory.
3. No production code changed to make a test pass, unless a real defect was
   found and the change was agreed.
4. No test asserts on mock behaviour; every new test asserts real observable
   behaviour of a running system.
5. No `Thread.sleep`-based synchronization for correctness — polling with a
   bounded budget and an explicit failure message instead.
6. Session's line coverage gate is on and green at 80 %; Pitest stays ≥ 80 % in
   both gated modules.
7. README and the plan describe what exists, with no promise left dangling.

---

## Explicitly out of scope

- Playwright / any browser test of `app.js` — its own milestone.
- Testcontainers or compose-based E2E in Gradle — `scripts/smoke.sh` covers the
  containerised path.
- Gateway in the integration chain (decision 10).
- Eureka in the loop: registration behaviour stays deferred to its own work
  (the WireMock-for-Eureka idea recorded earlier).
- Raising coverage/mutation thresholds above 80 %.
