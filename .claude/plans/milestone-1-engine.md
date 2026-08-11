# Milestone 1 — Game Engine Service + H2

> **Status: already completed and merged** (branch `milestone-1` → PR #3, merged at `800852d`).
> This brief is a **retrospective reconstruction** for the historical record — see the note at
> the top of `milestone-0-restructure.md` for why this file is being added after the fact.

Branch: `milestone-1` (from `main`, after Milestone 0's restructure).

## Context

`game-engine-service` and `common` existed only as empty, compilable skeletons from
Milestone 0. This milestone builds the actual Game Engine: rules, persistence, and the two
HTTP endpoints, fully isolated from Eureka/Session/UI (those are later milestones).

Decisions were grilled with the user and double-checked against `docs/task.md` before
implementation — do not re-litigate them:

## Task

1. **`common` DTOs** (plain Java records, no Spring): `CellState {EMPTY, X, O}`, `GameStatus
   {IN_PROGRESS, WIN, DRAW}`, `GameState` (id, board `List<List<CellState>>`, status, nextTurn
   X/O, winner X/O/null), `MoveRequest` (player X/O + row + col). These are the single shared
   contract — Engine and (later) Session depend on `common`, never copy-paste these types.

2. **Engine has EXACTLY two endpoints, per `task.md`** — `POST /games/{gameId}/move` and
   `GET /games/{gameId}`. **No `/api` prefix, no `POST /games`** create-game endpoint (explicit
   user instruction: "все по task.md, ничего лишнего, не добавляй /api"). Game creation in M1
   happens implicitly via `GameRepository`/H2 semantics available to the caller — Session will
   initialize games in Milestone 3.

3. **Persistence**: `GameEntity` (JPA: id String UUID via `@PrePersist`, board stored as a
   JSON/String column, status, nextTurn) + `GameRepository extends
   JpaRepository<GameEntity, String>`. `application.yml`: `jdbc:h2:mem:games;DB_CLOSE_DELAY=-1`,
   H2 Console enabled, `server.port=8081`, `spring.application.name=game-engine-service`,
   `spring.jpa.hibernate.ddl-auto=update`.

4. **Layering** (package-by-layer per CLAUDE.md): `controller.GameController` (HTTP) →
   `service.GameEngineService` (business logic) → `repository.GameRepository` (data access).
   `validation.MoveValidator` and `validation.WinnerChecker` as separate, focused components
   (Interface Segregation — not one fat service). `mapper.GameMapper` converts
   `GameEntity` ↔ `GameState` (board String/JSON ↔ `List<List<CellState>>`, via a
   constructor-injected Jackson `ObjectMapper`).

5. **Error handling**: `exception.GameNotFoundException` → 404, `exception.InvalidMoveException`
   → 400, `exception.GameConflictException` (game already finished / wrong player's turn) →
   409, all via `exception.GameExceptionHandler` (`@RestControllerAdvice`) — never a bare 500.
   Exception internals (message/stack trace) are logged server-side via SLF4J, never returned
   in the response body.

6. **API docs**: wire `springdoc-openapi-starter-webmvc-ui` (v3.x) — verify `/v3/api-docs` +
   `/swagger-ui.html` describe the Engine API.

## Tests (TDD — written first per commit history)

- Unit tests: `MoveValidatorTest`, `WinnerCheckerTest`, `GameEngineServiceTest` (move
  validation, win/draw detection, invalid-move handling).
- `GameRepositoryTest` (`@DataJpaTest`) — saving/reading `GameEntity`.
- `GameMapperTest` — entity ↔ DTO conversion, including the board JSON round-trip.
- `GameControllerIntegrationTest` (`@SpringBootTest` + MockMvc) — full HTTP flow, 200/400/404/409.
- `GameExceptionHandlerTest` — each exception maps to the correct status, no internals leaked.
- `JacksonConfigTest` — the explicit Jackson 2 `ObjectMapper` bean loads (Spring Boot 4.1's
  auto-config only provides a Jackson 3 bean; see
  [[spring-boot4-jackson-objectmapper-gotcha]] in project memory).
- **Pitest** (mutation, mandatory) + **JaCoCo** (coverage, 80% gate) configured and passing.

## Explicitly out of scope — do not add

- No `/api` prefix, no `POST /games` create-game endpoint.
- Nothing about Eureka, Session, UI, or Gateway — those are later milestones.

## Constraints

- Follow CLAUDE.md fully: SOLID/DRY/KISS/YAGNI, TDD, `code-quality` checklist.
- Branch `milestone-1`, PR title `[MILESTONE-1] ...`, commits prefixed `[MILESTONE-1]`.

## What actually shipped (in order)

- `8f793fc`/`2c9f250` — Implement Game Engine Service + H2
- `3598a74` — Fix Jackson DI gotcha, tighten mapper layering, gate Pitest
- `5b27267` — Make board mutability explicit, stop leaking bare 500s
- `8854f67` — Add NOT NULL constraints, remove hardcoded JSON in tests
- `7a1cb66` — Move `GameExceptionHandler` to `exception` pkg, log 500s, tidy `isFull`
- `dbb3bce` — Add `CellState.opposite()` and test tooling for `common`
- `50a9b37` — Use `CellState.opposite()`, extract `assertPlayable` in service
- `deebe62` — Validate `MoveRequest.player`, fix confusing 409 on missing field
- Merged to `main` via PR #3 (`800852d`).

(A later standalone fix — `POST /games/{gameId}/move` becoming create-or-move upsert, needed by
Milestone 3 — is tracked separately in `milestone-1-engine-upsert-fix.md`.)

## Report back

Result: Game Engine works and is fully tested in isolation (unit + `@DataJpaTest` +
MockMvc integration + Pitest/JaCoCo), state persists within a single run via H2.
