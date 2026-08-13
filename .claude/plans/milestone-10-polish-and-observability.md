# Milestone 10 — Final polish, audit fixes and observability

> **For agentic workers:** REQUIRED SUB-SKILL: use the project `sdd` skill to
> implement this plan task-by-task (it wraps
> `superpowers:subagent-driven-development` with this project's TDD +
> code-quality requirements). Steps use checkbox (`- [x]` / `- [ ]`) syntax for
> tracking. **All tasks below are complete** — delivered via PRs #24–#27 on `main`.

**Goal:** close every real defect found by the 2026-08-13 whole-repo audit, give
the system real observability (metrics + distributed tracing), and make the
documentation describe only what actually exists.

**Architecture:** no new services and no new architectural seams. Every change is
either (a) a correction inside an existing seam, (b) a quality gate extended to
code it never covered, or (c) an observability layer bolted onto the existing
actuator surface. The one genuinely new mechanism is bounded session retention
inside `InMemorySessionStore` — deliberately an *implementation* detail of the
existing `SessionStore` port, so no service, controller or DTO changes.

**Tech Stack:** Java 21, Spring Boot 4.1.0, Spring Framework 7.0.8, Spring Cloud
2025.1.2, Micrometer + Prometheus registry, Micrometer Tracing on OpenTelemetry,
Vitest + jsdom (test-only, for `app.js`), Pitest, JaCoCo.

**Spec:** this plan implements the findings of the whole-repo audit recorded in
this file's *Audit findings addressed* section, plus the four remaining
unticked Milestone 10 items in `docs/tic-tac-toe-plan.md:527-535`.

---

## Global Constraints

Copied verbatim from `CLAUDE.md` — every task's requirements implicitly include
these.

- **Java 21 (LTS), Spring Boot 4.1.x, Spring Cloud 2025.1.2 "Oakwood".** Do not
  pin JUnit 5, Mockito, H2 or Micrometer versions — they come managed via the
  Spring Boot BOM.
- **Branch:** all code and tests go on `milestone-10`. **Docs and rule changes go
  straight to `main`** — including this plan file and every README edit.
- **Commit only after explicit user confirmation.** Finishing a task is not
  permission to commit.
- **Commit and PR titles start with `[MILESTONE-10]`.**
- **TDD is non-negotiable:** the failing test comes first, and the test-writer and
  the implementer are two different subagent dispatches.
- **Mutation testing is mandatory** for every new production class; the Pitest
  gate is 80% and it must stay green.
- **Package by layer** under the module root (`controller`, `service`, `domain`,
  `repository`, `mapper`, `validation`, `exception`, `config`, `store`,
  `publisher`, `strategy`, `client`). Tests mirror the same subpackages.
- **Never put exception internals in a client-facing response body** — log them
  via SLF4J and return a generic message.
- **Run Gradle per module** (`./gradlew :game-engine-service:test`), not
  ad-hoc from a service directory.

### Out of scope, by explicit decision

- **Security.** No Spring Security, no auth, no rate limiting at the edge. The
  user ruled it out for this milestone. Task 10 records it as a stated boundary in
  the README instead of leaving it unmentioned.
- **Schema migrations (Flyway/Liquibase).** Out of scope while the database is
  in-memory H2; recorded as a Known gap.
- **`Player` as a separate type.** The audit found `CellState` conflates "cell
  content" with "player". The user chose the minimal fix (validation → 400) over
  the contract change. Task 1 implements the minimal fix and records the modelling
  debt in the README.

---

## Audit findings addressed

| # | Finding | Task |
|---|---|---|
| 1 | `CellState.EMPTY.opposite()` silently returns `X`, untested | 1 |
| 2 | `player:"EMPTY"` answered 409 instead of 400 | 1 |
| 3 | `MoveValidator.canPlay` takes an unused `player` parameter | 1 |
| 4 | `common` module is behind no Pitest/JaCoCo gate | 2 |
| 5 | Two `@RestControllerAdvice` copies diverged (405 `Allow` header) | 3 |
| 6 | `ex.getMessage()` reaches the client body on 405 | 3 |
| 7 | `InMemorySessionStore` grows without bound — no TTL, no cap | 4 |
| 8 | No ceiling on concurrent simulations | 5 |
| 9 | No metrics, no tracing, no correlation id across services | 6 |
| 10 | `app.js` (234 lines) covered by nothing; no JS test infrastructure | 7 |
| 11 | `EventSource` never closed on the frontend error paths | 7 |
| 12 | `spring.jpa.open-in-view` left at its default `true` | 8 |
| 13 | `spring-boot-starter-websocket` in session is a dead dependency | 8 |
| 14 | `nextTurn` still flips after a WIN | 8 |
| 15 | Simulation runner catches `RuntimeException`, not `Throwable` | 8 |
| 16 | Stale build comment cites the deleted `CorsConfig`/`CorsConfigTest` | 8 |
| 17 | H2 console enabled in the shipped image | 8 |
| 18 | `Last-Event-ID` replay promised in docs, never implemented | 10 |
| 19 | Hand-pinned `spring-retry` where Spring Framework 7 has `@Retryable` | 9 |

---

## File structure

**Created**

| File | Responsibility |
|---|---|
| `common/src/main/java/.../common/web/AbstractRestExceptionHandler.java` | The shared half of both services' advice: catch-all 500, unknown resource, 405 with `Allow`, and the `ErrorResponse` builder |
| `common/src/test/java/.../common/web/AbstractRestExceptionHandlerTest.java` | Proves the shared handlers, once, for both services |
| `game-session-service/src/main/java/.../session/store/SessionRetentionPolicy.java` | Value type: how long terminal sessions are kept and how many may exist |
| `game-session-service/src/main/java/.../session/exception/SessionCapacityException.java` | Signals the running-session ceiling was reached → 503 |
| `game-session-service/src/main/java/.../session/config/ObservabilityConfig.java` | Meter/observation configuration |
| `game-session-service/src/main/java/.../session/service/SimulationMetrics.java` | Counters and timers for the auto-play loop |
| `game-engine-service/src/main/java/.../engine/service/EngineMetrics.java` | Counters for moves applied/rejected and games created |
| `ui-service/package.json` | Test-only toolchain (Vitest + jsdom). Not a build step for the served page |
| `ui-service/vitest.config.js` | jsdom environment, coverage thresholds |
| `ui-service/src/main/resources/static/render.js` | The pure rendering/formatting functions, extracted so they are importable |
| `ui-service/src/test/javascript/render.test.js` | Tests for the extracted functions |
| `ui-service/src/test/javascript/session-flow.test.js` | Tests for the create → subscribe → simulate flow, including stream teardown |

**Modified**

| File | Change |
|---|---|
| `common/src/main/java/.../common/CellState.java` | `opposite()` rejects `EMPTY` |
| `common/build.gradle.kts` | Pitest + JaCoCo gates; `compileOnly` spring-web for the shared advice |
| `game-engine-service/.../validation/MoveValidator.java` | Drop the unused parameter; add the symbol check |
| `game-engine-service/.../service/GameEngineService.java` | Symbol check before the turn check; stop flipping `nextTurn` on a terminal move |
| `game-engine-service/.../exception/GameExceptionHandler.java` | Extends the shared base; keeps only engine-specific handlers |
| `game-engine-service/src/main/resources/application.yml` | `open-in-view: false`, H2 console off by default, Prometheus endpoint |
| `game-session-service/.../exception/SessionExceptionHandler.java` | Extends the shared base; adds the capacity handler |
| `game-session-service/.../store/InMemorySessionStore.java` | Retention sweep + running-session ceiling |
| `game-session-service/.../service/SessionSimulationRunner.java` | `Throwable` safety net and metrics |
| `game-session-service/build.gradle.kts` | Drop `starter-websocket`; `starter-web` → `starter-webmvc`; observability deps; fix the stale comment |
| `ui-service/src/main/resources/static/app.js` | Import from `render.js`; close the stream on every terminal path |
| `ui-service/build.gradle.kts` | `npmTest` task wired into `check` |
| `.github/workflows/ci.yml` | `actions/setup-node` so the JS suite runs in CI |
| `README.md`, `docs/tic-tac-toe-plan.md` | Truth pass — Task 10 |

---

# Task 1: Reject `EMPTY` as a player, at the right layer

**Files:**
- Modify: `common/src/main/java/com/flamingo/tiktaktoe/common/CellState.java:14-16`
- Modify: `game-engine-service/src/main/java/com/flamingo/tiktaktoe/engine/validation/MoveValidator.java:19-24`
- Modify: `game-engine-service/src/main/java/com/flamingo/tiktaktoe/engine/service/GameEngineService.java:55-62`
- Test: `common/src/test/java/com/flamingo/tiktaktoe/common/CellStateTest.java`
- Test: `game-engine-service/src/test/java/com/flamingo/tiktaktoe/engine/validation/MoveValidatorTest.java`
- Test: `game-engine-service/src/test/java/com/flamingo/tiktaktoe/engine/controller/GameControllerIntegrationTest.java`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `MoveValidator.isPlayerSymbol(CellState) : boolean` and the narrowed
  `MoveValidator.canPlay(List<List<CellState>> board, int row, int col) : boolean`
  (the `CellState player` parameter is **removed**). Task 3 does not touch these.

**Why this ordering matters.** `GameEngineService.makeMove` currently runs
`assertPlayable` (turn check → 409) *before* `validator.canPlay`. A body carrying
`player: "EMPTY"` therefore fails the turn check and answers `409 "Not EMPTY's
turn"`. `EMPTY` is not a player at all, so the symbol check has to run *first* and
produce a 400. Verified against the running service on 2026-08-13.

- [x] **Step 1: Write the failing tests**

`common/src/test/java/com/flamingo/tiktaktoe/common/CellStateTest.java` — add:

```java
@Test
void oppositeOfEmptyIsRejected() {
    assertThatThrownBy(CellState.EMPTY::opposite)
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("EMPTY");
}
```

`game-engine-service/src/test/java/com/flamingo/tiktaktoe/engine/validation/MoveValidatorTest.java` — add:

```java
@Test
void xAndOArePlayerSymbols() {
    assertThat(validator.isPlayerSymbol(CellState.X)).isTrue();
    assertThat(validator.isPlayerSymbol(CellState.O)).isTrue();
}

@Test
void emptyIsNotAPlayerSymbol() {
    assertThat(validator.isPlayerSymbol(CellState.EMPTY)).isFalse();
}

@Test
void nullIsNotAPlayerSymbol() {
    assertThat(validator.isPlayerSymbol(null)).isFalse();
}
```

`game-engine-service/src/test/java/com/flamingo/tiktaktoe/engine/controller/GameControllerIntegrationTest.java` — add:

```java
@Test
void moveWithEmptyAsPlayerIsRejectedAsBadRequest() throws Exception {
    mockMvc.perform(post("/games/{id}/move", "empty-player-game")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"player\":\"EMPTY\",\"row\":0,\"col\":0}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(
                    org.hamcrest.Matchers.containsString("turn"))));
}

@Test
void aRejectedEmptyPlayerCreatesNoGame() throws Exception {
    mockMvc.perform(post("/games/{id}/move", "empty-player-no-game")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"player\":\"EMPTY\",\"row\":0,\"col\":0}"));
    mockMvc.perform(get("/games/{id}", "empty-player-no-game"))
            .andExpect(status().isNotFound());
}
```

- [x] **Step 2: Run the tests and confirm they fail for the right reason**

```bash
./gradlew :common:test --tests '*CellStateTest*'
./gradlew :game-engine-service:test --tests '*MoveValidatorTest*' --tests '*GameControllerIntegrationTest*'
```

Expected: `CellStateTest` fails because `EMPTY.opposite()` returns `X` instead of
throwing; `MoveValidatorTest` fails to compile because `isPlayerSymbol` does not
exist; the controller test fails with `expected 400 but was 409`.

- [x] **Step 3: Implement**

`CellState.java`:

```java
public CellState opposite() {
    if (this == EMPTY) {
        throw new IllegalArgumentException("EMPTY has no opposite — it is not a player");
    }
    return this == X ? O : X;
}
```

`MoveValidator.java` — the `player` parameter moves out of `canPlay` (it was never
read) and into a check of its own:

```java
/** X and O are players; EMPTY is a cell state, not a symbol anyone can play. */
public boolean isPlayerSymbol(CellState player) {
    return player == CellState.X || player == CellState.O;
}

/** A move is legal when the position is within bounds and the target cell is empty. */
public boolean canPlay(List<List<CellState>> board, int row, int col) {
    if (board == null || row < 0 || col < 0 || row >= SIZE || col >= SIZE) {
        return false;
    }
    return board.get(row).get(col) == CellState.EMPTY;
}
```

`GameEngineService.makeMove` — the symbol check runs **before** the game is even
loaded, so an invalid symbol cannot create a game as a side effect:

```java
@Transactional
public GameState makeMove(String gameId, MoveRequest move) {
    if (!validator.isPlayerSymbol(move.player())) {
        throw new InvalidMoveException("Player must be X or O");
    }
    GameEntity entity = repository.findById(gameId).orElseGet(() -> createGame(gameId));
    assertPlayable(entity, move);

    List<List<CellState>> board = mapper.parseBoard(entity.getBoard());
    if (!validator.canPlay(board, move.row(), move.col())) {
        throw new InvalidMoveException("Cell " + move.row() + "," + move.col() + " is not playable");
    }
    ...
```

- [x] **Step 4: Run the tests and confirm green, including the mutation gate**

```bash
./gradlew :common:test :game-engine-service:check
```

Expected: PASS, Pitest still ≥80% on `game-engine-service`.

- [x] **Step 5: Commit** (after the user confirms — see Global Constraints)

```bash
git add common/src game-engine-service/src
git commit -m "[MILESTONE-10] Reject EMPTY as a player with 400 instead of 409"
```

---

# Task 2: Put `common` behind the mandatory gates

**Files:**
- Modify: `common/build.gradle.kts`
- Test: `common/src/test/java/com/flamingo/tiktaktoe/common/GameStateFactoryTest.java`
- Test: `common/src/test/java/com/flamingo/tiktaktoe/common/ErrorResponseTest.java`

**Interfaces:**
- Consumes: Task 1's `CellState.opposite()` behaviour (its new test is part of what
  keeps the mutation score up).
- Produces: `./gradlew :common:check` now fails below 80% mutation or 80% line
  coverage. Every later task that touches `common` must keep it green.

**Why.** `CLAUDE.md` calls mutation testing mandatory for all production code, but
the Pitest plugin was only ever added to the two modules that came *after* the rule
existed. `common` holds `CellState`, `GameStateFactory` and `ErrorResponse` and is
gated by nothing — which is exactly where Task 1's bug was living.

- [x] **Step 1: Add the plugins and the gates**

`common/build.gradle.kts`:

```kotlin
plugins {
    java
    id("io.spring.dependency-management") version "1.1.7"
    jacoco
    id("info.solidsoft.pitest") version "1.19.0"
}
```

and, after the existing `tasks.named<Test>("test")` block:

```kotlin
tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
    dependsOn(tasks.named("test"))
    violationRules {
        rule {
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.80".toBigDecimal()
            }
        }
    }
}

tasks.named("check") {
    dependsOn(tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification"))
    dependsOn(tasks.named("pitest"))
}

pitest {
    junit5PluginVersion.set("1.2.1")
    pitestVersion.set("1.22.1")
    targetClasses.set(setOf("com.flamingo.tiktaktoe.common.*"))
    outputFormats.set(setOf("XML", "HTML"))
    timestampedReports.set(false)
    verbose.set(true)
    mutationThreshold.set(80)
}
```

- [x] **Step 2: Run the gate and read the surviving mutants**

```bash
./gradlew :common:check
open common/build/reports/pitest/index.html
```

Expected: it may well **fail** on the first run. That failure is the point — it is
the list of assertions the module never had.

- [x] **Step 3: Kill the surviving mutants with real assertions**

Do not weaken the threshold. For each survivor, add a test that asserts the
behaviour the mutant changed. Known thin spots to expect:

```java
// ErrorResponseTest — the factory sets every field, not just some
@Test
void ofPopulatesEveryField() {
    ErrorResponse response = ErrorResponse.of(404, "Not Found", "no such game", "/games/x");
    assertThat(response.status()).isEqualTo(404);
    assertThat(response.error()).isEqualTo("Not Found");
    assertThat(response.message()).isEqualTo("no such game");
    assertThat(response.path()).isEqualTo("/games/x");
    assertThat(response.timestamp()).isNotNull();
}

// GameStateFactoryTest — the returned board is genuinely immutable at both levels
@Test
void theEmptyBoardCannotBeMutatedByACaller() {
    List<List<CellState>> board = GameStateFactory.empty("g").board();
    assertThatThrownBy(() -> board.get(0).set(0, CellState.X))
            .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> board.add(List.of()))
            .isInstanceOf(UnsupportedOperationException.class);
}

@Test
void everyFreshGameStartsInProgressWithXToMoveAndNoWinner() {
    GameState state = GameStateFactory.empty("g");
    assertThat(state.id()).isEqualTo("g");
    assertThat(state.status()).isEqualTo(GameStatus.IN_PROGRESS);
    assertThat(state.nextTurn()).isEqualTo(CellState.X);
    assertThat(state.winner()).isNull();
}
```

- [x] **Step 4: Confirm green**

```bash
./gradlew :common:check
```

Expected: BUILD SUCCESSFUL, mutation ≥80%.

- [x] **Step 5: Commit**

```bash
git add common/
git commit -m "[MILESTONE-10] Gate the common module with Pitest and JaCoCo"
```

---

# Task 3: One error contract, one implementation

**Files:**
- Create: `common/src/main/java/com/flamingo/tiktaktoe/common/web/AbstractRestExceptionHandler.java`
- Create: `common/src/test/java/com/flamingo/tiktaktoe/common/web/AbstractRestExceptionHandlerTest.java`
- Modify: `common/build.gradle.kts`
- Modify: `game-engine-service/src/main/java/com/flamingo/tiktaktoe/engine/exception/GameExceptionHandler.java`
- Modify: `game-session-service/src/main/java/com/flamingo/tiktaktoe/session/exception/SessionExceptionHandler.java`
- Test: `game-session-service/src/test/java/com/flamingo/tiktaktoe/session/exception/SessionExceptionHandlerTest.java`

**Interfaces:**
- Consumes: `ErrorResponse.of(int, String, String, String)` from `common`.
- Produces: `AbstractRestExceptionHandler` with `protected static
  ResponseEntity<ErrorResponse> errorResponse(HttpStatus, String, HttpServletRequest)`
  and `protected Logger log()`. Subclasses add only their own domain handlers.

**The dependency decision, stated explicitly.** `common` currently declares only
`jakarta.validation-api`; this task puts a Spring MVC type in it. That is
deliberate and bounded: `common` already owns the *error contract*
(`ErrorResponse`), so owning the plumbing that produces it is coherent, and the
web dependency is declared **`compileOnly`** — it is used to compile the base
class and never travels to a consumer, both of which already have Spring MVC on
their own classpath. If a future non-web consumer of `common` appears, this is the
one thing to revisit.

`common/build.gradle.kts` gains:

```kotlin
compileOnly("org.springframework:spring-web")
compileOnly("org.springframework:spring-webmvc")
compileOnly("jakarta.servlet:jakarta.servlet-api")
testImplementation("org.springframework:spring-web")
testImplementation("org.springframework:spring-webmvc")
testImplementation("jakarta.servlet:jakarta.servlet-api")
```

- [x] **Step 1: Write the failing test**

`common/src/test/java/com/flamingo/tiktaktoe/common/web/AbstractRestExceptionHandlerTest.java`:

```java
class AbstractRestExceptionHandlerTest {

    private static final class TestHandler extends AbstractRestExceptionHandler {
    }

    private final TestHandler handler = new TestHandler();

    private MockHttpServletRequest requestTo(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI(uri);
        return request;
    }

    @Test
    void methodNotAllowedCarriesAnAllowHeaderAndNoExceptionInternals() {
        HttpRequestMethodNotSupportedException ex =
                new HttpRequestMethodNotSupportedException("PUT", List.of("GET", "POST"));

        ResponseEntity<ErrorResponse> response =
                handler.handleMethodNotSupported(ex, requestTo("/games/abc"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getHeaders().getAllow())
                .containsExactlyInAnyOrder(HttpMethod.GET, HttpMethod.POST);
        assertThat(response.getBody().message()).doesNotContain("PUT is not supported");
        assertThat(response.getBody().path()).isEqualTo("/games/abc");
    }

    @Test
    void unknownResourceIsNotFoundWithAFixedMessage() {
        ResponseEntity<ErrorResponse> response = handler.handleNoResourceFound(
                new NoResourceFoundException(HttpMethod.GET, "/nope"), requestTo("/nope"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().message()).isEqualTo("Resource not found");
    }

    @Test
    void theCatchAllNeverLeaksTheThrowableIntoTheBody() {
        ResponseEntity<ErrorResponse> response = handler.handleGeneric(
                new IllegalStateException("connection string user=admin password=hunter2"),
                requestTo("/sessions/abc"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().message()).isEqualTo("Internal server error");
        assertThat(response.getBody().message()).doesNotContain("hunter2");
    }
}
```

- [x] **Step 2: Run it and confirm it fails**

```bash
./gradlew :common:test --tests '*AbstractRestExceptionHandlerTest*'
```

Expected: compilation failure — `AbstractRestExceptionHandler` does not exist.

- [x] **Step 3: Implement the base class**

Move the shared handlers out of `GameExceptionHandler` verbatim (they are the
better of the two implementations — the session copy is the one missing the
`Allow` header and leaking `ex.getMessage()`):

```java
package com.flamingo.tiktaktoe.common.web;

/**
 * The half of a service's {@code @RestControllerAdvice} that is not
 * service-specific: the catch-all 500, unknown resource, unsupported method, and
 * the single place that builds an {@link ErrorResponse}.
 *
 * <p>It exists because the two services had drifted: one answered 405 with an
 * {@code Allow} header per RFC 9110 and the other did not, and both put
 * {@code ex.getMessage()} in the body, which CLAUDE.md forbids. A shared base
 * makes "the whole system answers errors in one shape" true by construction
 * rather than by two people remembering.
 *
 * <p>Subclasses add their own domain handlers and are the ones annotated
 * {@code @RestControllerAdvice} — this class is not a bean.
 */
public abstract class AbstractRestExceptionHandler {

    protected static final String GENERIC_SERVER_ERROR = "Internal server error";

    private final Logger log = LoggerFactory.getLogger(getClass());

    protected Logger log() {
        return log;
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResourceFound(NoResourceFoundException ex,
                                                               HttpServletRequest request) {
        return errorResponse(HttpStatus.NOT_FOUND, "Resource not found", request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {
        Set<HttpMethod> methods = Arrays.stream(
                        ex.getSupportedMethods() == null ? new String[0] : ex.getSupportedMethods())
                .map(HttpMethod::valueOf)
                .collect(Collectors.toSet());
        HttpHeaders headers = new HttpHeaders();
        headers.setAllow(methods);
        ErrorResponse body = ErrorResponse.of(
                HttpStatus.METHOD_NOT_ALLOWED.value(),
                HttpStatus.METHOD_NOT_ALLOWED.getReasonPhrase(),
                "Method not allowed",
                request.getRequestURI());
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).headers(headers).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception ex, HttpServletRequest request) {
        log.error("Unexpected error on {}", request.getRequestURI(), ex);
        return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, GENERIC_SERVER_ERROR, request);
    }

    protected static ResponseEntity<ErrorResponse> errorResponse(HttpStatus status, String message,
                                                                 HttpServletRequest request) {
        ErrorResponse body = ErrorResponse.of(
                status.value(), status.getReasonPhrase(), message, request.getRequestURI());
        return ResponseEntity.status(status).body(body);
    }
}
```

- [x] **Step 4: Reduce both services to their domain handlers**

`GameExceptionHandler extends AbstractRestExceptionHandler` keeps only
`GameNotFoundException`, `MethodArgumentNotValidException`, `InvalidMoveException`,
`GameConflictException`, `OptimisticLockingFailureException`,
`DataIntegrityViolationException`, `HttpMessageNotReadableException`,
`BoardMappingException` — and deletes its own `handleNoResourceFound`,
`handleMethodNotSupported`, `handleGeneric` and the private `errorResponse`.

`SessionExceptionHandler extends AbstractRestExceptionHandler` keeps only
`SessionNotFoundException` and `SessionConflictException` (plus
`SessionCapacityException` once Task 5 lands), and deletes the same three.

- [x] **Step 5: Prove the session service's 405 changed behaviour**

Add to `SessionExceptionHandlerTest`:

```java
@Test
void methodNotAllowedNowCarriesTheAllowHeader() throws Exception {
    mockMvc.perform(put("/sessions/{id}", "any"))
            .andExpect(status().isMethodNotAllowed())
            .andExpect(header().exists("Allow"))
            .andExpect(jsonPath("$.message").value("Method not allowed"));
}
```

- [x] **Step 6: Run everything**

```bash
./gradlew :common:check :game-engine-service:check :game-session-service:check
```

Expected: all green. Both services answer 405 identically.

- [x] **Step 7: Commit**

```bash
git add common game-engine-service game-session-service
git commit -m "[MILESTONE-10] Share one exception-handling base so both services answer errors identically"
```

---

# Task 4: Bounded session retention

**Files:**
- Create: `game-session-service/src/main/java/com/flamingo/tiktaktoe/session/store/SessionRetentionPolicy.java`
- Modify: `game-session-service/src/main/java/com/flamingo/tiktaktoe/session/store/InMemorySessionStore.java`
- Modify: `game-session-service/src/main/resources/application.yml`
- Test: `game-session-service/src/test/java/com/flamingo/tiktaktoe/session/store/InMemorySessionStoreTest.java`

**Interfaces:**
- Consumes: `SessionStore` (unchanged — this is deliberately an implementation
  detail, so no service, controller or DTO changes).
- Produces: `SessionRetentionPolicy(Duration terminalRetention, int maxSessions)`
  and `InMemorySessionStore.evictExpired(Instant now) : int` (package-private,
  returns how many were removed — that return value is what makes it testable
  without sleeping).

**Why.** The map never removes anything. Every session ever created lives until the
JVM restarts, and `POST /sessions` needs no body and no credentials.

**Time is injected, never slept.** The sweep takes an `Instant now` and the store
holds a `Clock`. A test that proves eviction by sleeping for a real TTL is a slow,
flaky test; a test that passes a later `Instant` is neither.

- [x] **Step 1: Write the failing tests**

```java
@Test
void terminalSessionsAreEvictedOnceTheyAreOlderThanTheRetention() {
    Instant created = Instant.parse("2026-01-01T00:00:00Z");
    InMemorySessionStore store = new InMemorySessionStore(
            new SessionRetentionPolicy(Duration.ofMinutes(10), 100),
            Clock.fixed(created, ZoneOffset.UTC));

    store.save(new SessionRecord("done", SessionStatus.COMPLETED, null, List.of()));

    assertThat(store.evictExpired(created.plus(Duration.ofMinutes(9)))).isZero();
    assertThat(store.find("done")).isNotNull();

    assertThat(store.evictExpired(created.plus(Duration.ofMinutes(11)))).isEqualTo(1);
    assertThat(store.find("done")).isNull();
}

@Test
void aRunningSessionIsNeverEvictedNoMatterHowOld() {
    Instant created = Instant.parse("2026-01-01T00:00:00Z");
    InMemorySessionStore store = new InMemorySessionStore(
            new SessionRetentionPolicy(Duration.ofMinutes(10), 100),
            Clock.fixed(created, ZoneOffset.UTC));

    store.save(new SessionRecord("busy", SessionStatus.RUNNING, null, List.of()));

    assertThat(store.evictExpired(created.plus(Duration.ofDays(365)))).isZero();
    assertThat(store.find("busy")).isNotNull();
}

@Test
void savingBeyondTheCeilingIsRejectedRatherThanGrowingForever() {
    InMemorySessionStore store = new InMemorySessionStore(
            new SessionRetentionPolicy(Duration.ofMinutes(10), 2),
            Clock.systemUTC());

    store.save(new SessionRecord("a", SessionStatus.CREATED, null, List.of()));
    store.save(new SessionRecord("b", SessionStatus.CREATED, null, List.of()));

    assertThatThrownBy(() -> store.save(new SessionRecord("c", SessionStatus.CREATED, null, List.of())))
            .isInstanceOf(SessionCapacityException.class);
}

@Test
void updatingAnExistingSessionIsNeverRejectedByTheCeiling() {
    InMemorySessionStore store = new InMemorySessionStore(
            new SessionRetentionPolicy(Duration.ofMinutes(10), 1),
            Clock.systemUTC());

    store.save(new SessionRecord("a", SessionStatus.CREATED, null, List.of()));

    assertThatCode(() -> store.save(new SessionRecord("a", SessionStatus.RUNNING, null, List.of())))
            .doesNotThrowAnyException();
}
```

The last test is the one that matters most: the ceiling must reject *new* sessions
only. Rejecting an update would strand a running simulation halfway through.

- [x] **Step 2: Run and confirm red**

```bash
./gradlew :game-session-service:test --tests '*InMemorySessionStoreTest*'
```

Expected: compilation failure — `SessionRetentionPolicy` does not exist.

- [x] **Step 3: Implement**

The store now holds `record Entry(SessionRecord record, Instant lastUpdated)`, and:

```java
@Override
public SessionRecord save(SessionRecord record) {
    entries.compute(record.sessionId(), (id, existing) -> {
        if (existing == null && entries.size() >= policy.maxSessions()) {
            throw new SessionCapacityException(
                    "Session capacity reached (" + policy.maxSessions() + "); try again later");
        }
        return new Entry(record, clock.instant());
    });
    return record;
}

/** Package-private and time-injected so a test can advance the clock instead of sleeping. */
int evictExpired(Instant now) {
    int before = entries.size();
    entries.values().removeIf(entry -> isTerminal(entry.record().status())
            && entry.lastUpdated().plus(policy.terminalRetention()).isBefore(now));
    return before - entries.size();
}

@Scheduled(fixedDelayString = "${session.store.sweep-interval-ms}")
void sweep() {
    int evicted = evictExpired(clock.instant());
    if (evicted > 0) {
        log.debug("Evicted {} terminal session(s) past retention", evicted);
    }
}
```

`@EnableScheduling` goes on the existing `AsyncConfig` (a `TaskScheduler`, per
CLAUDE.md — never a `Thread.sleep` pacing loop).

`application.yml`:

```yaml
session:
  store:
    # A finished session is kept long enough for a browser to fetch its result and
    # for an operator to look at it, then removed. Without this the map only grows.
    terminal-retention-ms: 900000   # 15 min
    sweep-interval-ms: 60000        # 1 min
    # A ceiling, not a target: reaching it means something is creating sessions
    # faster than they finish, and answering 503 is better than an OOM.
    max-sessions: 10000
```

`SessionCapacityException` maps to **503 Service Unavailable** in
`SessionExceptionHandler` (the condition is transient and retryable — 507 is about
storage the server owns permanently, and 429 would imply per-client rate limiting,
which this is not):

```java
@ExceptionHandler(SessionCapacityException.class)
public ResponseEntity<ErrorResponse> handleCapacity(SessionCapacityException ex,
                                                    HttpServletRequest request) {
    log().warn("Rejected a session: {}", ex.getMessage());
    return errorResponse(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage(), request);
}
```

- [x] **Step 4: Add the endpoint-level test**

`SessionControllerIntegrationTest`:

```java
@Test
void creatingASessionBeyondTheCeilingAnswers503WithTheSharedErrorBody() throws Exception {
    // property override via @TestPropertySource(properties = "session.store.max-sessions=1")
    mockMvc.perform(post("/sessions")).andExpect(status().isCreated());
    mockMvc.perform(post("/sessions"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.status").value(503))
            .andExpect(jsonPath("$.path").value("/sessions"));
}
```

- [x] **Step 5: Run**

```bash
./gradlew :game-session-service:check
```

- [x] **Step 6: Commit**

```bash
git add game-session-service
git commit -m "[MILESTONE-10] Bound session retention with a TTL sweep and a capacity ceiling"
```

---

# Task 5: A ceiling on concurrent simulations

**Files:**
- Modify: `game-session-service/src/main/java/com/flamingo/tiktaktoe/session/service/SessionSimulationRunner.java`
- Modify: `game-session-service/src/main/java/com/flamingo/tiktaktoe/session/config/AsyncConfig.java`
- Modify: `game-session-service/src/main/resources/application.yml`
- Test: `game-session-service/src/test/java/com/flamingo/tiktaktoe/session/service/SessionSimulationRunnerTest.java`

**Interfaces:**
- Consumes: Task 4's store.
- Produces: no new public signatures — `run` gains
  `@ConcurrencyLimit("${session.simulation.max-concurrent}")`.

**Why, and why here specifically.** `spring.threads.virtual.enabled: true` means a
blocked simulation costs a virtual thread instead of a pool slot — the config
comment says outright that concurrency is "bounded by memory rather than pool
size". That is a ceiling of "when the JVM dies".

`org.springframework.resilience.annotation.ConcurrencyLimit` is in
spring-context 7.0.8 (verified in the jar on 2026-08-13) together with
`EnableResilientMethods` and `InvocationRejectedException` — no extra dependency.

**Put it on `run`, not on `simulate`.** `simulate` returns the moment it hands off,
so limiting it would limit nothing. Limiting `run` — with the default blocking
`ThrottlePolicy` — makes surplus simulations *wait for a slot* rather than run all
at once, which is the intended behaviour: a session already claimed RUNNING must
not be dropped, and the HTTP caller already got its 202.

- [x] **Step 1: Write the failing test**

```java
@Test
void noMoreThanTheConfiguredNumberOfSimulationsRunAtOnce() throws Exception {
    // The stub engine client blocks until released, so every started simulation
    // parks inside makeMove and the concurrent count is observable.
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger inFlight = new AtomicInteger();
    AtomicInteger peak = new AtomicInteger();

    GameEngineClient blocking = (gameId, move) -> {
        peak.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
        try {
            release.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        inFlight.decrementAndGet();
        throw new IllegalStateException("stop the loop after one move");
    };

    // runner built with max-concurrent = 2, then 5 sessions started
    ...
    release.countDown();
    assertThat(peak.get()).isLessThanOrEqualTo(2);
}
```

- [x] **Step 2: Run and confirm red** — `peak` reaches 5.

```bash
./gradlew :game-session-service:test --tests '*SessionSimulationRunnerTest*'
```

- [x] **Step 3: Implement**

`AsyncConfig` gains `@EnableResilientMethods` alongside `@EnableAsync`, and
`SessionSimulationRunner.run` gains:

```java
@Async
@ConcurrencyLimit("${session.simulation.max-concurrent}")
public void run(String sessionId) {
```

```yaml
session:
  simulation:
    move-delay-ms: 1000
    # Virtual threads make an unbounded number of parked simulations *possible*,
    # not *desirable*: each one holds a session record, a game row in the engine
    # and an open SSE emitter. This is the ceiling those add up to.
    max-concurrent: 50
```

- [x] **Step 4: Confirm green and that a full game still plays**

```bash
./gradlew :game-session-service:check
```

Expected: the limit test passes **and** `SessionAutoPlayIntegrationTest` plus the
whole `integrationTest` suite still complete — a limit that deadlocks the happy
path is worse than no limit.

- [x] **Step 5: Commit**

```bash
git add game-session-service
git commit -m "[MILESTONE-10] Cap concurrent auto-play simulations with @ConcurrencyLimit"
```

---

# Task 6: Metrics, tracing and correlated logs

**Files:**
- Create: `game-session-service/src/main/java/com/flamingo/tiktaktoe/session/service/SimulationMetrics.java`
- Create: `game-engine-service/src/main/java/com/flamingo/tiktaktoe/engine/service/EngineMetrics.java`
- Modify: both `build.gradle.kts`, both `application.yml`, `SessionSimulationRunner`, `GameEngineService`
- Test: `game-session-service/src/test/java/com/flamingo/tiktaktoe/session/service/SimulationMetricsTest.java`
- Test: `game-engine-service/src/test/java/com/flamingo/tiktaktoe/engine/service/EngineMetricsTest.java`
- Test: `game-session-service/src/integrationTest/java/.../integration/TracePropagationIT.java`

**Interfaces:**
- Consumes: Micrometer's `MeterRegistry` (auto-configured by actuator).
- Produces: `SimulationMetrics.recordCompleted(Duration)`,
  `recordFailed(String reason)`, `recordMoveApplied()`;
  `EngineMetrics.recordMoveApplied(GameStatus)`, `recordMoveRejected(String reason)`.

**Stack choice, and why not Datadog.** Datadog needs an account, an API key and an
agent container — three external dependencies in a project whose whole promise is
`docker compose up` on a clean machine. Micrometer is already on the classpath via
actuator; adding the Prometheus registry is one BOM-managed dependency and one
exposed endpoint, with no external service at all. Datadog later is a *registry
swap*, not a rewrite — which is exactly the seam argument this codebase makes
everywhere else.

Dependencies (no versions — BOM-managed, per Global Constraints):

```kotlin
implementation("io.micrometer:micrometer-registry-prometheus")
implementation("io.micrometer:micrometer-tracing-bridge-otel")
implementation("io.opentelemetry:opentelemetry-exporter-otlp")
```

- [x] **Step 1: Verify the BOM manages those coordinates before writing code**

```bash
./gradlew :game-session-service:dependencies --configuration runtimeClasspath | grep -E "micrometer-registry-prometheus|micrometer-tracing|opentelemetry-exporter-otlp"
```

Expected: each resolves to a concrete version with no explicit version in the build
file. If one does not resolve, it is **not** BOM-managed — pin it explicitly and
say so in a comment, exactly as `spring-retry` is pinned today.

- [x] **Step 2: Write the failing metrics tests**

```java
@Test
void aCompletedSimulationIsCountedAndTimed() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    SimulationMetrics metrics = new SimulationMetrics(registry);

    metrics.recordCompleted(Duration.ofSeconds(9));

    assertThat(registry.get("tiktaktoe.simulation")
            .tag("outcome", "completed").timer().count()).isEqualTo(1);
    assertThat(registry.get("tiktaktoe.simulation")
            .tag("outcome", "completed").timer().totalTime(TimeUnit.SECONDS)).isEqualTo(9.0);
}

@Test
void failuresAreCountedByReasonSoOneCauseCannotHideBehindAnother() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    SimulationMetrics metrics = new SimulationMetrics(registry);

    metrics.recordFailed("engine-unavailable");
    metrics.recordFailed("engine-unavailable");
    metrics.recordFailed("interrupted");

    assertThat(registry.get("tiktaktoe.simulation.failed")
            .tag("reason", "engine-unavailable").counter().count()).isEqualTo(2.0);
    assertThat(registry.get("tiktaktoe.simulation.failed")
            .tag("reason", "interrupted").counter().count()).isEqualTo(1.0);
}
```

Engine side:

```java
@Test
void appliedMovesAreCountedByResultingStatus() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    EngineMetrics metrics = new EngineMetrics(registry);

    metrics.recordMoveApplied(GameStatus.IN_PROGRESS);
    metrics.recordMoveApplied(GameStatus.WIN);

    assertThat(registry.get("tiktaktoe.moves.applied")
            .tag("status", "IN_PROGRESS").counter().count()).isEqualTo(1.0);
    assertThat(registry.get("tiktaktoe.moves.applied")
            .tag("status", "WIN").counter().count()).isEqualTo(1.0);
}
```

- [x] **Step 3: Run and confirm red**

```bash
./gradlew :game-session-service:test --tests '*SimulationMetricsTest*'
./gradlew :game-engine-service:test --tests '*EngineMetricsTest*'
```

- [x] **Step 4: Implement the metric holders and call them**

`SimulationMetrics` and `EngineMetrics` are thin, injected collaborators — the
loop and the rules keep their single responsibility and simply report. **No metric
is recorded from a controller**; they belong where the event happens.

Names, fixed here so later tasks and dashboards agree:

| Meter | Type | Tags |
|---|---|---|
| `tiktaktoe.simulation` | timer | `outcome` = `completed` \| `failed` |
| `tiktaktoe.simulation.failed` | counter | `reason` |
| `tiktaktoe.simulation.moves` | counter | — |
| `tiktaktoe.moves.applied` | counter | `status` = `IN_PROGRESS` \| `WIN` \| `DRAW` |
| `tiktaktoe.moves.rejected` | counter | `reason` = `not-playable` \| `wrong-turn` \| `finished` \| `bad-symbol` |
| `tiktaktoe.sessions.active` | gauge | — (reads the store's size) |

- [x] **Step 5: Expose the endpoint on both services**

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus,metrics
  tracing:
    sampling:
      # 1.0 is a development setting: this system produces a handful of traces per
      # game, and a sampled-away trace is useless when the whole point is to follow
      # one session end to end. Lower it the day traffic is real.
      probability: 1.0
  otlp:
    tracing:
      # Unset by default so nothing is exported and no collector is required to run
      # the stack; set OTEL endpoint via env when a collector exists.
      endpoint: ${OTEL_EXPORTER_OTLP_ENDPOINT:}
```

- [x] **Step 6: Correlated logs**

Add to both services' `application.yml` so every line carries the ids that let a
Session log line be joined to the Engine line it caused:

```yaml
logging:
  pattern:
    level: "%5p [${spring.application.name},%X{traceId:-},%X{spanId:-}]"
```

The simulation runner keeps `sessionId` in its explicit log messages. A custom MDC
scope is unnecessary because Micrometer already supplies `traceId` and `spanId`, and
thread-pool context must not leak between simulations.

- [x] **Step 7: Prove the trace actually crosses the service boundary**

`TracePropagationIT` (in the existing `integrationTest` source set, which already
boots a real Engine):

```java
@Test
void theEngineSeesTheTraceIdTheSessionStarted() {
    // Run a session against the embedded engine, capture the traceparent header the
    // engine received, and assert it carries the same trace id the session's
    // Tracer reports for the simulation.
}
```

This is the assertion that makes tracing real rather than configured: a
`traceparent` header that is generated but never propagated looks identical in the
config file.

- [x] **Step 8: Run the whole thing**

```bash
./gradlew :game-engine-service:check :game-session-service:check
curl -s localhost:8081/actuator/prometheus | grep tiktaktoe
```

- [x] **Step 9: Commit**

```bash
git add game-engine-service game-session-service
git commit -m "[MILESTONE-10] Add Prometheus metrics, OTel tracing and correlated logs"
```

---

# Task 7: Test the frontend, and stop leaking streams

**Files:**
- Create: `ui-service/package.json`, `ui-service/vitest.config.js`
- Create: `ui-service/src/main/resources/static/render.js`
- Create: `ui-service/src/test/javascript/render.test.js`
- Create: `ui-service/src/test/javascript/session-flow.test.js`
- Modify: `ui-service/src/main/resources/static/app.js`
- Modify: `ui-service/src/main/resources/static/index.html` (module script tags)
- Modify: `ui-service/build.gradle.kts`, `.github/workflows/ci.yml`

**Interfaces:**
- Produces (`render.js`, ES module exports): `describeStatus(session) : string`,
  `historyLines(moves) : string[]`, `boardCells(board) : string[]` — pure
  functions, no DOM. `app.js` keeps the DOM wiring and imports these.

**The decision this reverses, stated plainly.** Milestone 4 chose "no framework, no
npm, no build step" and called a frontend toolchain a KISS/YAGNI violation. That
reasoning was about the **runtime**: the page still ships as plain static files
with no bundler and no `node_modules` in the image. What is added here is a
**test-only** toolchain, which the M4 argument never covered — and the cost of not
having it is 234 lines of logic covered by nothing while the Java side is gated at
80% mutation. The Docker image is unaffected: `bootJar` does not run the JS suite.

- [x] **Step 1: Set up the toolchain**

`ui-service/package.json`:

```json
{
  "name": "tiktaktoe-ui",
  "private": true,
  "type": "module",
  "scripts": { "test": "vitest run" },
  "devDependencies": { "jsdom": "^28.0.0", "vitest": "^4.0.0" }
}
```

- [x] **Step 2: Write the failing tests**

`render.test.js`:

```js
import { describe, expect, it } from 'vitest';
import { describeStatus, historyLines, boardCells } from '../../main/resources/static/render.js';

describe('describeStatus', () => {
    it('says "Not started" when there is no game yet', () => {
        expect(describeStatus({ status: 'CREATED', gameState: null })).toBe('Not started');
    });

    it('leads with the status word, then the detail', () => {
        expect(describeStatus({ gameState: { status: 'WIN', winner: 'X' } }))
            .toBe('Win — X won the game');
    });

    it('shows an unknown status verbatim rather than blank', () => {
        expect(describeStatus({ gameState: { status: 'ABANDONED' } })).toBe('ABANDONED');
    });
});

describe('historyLines', () => {
    it('renders coordinates 1-based for humans while the wire stays 0-based', () => {
        expect(historyLines([{ player: 'X', row: 2, col: 1 }]))
            .toEqual(['X → row 3, column 2']);
    });
});

describe('boardCells', () => {
    it('renders a null board as nine blanks', () => {
        expect(boardCells(null)).toEqual(Array(9).fill(''));
    });

    it('renders EMPTY as blank and marks as themselves', () => {
        expect(boardCells([['X', 'EMPTY', 'O'], ['EMPTY', 'X', 'EMPTY'], ['O', 'EMPTY', 'X']]))
            .toEqual(['X', '', 'O', '', 'X', '', 'O', '', 'X']);
    });
});
```

`session-flow.test.js` — the leak from finding #11:

```js
it('closes the stream when the simulate call fails, instead of leaving it open', async () => {
    // fetch: POST /sessions -> 201, POST /simulate -> 503
    // EventSource is stubbed and records close() calls
    await startSimulation();
    expect(eventSourceStub.close).toHaveBeenCalledOnce();
});

it('does not open a second stream when start is clicked twice', async () => {
    await startSimulation();
    await startSimulation();
    expect(openedStreams).toHaveLength(1);
});
```

- [x] **Step 3: Run and confirm red**

```bash
cd ui-service && npm install && npm test
```

Expected: `render.js` does not exist; the flow tests fail because `app.js` never
calls `close()` on the error paths.

- [x] **Step 4: Extract the pure functions and fix the leak**

`app.js` keeps a module-level reference to the current `EventSource` and closes it
before opening another and on every terminal path (`done`, create failure, simulate
failure).

- [x] **Step 5: Wire it into Gradle and CI**

`ui-service/build.gradle.kts`:

```kotlin
val npmTest = tasks.register<Exec>("npmTest") {
    description = "Runs the Vitest suite for the static page."
    group = "verification"
    workingDir = projectDir
    commandLine("npm", "test", "--silent")
}
tasks.named("check") { dependsOn(npmTest) }
```

`.github/workflows/ci.yml` — add before the Gradle step:

```yaml
      - name: Set up Node
        uses: actions/setup-node@v6
        with:
          node-version: '22'
      - name: Install UI test dependencies
        run: npm ci --prefix ui-service
```

If `npm ci` needs a lockfile, commit `ui-service/package-lock.json`.

- [x] **Step 6: Run**

```bash
./gradlew :ui-service:check
```

- [x] **Step 7: Commit**

```bash
git add ui-service .github/workflows/ci.yml
git commit -m "[MILESTONE-10] Test the UI logic and close the SSE stream on every exit path"
```

---

# Task 8: The config and cleanup batch

**Files:**
- Modify: `game-engine-service/src/main/resources/application.yml`
- Modify: `game-engine-service/src/main/java/.../service/GameEngineService.java:68`
- Modify: `game-session-service/build.gradle.kts:25,47,166-169`
- Modify: `game-session-service/src/main/java/.../service/SessionSimulationRunner.java:96`

**Interfaces:** no signature changes.

Each item here is independently small; they ship as one commit because none of them
is worth a reviewer's separate gate.

- [x] **Step 1: Write the tests that pin the behavioural ones**

```java
// GameEngineServiceTest — nextTurn must not flip past the end of the game
@Test
void aWinningMoveLeavesNextTurnOnTheWinnerRatherThanHandingItToTheLoser() {
    // play X to a win, then assert the returned state's nextTurn is X (the last
    // player to move), not O — a finished game has no next player, and pointing at
    // the loser is a lie the API currently tells.
}

// SessionSimulationRunnerTest — an Error must not strand the session RUNNING
@Test
void anErrorFromTheEngineClientStillEndsTheSessionAsFailed() {
    GameEngineClient exploding = (gameId, move) -> { throw new StackOverflowError("boom"); };
    // assert the stored record is FAILED, and the Error is rethrown after recording
}
```

- [x] **Step 2: Run and confirm red**

```bash
./gradlew :game-engine-service:test :game-session-service:test
```

- [x] **Step 3: Apply the changes**

1. `game-engine-service/application.yml`:

```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: update
    # Off explicitly. Left at its default, Hibernate keeps a session open for the
    # whole request including response rendering, which hides lazy loading in the
    # view layer and holds a connection longer than the work needs. Spring logs a
    # warning about this on every start; this is the answer to it.
    open-in-view: false
  h2:
    console:
      # Off by default. The console is a full SQL surface on the service's own port
      # and there is no reason for it to exist in an image. Enable it for local
      # debugging with SPRING_H2_CONSOLE_ENABLED=true.
      enabled: ${H2_CONSOLE_ENABLED:false}
```

2. `GameEngineService.makeMove` — only advance the turn while the game continues:

```java
CellState winner = winnerChecker.getWinner(board);
if (winner != null) {
    entity.setStatus(GameStatus.WIN);
    entity.setWinner(winner);
} else if (winnerChecker.isFull(board)) {
    entity.setStatus(GameStatus.DRAW);
} else {
    // Only a game that continues has a next player. Flipping unconditionally left a
    // finished game pointing at the loser.
    entity.setNextTurn(move.player().opposite());
}
```

3. `game-session-service/build.gradle.kts`:
   - delete `implementation("org.springframework.boot:spring-boot-starter-websocket")`
     — verified unused (nothing in `src` references WebSocket or STOMP; the M5
     design chose SSE and M4 removed this same dependency from `ui-service`)
   - `spring-boot-starter-web` → `spring-boot-starter-webmvc`, matching the engine
   - the Pitest `excludedClasses` comment cites `CorsConfig` and `CorsConfigTest`,
     both deleted in M6. Rewrite it to name only `RestClientConfigTest`.

4. `SessionSimulationRunner` — a `Throwable` safety net so an `Error` cannot leave
   the session RUNNING forever, rethrown so the JVM still sees it:

```java
} catch (RuntimeException e) {
    log.error("Auto-play simulation failed for session {}", sessionId, e);
    store.save(new SessionRecord(sessionId, SessionStatus.FAILED, currentState, List.copyOf(history)));
} catch (Error e) {
    // An Error is not ours to handle, but leaving the session RUNNING forever is
    // worse than recording the truth on the way out. Record, then rethrow.
    log.error("Auto-play simulation for session {} died", sessionId, e);
    store.save(new SessionRecord(sessionId, SessionStatus.FAILED, currentState, List.copyOf(history)));
    throw e;
}
```

- [x] **Step 4: Run everything, including the docker smoke path**

```bash
./gradlew build --continue
docker compose up --build --wait && ./scripts/smoke.sh && docker compose down
```

Expected: green, and no `open-in-view` warning in the engine's startup log.

- [x] **Step 5: Commit**

```bash
git add game-engine-service game-session-service
git commit -m "[MILESTONE-10] Close the configuration and dead-code findings from the audit"
```

---

# Task 9: Drop the hand-pinned `spring-retry` (evaluate, then decide)

**Files:**
- Modify: `game-session-service/build.gradle.kts:35-45`
- Modify: `game-session-service/src/main/java/.../client/RestGameEngineClient.java`
- Modify: `game-session-service/src/main/java/.../config/AsyncConfig.java`
- Test: `game-session-service/src/test/java/.../client/RestGameEngineClientRetryTest.java`

**Why this is a task and not a footnote.** `CLAUDE.md` says "use Spring Boot 4's
built-in `@Retryable`". The code instead pins `spring-retry:2.0.13` by hand *and*
carries a `runtimeOnly("org.aspectj:aspectjweaver")` purely to keep
`@EnableRetry`'s AspectJ proxy creator from failing at class-init. Spring Framework
7.0.8 ships `org.springframework.resilience.annotation.Retryable` and
`@EnableResilientMethods` (verified in the jar), which Task 5 already enables — so
this removes a hand-pinned dependency *and* a workaround.

**This task may legitimately end in "no".** The two annotations are not identical:
`spring-retry` filters with `retryFor`, the framework's uses
`includes`/`excludes`/`predicate`, and the `RetryListener` bean wiring differs. The
deliverable is a decision backed by a passing test suite, not a migration at any
cost.

- [x] **Step 1: Keep the existing retry tests exactly as they are**

`RestGameEngineClientRetryTest` already proves the contract against a real
MockWebServer: 3 attempts on a 5xx, **no** retry on a 4xx, backoff between
attempts. It is the parity harness. **Do not modify it** — if it cannot pass
against the framework annotation, that is the finding.

- [x] **Step 2: Swap the annotation**

```java
import org.springframework.resilience.annotation.Retryable;

@Override
@Retryable(
        includes = {ResourceAccessException.class, HttpServerErrorException.class},
        maxAttempts = 3,
        delay = 500,
        multiplier = 2)
public GameState makeMove(String gameId, MoveRequest move) {
```

Then remove from `build.gradle.kts`:

```kotlin
implementation("org.springframework.retry:spring-retry:2.0.13")
runtimeOnly("org.aspectj:aspectjweaver")
```

and from `AsyncConfig`: `@EnableRetry` and the `RetryListener` bean (the
framework's equivalent observability is Micrometer's, added in Task 6).

- [x] **Step 3: Run the parity harness**

```bash
./gradlew :game-session-service:test --tests '*RestGameEngineClientRetryTest*'
./gradlew :game-session-service:integrationTest --tests '*EngineUnavailableIT*'
```

- [x] **Step 4: Decide, and record the decision either way**

- **Green →** commit the removal, and note in the README's tech-stack section that
  retry is framework-native.
- **Red →** revert to `spring-retry`, and replace the CLAUDE.md-vs-code mismatch
  with a comment in `RestGameEngineClient` stating exactly which behaviour the
  framework annotation could not reproduce. A documented deliberate choice is a
  fine outcome; an undocumented divergence from CLAUDE.md is not.

- [x] **Step 5: Commit**

```bash
git add game-session-service
git commit -m "[MILESTONE-10] Retry through the framework instead of a hand-pinned spring-retry"
```

---

# Task 10: The documentation truth pass (on `main`, not the branch)

**Files:**
- Modify: `README.md`
- Modify: `docs/tic-tac-toe-plan.md:443-453` (Milestone 5) and `:527-535` (Milestone 10)

**This task is docs, so per CLAUDE.md it goes straight to `main`** — not onto
`milestone-10`. Run it after the branch merges, so it describes what actually
shipped.

- [x] **Step 1: Delete claims that are not true**

The audit found documentation promising behaviour that does not exist. Remove the
promise, not the feature:

1. `docs/tic-tac-toe-plan.md:448` — "Set an event id per move so `Last-Event-ID`
   can replay what was missed after a reconnect". Event ids **are** set; replay is
   **not** implemented — `SessionController.stream()` never reads the header and no
   event buffer exists. Rewrite to state what is true and why it is enough:

   > - [x] Set an event id per move. Note this is **not** replay: the server never
   >   reads `Last-Event-ID`, and it does not need to — every event carries the
   >   full `SessionResponse`, so a client that reconnects is made current by the
   >   next event rather than by re-receiving the ones it missed.

2. Sweep the rest of the plan and the README for the same failure mode with:

```bash
grep -rniE "replay|will be|planned|automatically retr|self-heal" README.md docs/ .claude/plans/
```

For each hit: either it is true, or the sentence changes. Future intentions belong
under *Possible Improvements*, phrased as intentions — never in the present tense.

- [x] **Step 2: Record the boundaries that were chosen, not overlooked**

Add to the README's **Known gaps**, each one sentence with its reason:

- **No authentication or rate limiting.** Every endpoint is open. Deliberate for an
  assignment with no user model; the first thing to add before any real exposure.
- **No schema migrations.** `ddl-auto: update` against in-memory H2. A real
  database needs Flyway and `validate` before it needs anything else here.
- **`CellState` doubles as the player symbol.** `MoveRequest.player` is typed
  `CellState`, so `EMPTY` is syntactically expressible and is rejected at
  validation (400) rather than by the type system. A separate `Player` type is the
  clean fix and is a breaking contract change.
- **A session crash strands its game.** If the session service dies mid-simulation,
  the engine keeps the game `IN_PROGRESS` forever; nothing reconciles it.
- **One game per session, forever.** `sessionId` doubles as `gameId`, as
  `task.md` permits.

- [x] **Step 3: Update the roadmap and the milestone checkboxes**

Tick the four remaining Milestone 10 items in `docs/tic-tac-toe-plan.md:527-535`
(each with the evidence that closes it, matching the convention established in the
earlier milestones), and flip the README roadmap's row 10 from 🔜 to ✅.

- [x] **Step 4: The final end-to-end runs**

The plan's own remaining item is "final end-to-end run of the full game cycle
several times in a row". Three consecutive full games through the gateway, from a
cold stack:

```bash
docker compose up --build --wait
for i in 1 2 3; do ./scripts/smoke.sh || { echo "run $i FAILED"; break; }; done
curl -s localhost:8080/actuator/health
docker compose down
```

Record the result — including the metric values from
`/actuator/prometheus` after the three runs — in this file under a
**Verified on \<date\>** heading, in the same shape Milestone 9 and 11 used.

- [x] **Step 5: Commit to `main`**

```bash
git add README.md docs/tic-tac-toe-plan.md .claude/plans/milestone-10-polish-and-observability.md
git commit -m "Record Milestone 10: what the polish pass fixed and what it deliberately did not"
```

---

## Execution order and why

1–3 first: they are small, self-contained corrections that later tasks build on
(Task 3 needs Task 1's tests to keep `common`'s new mutation gate green, and Task 4
adds a handler to the class Task 3 restructures).

4–5 next: both change session lifecycle, and 5's test depends on 4's store.

6 after them: metrics that count session outcomes need the outcomes to be final.

7 is independent of all of the above and can be done in parallel by a different
agent if the schedule calls for it — it touches no Java.

8–9 last on the branch: 8 is cleanup that benefits from everything else being
settled, and 9 may end in a revert, so nothing should depend on it.

10 after the merge, on `main`.

## Self-review

- **Spec coverage:** all 19 audit findings map to a task (see the table above); all
  four remaining Milestone 10 items from `docs/tic-tac-toe-plan.md:527-535` are
  covered by Tasks 6 (metrics and tracing), 10 (README, comments, final runs) and 9 (`@Retryable`,
  which was already ticked and is now re-examined).
- **Deliberate exclusions:** security and Flyway are named in *Out of scope* and
  documented in Task 10 Step 2 rather than silently dropped.
- **Type consistency:** `MoveValidator.canPlay` loses its `player` parameter in
  Task 1 and no later task calls the old signature; `SessionCapacityException` is
  created in Task 4 and handled in the class Task 3 restructures, so Task 4 must
  run after Task 3 — reflected in the execution order.

---

## Follow-up: harden admission (post Task 4 / Task 5)

> Status: **done and on `main`.** Admission follow-up shipped in PR #27 (session);
> engine/common/UI polish in #26 / #25 / #24. No open implementation work left in
> this plan.

### What landed in Tasks 4–5 (before the follow-up)

| Control | Intent | Where |
|---|---|---|
| `session.store.max-sessions` (default 10 000) | Hard ceiling on how many session records the in-memory store may hold; over capacity → `503` via `SessionCapacityException` | `InMemorySessionStore.save` |
| `session.simulation.max-concurrent` (default 50) + `@ConcurrencyLimit(BLOCK)` on `SessionSimulationRunner.run` | Cap how many auto-play loops may run at once after virtual threads removed the old platform-pool accident limit | `SessionSimulationRunner` |

Both were correct *directions*. The follow-up (below) replaced the soft spots: store admission is now a map-wide `Semaphore`, and simulation overload is a hard `503` before claim (no `@ConcurrencyLimit` wait queue).

### Why this follow-up existed

The polish pass introduced capacity as an operator-facing guarantee (“better a 503 than an OOM / unbounded work”). Two concurrency details made those guarantees softer than the code and docs implied:

1. **Store ceiling check is not map-wide atomic.**  
   `ConcurrentHashMap.compute` is atomic *per key*. Inside the lambda, `entries.size() >= maxSessions` is read while another thread can be inserting a *different* `sessionId`. Two concurrent `POST /sessions` at the boundary can both pass the check and both insert, so the map can exceed `max-sessions`. The sequential capacity IT (`max-sessions=1`, create then create) stays green and does not catch this. A ceiling that can be overrun under parallel create is not a hard ceiling — it only approximates one.

2. **`@ConcurrencyLimit(BLOCK)` bounds active simulations, not accepted work.**  
   `simulate` claims `RUNNING` and hands off to `@Async` immediately (by design — HTTP must not wait for a slot). Surplus virtual threads then park on the concurrency limiter. Active Engine work stays ≤ 50, but a burst can still create a large *waiting* set (up to roughly the store size). Operators reading `max-concurrent: 50` as “load is limited to 50” are reading more than the annotation delivers. Task 5 deliberately chose BLOCK so a session already claimed `RUNNING` is not abandoned; that tradeoff is still valid — the follow-up should make *admission* explicit rather than leave an unbounded wait queue as the silent half of the policy.

Fixing these kept the Milestone 10 story coherent: the limits added for heap and load behave as hard admissions under concurrency, with tests that prove the parallel case — not only the happy sequential path.

Also in the same docs pass: the README **Status** blurb still said Milestones 7 and 10 were open while the Roadmap table marked both ✅ — aligned in this follow-up.

### Proposed shape (settled — built as below)

**A — Atomic store admission**

- Replace “check `size()` inside per-key `compute`” with a **single map-wide admission** (exact mechanism TBD: `Semaphore`, `AtomicInteger` occupied counter with acquire/release, or one store-level lock around check+insert for *new* keys).
- Release / decrement on eviction of terminal sessions (and never leak a slot if `save` fails after acquire).
- Add a **parallel** unit/IT that hammers `save` at the ceiling and asserts `size() <= maxSessions` (and that excess callers get `SessionCapacityException`).

**B — Simulation admission before background work**

- Prefer rejecting overload with a clear `503` *before* the session is claimed `RUNNING` / before `@Async` parks a waiter — so “full” means “try again later”, not “accepted and queued indefinitely”.
- Exact placement (orchestrator vs dedicated gate vs replacing `@ConcurrencyLimit`) and whether BLOCK remains as a second line of defence — TBD in grilling.
- Parallel / saturation test that proves surplus `simulate` calls get `503` promptly and that in-flight work stays within the configured limit.

**C — Docs**

- README Status sentence: drop “Still open: … Milestone 7 … Milestone 10”; match the Roadmap.
- Touch Known gaps only if a deliberate remainder stays (e.g. if we keep BLOCK as inner guard and document that).

### Settled decisions (grilling) — shared understanding

| # | Decision | Choice |
|---|---|---|
| Q1 | Scope | **Both** A (atomic store ceiling) and B (simulation hard reject) in this follow-up |
| Q2 | Store admission | **`Semaphore(maxSessions)`** — acquire on new session, release on terminal eviction |
| Q3 | Simulation overload | **Hard reject with `503`** before `claimForRunning` / before `@Async` parks a waiter; session must not become stranded `RUNNING` |
| Q4 | Where simulation admission lives | **`GameSessionOrchestrator.simulate`** acquires the slot itself (injected semaphore / shared bean) |
| Q5 | Exception on simulation reject | **Reuse `SessionCapacityException`** → existing 503 handler; distinct **message** for store vs simulation |
| Q6 | `@ConcurrencyLimit` | **Remove**; one explicit simulation `Semaphore` — acquire in orchestrator before claim, **`release` in `finally` inside `run`** |
| Q7 | README Status blurb | **Yes**, same follow-up — align Status with Roadmap (M7/M10 no longer “open”) |
| Q8 | Session after simulate reject | Leave **`CREATED`**; client may retry `POST .../simulate` |
| Q9 | Simulation `Semaphore` ownership | One Spring **`@Bean`** (permits = `max-concurrent`), injected into orchestrator + runner |
| Q10 | Store `Semaphore` ownership | Encapsulated inside **`InMemorySessionStore`**; release on eviction |

**Grilling status:** frontier empty. Implementation complete (see Done checklist).

### Implementation sketch (built)

**A — `InMemorySessionStore`**

- Construct `Semaphore(maxSessions)` from `SessionRetentionPolicy.maxSessions()`.
- On `save` of a **new** id: `tryAcquire()`; if false → `SessionCapacityException` (store-full message). On success, `compute` insert; if insert aborts unexpectedly after acquire, `release` (should be rare).
- Updates to an existing id: no acquire.
- `evictExpired`: for each removed terminal entry, `release()` once.
- Parallel test: many threads hammer `save` at a low ceiling; assert `entries.size() <= max` and that failures are `SessionCapacityException`.

**B — Simulation admission**

- `@Bean` simulation semaphore sized to `session.simulation.max-concurrent`.
- `GameSessionOrchestrator.simulate`: `tryAcquire()`; if false → `SessionCapacityException` (simulation-full message). If true: `claimForRunning`; on claim failure → `release` then rethrow; on success → `runner.run(sessionId)`.
- `SessionSimulationRunner.run`: wrap body in `try/finally { semaphore.release(); }`. Remove `@ConcurrencyLimit` (and drop `@EnableResilientMethods` if nothing else needs it).
- Update OpenAPI on `POST .../simulate` to document `503`.
- Tests: replace/extend `SimulationConcurrencyLimitTest` so surplus `simulate` calls get capacity exception / 503 promptly; peak in-flight still ≤ limit; HTTP hand-off stays non-blocking for accepted calls.

**C — Docs**

- README Status: remove “Still open: … Milestone 7 … Milestone 10”.
- OpenAPI / comments: state that `max-concurrent` is an admission ceiling (reject), not a wait queue.

### Open decisions

None — grilling and implementation complete; merged to `main` via PR #27 (and related M10 PRs).

### Done checklist (this follow-up)

- [x] Atomic store admission (`Semaphore` inside `InMemorySessionStore`)
- [x] Parallel store ceiling test
- [x] Simulation hard reject before claim (`Semaphore` bean + orchestrator acquire)
- [x] Release in `SessionSimulationRunner.run` `finally`; `@ConcurrencyLimit` removed
- [x] OpenAPI `503` on create + simulate
- [x] README Status aligned with Roadmap
- [x] Reviewer APPROVE after eviction/save/handoff release fixes
- [x] Commit / merge — PR #27 (`[MILESTONE-10] session: harden admission, retention, and observability`)

### Non-goals for this follow-up

- UI bounded reconnect / polling fallback (demo-acceptable; separate concern).
- Rewriting long historical comments in `application.yml` / CI for style alone.
- Security, rate limiting at the gateway, Postgres/Flyway, external telemetry backends — still out of scope as decided earlier in this plan.
