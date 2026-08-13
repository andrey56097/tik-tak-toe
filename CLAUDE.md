# CLAUDE.md

Project-level instructions for Claude Code agents working in this repository.

## Code Quality Standards (MANDATORY)

Every piece of production code must follow these principles. They are not
suggestions — they are the acceptance criteria for all implementation work.

### SOLID

| Principle | Rule in this project |
|---|---|
| **S** — Single Responsibility | One class = one reason to change. Controllers handle HTTP, services hold business logic, repositories own data access. Never mix layers. |
| **O** — Open/Closed | Design for extension, not modification. New behavior = new implementation of an interface, never edits to existing working code. |
| **L** — Liskov Substitution | Any implementation of an interface must be usable anywhere the interface is accepted, without behavior surprises. |
| **I** — Interface Segregation | Prefer small, focused interfaces (`MoveValidator`, `WinnerChecker`, `MoveStrategy`) over one fat `GameService`. Clients depend only on what they use. |
| **D** — Dependency Inversion | Depend on abstractions, not concretions. Services depend on interfaces (`GameRepository`, `GameEngineClient`, `MoveStrategy`), never on framework classes or concrete implementations directly. Inject via constructors (Spring DI). |

### Design patterns to prefer

- **Strategy** — move logic (`MoveStrategy`, `RandomMoveStrategy`, later `MinimaxMoveStrategy`)
- **Repository** — data access (`GameRepository extends JpaRepository`), so the DB can be swapped
- **Adapter / Ports & Adapters** — wrap external clients (`GameEngineClient` wraps `RestClient`); swap transport without touching business logic
- **Observer / Pub-Sub** — `GameUpdatePublisher` for pushing state updates to the UI (SSE stream; the UI polls until Milestone 5)
- **Factory / Builder** — create domain objects via static factories or builders, not long public constructors
- **DTO** — keep JPA entities (`GameEntity`) separate from API models (`GameState`, `MoveRequest`); the DB schema must never leak through the REST contract

### DRY, KISS, YAGNI

- **DRY:** no copy-paste. Shared DTOs/constants between Engine and Session live in the `common` module, pulled in as a dependency — never duplicated across services. `common` also owns `AbstractRestExceptionHandler`, the service-agnostic half of the error handling; its Spring MVC dependencies are `compileOnly` so the module stays usable by a non-web consumer.
- **KISS:** simplest thing that works. Don't add a message broker when REST suffices. Don't abstract something with no second implementation "on the horizon".
- **Keep workflow methods shallow:** a public orchestration method should read as a sequence of named phases. When it combines decisions, external calls, persistence, metrics, or terminal-state handling, extract cohesive private methods; do not create a new class unless that responsibility has an independent collaborator or lifecycle.
- **Comment intent, not narration:** keep a short comment at a non-obvious invariant, framework constraint, concurrency boundary, or deliberately surprising ordering. Explain *why* it exists; do not restate the code, recount development history, or document routine control flow.
- **YAGNI:** don't build for imagined future needs. Extensibility is achieved by *clean interfaces and seams*, not by speculative features.

### Extensibility — the DB *will* change on prod

This project treats persistence as swappable **by design**:

- Business logic must depend only on `GameRepository` (the interface), never on JPA or H2 specifics.
- A DB swap = new repository implementation + config, **zero changes** to services, controllers, or DTOs.
- Same seam principle applies to: move strategy (random → minimax), Engine transport (REST → broker), update delivery (polling → SSE → WebSocket).

### Error handling

- Custom domain exceptions (`InvalidMoveException`, `GameNotFoundException`) + `@RestControllerAdvice` → proper HTTP statuses (400/404/409), never bare 500s.
- External call failures (Engine unavailable) must be handled with retry/logging/graceful degradation — never hang.

### Logging

- Use **SLF4J** (`org.slf4j.Logger`/`LoggerFactory`) — add logging where it's actually needed: unexpected/server-side failures (5xx), external call failures, retries, and other events an operator would need to diagnose without a debugger. Don't log routine client errors (400/404/409) that are already communicated via the HTTP response.
- Never put exception internals (message, stack trace, cause) in a client-facing response body — log them server-side via SLF4J and return a generic message instead, especially for 5xx.

---

## Spring & Web Production Standards (MANDATORY)

These close the gap between "SOLID abstractions exist" and "this is a real Spring
service". They apply to **every REST service in this repo**. Where an earlier
milestone's code differs from a rule below, that earlier code is *debt* — fix it,
don't copy it. (These were codified after a production-readiness audit of Milestone
1/3; the audit's findings are the concrete violations these rules prevent.)

### REST layer and DTOs

- **Controllers are thin** — HTTP mapping only; all business logic lives in
  services behind injected interfaces.
- **Request/response bodies are DTOs, always.** Domain types, JPA entities, and
  store value types (`GameEntity`, `SessionRecord`, `MoveHistoryEntry`) must never
  appear in a REST contract. If a domain type would leak into the API, add a DTO
  and map it in the controller/mapper.
- **Every non-2xx response uses the shared `ErrorResponse` from `common`**
  (`{timestamp, status, error, message, path}`) — never raw exception strings in
  a body.
- **Every `@RestControllerAdvice` has a catch-all `@ExceptionHandler(Exception.class)`**
  that logs the throwable via SLF4J and returns a generic 500 `ErrorResponse`.
  Never let Spring's default error body (which can expose internals) reach the client.
- **Correct status codes**: 201 for create, 202 when accepted for background
  processing, 400 invalid input, 404 unknown resource, 409 conflicting state.
- **OpenAPI**: annotate every endpoint with `@Operation` + `@ApiResponse`
  (springdoc is configured — the docs are part of the contract).

### Service-to-service HTTP clients

- In a Servlet/MVC service use **`RestClient`** (synchronous). Do **not** use
  `WebClient` + `.block()` — that pulls the entire reactive stack into a blocking
  service for no benefit.
- Discovery: a `@LoadBalanced RestClient.Builder` resolves Eureka service ids.
- **Timeouts are mandatory** (connect + read) on every outbound client — an
  outbound call must never block indefinitely.
- **Retries only for transient failures** — network/timeout/5xx. Explicitly
  exclude 4xx client errors (`HttpClientErrorException`); retrying a 409/400 is a
  bug, not resilience.
- Log external call failures and retries via SLF4J.

### Async and concurrency

- **`@Async` lives on a dedicated bean** that callers inject and call. Never
  self-invocation (`this.method()` bypasses the proxy) and never `@Autowired
  setSelf(@Lazy ...)` self-injection. No exception.
- Don't pace work with `Thread.sleep` inside an `@Async` pool worker when a
  `TaskScheduler`/`ScheduledExecutorService` fits. If a sleep is genuinely
  acceptable (e.g. a bounded simulation), keep the loop **bounded** and document it.
- Loops that call external systems must have an **iteration cap** — a non-terminal
  response must fail the loop, not hang it forever.
- Concurrent writes: JPA entities use optimistic locking (`@Version`); two parallel
  moves on one game → one applied, the other gets 409.

### Persistence seam

- State lives behind an interface (`GameRepository`, `SessionStore`), never as a
  `Map` field in a service. An in-memory store is an *implementation* of the seam,
  so a DB swap stays "new implementation + config, zero service changes".
- Shared value-object factories (e.g. the empty board) live in `common`, not
  copy-pasted per service.

### Observability

- Every service depends on `spring-boot-starter-actuator` and exposes
  `health,info` — Eureka and operators rely on `/actuator/health`.
- SLF4J where an operator needs to diagnose: 5xx, external failures, retries,
  background-loop terminations. Not for routine 4xx already communicated by the response.

---

## Testing Standards (MANDATORY)

- **TDD:** test-first. No production code without a failing test first (see the `superpowers:test-driven-development` skill).
- Every new function/method has a test covering behavior and edge cases.
- Tests assert **real behavior**, never mock behavior.
- Each unit must be tested: `@DataJpaTest` for repositories, unit tests for services/validation, integration tests for inter-service flows.
- **Mutation testing is MANDATORY** — every new production code is covered by mutation tests. Pitest is configured as a Gradle plugin; the mutant score gates acceptance. Tests that let mutants survive are considered too weak and must be strengthened. *(Pitest config — see roadmap / Testing Standards in the plan.)*

---

## Development Workflow (MANDATORY)

- **Clarify before implementing.** Before writing ANY code, use the `grill-me`
  skill (which runs the `grilling` interview) to interview the human about every
  unclear detail — requirements, edge cases, behavior, interfaces. **Ask
  questions one at a time**, with a recommended answer for each, until we reach
  shared understanding. Only then may implementation begin. Never start coding
  while questions remain open.
- **Separate agents for implementation and tests.** When implementing a feature:
  1. A **test subagent** writes failing tests first (TDD red) — for production code, **mutation tests** included.
  2. An **implementer subagent** writes the production code to make the tests pass (TDD green).
  3. A **reviewer subagent** reviews both the code and the tests — spec compliance + code quality. The reviewer never writes the code.
  4. Run the `code-quality` skill checklist as part of acceptance.
- Use the `sdd` skill (project) — it wraps the canonical subagent-driven-development process.
- **Code and test work happens on a separate git branch** — never commit code or tests directly to `main`. Code and its tests live on the same branch, committed together (atomic commits). **Docs and rule changes go straight to `main`** (e.g. CLAUDE.md, README, plan, `.claude/README.md`). See the Git workflow in [.claude/README.md](.claude/README.md).
- **Code review is mandatory before any commit/merge.** No commit is made without a reviewer subagent pass.
- **Commit only after explicit user confirmation.** Never `git commit` on your own initiative — always ask the user first and wait for a clear «commit».
- **Write descriptive commit messages.** Each commit message must explain *what* was done and (where useful) *why* — a summary line plus bullet points of the changes. Never a bare "update"/"fix". Commits are atomic: one logical step = one commit.
- **Commit and PR titles start with `[MILESTONE-<n>]`** (e.g. `[MILESTONE-1] ...`) — the stage is visible at a glance. Milestone branches are named `milestone-<number>` (no slash).
- Build/test command: `./gradlew build` / `./gradlew test` (verify before marking anything complete).

---

## System Architecture

The system is a **Tic Tac Toe** — five independent Spring Boot services plus one shared module, all in this monorepo (no shared parent build; `common` is the *only* shared dependency).

| Service | Module (target) | Port | Eureka name |
| --- | --- | --- | --- |
| Eureka Server | `eureka-server/` | 8761 | — |
| API Gateway | `gateway/` | 8080 | — |
| Game Engine | `game-engine-service/` | 8081 | `GAME-ENGINE-SERVICE` |
| Game Session (orchestrator) | `game-session-service/` | 8082 | `GAME-SESSION-SERVICE` |
| UI | `ui-service/` | 8083 | `UI-SERVICE` |
| Shared DTOs + error contract | `common/` | — | — |

How the services talk:

- Browser → **Gateway** (`:8080`), which routes by name (`lb://GAME-SESSION-SERVICE`, …) using Eureka addresses.
- Session → Engine: **synchronous REST** via `RestClient` (`@LoadBalanced`, connect+read timeouts). Never `WebClient` + `.block()` — see Spring & Web Production Standards.
- Session → UI: the browser **polls** `GET /sessions/{id}` (Milestone 4), then switches to an **SSE** stream `GET /sessions/{id}/stream` (Milestone 5). The UI renders from *full* state via one `render(state)` function — never from deltas — which is what makes the transport swappable. WebSocket + STOMP is the documented alternative, not the current design.
- Engine → H2: Spring Data JPA (`jdbc:h2:mem:games;DB_CLOSE_DELAY=-1`).
- Each REST service (Engine, Session) exposes its API docs via **springdoc-openapi** (`/v3/api-docs` + Swagger UI) — see Version gotchas.

Coordination pattern: **Orchestration** — `GameSessionOrchestrator` (Session) is the central coordinator driving the auto-play workflow (create game → decide move → submit → check status → repeat). **Not a Saga**: no compensating operations — a failed step ends the session with an error. (Detailed rationale in the plan, «Architectural patterns at the system level».)

## Key domain vocabulary (planned)

Concrete class names from the plan — use these when implementing or reviewing:

- **Engine:** `GameController` (HTTP) · `GameEngineService` (rules) · `GameEntity` + `GameRepository extends JpaRepository<GameEntity, String>` · `MoveValidator` · `WinnerChecker`.
- **Session:** `GameSessionOrchestrator` · `SessionSimulationRunner` (the `@Async` move loop) · `SessionStore` (`InMemorySessionStore`) · `MoveStrategy` (`RandomMoveStrategy` for v1, `MinimaxMoveStrategy` later) · `GameEngineClient` (`RestGameEngineClient`) · `GameUpdatePublisher` (`SseGameUpdatePublisher`, Milestone 5).
- **`common` module (DRY):** `GameState`, `MoveRequest`, `CellState`, `GameStatus` — shared by Engine and Session, never copy-pasted. `GameStatus` is exactly `task.md`'s three values — `IN_PROGRESS` / `WIN` / `DRAW`; whose turn it is is **not** a status but a separate `GameState.nextTurn` field (X/O). `GameState` carries a `winner` (X/O/null); `MoveRequest` carries `player` (X/O) + `row`/`col` — Engine validates the submitted `player` matches whose turn.
- **Concurrency:** write-time sync via `@Transactional` + optimistic locking (`@Version`), or `synchronized`/`ReentrantLock` per `gameId`. Two parallel moves on one game → one applied, the other gets 409.

## Version gotchas (verified Aug 2026 — see plan)

- **Don't pin** JUnit 5, Mockito, or H2 versions — they come managed via the Spring Boot BOM.
- **Spring Cloud must be 2025.1.2** (not 2025.1.0/2025.1.1) — 2025.1.0/2025.1.1 support only Spring Boot 4.0.x and fail with Boot 4.1.0 at startup.
- Gateway starter is now `spring-cloud-starter-gateway-server-webflux` (renamed in Spring Cloud 2025.1.x); the legacy `spring-cloud-starter-gateway` signals an outdated BOM.
- Resilience: use Spring Boot 4's built-in `@Retryable` / `@ConcurrencyLimit`; only add `resilience4j-spring-boot4` if a full Circuit Breaker is required.
- H2 file mode (`jdbc:h2:file:./data/games`) is a one-line change when state recovery is needed.
- **API docs via springdoc-openapi v3.x** — v2.x targets Boot 3 only. Artifact for our MVC services (Engine/Session): `org.springdoc:springdoc-openapi-starter-webmvc-ui`; Gateway would use `-webflux-ui`. Docs are per-service (KISS), exposed at `/v3/api-docs` + `/swagger-ui.html`.

## Project Facts

- Java 21 (LTS), Spring Boot 4.1.x, Spring Cloud 2025.1.2 "Oakwood". Gradle wrapper (pinned), Kotlin DSL.
- **Package structure: organize code by layer into subpackages** under the module root (e.g. `com.flamingo.tiktaktoe.engine.<layer>`): `controller`, `service`, `domain`, `repository`, `mapper`, `validation`, `exception`. Do not dump all classes into one flat package once a module grows beyond a few files. **Tests mirror the same subpackages** (e.g. `MoveValidatorTest` lives in `validation/`).
- Package root: `com.flamingo.tiktaktoe`.
- **Requirements source of truth:** `docs/task.md` — the assignment (home task). The plan (`docs/tic-tac-toe-plan.md`) is built on it and tracks it item-by-item; read **both** before large work. If plan and task ever disagree, `task.md` wins.
- **Milestone plans live in `.claude/plans/`, named `milestone-<n>-<topic>.md`.** The `<topic>` is mandatory — the subject must be readable from the filename without opening it (`milestone-6-gateway.md`, never `milestone-6.md`). One plan per milestone, one file, kebab-case. Do **not** put plans in `docs/`, which holds only the assignment (`task.md`) and the overall plan (`tic-tac-toe-plan.md`). Plans are docs, so they go straight to `main` — never onto a milestone branch.
- **Roadmap:** 1 Engine+H2 → 2 Eureka → 3 Session → **4 UI (polling)** → **5 SSE push** → 6 Gateway → 7 Testing → 8 CI → 9 Docker → 10 Polish → 11 K8s. Milestones 1–3 are done. UI comes *before* the push channel on purpose: it makes all three `task.md` components exist and talk to each other at the earliest point, so everything after is an improvement on a working system. Full detail in the plan.
- Once the monorepo lands, run Gradle per service directory (`./gradlew test` inside each module), not from the root.
