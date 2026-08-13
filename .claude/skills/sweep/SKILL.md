---
name: sweep
description: Whole-repository sweep for the defects a diff-scoped review structurally cannot see — ungated code, duplicated concepts across services, dead artifacts, documentation that promises what the code does not do, and resources that grow without bound. Run at completion points (a feature fully implemented, before submission or release), not on every task or milestone.
---

# Sweep — the whole-repo pass

> **Announce:** "I'm using the sweep skill to review the whole repository, not the diff."

## What this is for

`code-quality` checks **a change**. This skill checks **the repository**. The two
find different things, and the second one is not a superset of the first — it is a
different search entirely.

This skill exists because of a real, measured failure. A whole-repo audit of this
project on 2026-08-13 found 19 defects in code that had already passed TDD, a
separate reviewer subagent, a mutation gate at 80 %, and a `code-quality` checklist
run. **Three of the findings were violations of rules already written in that
checklist** — the rules were not missing, they were unfindable from where the
reviewer was standing.

## Do not respond to a finding by adding a checklist item

That is the instinct, and it does not work here — it was already tried. The
checklist said "no duplication; shared code comes from `common`, not copy-paste"
while two `@RestControllerAdvice` classes sat in two modules and silently drifted
apart. Adding a twentieth line to a list that is applied to a diff cannot fix a
defect that is invisible in every diff.

Five mechanisms produce these defects. Each dimension below targets one.

---

## When to run

**Run it at a completion point:**

- A feature or assignment is implemented end to end and you are about to call it
  done.
- Before a submission, a release, or a hand-off to another person.
- After several milestones have landed and nobody has looked across them.

**Do not run it** per task, per milestone, or on every branch. It is deliberately
infrequent: its value comes from having enough accumulated code for divergence to
show, and running it constantly turns it into noise that gets ignored.

## How to run it

Work through all five dimensions. **Collect findings; do not fix inline.** A sweep
that starts fixing turns into an unreviewed refactor halfway through and never
finishes the sweep. Output is a findings list, ordered by severity, that becomes a
plan.

For each finding, record: what is wrong, the file and line, what it costs, and —
this one is not optional — **which mechanism let it through**. That last field is
what tells you whether the process needs changing or just the code does.

---

## Dimension 1 — Gate coverage

> *Mechanism: quality gates are configured per module by whoever created the
> module. Nobody ever asks which production code is behind no gate at all.*

The gate is trusted as if it covered everything. It covers what someone wired up.

- [ ] **List every module, and whether it has a mutation and a coverage gate.**

```bash
for m in */build.gradle.kts; do
  printf "%-28s pitest:%s jacoco:%s\n" "$(dirname "$m")" \
    "$(grep -c 'pitest' "$m")" "$(grep -c 'jacoco' "$m")"
done
```

A module with production code and `pitest:0` is the answer to "where is the next
bug". In the 2026 audit that was `common` — and `CellState.opposite()` returning a
silently wrong value for `EMPTY` was living in it.

- [ ] **List production code in languages the gate does not cover at all.**
  Java gated at 80 % says nothing about JavaScript, shell, SQL or manifests.
  `app.js` was 234 lines of untested logic next to a Java suite gated at 80 %.

- [ ] **Check every exclusion still has the compensating test it names.**
  Exclusions are written with a justification like "the real gate for this is
  `FooTest`". When `FooTest` is later deleted, the exclusion silently becomes an
  unguarded hole.

```bash
grep -rn "excludedClasses\|excludes\|@Disabled\|ignoreFailures" --include="*.kts" --include="*.xml" .
# then: for every test class named in a justification, confirm it still exists
```

- [ ] **Check the gate actually runs in CI**, not just that it is configured.

---

## Dimension 2 — Cross-module divergence

> *Mechanism: every review is diff-scoped. From inside a diff you cannot see that
> this is the second copy of a thing that lives in another module.*

Two services built in two milestones by two agents will implement the same concept
twice, and the copies will drift.

- [ ] **Find classes whose names repeat across modules** — the same suffix in two
  places is almost always the same concept implemented twice.

```bash
find . -name "*.java" -path "*/src/main/*" -not -path "*/build/*" \
  | sed 's|.*/||' | sort | uniq -d
find . -name "*.java" -path "*/src/main/*" -not -path "*/build/*" \
  | grep -oE "[A-Za-z]+(Handler|Config|Client|Mapper|Validator|Store)\.java$" | sort | uniq -c | sort -rn
```

- [ ] **Diff the duplicates and look for behavioural drift, not just duplication.**
  The 2026 audit found both services handling 405 — one attached the `Allow`
  header per RFC 9110, the other did not. Same API, two answers, depending on
  which service you hit.

- [ ] **Diff the configuration keys.** Properties present in one service and absent
  in its sibling are either a bug or an undocumented deliberate difference.

```bash
for f in */src/main/resources/application.yml; do echo "== $f"; grep -oE "^ *[a-z-]+:" "$f" | sort -u; done
```

- [ ] **Diff the dependency lists.** Two services doing the same job should not
  differ in starter choices (`spring-boot-starter-web` in one,
  `spring-boot-starter-webmvc` in the other) without a reason someone wrote down.

- [ ] **Ask: is anything shared *by copy* that could be shared *by module*?**
  Note the counter-pressure — moving something into a shared module adds a
  dependency to it, and that has its own cost. Record the trade-off, do not
  reflexively hoist.

---

## Dimension 3 — Dead artifacts

> *Mechanism: milestones leave orphans. Something is removed in one place and its
> declaration, its config key, or the comment describing it survives elsewhere.*

- [ ] **Every declared dependency is referenced by source.**

```bash
# for each starter/library in a build file, look for any usage
grep -rn "implementation(\|runtimeOnly(\|api(" --include="build.gradle.kts" . | grep -v "/build/"
# then, per suspicious one:
grep -rn "WebSocket\|Stomp" --include="*.java" --include="*.yml" */src/main || echo "unused"
```

The 2026 audit found `spring-boot-starter-websocket` still declared in the session
service — dead since Milestone 5 chose SSE, and the *same* dependency had been
deliberately removed from another module in Milestone 4.

- [ ] **Every config property is read by something.**

```bash
grep -ohrE "^ *[a-z.-]+:" */src/main/resources/application.yml | tr -d ' :' | sort -u
# cross-check each against @Value / @ConfigurationProperties / getProperty usage
```

- [ ] **Every class named in a comment still exists.** Comments cite classes as
  justification ("the real gate for this is `CorsConfigTest`") and outlive them.

```bash
grep -rhoE "\b[A-Z][A-Za-z]+(Test|Config|Handler|Service|Client)\b" --include="*.java" --include="*.kts" . \
  | sort -u > /tmp/cited.txt
# for each: does a file of that name exist?
```

- [ ] **Every hand-pinned version still needs to be hand-pinned.** A dependency
  pinned because the BOM did not manage it, or added to work around a framework
  gap, should be re-checked when the framework moves. The 2026 audit found a
  hand-pinned `spring-retry` plus an `aspectjweaver` workaround where the
  framework had since grown a native equivalent.

---

## Dimension 4 — Claims versus code

> *Mechanism: prose is the one artifact no test can fail on. A codebase that
> documents heavily has proportionally more exposure to this.*

- [ ] **Every capability claimed in a README, plan or doc comment is traceable to
      code or a test.** Walk the claims, not the code — the direction matters,
      because you are looking for what is *asserted* and missing, and reading the
      code will never surface that.

```bash
grep -rniE "replay|automatically|retries|recovers|falls back|self-heal|ensures|guarantees" \
  README.md docs/ .claude/plans/ --include="*.md"
```

For each hit, name the file and line that implements it. If you cannot, the claim
is the finding. The 2026 audit found an SSE `Last-Event-ID` replay promised in a
plan and ticked off as done: event ids were emitted, the header was never read, no
buffer existed.

- [ ] **Every ticked checkbox in a plan is true today**, not true when it was
      ticked. Features get removed by later milestones.

- [ ] **Fix by deleting the claim, not by rushing the feature.** A promise nobody
      needs is removed. A promise that matters becomes a task. Future intentions
      are fine — in a "possible improvements" section, phrased as intentions,
      never in the present tense.

---

## Dimension 5 — Lifecycle and limits

> *Mechanism: TDD produces exactly what a test can assert. No test fails because a
> map has no eviction policy.*

This is the dimension that has no natural home in a test suite, so it needs a
deliberate pass. In the 2026 audit **every single operational finding survived
every other check**.

- [ ] **What grows without bound?** Walk every collection, cache, registry and map
      held in a field. For each: what removes entries, and what happens at the
      limit?

```bash
grep -rn "ConcurrentHashMap\|new HashMap\|new ArrayList\|newKeySet\|Collections.synchronized" \
  --include="*.java" */src/main
```

For each hit answer, in writing: **who removes, and when?** "Nothing" is a
finding. The session store held every session ever created, forever, behind an
endpoint that needed no body and no credentials.

- [ ] **What has no ceiling on the way in?** Every entry point that starts work —
      creates a record, spawns a background task, opens a connection. Virtual
      threads and unbounded queues both turn "too many" into "still accepting,
      until the JVM dies" instead of an honest rejection.

- [ ] **What is opened and never closed?** Streams, emitters, subscriptions,
      connections, watchers — on **every** exit path, including the error ones.
      Check the client side too: the browser leaked an `EventSource` on exactly
      the paths where the request had failed.

- [ ] **What has no timeout?** Every outbound call needs connect *and* read.
      Every loop that talks to another system needs an iteration cap.

- [ ] **What happens on restart?** State held only in memory is state that a
      restart destroys. Either that is acceptable and written down, or it is a
      gap.

- [ ] **What can only be diagnosed with a debugger?** If a failure in production
      leaves no metric, no log with correlation, and no trace, it is not
      operable — regardless of how well it is tested.

---

## Output

A findings list, most severe first. For each:

```
<severity> — <one-line claim>
  Where:     path/to/File.java:123
  Costs:     what actually goes wrong, concretely
  Mechanism: which of the five let this through
```

Then: **stop.** Take the list to the human, agree the scope, and turn what is
agreed into a plan (`.claude/plans/`). Fixing is a separate, reviewed piece of
work under `sdd` — it does not happen inside the sweep.

Severity here means *what it costs*, not *how ugly it is*. A silently wrong return
value in ungated code outranks a duplicated class every time.

## Honesty rules

- **Verify before claiming.** In the 2026 audit one suspected defect — a missing
  JSON field silently becoming `0` — turned out to be handled correctly by the
  framework, and was only ruled out by running a probe against the real service.
  Run the probe. Delete it afterwards.
- **Do not inflate.** If nothing is critical, the report says nothing is critical.
  A sweep that manufactures severity to look thorough is worse than no sweep.
- **Report what was not checked.** A dimension skipped for time is a line in the
  output, not a silence.
