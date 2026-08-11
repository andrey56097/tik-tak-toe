# [MILESTONE-1 FIX] Engine move endpoint becomes create-or-move (upsert)

Branch: `fix/engine-upsert-on-move` (already created from `main`, currently checked out —
work directly on it, do not create another branch).

## Context

This is a small, standalone fix to `game-engine-service`, split into its own PR/branch
(deliberately separate from the upcoming Milestone 3 Session Service work) at the user's
request, to keep PRs focused.

**Why this is needed:** Milestone 3 (Game Session Service) needs to create a brand-new game
in Engine, but per `task.md`, Engine exposes exactly two endpoints — `POST
/games/{gameId}/move` and `GET /games/{gameId}` — and there is deliberately **no** separate
"create game" endpoint (confirmed decision from Milestone 1: "no `/api` prefix, no `POST
/games`"). This was grilled and agreed with the user for Milestone 3: instead of adding a
new endpoint, `POST /games/{gameId}/move` becomes an **upsert** — if `gameId` doesn't exist
yet, create a fresh game (empty 3x3 board, `GameStatus.IN_PROGRESS`, `nextTurn = CellState.X`)
and then apply the submitted move to it in the same request, instead of throwing
`GameNotFoundException`. `GET /games/{gameId}` is unchanged — it still 404s for an unknown id.

Read `game-engine-service/src/main/java/com/flamingo/tiktaktoe/engine/service/GameEngineService.java`
fully before starting — this is the only class whose behavior changes.

## Task

1. **`GameEngineService.makeMove(String gameId, MoveRequest move)`**: currently calls
   `findGame(gameId)` which does `repository.findById(id).orElseThrow(() -> new
   GameNotFoundException(id))`. Change this so that when the game is not found, a new
   `GameEntity` is created instead of throwing — empty board (use `GameMapper` to write an
   empty 3x3 board of `CellState.EMPTY`, matching how tests currently build one), `status =
   GameStatus.IN_PROGRESS`, `nextTurn = CellState.X`, using the given `gameId` as the entity's
   id (do NOT let `@PrePersist` generate a random one here — the caller supplied this id on
   purpose so it can be looked up again via `GET /games/{gameId}`). Then continue through the
   exact same `assertPlayable` → validate → apply move → save flow as today, unchanged, for
   both the "existing game" and "freshly created game" cases (i.e. don't duplicate the move
   logic — just change how the entity is obtained before that logic runs).

2. **`getGame(String id)` is unchanged** — must keep throwing `GameNotFoundException` (404)
   for an unknown id. Do not make `GET` create anything.

3. **`GameEntity`**: check whether its constructor already allows passing an explicit id (it
   does: `GameEntity(String id, String board, GameStatus status, CellState nextTurn)`) — if
   `id` is non-null, `@PrePersist` only generates one when it's null, so passing the
   caller-supplied `gameId` explicitly should already work correctly with the existing
   `@PrePersist` logic. Verify this rather than assuming.

## Tests (TDD — write these first, watch them fail, then implement)

- `GameEngineServiceTest`: a new test where `repository.findById(gameId)` returns
  `Optional.empty()` and the move is otherwise valid (e.g. `MoveRequest(CellState.X, 0, 0)`)
  — asserts a `GameEntity` gets saved with the given `gameId`, the move applied (board cell
  set), `nextTurn` flipped to `O`, `status` still `IN_PROGRESS`. Also add a test that a
  *second* move on the same freshly-created game (i.e. simulate the entity now existing) still
  behaves exactly as the existing "normal" tests expect — don't just test creation in
  isolation, make sure the existing move-application logic still runs unchanged for the
  newly-created case.
- Confirm `getGameThrowsIfNotFound` (existing test) still passes unchanged — `GET` behavior
  must NOT change.
- `GameControllerIntegrationTest`: a new test posting a move to a `gameId` that was never
  created (e.g. a fresh random UUID never persisted) and asserting `200 OK` with the move
  applied in the response body — this is the real end-to-end proof of the new behavior through
  the actual HTTP layer + JPA/H2, not just the mocked-repository unit test.
- Run every existing test in `game-engine-service` (`./gradlew :game-engine-service:check`)
  and confirm nothing regresses. Pay attention to Pitest — the mutation gate is 80% and
  currently sits at 100%; new branches (the "game not found → create" path) need real test
  coverage or the mutation score will drop.

## Explicitly out of scope

- Do not touch `game-session-service`, `eureka-server`, `common`, or anything about Eureka.
- Do not add any new REST endpoint to `game-engine-service` — this is purely a behavior change
  to the existing `POST /games/{gameId}/move`.
- Do not change `GET /games/{gameId}`'s 404 behavior.

## Constraints

- Follow CLAUDE.md fully: SOLID/DRY/KISS/YAGNI, TDD, apply the `code-quality` skill checklist
  to your own work before finishing.
- Run `./gradlew :game-engine-service:check` and confirm `BUILD SUCCESSFUL` with the Pitest
  gate passing (currently 100%; don't let it regress below the 80% threshold — ideally stays
  at or near 100%).
- **Do not commit or push.** Leave everything as uncommitted working-tree changes — the
  orchestrating session will handle commit/push after user confirmation.

## Report back

Return: status (done/blocked), list of files changed, one-line summary of test results
(counts, pass/fail, mutation score), and any concerns or deviations from this brief (with
rationale) you had to make.
