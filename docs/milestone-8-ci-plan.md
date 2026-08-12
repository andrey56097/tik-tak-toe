# Milestone 8 — CI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every push to `main` and every pull request is automatically built and gated by GitHub Actions, and a red build makes the PR unmergeable.

**Architecture:** One workflow file, one job, one command — `./gradlew build` from the repository root. The root `settings.gradle.kts` includes all six modules, and Gradle's `build` lifecycle already implies `check`, which in `game-engine-service` and `game-session-service` already depends on `jacocoTestCoverageVerification` and `pitest`. The quality gates therefore come for free: **no Gradle file is touched by this milestone.** GitHub branch protection turns the resulting `build` status check into a merge gate.

**Tech Stack:** GitHub Actions, `actions/checkout@v4`, `actions/setup-java@v4` (Temurin 21), `gradle/actions/setup-gradle@v4`, `actions/upload-artifact@v4`, `gh` CLI for branch protection.

## Global Constraints

- **Repository:** `andrey56097/tik-tak-toe` (public, viewer permission ADMIN).
- **Working tree:** `/Users/andriibats/IdeaProjects/tik-tak-toe-m8`, branch `milestone-8`, branched from `main` at `f70ebe6`. The primary checkout `/Users/andriibats/IdeaProjects/tik-tak-toe` is occupied by another agent working on Milestone 6 (branch `milestone-6`, uncommitted gateway work) — **never run git or Gradle there.**
- **Nothing this milestone merges may touch** any `build.gradle.kts`, any file under `*/src/**`, or `settings.gradle.kts`. Coverage and mutation thresholds are Milestone 7's subject matter, not this milestone's. The single exception is the throwaway test in Task 3, which lives on a branch that is deleted unmerged.
- **Do not modify** `docs/tic-tac-toe-plan.md` — the user explicitly excluded the plan checkboxes from this milestone's scope.
- **The job id `build` is immutable once branch protection is enabled.** Branch protection stores the required check by name; renaming the job later produces a required check that can never report, which blocks every merge permanently.
- **Commit only after the user explicitly says so.** Never `git commit` or `git push` on your own initiative (CLAUDE.md).
- **Commit and PR titles start with `[MILESTONE-8]`.**
- **Code review by a reviewer subagent is mandatory before the merge** (CLAUDE.md); the user has approved dispatching it for this milestone.
- **Branch protection uses `strict: true`** — the branch must be up to date with `main` before merging. Milestone 6/7 work may land first, in which case this branch gets rebased and CI re-runs.
- Docs land **directly on `main`** (README badge, CI section, port note), separately from the branch's PR.

## Verified baseline (measured on `main`, 2026-08-12)

`./gradlew build --continue` from the repository root:

| Module | Result |
|---|---|
| `common`, `gateway`, `ui-service` | pass |
| `eureka-server` | **fails locally only** — `EurekaServerApplicationTest` uses `@SpringBootTest(webEnvironment = DEFINED_PORT)` and really binds 8761, so it fails whenever the dev stack is running. On a clean runner it passes. |
| `game-engine-service` | pass — jacoco verification met, Pitest 79/79 mutants killed (100%), line coverage 99% |
| `game-session-service` | pass — Pitest 74/74 mutants killed (100%) |

There is no substantive red in the baseline, so a blocking gate is safe from day one. Wall clock was 1m40s with warm modules; budget ~5 minutes cold in CI.

## File Structure

- **Create** `.github/workflows/ci.yml` — the entire CI definition: triggers, JDK/Gradle setup, the single `./gradlew build` invocation, and failure-only report upload. One responsibility: gate the repository.
- **Create** `docs/milestone-8-ci-plan.md` — this document.
- **Modify** `README.md` on `main` — badge in the header block, a `## Continuous Integration` section, and a note in `### Test` about port 8761.
- **Untouched:** every `build.gradle.kts`, every `src/**`, `.github/workflows/ai-review.yml`, `docs/tic-tac-toe-plan.md`.

---

### Task 0: Land this plan on `main`

**Files:**
- Create: `docs/milestone-8-ci-plan.md` (this document)

This is a doc, and CLAUDE.md sends docs straight to `main` rather than through a
branch — so it must not ride along in the Milestone 8 pull request. It is
currently sitting untracked in the `milestone-8` worktree; move it onto `main`
through a separate worktree so the primary checkout, which another agent is
using, is never touched.

- [ ] **Step 1: Create a docs worktree on `main`**

```bash
git -C /Users/andriibats/IdeaProjects/tik-tak-toe worktree add /Users/andriibats/IdeaProjects/tik-tak-toe-docs main
cp /Users/andriibats/IdeaProjects/tik-tak-toe-m8/docs/milestone-8-ci-plan.md \
   /Users/andriibats/IdeaProjects/tik-tak-toe-docs/docs/milestone-8-ci-plan.md
```

- [ ] **Step 2: Ask the user to confirm, then commit and push**

```bash
cd /Users/andriibats/IdeaProjects/tik-tak-toe-docs
git add docs/milestone-8-ci-plan.md
git commit -m "$(cat <<'EOF'
Plan Milestone 8: CI as a merge gate

- Record the measured baseline of ./gradlew build on main, including the
  engine and session mutation scores and the one local-only failure.
- Spell out why the gate needs no build.gradle.kts change, and why the CI
  job id becomes frozen the moment branch protection references it.
- Task out the workflow, branch protection, the throwaway red PR that proves
  a failing build blocks the merge, and the README documentation.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
git push origin main
```

- [ ] **Step 3: Remove the file from the milestone worktree, so the PR stays clean**

```bash
rm /Users/andriibats/IdeaProjects/tik-tak-toe-m8/docs/milestone-8-ci-plan.md
cd /Users/andriibats/IdeaProjects/tik-tak-toe-m8 && git status --short
```

Expected: the only entry is the untracked `.github/workflows/ci.yml` (once Task 1 has written it). Keep the docs worktree — Task 5 reuses it.

---

### Task 1: The CI workflow

**Files:**
- Create: `/Users/andriibats/IdeaProjects/tik-tak-toe-m8/.github/workflows/ci.yml`
- Test: the workflow's own run on GitHub Actions — there is no local unit test for a workflow file; the run **is** the test.

**Interfaces:**
- Produces: a status check whose context name is exactly `build`. Task 2 requires that exact string.
- Produces: an artifact named `reports-<run_id>`, uploaded only when the build fails.

- [ ] **Step 1: Write the workflow**

Create `.github/workflows/ci.yml`:

```yaml
name: CI

on:
  pull_request:
    branches: [main]
  push:
    branches: [main]
  workflow_dispatch:

permissions:
  contents: read

# One in-flight run per ref. Superseded PR runs are cancelled; runs on main are
# not, so every commit that lands keeps its own verdict.
concurrency:
  group: ci-${{ github.ref }}
  cancel-in-progress: ${{ github.event_name == 'pull_request' }}

jobs:
  # The job id is the branch-protection context name. Renaming it silently
  # breaks the merge gate — see docs/milestone-8-ci-plan.md.
  build:
    runs-on: ubuntu-latest
    timeout-minutes: 20

    steps:
      - name: Check out the repository
        uses: actions/checkout@v4

      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'

      - name: Set up Gradle
        uses: gradle/actions/setup-gradle@v4

      # `build` implies `check`, and `check` in the engine and session modules
      # already depends on jacocoTestCoverageVerification and pitest. One command
      # is the whole gate: compile, test, coverage floor, mutation floor, package.
      - name: Build, test and verify every module
        run: ./gradlew build --console=plain --stacktrace

      - name: Upload reports
        if: failure()
        uses: actions/upload-artifact@v4
        with:
          name: reports-${{ github.run_id }}
          path: |
            **/build/reports/tests/test
            **/build/reports/jacoco
            **/build/reports/pitest
          retention-days: 7
          if-no-files-found: ignore
```

- [ ] **Step 2: Verify the file is valid YAML before pushing**

Run:

```bash
cd /Users/andriibats/IdeaProjects/tik-tak-toe-m8
python3 -c "import yaml,sys; d=yaml.safe_load(open('.github/workflows/ci.yml')); print(sorted(d['jobs'])); print(d['jobs']['build']['runs-on'])"
```

Expected: `['build']` then `ubuntu-latest`. If the job id prints as anything other than `build`, fix it now — after Task 2 it is frozen.

- [ ] **Step 3: Dispatch the reviewer subagent**

Review scope: `.github/workflows/ci.yml` against this plan and against CLAUDE.md. The reviewer writes no code. Points to check explicitly: job id is `build`; `upload-artifact` is guarded by `if: failure()` (an unguarded upload step never runs after a failed build — the exact case where the reports are needed); no Gradle or `src/**` file is touched; triggers do not double-run PR branches.

- [ ] **Step 4: Ask the user for permission to commit, then commit**

Do not run this until the user says so.

```bash
cd /Users/andriibats/IdeaProjects/tik-tak-toe-m8
git add .github/workflows/ci.yml
git commit -m "$(cat <<'EOF'
[MILESTONE-8] Gate every push and PR behind a GitHub Actions build

- Add .github/workflows/ci.yml: one job running ./gradlew build from the
  repository root, on PRs to main, pushes to main, and manual dispatch.
- The root build already implies check, and check in the engine and session
  modules already depends on jacocoTestCoverageVerification and pitest, so
  the coverage and mutation floors are enforced without touching any
  build.gradle.kts.
- Cancel superseded PR runs, but never cancel a run on main.
- Upload test, jacoco and pitest reports as an artifact on failure only —
  an unguarded upload step would be skipped exactly when the reports matter.
Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 5: Push and open the pull request**

```bash
cd /Users/andriibats/IdeaProjects/tik-tak-toe-m8
git push -u origin milestone-8
gh pr create --base main --head milestone-8 \
  --title "[MILESTONE-8] Gate every push and PR behind a GitHub Actions build" \
  --body "$(cat <<'EOF'
Adds the CI workflow: every PR to `main` and every push to `main` runs
`./gradlew build` from the repository root.

**Why one command is the whole gate.** The root `settings.gradle.kts` includes
all six modules; Gradle's `build` implies `check`; and `check` in
`game-engine-service` and `game-session-service` already depends on
`jacocoTestCoverageVerification` and `pitest`. So coverage and mutation floors
are enforced with **zero changes to any `build.gradle.kts`** — which also means
this branch cannot conflict with the Gateway/Testing work happening in parallel.

**Measured baseline on `main`:** engine 79/79 mutants killed, session 74/74,
engine line coverage 99%. The only local failure is `eureka-server:test`, which
binds port 8761 for real and therefore fails whenever the dev stack is running —
a clean runner has no such conflict.

Reports (tests, jacoco, pitest) are uploaded as an artifact **on failure only**.

Follow-ups on this milestone, outside this PR: branch protection on `main`
(required check `build`, `strict: true`, `enforce_admins: false`), a throwaway
red PR proving a failing build blocks the merge, and README docs on `main`.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

- [ ] **Step 6: Watch the run and confirm it is green**

```bash
cd /Users/andriibats/IdeaProjects/tik-tak-toe-m8
gh run watch "$(gh run list --workflow=ci.yml --branch milestone-8 --limit 1 --json databaseId --jq '.[0].databaseId')" --exit-status
gh pr checks
```

Expected: the run concludes `success`, and `gh pr checks` lists a check named `build` with status `pass`. If `eureka-server:test` fails here, that contradicts the baseline analysis — download the artifact and report it rather than editing the test.

---

### Task 2: Branch protection on `main`

**Files:** none — this is repository configuration applied through the GitHub API.

**Interfaces:**
- Consumes: the status check context `build` produced by Task 1. Protection can only be verified meaningfully once at least one run has reported that context.

- [ ] **Step 1: Record the current state, so the change is reversible**

```bash
gh api repos/andrey56097/tik-tak-toe/branches/main/protection
```

Expected right now: HTTP 404 `Branch not protected`. Keep that fact — removing protection later is `gh api -X DELETE repos/andrey56097/tik-tak-toe/branches/main/protection`.

- [ ] **Step 2: Ask the user to confirm, then apply protection**

This changes the settings of a public repository and affects everyone who pushes. Do not run it without an explicit go-ahead.

```bash
gh api -X PUT repos/andrey56097/tik-tak-toe/branches/main/protection --input - <<'EOF'
{
  "required_status_checks": {
    "strict": true,
    "checks": [{"context": "build"}]
  },
  "enforce_admins": false,
  "required_pull_request_reviews": null,
  "restrictions": null
}
EOF
```

Why each field:
- `checks: [{context: "build"}]` — the job id from Task 1.
- `strict: true` — the branch must be up to date with `main` before merging.
- `enforce_admins: false` — deliberate. Required status checks otherwise reject direct pushes to `main`, which would break this repository's documented rule that docs and rule changes go straight to `main`. As an admin the user keeps that path; everyone else goes through a green PR.
- `required_pull_request_reviews: null` — review is enforced by the project's process (a reviewer subagent), not by GitHub, and a required-reviewer rule cannot be satisfied by a solo maintainer approving their own PR.

- [ ] **Step 3: Verify protection reads back as intended**

```bash
gh api repos/andrey56097/tik-tak-toe/branches/main/protection \
  --jq '{strict: .required_status_checks.strict,
         contexts: .required_status_checks.contexts,
         admins: .enforce_admins.enabled}'
```

Expected: `{"strict": true, "contexts": ["build"], "admins": false}`.

---

### Task 3: Prove a red build blocks the merge

**Files:**
- Create (throwaway, never merged): `common/src/test/java/com/flamingo/tiktaktoe/common/CiGateProofTest.java` on branch `ci-gate-proof`

**Interfaces:**
- Consumes: protection from Task 2 and the `build` check from Task 1.
- Produces: evidence for the plan item "Verify that a PR cannot be merged if CI is red". Produces no lasting change — branch and PR are deleted at the end.

- [ ] **Step 1: Create the throwaway branch**

```bash
cd /Users/andriibats/IdeaProjects/tik-tak-toe-m8
git checkout -b ci-gate-proof main
```

- [ ] **Step 2: Write a test that deliberately fails**

Create `common/src/test/java/com/flamingo/tiktaktoe/common/CiGateProofTest.java`:

```java
package com.flamingo.tiktaktoe.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Throwaway. Exists only to make CI red on the ci-gate-proof branch, proving
 * that branch protection blocks the merge button. This branch is never merged
 * and is deleted once the evidence is captured.
 */
class CiGateProofTest {

    @Test
    void failsOnPurpose() {
        assertThat(1).isEqualTo(2);
    }
}
```

`common` is chosen on purpose: it is the fastest module and it fails before Pitest ever starts, so the proof run costs under a minute.

- [ ] **Step 3: Push it and open the PR**

```bash
cd /Users/andriibats/IdeaProjects/tik-tak-toe-m8
git add common/src/test/java/com/flamingo/tiktaktoe/common/CiGateProofTest.java
git commit -m "[MILESTONE-8] Add a deliberately failing test to prove the CI gate

Throwaway branch. Verifies that branch protection makes a pull request with a
red build unmergeable. Never merged; deleted once the evidence is captured.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
git push -u origin ci-gate-proof
gh pr create --base main --head ci-gate-proof \
  --title "[MILESTONE-8] DO NOT MERGE — proof that the CI gate blocks a red PR" \
  --body "Throwaway. Contains a test that fails on purpose. Opened only to show that branch protection blocks the merge button while \`build\` is red. Will be closed unmerged."
```

- [ ] **Step 4: Confirm CI goes red**

```bash
cd /Users/andriibats/IdeaProjects/tik-tak-toe-m8
gh pr checks --watch
```

Expected: check `build` reports `fail`.

- [ ] **Step 5: Capture the evidence that the merge is blocked**

```bash
cd /Users/andriibats/IdeaProjects/tik-tak-toe-m8
gh pr view --json mergeable,mergeStateStatus,statusCheckRollup \
  --jq '{mergeable, mergeStateStatus, checks: [.statusCheckRollup[] | {name, conclusion}]}'
gh pr merge --merge   # expected to be refused
```

Expected: `mergeStateStatus` is `BLOCKED`, and `gh pr merge` exits non-zero with a message that the pull request is not mergeable / required status checks have not passed. Record both outputs in the report to the user. Also confirm the failure artifact exists:

```bash
gh run download "$(gh run list --workflow=ci.yml --branch ci-gate-proof --limit 1 --json databaseId --jq '.[0].databaseId')" --dir /tmp/ci-proof-reports && ls /tmp/ci-proof-reports
```

Expected: a `reports-<run_id>` directory containing the `common` test report — proof that `if: failure()` uploads work.

- [ ] **Step 6: Clean up completely**

```bash
cd /Users/andriibats/IdeaProjects/tik-tak-toe-m8
gh pr close ci-gate-proof --delete-branch
git checkout milestone-8
git branch -D ci-gate-proof
git ls-remote --heads origin ci-gate-proof   # expected: no output
```

---

### Task 4: Land the milestone

**Files:** none beyond what Task 1 committed.

- [ ] **Step 1: Rebase onto `main`, because protection is `strict`**

```bash
cd /Users/andriibats/IdeaProjects/tik-tak-toe-m8
git fetch origin
git rebase origin/main
```

If Milestone 6 landed first, this pulls it in. Conflicts are not expected — this branch touches only `.github/workflows/ci.yml` and `docs/milestone-8-ci-plan.md`. If a conflict does appear, stop and report it rather than resolving it blind.

- [ ] **Step 2: Push the rebased branch and wait for green**

```bash
cd /Users/andriibats/IdeaProjects/tik-tak-toe-m8
git push --force-with-lease
gh pr checks --watch
```

Expected: `build` passes on the rebased head. This run is the first one that exercises the gate on somebody else's code, so read the log even if it is green.

- [ ] **Step 3: Ask the user to confirm the merge, then merge**

```bash
cd /Users/andriibats/IdeaProjects/tik-tak-toe-m8
gh pr merge milestone-8 --merge --delete-branch
```

- [ ] **Step 4: Confirm the post-merge run on `main` is green**

```bash
gh run watch "$(gh run list --workflow=ci.yml --branch main --limit 1 --json databaseId --jq '.[0].databaseId')" --exit-status
```

Expected: `success`. This is the run the README badge will point at.

---

### Task 5: Documentation, committed directly to `main`

**Files:**
- Modify: `README.md` — three separate edits (header badge, `### Test` note, new `## Continuous Integration` section)

Per CLAUDE.md, docs go straight to `main`, not through a branch. Do this **after** Task 4, because a badge for a workflow that is not yet on `main` renders as `no status`. Reuse the docs worktree from Task 0 and bring it up to date, so the primary checkout stays untouched:

```bash
cd /Users/andriibats/IdeaProjects/tik-tak-toe-docs
git pull --ff-only origin main
```

- [ ] **Step 1: Add the badge to the header block**

In `README.md`, the centred badge block (currently `README.md:8-12` on `main`) ends with the License badge. Insert the CI badge as the **first** badge of that block, above the Java badge:

```markdown
[![CI](https://github.com/andrey56097/tik-tak-toe/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/andrey56097/tik-tak-toe/actions/workflows/ci.yml)
```

The other badges are static `shields.io` images with `style=for-the-badge`; this one is a live GitHub badge and deliberately keeps GitHub's default style, because a status badge should look like a status, not like a version label.

- [ ] **Step 2: Note the port-8761 trap in `### Test`**

In the `### Test` section (currently `README.md:247-259`), after the line `To run one module's suite alone, name it — ./gradlew :game-engine-service:test.`, add:

```markdown
> **Stop the stack before running the full build.** `EurekaServerApplicationTest`
> starts the Eureka server on its real port (`DEFINED_PORT`, 8761), so
> `./gradlew build` fails with `PortInUseException` while the services are
> running locally. CI runs on a clean machine and is unaffected.
```

- [ ] **Step 3: Add the `## Continuous Integration` section**

Insert it between `## Testing Strategy` and `## Git Workflow` (currently `README.md:407`, at the `---` separator that follows the Testing Strategy diagram):

```markdown
## Continuous Integration

Every pull request to `main` and every push to `main` runs
[`.github/workflows/ci.yml`](.github/workflows/ci.yml) — a single job that
executes `./gradlew build` from the repository root on Temurin 21.

One command is the entire gate, because the root build already implies it:

| Layer | Where it comes from | Threshold |
|---|---|---|
| Compile + unit/integration tests | `build` → `check` → `test`, every module | all green |
| Line coverage | `jacocoTestCoverageVerification`, `game-engine-service` | 80% |
| Mutation score | `pitest`, `game-engine-service` and `game-session-service` | 80% |

When a run fails, the test, JaCoCo and Pitest HTML reports are attached to it as
a `reports-<run_id>` artifact — the surviving mutants are readable there, which
the step log does not show. Successful runs upload nothing.

`main` is protected: `build` is a required status check, a branch must be up to
date with `main` before merging, and the rule is not enforced for admins so that
docs-only commits can still go straight to `main`.
```

- [ ] **Step 4: Ask the user to confirm, then commit to `main` and push**

```bash
cd /Users/andriibats/IdeaProjects/tik-tak-toe-docs
git add README.md
git commit -m "$(cat <<'EOF'
Document the CI gate in the README

- Add a live CI status badge for the main branch.
- Add a Continuous Integration section: what the single ./gradlew build
  covers, which thresholds it enforces and where they are configured, where
  to find the reports of a failed run, and how main is protected.
- Warn in Test that the full build fails locally with PortInUseException
  while the stack is running, because EurekaServerApplicationTest binds the
  real 8761.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
EOF
)"
git push origin main
```

- [ ] **Step 5: Confirm the badge is live, then remove the docs worktree**

```bash
curl -sI "https://github.com/andrey56097/tik-tak-toe/actions/workflows/ci.yml/badge.svg?branch=main" | head -1
git -C /Users/andriibats/IdeaProjects/tik-tak-toe worktree remove /Users/andriibats/IdeaProjects/tik-tak-toe-docs
```

Expected: `HTTP/2 200`. Then open the README on GitHub and confirm the badge reads `passing`.

- [ ] **Step 6: Remove the milestone worktree**

```bash
git -C /Users/andriibats/IdeaProjects/tik-tak-toe worktree remove /Users/andriibats/IdeaProjects/tik-tak-toe-m8
git -C /Users/andriibats/IdeaProjects/tik-tak-toe worktree list
```

Expected: only the primary checkout remains (plus whatever the Milestone 6 agent owns).

---

## Out of scope, reported rather than fixed

- **`eureka-server:test` binds port 8761 for real.** It makes `./gradlew build` unrunnable locally while the stack is up, which undercuts Milestone 7's promise of "one command runs the suite". The fix belongs to whoever owns that test (`webEnvironment = RANDOM_PORT` plus an assertion on the configured property, or a dedicated profile); this milestone only documents it.
- **`game-session-service` has no JaCoCo plugin** — only `game-engine-service` does. So the 80% line-coverage floor currently gates one module while the mutation floor gates two. Adjusting that means editing `build.gradle.kts`, which is Milestone 7's territory.
- **`docs/tic-tac-toe-plan.md` checkboxes for Milestone 8** are left unticked at the user's explicit request.
