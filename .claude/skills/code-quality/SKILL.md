---
name: code-quality
description: Apply this project's SOLID/DRY/KISS/extensibility standards as a checklist to production code. Run for any implementation work before it is considered done.
---

# Code Quality Skill

Operationalizes the code-quality standards from the project `CLAUDE.md`. Use this
**whenever you write or review production code** in this repository.

> **Announce:** "I'm using the code-quality skill to check this code."

## What to check (checklist)

### 1. SOLID

- [ ] **S** — Does each class have ONE responsibility? (Controller/Service/Repository split; no mixing.)
- [ ] **O** — Is new behavior added by *extending* (new implementation), not *modifying* working code?
- [ ] **L** — Can any implementation of an interface substitute another without breaking callers?
- [ ] **I** — Are interfaces small and focused on what clients actually use?
- [ ] **D** — Does code depend on abstractions (`GameRepository`, `GameEngineClient`, `MoveStrategy`), injected via constructor? No `new` of concrete dependencies, no framework types leaking into services.

### 2. Extensibility (the DB *will* change)

- [ ] Does business logic touch **only** interfaces — never JPA/H2 specifics?
- [ ] Could a DB swap happen with **zero** changes to services/controllers/DTOs?
- [ ] Are the seams clean: `MoveStrategy`, `GameRepository`, `GameEngineClient`, `GameUpdatePublisher`?

### 3. DRY / KISS / YAGNI

- [ ] **DRY** — no duplication; shared DTOs come from `common` module, not copy-paste.
- [ ] **KISS** — is this the simplest thing that works? No speculative abstraction.
- [ ] **YAGNI** — did I build only what's needed now, with *seams* (not features) for the future?

### 4. DTO & layering

- [ ] JPA entity (`GameEntity`) is separate from API models (`GameState`/`MoveRequest`).
- [ ] No DB schema leaks through the REST contract.
- [ ] Layering is top-down: Controller → Service → Repository, no reverse deps.

### 5. Error handling

- [ ] Domain exceptions + `@RestControllerAdvice` → proper HTTP statuses (400/404/409).
- [ ] External failures handled (retry/log/degrade), never hang.

### 6. Spring & web production standards

- [ ] Controllers thin; request/response bodies are DTOs — no domain/entity/store types in the REST contract.
- [ ] Non-2xx responses use the shared `ErrorResponse` (from `common`) — no raw strings in bodies.
- [ ] Every `@RestControllerAdvice` has a catch-all `Exception` handler → SLF4J log + generic 500 `ErrorResponse`.
- [ ] Status codes correct (201 create / 202 background-accept / 400 / 404 / 409).
- [ ] Endpoints annotated with OpenAPI (`@Operation` / `@ApiResponse`).
- [ ] Sync service-to-service via `RestClient` (no `WebClient`+`block`), `@LoadBalanced`, connect+read timeouts set.
- [ ] Retries exclude 4xx (`HttpClientErrorException`); retry only transient (network / timeout / 5xx).
- [ ] `@Async` on a dedicated injected bean — no self-invocation, no `@Autowired setSelf(@Lazy ...)`.
- [ ] Loops that call external systems are bounded (iteration cap).
- [ ] JPA entities use `@Version` optimistic locking; state behind an interface (`SessionStore` / `GameRepository`), not a service field.
- [ ] Actuator (`health,info`) on every service.
- [ ] Bean Validation on request DTOs (`@Valid` + jakarta annotations); ranges constrained (`@Min` / `@Max`).

### 7. Lifecycle & limits

TDD produces exactly what a test asserts, and no test fails because a map has no
eviction policy. This section is the one dimension of the checklist that a green
suite cannot vouch for — so it is checked by reading, deliberately, every time.

- [ ] **Anything this change stores** — map, cache, registry, list held in a field:
      who removes entries, and when? "Nothing" is a defect, not a simplification.
- [ ] **Anything this change opens** — stream, emitter, subscription, connection:
      closed on *every* exit path, including the error and exception ones.
- [ ] **Anything this change starts** — background task, async call, loop: is there
      a ceiling on how many can run at once? Virtual threads raise the ceiling;
      they do not create one.
- [ ] **Anything this change calls** — connect *and* read timeout set; loops that
      talk to another system have an iteration cap.
- [ ] **Anything this change accepts** — an endpoint that creates state needs a
      limit, and an honest rejection (503/429) when it is reached, rather than
      accepting work until the JVM dies.
- [ ] **If this fails in production** — is there a metric, a log line with the
      correlating id, or a trace? Code that can only be diagnosed with a debugger
      is not done, however well it is tested.

> The whole-repo version of this check, plus the four dimensions that are
> invisible from inside a single diff, live in the `sweep` skill — run at
> completion points, not per task.

## When done

- [ ] All applicable checks pass.
- [ ] Fixed any violations found (do not leave them "as-is").
- [ ] If a violation cannot be fixed now, note it explicitly and flag for review.
