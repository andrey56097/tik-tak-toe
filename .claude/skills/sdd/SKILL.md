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

For each task, dispatch **three separate subagents, in strict sequence**. This is
non-negotiable: **the same agent must never write both the tests and the production
code for a task.** An agent's own self-report ("I wrote the test first, watched it
fail, then implemented") is not verifiable evidence — it's the agent's unverified
claim about itself. Only a genuinely separate test-writing dispatch, checked by the
orchestrating session *before* the implementer is even started, produces real proof
of red-green ordering.

### 1. Test subagent (writes ONLY the tests)

- Fresh context. Given the task brief only — not the session history, and
  explicitly **not** shown any implementation approach or existing solution.
- Writes the failing test(s) for the behavior described in the brief. Writes
  **no production code** — if the brief requires a new class/method to even
  compile, it may add the minimal empty stub (signature only, `throw new
  UnsupportedOperationException()` or equivalent) needed for the test to
  compile and fail for the *right* reason, nothing more.
- Must run the test itself and confirm it fails (red) for the expected reason
  (missing behavior, not a compile error or wrong setup) before reporting back.
- Returns: the test file(s), the exact failure output, and a one-line
  description of what behavior each test proves.
- **Do not commit.**

### 2. Orchestrating session verifies red

- Before dispatching the implementer, the orchestrating session (you) runs the
  test(s) itself (`./gradlew test`) and confirms they fail for the reported
  reason. This is the actual evidence of ordering — don't skip it.

### 3. Implementer subagent (writes ONLY the production code)

- Fresh context. Given the task brief **and** the test subagent's test file(s)
  — not the session history, not any prior implementation attempt.
- Writes the minimal production code to make the given tests pass. Must not
  edit the tests to make them pass (if a test seems wrong, it reports that as
  a concern instead of changing it — flag it back to the orchestrating session).
- Must apply the **code-quality** checklist (run `code-quality` skill) to its
  own work.
- Must run `./gradlew test` and report green.
- Returns: status, files changed, one-line test summary, concerns.
- **Do not commit.**

### 4. Reviewer subagent (reviews the code + tests)

- **Never writes code.** Reviews only.
- Checks **spec compliance** (did the task ask for this?) AND **code quality**
  (SOLID/DRY/KISS/extensibility, per `code-quality`).
- Checks the **tests** are meaningful: they assert real behavior, cover edge
  cases, and would actually catch regressions (watch for tests that pass
  trivially or test the mock instead of the code).
- Independently re-runs the build/mutation gate — does not just trust the
  implementer's reported numbers.
- Returns a verdict: approve / findings.

### Fix loop

- If the reviewer finds Critical/Important issues → send findings back to the
  **implementer** to fix (resume the same implementer; fresh one on later rounds).
  If the issue is with a test itself, that goes back to the **test subagent**,
  not the implementer.
- **Never** fix findings in the main session — that bypasses review.
- After fixes, the reviewer re-checks **only the fix diff** (scoped re-review).

## Rules

- **The test-writer and the implementer must be two different subagent
  dispatches, every time** — never one agent doing both under the label "TDD",
  no exceptions for "small" or "obvious" changes.
- **One implementer at a time** — never dispatch multiple implementers in parallel (file conflicts).
- **TDD is non-negotiable** for this project — see CLAUDE.md → Testing Standards.
- **code-quality is non-negotiable** — see CLAUDE.md → Code Quality Standards.
- Hand artifacts over as **files** (brief → report → diff), not pasted history.
- `main` is stable; milestone work goes on `milestone-<n>` branches, other work on
  `feature/*`/`fix/*`/`chore/*` (see `.claude/README.md`).

## Full reference

The complete process — model selection, review packages, fix-loop escalation,
ledger bookkeeping, final whole-branch review — is in the canonical skill:
`superpowers:subagent-driven-development`. Read it before running this skill
on a multi-task plan.
