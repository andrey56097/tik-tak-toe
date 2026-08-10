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

## When done

- [ ] All applicable checks pass.
- [ ] Fixed any violations found (do not leave them "as-is").
- [ ] If a violation cannot be fixed now, note it explicitly and flag for review.
