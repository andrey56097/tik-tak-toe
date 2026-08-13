# Milestone 9 — Docker + docker-compose Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `docker compose up` on a clean machine — no local JDK, no prior `./gradlew build` — brings up all five services, each isolated in its own container, talking to each other over one compose network by service name, and a full game plays through `http://localhost:8080`. `docker compose down` takes it all away.

**Architecture:** One `docker/Dockerfile`, multi-stage, parameterised by `ARG MODULE`; compose builds it five times with five different `MODULE` values, producing one image per service. The build context is the **repository root**, because every module's build needs the root `../../settings.gradle.kts`, and the engine and session modules additionally need `:common`. Nothing Docker-specific enters `application.yml`: the container-only addresses (`eureka-server` instead of `localhost`) are supplied as environment variables in `docker-compose.yml`, which Spring's relaxed binding maps onto the existing properties. Running the stack from an IDE keeps working exactly as before.

**Tech Stack:** Docker 29.6 / Compose v5.3, BuildKit (`# syntax=docker/dockerfile:1`, cache mounts), `eclipse-temurin:21-jdk` for the build stage and `eclipse-temurin:21-jre` for the runtime stage, `bash` + `curl` for the smoke script.

## Global Constraints

- **Working tree:** `/Users/andriibats/IdeaProjects/tik-tak-toe-m9`, branch `milestone-9`, branched from `milestone-6` at `71d5edb`. The gateway exists only on that branch, and a five-service compose file without the gateway would be a fiction — hence the base. **When Milestone 6 merges into `main`, rebase this branch onto `main` before opening the PR.**
- **Do not touch other agents' checkouts:** `/Users/andriibats/IdeaProjects/tik-tak-toe` (Milestone 6) and `/Users/andriibats/IdeaProjects/tik-tak-toe-m8` (Milestone 8) are occupied. Never run git or Gradle there.
- **Only one piece of production code changes in this milestone:** `eureka-server` gains `spring-boot-starter-actuator`. It is required by CLAUDE.md ("every service depends on actuator and exposes `health,info`") and is missing today — a pre-existing debt that this milestone must clear anyway, because the compose healthcheck reads `/actuator/health`. Everything else is new files.
- **No `application.yml` is edited.** Every container-only value arrives as an environment variable. If a value cannot be expressed that way, stop and report rather than editing the yml.
- **Ports keep their documented identities inside the network** — engine 8081, session 8082, ui 8083, gateway 8080, eureka 8761. Only 8080 and 8761 are published to the host.
- **Commit only after the user explicitly says so.** Never `git commit` or `git push` on your own initiative (CLAUDE.md).
- **Commit and PR titles start with `[MILESTONE-9]`.**
- **Code review by a reviewer subagent is mandatory before the merge** (CLAUDE.md).
- Docs (this plan, the README section) land **directly on `main`**, separately from the branch's PR.
- Docker Desktop must be running; at the time of writing the daemon socket was absent (`/Users/andriibats/.docker/run/docker.sock`). Every verification step below needs it.

## Decisions taken in the design interview (2026-08-13)

| Question | Decision | Why |
|---|---|---|
| Where does the jar come from? | Multi-stage: Gradle runs **inside** the build stage | The plan's promise is "one command on a clean machine". A `COPY build/libs/*.jar` Dockerfile needs a local JDK and silently ships a stale jar when someone forgets to rebuild. |
| Five Dockerfiles or one? | **One** `docker/Dockerfile` with `ARG MODULE` | The five would differ only in a module name. DRY per CLAUDE.md, and the layers before the source copy are shared across all five builds. Still one image per service, which is what the roadmap item means. |
| How do containers learn each other's addresses? | **Environment variables in compose** | Zero changes to code and yml, all Docker specifics in one file, IDE runs unaffected. A `docker` Spring profile would add five files and a second source of truth. |
| How do services register in Eureka? | `EUREKA_INSTANCE_PREFER_IP_ADDRESS=true` | By default an instance registers under the container's hostname (its ID). The container IP is always routable on the compose network and does not depend on hostname resolution or on one-replica-per-service. |
| Startup ordering | `HEALTHCHECK` on all five + `depends_on: condition: service_healthy` | Makes `docker compose up --wait` a real readiness gate and `docker compose ps` honest. The cost is the actuator dependency in `eureka-server`, which was owed anyway. |
| Published ports | `8080` (gateway) and `8761` (Eureka dashboard) only | Milestone 6 made 8080 the single entry point; the dashboard is published because "all four registered" is the thing a reviewer wants to see. Engine, session and UI stay internal. |
| Persistence | H2 stays **in-memory** | This milestone is about packaging and networking. File mode is already recorded as a one-line future improvement. |
| Proof it works | `scripts/smoke.sh` | A repeatable, non-zero-on-failure check that anyone can re-run, without adding Docker to `./gradlew build`. A Testcontainers compose test would put minutes and a Docker requirement into CI. |

## File Structure

- **Create** `docker/Dockerfile` — the single build recipe for all five services.
- **Create** `.dockerignore` — keeps `../../build`, `../../.gradle`, `.git/` and IDE files out of the build context.
- **Create** `docker-compose.yml` (repository root) — five services, one network, healthchecks, ordering, environment.
- **Create** `scripts/smoke.sh` — brings the stack up, plays one game through `:8080`, tears it down.
- **Modify** `../../eureka-server/build.gradle.kts` — add `spring-boot-starter-actuator`.
- **Modify** `../../eureka-server/src/main/resources/application.yml` — expose `health,info`, for parity with the other four services.
- **Create** `eureka-server/src/test/java/.../EurekaServerHealthTest.java` — the failing-first test for the above.
- **Modify on `main` (not on the branch)** — `../../README.md` run instructions, and this plan's checkboxes in `../../docs/tic-tac-toe-plan.md` if the user asks for them.
- **Untouched:** every other `build.gradle.kts`, every other `application.yml`, all of `*/src/main/java`.

---

### Task 0: Land this plan on `main`

**Files:**
- Create: `docs/milestone-9-docker-plan.md` (this document), in the docs worktree on `main`

- [ ] **Step 1: Confirm the docs worktree is on `main` and current**

```bash
cd /Users/andriibats/IdeaProjects/tik-tak-toe-docs && git status --short && git log --oneline -1
```

The Milestone 8 agent may have uncommitted README work here. Stage **only** this file.

- [ ] **Step 2: Ask the user to confirm, then commit and push**

```bash
cd /Users/andriibats/IdeaProjects/tik-tak-toe-docs
git add docs/milestone-9-docker-plan.md
git commit -m "Plan Milestone 9: the whole stack in containers, one command"
git push origin main
```

---

### Task 1: Actuator on the Eureka server (TDD)

**Files:**
- Create: `eureka-server/src/test/java/com/flamingo/tiktaktoe/eureka/EurekaServerHealthTest.java`
- Modify: `../../eureka-server/build.gradle.kts`, `../../eureka-server/src/main/resources/application.yml`

**Interfaces:**
- Produces: `GET /actuator/health` → `200 {"status":"UP"}` on 8761. Task 3's healthcheck consumes exactly this.

- [ ] **Step 1 (red): Write the failing test**

`EurekaServerApplicationTest` already boots this service on `DEFINED_PORT`. The new test asserts the health endpoint answers `UP`, using `TestRestTemplate` against port 8761. It fails today with 404, because the actuator is not on the classpath.

```bash
cd /Users/andriibats/IdeaProjects/tik-tak-toe-m9 && ./gradlew :eureka-server:test --tests '*EurekaServerHealthTest*'
```

Expected: **fail** (404). If the failure is `PortInUseException` instead, a local stack is running — stop it first; that trap is documented in the README.

- [ ] **Step 2 (green): Add the dependency and the exposure block**

`../../eureka-server/build.gradle.kts`, next to the existing starters:

```kotlin
implementation("org.springframework.boot:spring-boot-starter-actuator")
```

`../../eureka-server/src/main/resources/application.yml`, at the end:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info
```

Re-run the test — expected: green. Then `./gradlew :eureka-server:build` to confirm nothing else broke.

---

### Task 2: The build recipe

**Files:**
- Create: `docker/Dockerfile`, `.dockerignore`
- Test: building one image and running it standalone — the build **is** the test.

**Interfaces:**
- Consumes: `ARG MODULE` (a Gradle project name, e.g. `game-engine-service`).
- Produces: an image whose entrypoint runs that module's boot jar.

- [ ] **Step 1: Write `.dockerignore` first**

Without it the context includes every `../../build` directory and `.git`, which is hundreds of megabytes shipped to the daemon on every build. Keep `../../gradle/wrapper` — the build stage needs the wrapper jar.

```
.git
.gradle
**/build
.idea
*.iml
docs
README.md
```

- [ ] **Step 2: Write `docker/Dockerfile`**

```dockerfile
# syntax=docker/dockerfile:1

# One recipe, five images. The build context is the repository root because the
# root settings.gradle.kts drives every module's build, and engine/session also
# compile against :common.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY ../../docs .
ARG MODULE
# The cache mount keeps ~/.gradle across builds, so the five images share one
# dependency download instead of repeating it five times. bootJar (not build)
# on purpose: tests are the Gradle build's job, not the image's, and bootJar
# leaves exactly one jar in build/libs, which makes the COPY below unambiguous.
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon :${MODULE}:bootJar

FROM eclipse-temurin:21-jre AS runtime
ARG MODULE
WORKDIR /app
COPY --from=build /workspace/${MODULE}/build/libs/*.jar app.jar
# Nothing here needs root, and a JVM that cannot write to its own image is a
# smaller blast radius if a dependency is ever exploited.
RUN useradd --system --uid 10001 --create-home spring
USER spring
# Containers get a memory limit, not a machine; MaxRAMPercentage makes the heap
# follow that limit instead of the host's RAM.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
```

- [ ] **Step 3: Verify `curl` exists in the runtime image — the healthcheck depends on it**

```bash
docker run --rm eclipse-temurin:21-jre sh -lc 'command -v curl || echo NO_CURL; command -v wget || echo NO_WGET'
```

If both are missing, add to the runtime stage **before** the `USER` line:

```dockerfile
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
```

and record in the commit message why a ~5 MB tool is in a runtime image. Do not switch the healthcheck to a bare TCP probe: a listening port is not readiness.

- [ ] **Step 4: Build one image and run it standalone**

```bash
cd /Users/andriibats/IdeaProjects/tik-tak-toe-m9
docker build -f docker/Dockerfile --build-arg MODULE=game-engine-service -t tiktaktoe/game-engine-service:dev .
docker run --rm -p 18081:8081 -e EUREKA_CLIENT_ENABLED=false tiktaktoe/game-engine-service:dev &
sleep 25 && curl -fsS localhost:18081/actuator/health && curl -fsS -X POST localhost:18081/games
```

Expected: `{"status":"UP"}` and a fresh game JSON. Kill the container afterwards. This proves the recipe before compose multiplies it by five.

---

### Task 3: `docker-compose.yml`

**Files:**
- Create: `docker-compose.yml` (repository root)

**Interfaces:**
- Consumes: `docker/Dockerfile` and its `MODULE` arg.
- Produces: services named `eureka-server`, `game-engine-service`, `game-session-service`, `ui-service`, `gateway` on the default compose network. The service names double as DNS names and as the Eureka URL host.

- [ ] **Step 1: Write the file**

```yaml
# Every service is built from the same docker/Dockerfile with a different MODULE.
# Container-only configuration lives here and only here — no application.yml in
# this repository knows that Docker exists.
x-service-health: &service-health
  # $$SERVER_PORT is escaped so the shell inside the container expands it, not
  # compose on the host. SERVER_PORT is also what Spring binds server.port from,
  # so the probe and the server can never disagree about the port.
  test: ["CMD-SHELL", "curl -fsS http://localhost:$$SERVER_PORT/actuator/health || exit 1"]
  interval: 5s
  timeout: 3s
  retries: 24
  start_period: 20s

x-eureka-client: &eureka-client
  EUREKA_CLIENT_SERVICEURL_DEFAULTZONE: http://eureka-server:8761/eureka/
  # Registering the container's IP rather than its hostname (which defaults to
  # the container id) keeps lb:// resolvable no matter how the container is named.
  EUREKA_INSTANCE_PREFER_IP_ADDRESS: "true"
  # The default 30s registry fetch would make the first game after startup fail
  # to resolve GAME-ENGINE-SERVICE. Five seconds matches the lease renewal the
  # services already configure.
  EUREKA_CLIENT_REGISTRY_FETCH_INTERVAL_SECONDS: "5"

services:
  eureka-server:
    build:
      context: .
      dockerfile: docker/Dockerfile
      args:
        MODULE: eureka-server
    environment:
      SERVER_PORT: "8761"
      # Eureka caches its registry responses for 30s by default, which delays
      # every client's first successful lookup for no reason at this scale.
      EUREKA_SERVER_RESPONSECACHEUPDATEINTERVALMS: "5000"
    ports:
      - "8761:8761"   # dashboard: the proof that all four registered
    healthcheck: *service-health

  game-engine-service:
    build:
      context: .
      dockerfile: docker/Dockerfile
      args:
        MODULE: game-engine-service
    environment:
      <<: *eureka-client
      SERVER_PORT: "8081"
    depends_on:
      eureka-server:
        condition: service_healthy
    healthcheck: *service-health

  game-session-service:
    build:
      context: .
      dockerfile: docker/Dockerfile
      args:
        MODULE: game-session-service
    environment:
      <<: *eureka-client
      SERVER_PORT: "8082"
      # Unchanged from application.yml — the engine is still reached through
      # Eureka by service id, never by container name. Docker networking and
      # service discovery stay separate concerns.
    depends_on:
      eureka-server:
        condition: service_healthy
      game-engine-service:
        condition: service_healthy
    healthcheck: *service-health

  ui-service:
    build:
      context: .
      dockerfile: docker/Dockerfile
      args:
        MODULE: ui-service
    environment:
      <<: *eureka-client
      SERVER_PORT: "8083"
    depends_on:
      eureka-server:
        condition: service_healthy
    healthcheck: *service-health

  gateway:
    build:
      context: .
      dockerfile: docker/Dockerfile
      args:
        MODULE: gateway
    environment:
      <<: *eureka-client
      SERVER_PORT: "8080"
    ports:
      - "8080:8080"   # the only door into the system
    depends_on:
      game-session-service:
        condition: service_healthy
      ui-service:
        condition: service_healthy
    healthcheck: *service-health
```

No `networks:` block: compose creates a default bridge network for the project and attaches all five, which is exactly the "shared network, service-name addressing" the roadmap asks for. Adding an explicit network would be ceremony.

- [ ] **Step 2: Validate the file before building**

```bash
cd /Users/andriibats/IdeaProjects/tik-tak-toe-m9 && docker compose config --quiet && echo OK
```

- [ ] **Step 3: Bring the stack up and wait for readiness**

```bash
cd /Users/andriibats/IdeaProjects/tik-tak-toe-m9
docker compose up --build --wait --wait-timeout 600
docker compose ps
curl -s localhost:8761/eureka/apps -H 'Accept: application/json' | python3 -m json.tool | grep -i '"name"'
```

Expected: five containers `healthy`, and the registry lists `GATEWAY`, `GAME-ENGINE-SERVICE`, `GAME-SESSION-SERVICE`, `UI-SERVICE`.

Known trap to watch for: `EUREKA_SERVER_RESPONSECACHEUPDATEINTERVALMS` relies on relaxed binding of `eureka.server.responseCacheUpdateIntervalMs`. If the registry still looks stale after 30s, drop that variable rather than guessing — correctness does not depend on it, only latency.

---

### Task 4: `scripts/smoke.sh`

**Files:**
- Create: `scripts/smoke.sh` (executable)

**Interfaces:**
- Consumes: a published `:8080`. Everything it touches is the public contract — no container internals.
- Produces: exit code 0 on a finished game, non-zero otherwise; always tears the stack down.

- [ ] **Step 1: Write the script**

```bash
#!/usr/bin/env bash
# Proves the containerised stack actually plays a game: brings compose up,
# creates a session through the gateway, starts the simulation and waits for a
# terminal state — all through the single published port, exactly as a browser
# would. Non-zero exit means the stack is broken.
set -euo pipefail

BASE="${BASE:-http://localhost:8080}"
COMPOSE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
KEEP_UP="${KEEP_UP:-false}"

cleanup() {
  if [ "$KEEP_UP" = "true" ]; then
    echo "KEEP_UP=true — leaving the stack running"
    return
  fi
  echo "--- docker compose down"
  docker compose -f "$COMPOSE_DIR/docker-compose.yml" down --remove-orphans
}
trap cleanup EXIT

echo "--- docker compose up"
docker compose -f "$COMPOSE_DIR/docker-compose.yml" up --build --wait --wait-timeout 600

echo "--- create a session"
SESSION_ID="$(curl -fsS -X POST "$BASE/sessions" | python3 -c 'import json,sys; print(json.load(sys.stdin)["sessionId"])')"
echo "sessionId=$SESSION_ID"

echo "--- start the simulation"
curl -fsS -X POST "$BASE/sessions/$SESSION_ID/simulate" -o /dev/null -w 'HTTP %{http_code}\n'

echo "--- wait for a terminal session status"
for _ in $(seq 1 60); do
  BODY="$(curl -fsS "$BASE/sessions/$SESSION_ID")"
  STATUS="$(printf '%s' "$BODY" | python3 -c 'import json,sys; print(json.load(sys.stdin)["status"])')"
  case "$STATUS" in
    COMPLETED)
      GAME="$(printf '%s' "$BODY" | python3 -c 'import json,sys; s=json.load(sys.stdin)["gameState"]; print(s["status"], s.get("winner"))')"
      echo "PASS: session COMPLETED, game $GAME"
      exit 0
      ;;
    FAILED)
      echo "FAIL: session FAILED"
      printf '%s\n' "$BODY"
      exit 1
      ;;
  esac
  sleep 2
done

echo "FAIL: session did not finish within 120s (last status: ${STATUS:-unknown})"
exit 1
```

- [ ] **Step 2: Make it executable and run it**

```bash
cd /Users/andriibats/IdeaProjects/tik-tak-toe-m9
chmod +x scripts/smoke.sh
./scripts/smoke.sh
```

Expected: `PASS: session COMPLETED, game WIN X` (or `DRAW None`), exit 0, and the stack removed afterwards. A game is ~9 moves at 1s apart, so expect the wait loop to take 10–20 seconds.

---

### Task 5: End-to-end verification and review

- [ ] **Step 1: The browser path, which the smoke script does not cover**

```bash
cd /Users/andriibats/IdeaProjects/tik-tak-toe-m9 && KEEP_UP=true ./scripts/smoke.sh
```

Then open `http://localhost:8080` and play a game: the board must update live (SSE through the gateway), and the browser must never contact 8081/8082/8083. Verify in the network panel that the stream request is `text/event-stream` and arrives incrementally rather than in one buffered chunk at the end. Then `docker compose down`.

- [ ] **Step 2: `docker compose down` leaves nothing behind**

```bash
cd /Users/andriibats/IdeaProjects/tik-tak-toe-m9
docker compose down
docker compose ps --all
docker network ls | grep tik-tak-toe || echo "network removed"
```

- [ ] **Step 3: The Gradle build is unaffected**

```bash
cd /Users/andriibats/IdeaProjects/tik-tak-toe-m9 && ./gradlew build --continue
```

Only `eureka-server` changed, and only additively. If Milestone 8's CI has landed by then, this is the same command the gate runs.

- [ ] **Step 4: Dispatch the reviewer subagent**

Review scope: `docker/Dockerfile`, `.dockerignore`, `docker-compose.yml`, `scripts/smoke.sh`, the `eureka-server` diff — against this plan and CLAUDE.md. Points to check explicitly: no `application.yml` other than `eureka-server`'s was touched; no secret or host-specific path is baked into an image; the runtime stage does not run as root; the healthcheck probes readiness rather than a listening port; the smoke script cleans up on every exit path including failure; nothing in the compose file addresses the engine by container name instead of through Eureka.

- [ ] **Step 5: Ask for permission, then commit**

One commit for the milestone (atomic: the Dockerfile, the compose file, the smoke script and the actuator change only make sense together), titled `[MILESTONE-9] Bring the whole stack up with one docker compose command`.

- [ ] **Step 6: Rebase onto `main` once Milestone 6 has landed, then open the PR**

```bash
cd /Users/andriibats/IdeaProjects/tik-tak-toe-m9
git fetch origin && git rebase origin/main
```

Conflicts are not expected: this branch adds new files and touches one `build.gradle.kts` that no other milestone edits.

---

### Task 6: Documentation on `main`

- [ ] **Step 1: README — a `docker compose up` run path**

Add to the README's run instructions: the one-command path, the two published ports and why only two, the note that images build from source inside Docker so the first build takes minutes, and `scripts/smoke.sh` as the way to verify a stack without a browser. Keep the existing "run from the IDE" instructions — they still work unchanged, which is the point of configuring Docker purely through environment variables.

- [ ] **Step 2: Tick the Milestone 9 checkboxes in `docs/tic-tac-toe-plan.md`** (only if the user asks — Milestone 8 explicitly excluded plan edits).

---

## Out of scope, reported rather than fixed

- **No image is published to a registry.** Compose builds locally; a `docker push` flow belongs with CI (Milestone 8) or Kubernetes (Milestone 11).
- **No CI job builds the images.** Adding the smoke script to GitHub Actions is a one-step follow-up once Milestone 8 lands, deliberately left to that milestone's owner.
- **Single replica per service.** `prefer-ip-address` already makes scaling possible, but nothing here is tested with `docker compose up --scale`.
- **`eureka-server`'s `DEFINED_PORT` test still binds 8761 for real**, so `./gradlew build` fails locally while the containers are up. Same trap Milestone 8 documented; the fix belongs to whoever owns that test.
