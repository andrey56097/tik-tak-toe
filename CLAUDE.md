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
- **Adapter / Ports & Adapters** — wrap external clients (`GameEngineClient` wraps `WebClient`); swap transport without touching business logic
- **Observer / Pub-Sub** — WebSocket (STOMP topic) for pushing updates
- **Factory / Builder** — create domain objects via static factories or builders, not long public constructors
- **DTO** — keep JPA entities (`GameEntity`) separate from API models (`GameState`, `MoveRequest`); the DB schema must never leak through the REST contract

### DRY, KISS, YAGNI

- **DRY:** no copy-paste. Shared DTOs/constants between Engine and Session live in the `common` module, pulled in as a dependency — never duplicated across services.
- **KISS:** simplest thing that works. Don't add a message broker when REST suffices. Don't abstract something with no second implementation "on the horizon".
- **YAGNI:** don't build for imagined future needs. Extensibility is achieved by *clean interfaces and seams*, not by speculative features.

### Extensibility — the DB *will* change on prod

This project treats persistence as swappable **by design**:

- Business logic must depend only on `GameRepository` (the interface), never on JPA or H2 specifics.
- A DB swap = new repository implementation + config, **zero changes** to services, controllers, or DTOs.
- Same seam principle applies to: move strategy (random → minimax), Engine transport (REST → broker), update delivery (WebSocket → SSE).

### Error handling

- Custom domain exceptions (`InvalidMoveException`, `GameNotFoundException`) + `@RestControllerAdvice` → proper HTTP statuses (400/404/409), never bare 500s.
- External call failures (Engine unavailable) must be handled with retry/logging/graceful degradation — never hang.

---

## Testing Standards (MANDATORY)

- **TDD:** test-first. No production code without a failing test first (see the `superpowers:test-driven-development` skill).
- Every new function/method has a test covering behavior and edge cases.
- Tests assert **real behavior**, never mock behavior.
- Each unit must be tested: `@DataJpaTest` for repositories, unit tests for services/validation, integration tests for inter-service flows.
- **Mutation testing (planned):** Pitest will be added as a Gradle plugin to measure test effectiveness (mutant score). Treat high coverage + mutation survival as a signal tests are too weak. *(Not yet configured — see roadmap.)*

---

## Development Workflow (MANDATORY)

- **Clarify before implementing.** Before writing ANY code, use the `grill-me`
  skill (which runs the `grilling` interview) to interview the human about every
  unclear detail — requirements, edge cases, behavior, interfaces. **Ask
  questions one at a time**, with a recommended answer for each, until we reach
  shared understanding. Only then may implementation begin. Never start coding
  while questions remain open.
- **Implementation by one agent, verification by another.** When implementing a feature:
  1. An **implementer subagent** writes the production code (and its own failing tests first, per TDD).
  2. A **separate reviewer subagent** reviews the code and the tests — spec compliance + code quality. The reviewer never writes the code.
  3. Run the `code-quality` skill checklist as part of acceptance.
- Use the `sdd` skill (project) — it wraps the canonical subagent-driven-development process.
- Follow the **Git workflow** in [.claude/README.md](.claude/README.md): milestone branches, atomic commits.
- Build/test command: `./gradlew build` / `./gradlew test` (verify before marking anything complete).

---

## System Architecture

The system is a **distributed Tic Tac Toe** — five independent Spring Boot services plus one shared module, all in this monorepo (no shared parent build; `common` is the *only* shared dependency).

| Service | Module (target) | Port | Eureka name |
| --- | --- | --- | --- |
| Eureka Server | `eureka-server/` | 8761 | — |
| API Gateway | `gateway/` | 8080 | — |
| Game Engine | `game-engine-service/` | 8081 | `GAME-ENGINE-SERVICE` |
| Game Session (orchestrator) | `game-session-service/` | 8082 | `GAME-SESSION-SERVICE` |
| UI | `ui-service/` | 8083 | `UI-SERVICE` |
| Shared DTOs | `common/` | — | — |

How the services talk:

- Browser → **Gateway** (`:8080`), which routes by name (`lb://GAME-SESSION-SERVICE`, …) using Eureka addresses.
- Session → Engine: **synchronous REST** via `WebClient` (`@LoadBalanced`).
- Session → UI: **WebSocket** (STOMP + SockJS), topic `/topic/game/{id}`.
- Engine → H2: Spring Data JPA (`jdbc:h2:mem:games;DB_CLOSE_DELAY=-1`).
- Each REST service (Engine, Session) exposes its API docs via **springdoc-openapi** (`/v3/api-docs` + Swagger UI) — see Version gotchas.

Coordination pattern: **Orchestration** — `GameSessionOrchestrator` (Session) is the central coordinator driving the auto-play workflow (create game → decide move → submit → check status → repeat). **Not a Saga**: no compensating operations — a failed step ends the session with an error. (Detailed rationale in the plan, «Architectural patterns at the system level».)

## Key domain vocabulary (planned)

Concrete class names from the plan — use these when implementing or reviewing:

- **Engine:** `GameController` (HTTP) · `GameEngineService` (rules) · `GameEntity` + `GameRepository extends JpaRepository<GameEntity, String>` · `MoveValidator` · `WinnerChecker`.
- **Session:** `GameSessionOrchestrator` · `MoveStrategy` (`RandomMoveStrategy` for v1, `MinimaxMoveStrategy` later) · `GameEngineClient` (`RestGameEngineClient`) · `GameUpdatePublisher` / `GameBroadcaster`.
- **`common` module (DRY):** `GameState`, `MoveRequest`, `CellState`, `GameStatus` — shared by Engine and Session, never copy-pasted. `GameStatus` aligns with `task.md`: `IN_PROGRESS` / `WIN` / `DRAW` (+ `NEXT_TURN` to explicitly track whose turn). `GameState` carries a `winner` (X/O/null); `MoveRequest` carries `player` (X/O) + `row`/`col` — Engine validates the submitted `player` matches whose turn.
- **Concurrency:** write-time sync via `@Transactional` + optimistic locking (`@Version`), or `synchronized`/`ReentrantLock` per `gameId`. Two parallel moves on one game → one applied, the other gets 409.

## Version gotchas (verified Aug 2026 — see plan)

- **Don't pin** JUnit 5, Mockito, or H2 versions — they come managed via the Spring Boot BOM.
- Gateway starter is now `spring-cloud-starter-gateway-server-webflux` (renamed in Spring Cloud 2025.1.0); the legacy `spring-cloud-starter-gateway` signals an outdated BOM.
- Resilience: use Spring Boot 4's built-in `@Retryable` / `@ConcurrencyLimit`; only add `resilience4j-spring-boot4` if a full Circuit Breaker is required.
- H2 file mode (`jdbc:h2:file:./data/games`) is a one-line change when state recovery is needed.
- **API docs via springdoc-openapi v3.x** — v2.x targets Boot 3 only. Artifact for our MVC services (Engine/Session): `org.springdoc:springdoc-openapi-starter-webmvc-ui`; Gateway would use `-webflux-ui`. Docs are per-service (KISS), exposed at `/v3/api-docs` + `/swagger-ui.html`.

## Project Facts

- Java 21 (LTS), Spring Boot 4.1.x, Spring Cloud 2025.1.0 "Oakwood". Gradle wrapper (pinned), Kotlin DSL.
- Package root: `com.flamingo.tiktaktoe`.
- **Requirements source of truth:** `docs/task.md` — the assignment (home task). The plan (`docs/tic-tac-toe-plan.md`) is built on it and tracks it item-by-item; read **both** before large work. If plan and task ever disagree, `task.md` wins.
- **Current scope: skeleton app (Milestone 0).** Roadmap: 1 Engine+H2 → 2 Eureka → 3 Session → 4 WebSocket → 5 UI → 6 Gateway → 7 Testing → 8 Docker → 9 Polish. Full detail in the plan.
- Once the monorepo lands, run Gradle per service directory (`./gradlew test` inside each module), not from the root.
