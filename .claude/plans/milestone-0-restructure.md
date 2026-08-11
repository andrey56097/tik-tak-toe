# Milestone 0 — Monorepo restructure

> **Status: already completed and merged** (branch `milestone-0` → PR #2, commits `5ed8026`,
> `4240da7`, merged at `c842033`). This brief is a **retrospective reconstruction** for the
> historical record — `.claude/plans/` had briefs for Milestone 2 and the Milestone 1 upsert
> fix but not for Milestone 0/1, so this documents what was actually decided and built,
> written in the same format the live briefs use.

Branch: `milestone-0` (from `main`).

## Context

The repo started as a single default Spring Initializr skeleton
(`src/main/java/com/flamingo/tiktaktoe/TikTakToeApplication.java` + one root
`build.gradle.kts`). Per the plan (`docs/task.md` + `docs/tic-tac-toe-plan.md`), the system is
five independent Spring Boot services plus one shared module — the skeleton needed to become
a real monorepo before any service-specific work (Milestone 1+) could start.

Decisions were grilled with the user before implementation:

## Task

1. **Delete the single-module skeleton** — remove the old `TikTakToeApplication` and the root
   `build.gradle.kts`; the monorepo has no shared parent build.

2. **Create 6 modules**, each an independent Gradle project under `settings.gradle.kts`:
   `common`, `eureka-server`, `gateway`, `game-engine-service`, `game-session-service`,
   `ui-service`. `common` is the **only** shared dependency (via `project(":common")`), pulled
   in by Engine and Session — no shared root build file otherwise.

3. **Package root**: `com.flamingo.tiktaktoe.<module>` per module (`.engine`, `.session`,
   `.gateway`, `.ui`, `.common`, eureka-server has no sub-package needed yet).

4. **`common`**: compilable placeholder module only (`CommonModule.java`) — no DTOs yet.
   `GameState`/`MoveRequest`/`CellState`/`GameStatus` are Milestone 1 work, once Engine's
   actual contract is known.

5. **Application classes**: each of the 5 service modules gets its own `@SpringBootApplication`
   class (`EurekaServerApplication`, `GatewayApplication`, `GameEngineApplication`,
   `GameSessionApplication`, `UiApplication`). `common` stays a plain Java library, no main
   class.

6. **Dependencies kept minimal** — only what's needed to compile and start each module:
   Engine/Session get `web` + test starters; Gateway gets
   `spring-cloud-starter-gateway-server-webflux`; Eureka gets `web` +
   `spring-cloud-starter-netflix-eureka-server`; UI gets `web` + `websocket`. Business-specific
   deps (JPA/H2, WebClient, eureka-client) are added in their own milestones, not here.

## Explicitly out of scope — do not add

- No business logic in any module (no `GameEntity`, no controllers, no DTOs beyond the
  `common` placeholder).
- No Eureka client wiring yet (that's Milestone 2).
- No cross-module dependencies beyond `common`.

## Constraints

- Follow CLAUDE.md: SOLID/DRY/KISS/YAGNI, package-by-layer once a module grows.
- Verify with `./gradlew build` from the repo root, plus an optional `bootRun` smoke check per
  module.
- Branch `milestone-0`, PR title `[MILESTONE-0] ...`, commits prefixed `[MILESTONE-0]`.

## What actually shipped

- `5ed8026` — `[MILESTONE-0] Restructure skeleton into 6-module monorepo`
- `4240da7` — `[MILESTONE-0] Add websocket starter to game-session-service`
- Merged to `main` via PR #2 (`c842033`).

## Report back

Result: empty project skeleton, all 6 modules compile and run independently — the base
Milestone 1 (Engine) was built on top of.
