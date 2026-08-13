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

## When to use — full process

Use the full three-subagent process (below) when a change is any of:

- New functionality, or a change with a real design/architecture decision behind it
  (new class, new endpoint, new cross-service contract, a grilled design decision).
- Touches multiple layers (controller + service + persistence, or crosses a
  service/module boundary).
- Anything the user hasn't already fully specified — if you'd need to make a judgment
  call about *what* to build, not just *how*, that's full-process territory.

## Light path — for low-stakes, already-specified changes

Skip the subagent dispatches and edit directly in this session when **all** of these
hold:

- The user has stated the exact change wanted (rename, move a method, add an
  annotation, tweak a config value, apply a specific fix already agreed on) — no
  design decision left for you to make.
- It introduces no new architecture/pattern and stays within one class or a small,
  mechanical, easily-reviewable diff.
- It's on code that's already tested and reviewed — you're not adding new untested
  behavior, just reshaping or correcting existing behavior.

Light path still means: make the edit, run the existing test suite yourself and
confirm it's still green (don't skip verification just because you skipped
subagents), show the diff, and — per the Rules below — **never commit without the
user's explicit confirmation**, exactly like the full process. The only thing being
skipped is the subagent dispatch overhead, not the verification or the commit gate.

If partway through a "light" change you find yourself making a design call the user
didn't already make, stop and either ask or escalate to the full process — don't let
a light-path change quietly grow into an unreviewed architectural one.

## Fast path — for heavy batches where per-task subagents cost more than they buy

The user's default on a large milestone (many tests, several files, a long
tail) is **write everything, run once, review once**. When the user asks for
speed — or when the work is a long series of characterisation tests with no
design decisions left — the orchestrating session may:

1. **Write the tests and support code itself**, in the main session, using the
   existing briefs/plan as the contract. No test subagent, no implementer
   subagent, no per-task reviewer.
2. **Run the suite once** (`./gradlew :<module>:integrationTest` and the unit
   suite) and fix whatever is broken until it is green — before any review.
   Proof of teeth still matters: if a test could pass vacuously, demonstrate it
   fails under a deliberate perturbation (or justify why it cannot) in the
   review package.
3. **A single reviewer pass** at the end of the batch — one reviewer subagent
   over the whole diff (tests + build wiring), not one per task.

What the fast path does **not** relax (these stay mandatory, from the Rules
below and CLAUDE.md): never commit without the user's explicit confirmation;
never weaken an assertion to make a test pass (a failure is a finding about
the system); never change `src/main` just to satisfy a test; code-quality
checklist still applies; review still happens once before anything is committed;
code still lives on a branch, not `main`.

When to choose fast vs full: if the batch has even one task with a real design
decision or brand-new production behaviour, that task goes full-process; the
rest of the batch can still ride the fast path. If the user says "fast path" or
"do it yourself / one run / one review", honour it over the default.

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

- **Never `git commit` or `git push` at any point in this process without the
  user explicitly confirming first** — not after the test subagent, not after
  the implementer, not after the reviewer approves, not even for a one-line
  skill/doc fix discovered mid-task. Finishing the sdd loop (tests written,
  code green, reviewer approved) is not itself permission to commit — it's a
  separate CLAUDE.md rule ("Commit only after explicit user confirmation")
  that stays in force throughout, with zero exceptions. Present the diff and
  stop; wait for the user's explicit go-ahead every single time, even for
  changes to this skill file itself.
- **The test-writer and the implementer must be two different subagent
  dispatches, every time** — never one agent doing both under the label "TDD",
  no exceptions for "small" or "obvious" changes. (Sole exception: the **Fast
  path** above, where the orchestrating session writes the tests itself and the
  user has explicitly traded per-task separation for speed. TDD still holds
  wherever there is new production behaviour.)
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
