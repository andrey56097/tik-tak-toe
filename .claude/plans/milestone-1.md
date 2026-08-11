# [MILESTONE-1] Game Engine Service

The first real service: rules, persistence, and the two HTTP endpoints from
`task.md`. Built in isolation — no Eureka, Session, UI or Gateway.

This is the record of what was built, stage by stage. It replaces the two
per-dispatch briefs the milestone was planned with (`milestone-1-engine`,
`milestone-1-engine-upsert-fix`) and adds the production-standards pass, which
had no record at all.

Endpoints delivered — **exactly two**, no `/api` prefix, no separate create
endpoint:

| Endpoint | Status | Returns |
|---|---|---|
| `POST /games/{gameId}/move` | 200 | game state after the move; creates the game if the id is unknown |
| `GET /games/{gameId}` | 200 / 404 | current board, status, whose turn, winner |

Errors: 400 malformed body or unplayable cell, 404 unknown game, 409 finished
game / wrong player's turn / lost concurrency race — all with the shared
`ErrorResponse` body.

---

## Stage A — Engine + H2 (PR #3)

`common` gained the shared contract as plain Java records — `CellState`,
`GameStatus`, `GameState`, `MoveRequest`. Engine and later Session depend on
`common`; these types are never copy-pasted.

Layering per CLAUDE.md's package-by-layer rule: `controller.GameController` →
`service.GameEngineService` → `repository.GameRepository`. Rules that would
have bloated the service are separate focused components —
`validation.MoveValidator` (bounds + cell empty) and `validation.WinnerChecker`
(lines + full board). `mapper.GameMapper` converts `GameEntity` ↔ `GameState`,
storing the board as a JSON string column.

Persistence is H2 in-memory (`jdbc:h2:mem:games;DB_CLOSE_DELAY=-1`), reached
only through `GameRepository` — the service never touches JPA specifics, so the
DB stays swappable.

**Two decisions worth remembering.** No `/api` prefix and no `POST /games`
create endpoint: `task.md` lists two endpoints and nothing else was added.
And Spring Boot 4.1's auto-config only provides a Jackson 3 bean, so the
Jackson 2 `ObjectMapper` the mapper needs is declared explicitly in
`JacksonConfig` — without it the context fails at startup.

## Stage B — The move endpoint becomes an upsert (PR #5)

Milestone 3 needed Session to start a brand-new game, but Engine deliberately
has no create endpoint. Rather than adding one and contradicting `task.md`,
`POST /games/{gameId}/move` became an upsert: an unknown `gameId` creates a
fresh game (empty board, `IN_PROGRESS`, X to move) and applies the submitted
move in the same request.

The caller-supplied id is used as the entity id, so the game can be read back
via `GET`. `@PrePersist` only generates an id when one is absent, so this works
without touching it.

`GET /games/{gameId}` was **not** changed — an unknown id still 404s. Only the
move endpoint creates anything.

## Stage C — Production standards (PR #11)

Engine predates CLAUDE.md's Spring & Web Production Standards, so it kept
practices Session had already been corrected for. By the standards' own wording
that is debt — "where an earlier milestone's code differs from a rule below,
that earlier code is *debt* — fix it, don't copy it."

- **Error contract.** `GameExceptionHandler` returned bare strings; every
  non-2xx now carries the shared `ErrorResponse` from `common`. Added the
  required catch-all `@ExceptionHandler(Exception.class)` — without it an
  unanticipated exception reached the client as Spring's default body, which
  can expose internals. 5xx is logged and masked; expected 4xx is not logged.
- **Optimistic locking.** `GameEntity` gained `@Version`. Two moves submitted
  concurrently for one game both read the same version; the first write wins
  and the second fails its check instead of silently overwriting the first.
  `OptimisticLockingFailureException` maps to 409 — a conflicting write, not a
  server fault. This is CLAUDE.md's "two parallel moves on one game → one
  applied, the other gets 409".
- **OpenAPI.** springdoc was already a dependency but nothing was annotated, so
  the published contract was empty. Both endpoints now carry
  `@Operation`/`@ApiResponse`.
- **Actuator.** Engine had none, while Eureka and operators read
  `/actuator/health`.
- **DRY.** `createGame` now uses `GameStateFactory.empty()` from `common`
  instead of its own `emptyBoard()`/`emptyRow()`.

**A correction recorded here on purpose.** The Milestone 3 plan claimed Engine
*could not* adopt `GameStateFactory` because the factory returns an immutable
board while Engine updates cells in place. That was wrong: the board is only
serialised at creation, and every later mutation happens on the fresh list
`parseBoard` returns.

---

## Testing

52 tests, all green; Pitest 75/75 mutants killed (threshold 80).

Unit tests for `MoveValidator`, `WinnerChecker` and `GameEngineService`;
`@DataJpaTest` for the repository; `GameMapperTest` for the board JSON
round-trip; `GameControllerIntegrationTest` (`@SpringBootTest` + MockMvc) for
the full HTTP flow including the upsert path.

`GameExceptionHandlerTest` is a `@WebMvcTest` slice driving real MVC exception
resolution, asserting the `ErrorResponse` shape for 404/400/409/500 and that
neither a board-mapping failure nor an unexpected exception leaks its message.

**The concurrency tests are deliberately deterministic.** A two-thread test
would need both readers to load the entity before either commits, which cannot
be arranged from outside a `@Transactional` method without making the test
flaky. Instead `GameRepositoryTest` proves the version advances and that a
stale copy's write is rejected, and the handler slice proves that failure
becomes a 409.

`ActuatorHealthTest` pins the endpoint exposure, which is pure configuration
and would otherwise fail silently on a typo.

## Known gaps

Tracked in the README's *Possible Improvements*, not duplicated here — the two
that originate in this milestone are the unauthenticated upsert (any well-formed
id materialises a game) and `MoveRequest` carrying no `row`/`col` bounds
annotations, relying on `MoveValidator` to reject them with a 400.
