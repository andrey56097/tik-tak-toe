---
name: sdd
description: Execute implementation work with separate implementer and reviewer subagents — code by one agent, tests verified by another. Follows the canonical superpowers:subagent-driven-development process plus this project's TDD + code-quality requirements.
---

# SDD — Subagent-Driven Development (project skill)

This skill wraps the canonical `superpowers:subagent-driven-development` process
for this repository, with this project's hard requirements bolted on:
**implementation by one agent, verification by a separate agent**, plus
**TDD** and the **code-quality** checklist.

> **Announce:** "I'm using the sdd skill to execute this with separated implementer/reviewer subagents."

## When to use

- Implementing tasks from `docs/tic-tac-toe-plan.md` (or any feature/bugfix).
- Whenever you'd otherwise write code directly in the session for a non-trivial change.

## Before you start — clarify first

If the task's requirements are not fully pinned down, run the `grill-me` skill
first (it drives a `grilling` interview) to interview the human about every
unclear detail — **one question at a time, with a recommended answer each** —
until we reach shared understanding. Only then dispatch the implementer.
Never start implementation while questions remain open.

## Core process (one task)

For each task, dispatch **two separate subagents**:

### 1. Implementer subagent (writes the code)

- Fresh context. Given the task brief only — not the session history.
- **Must follow TDD** (`superpowers:test-driven-development`): write the failing
  test first, watch it fail, then write minimal code to pass.
- Must apply the **code-quality** checklist (run `code-quality` skill) to its own work.
- Must run `./gradlew test` and report green.
- Returns: status, commits, one-line test summary, concerns.

### 2. Reviewer subagent (reviews the code + tests)

- **Never writes code.** Reviews only.
- Checks **spec compliance** (did the task ask for this?) AND **code quality**
  (SOLID/DRY/KISS/extensibility, per `code-quality`).
- Checks the **tests** are meaningful: they assert real behavior, cover edge
  cases, and would actually catch regressions (watch for tests that pass
  trivially or test the mock instead of the code).
- Returns a verdict: approve / findings.

### Fix loop

- If the reviewer finds Critical/Important issues → send findings back to the
  **implementer** to fix (resume the same implementer; fresh one on later rounds).
- **Never** fix findings in the main session — that bypasses review.
- After fixes, the reviewer re-checks **only the fix diff** (scoped re-review).

## Rules

- **One implementer at a time** — never dispatch multiple implementers in parallel (file conflicts).
- **TDD is non-negotiable** for this project — see CLAUDE.md → Testing Standards.
- **code-quality is non-negotiable** — see CLAUDE.md → Code Quality Standards.
- Hand artifacts over as **files** (brief → report → diff), not pasted history.
- `main` is stable; milestone work goes on `milestone/*` branches (see `.claude/README.md`).

## Full reference

The complete process — model selection, review packages, fix-loop escalation,
ledger bookkeeping, final whole-branch review — is in the canonical skill:
`superpowers:subagent-driven-development`. Read it before running this skill
on a multi-task plan.
