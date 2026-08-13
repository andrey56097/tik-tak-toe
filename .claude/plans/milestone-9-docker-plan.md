# Milestone 9 — Docker + docker-compose

**Goal:** `docker compose up` on a clean machine — no local JDK, no prior `./gradlew build` — brings up all five services, each isolated in its own container, talking to each other over one compose network by service name, and a full game plays through `http://localhost:8080`. `docker compose down` takes it all away.

**Architecture:** One `docker/Dockerfile`, multi-stage, parameterised by `ARG MODULE`; compose builds it five times with five different `MODULE` values, producing one image per service. The build context is the **repository root**, because every module's build needs the root `settings.gradle.kts`, and the engine and session modules additionally need `:common`. Nothing Docker-specific enters any `application.yml`: the container-only addresses (`eureka-server` instead of `localhost`) are supplied as environment variables in `docker-compose.yml`, which Spring's relaxed binding maps onto the existing properties. Running the stack from an IDE keeps working exactly as before.

**Tech stack:** Docker 29.6 / Compose v5.3, BuildKit (`# syntax=docker/dockerfile:1`, cache mounts), `eclipse-temurin:21-jdk` for the build stage and `eclipse-temurin:21-jre` for the runtime stage, `bash` + `curl` for the smoke script.

> This document is the milestone's brief and its record. The shipped files —
> `docker/Dockerfile`, `.dockerignore`, `docker-compose.yml`, `scripts/smoke.sh` —
> are the source of truth for *what* was built; every one of them carries the
> reasoning inline. This document keeps the decisions, the constraints they came
> from, and what the verification actually showed.

## Constraints this milestone worked under

- **Branch:** `milestone-9`, branched from `milestone-6` at `71d5edb` — the gateway existed only there at the time, and a five-service compose file without the gateway would have been a fiction. Milestones 6 and 8 have since merged into `main`, so the branch is rebased onto `main` before the pull request.
- **Only one piece of production code changes:** two additive lines' worth in `eureka-server` (see Task 1). Everything else is new files.
- **No `application.yml` is edited** beyond that. Every container-only value arrives as an environment variable; a value that could not be expressed that way would have been reported rather than worked around.
- **Ports keep their documented identities inside the network** — engine 8081, session 8082, ui 8083, gateway 8080, eureka 8761. Only 8080 and 8761 are published to the host.
- **Commit only after the user explicitly says so**, commit and PR titles start with `[MILESTONE-9]`, and a reviewer subagent passes before the merge (CLAUDE.md).
- Docs land **directly on `main`**, separately from the branch's pull request.

## Decisions taken in the design interview (2026-08-13)

| Question | Decision | Why |
|---|---|---|
| Where does the jar come from? | Multi-stage: Gradle runs **inside** the build stage | The promise is "one command on a clean machine". A `COPY build/libs/*.jar` Dockerfile needs a local JDK and silently ships a stale jar when someone forgets to rebuild. |
| Five Dockerfiles or one? | **One** `docker/Dockerfile` with `ARG MODULE` | The five would differ only in a module name. DRY per CLAUDE.md, and the layers before `ARG MODULE` are shared across all five builds. Still one image per service, which is what the roadmap item means. |
| How do containers learn each other's addresses? | **Environment variables in compose** | Zero changes to code and yml, all Docker specifics in one file, IDE runs unaffected. A `docker` Spring profile would add five files and a second source of truth. |
| How do services register in Eureka? | `EUREKA_INSTANCE_PREFER_IP_ADDRESS=true` | By default an instance registers under the container's hostname, which in Docker is its id. The container IP is always routable on the compose network and does not depend on hostname resolution or on one replica per service. |
| Startup ordering | `HEALTHCHECK` on all five + `depends_on: condition: service_healthy` | Makes `docker compose up --wait` a real readiness gate and `docker compose ps` honest. |
| Published ports | `8080` (gateway) and `8761` (Eureka dashboard) only | Milestone 6 made 8080 the single entry point; the dashboard is published because "all four registered" is the thing a reviewer wants to see. Engine, session and UI stay internal. |
| Persistence | H2 stays **in-memory** | This milestone is about packaging and networking. File mode is already recorded as a one-line future improvement. |
| Proof it works | `scripts/smoke.sh` | A repeatable, non-zero-on-failure check anyone can re-run, without adding Docker to `./gradlew build`. A Testcontainers compose test would put minutes and a Docker requirement into CI. |

## What shipped

- **`docker/Dockerfile`** — the single build recipe. Build stage runs `./gradlew --no-daemon :${MODULE}:bootJar` behind a `sharing=locked` cache mount on `GRADLE_USER_HOME`; runtime stage copies the one jar out, drops to a non-root `spring` user and runs it. `bootJar` rather than `build`: tests belong to the Gradle build and to CI, and `bootJar` leaves exactly one jar in `build/libs`, which is what makes the `COPY` unambiguous.
- **`.dockerignore`** — keeps `.git`, `.gradle`, every `build/`, IDE files and docs out of a context that is the whole repository.
- **`docker-compose.yml`** — five services, YAML anchors for the shared healthcheck and the shared Eureka client environment, `depends_on: service_healthy` ordering, and the two published ports. No `networks:` block: compose's project-scoped default network already registers each service name as a DNS name.
- **`scripts/smoke.sh`** — up, create a session through the gateway, simulate, poll to a terminal status, tear down. Non-zero exit on anything else; `KEEP_UP=true` leaves the stack running for manual poking.

### Task 1: Actuator on the Eureka server

- [x] `eureka-server/src/test/java/com/flamingo/tiktaktoe/eureka/EurekaServerHealthTest.java` — asserts `/actuator/health` reports `UP` and `/actuator/info` answers at all.
- [x] `eureka-server/build.gradle.kts` — declares `spring-boot-starter-actuator` explicitly.
- [x] `eureka-server/src/main/resources/application.yml` — exposes `health,info`.

**What was actually broken here, corrected from the original plan.** The plan assumed this service had no actuator at all. It did: `spring-cloud-starter-netflix-eureka-server` pulls `spring-boot-starter-actuator` in transitively at compile scope, so `/actuator/health` — the endpoint the compose healthcheck gates the other four containers on — already answered, and that half of the test was green before any production change. The real gap was `/actuator/info`: this was the only service not declaring a `management.endpoints.web.exposure` block, and the default exposes `health` alone, so `info` was a 404. That is the test that went red first, and the yml block is what turned it green. The explicit dependency line is kept deliberately — a capability that load-bearing should not rest on another starter's dependency graph — but it changed no behaviour.

### Task 2-4: images, compose, smoke script

- [x] `.dockerignore`, then `docker/Dockerfile`, verified by building one image and running it standalone before compose multiplied it by five.
- [x] `docker-compose.yml`, validated with `docker compose config` before the first build.
- [x] `scripts/smoke.sh`, executable, `bash -n` clean.

`curl` was verified present in `eclipse-temurin:21-jre` (Ubuntu 26.04), so the contingency of installing it into the runtime image was not needed. A bare TCP probe was ruled out on purpose: a listening port is not readiness.

## Verified on 2026-08-13

Run on macOS with Docker 29.6.1 / Compose v5.3. The host's own dev stack occupied 8080-8083 and 8761 at the time, so this run published the gateway on 18080 and Eureka on 18761 through a throwaway override file; nothing else differed.

| Check | Result |
|---|---|
| `docker compose up --build --wait` | all five containers `healthy`; ordering held: eureka → engine/ui → session → gateway |
| Eureka registry | `GATEWAY 172.20.0.6:8080`, `GAME-SESSION-SERVICE 172.20.0.5:8082`, `UI-SERVICE 172.20.0.4:8083`, `GAME-ENGINE-SERVICE 172.20.0.3:8081`, all UP, all by container IP |
| Full game through the gateway | `POST /sessions` → `simulate` (202) → `COMPLETED`, `WIN`, winner X, 9 moves |
| UI through the same port | `GET /` → 200 `text/html` |
| SSE through the gateway | 9 events, one per second, arriving incrementally — the gateway does not buffer `text/event-stream` |
| `docker compose down` | no containers, no network left |
| Image sizes | 605-676 MB each, of which 524 MB is the shared `eclipse-temurin:21-jre` base |
| `./gradlew build --continue` | green except `eureka-server:test`, which fails with `PortInUseException` while a local stack holds 8761 — the pre-existing trap Milestone 8 documented, not a regression |

## Out of scope, reported rather than fixed

- **No image is published to a registry.** Compose builds locally; a `docker push` flow belongs with CI (Milestone 8) or Kubernetes (Milestone 11).
- **No CI job builds the images.** Adding `scripts/smoke.sh` to GitHub Actions is a one-step follow-up on top of Milestone 8's workflow.
- **Single replica per service.** `prefer-ip-address` already makes scaling possible, but nothing here is tested with `docker compose up --scale`.
- **The five images build one at a time.** The Gradle cache mount is `sharing=locked`, because a Gradle cache is not safe for concurrent writers; a cold `docker compose up --build` therefore takes ~10 minutes, with four builds apparently idle while the fifth compiles. Per-module cache ids would restore parallelism at five copies of the dependency cache.
- **No memory limits on the containers.** `-XX:MaxRAMPercentage=75.0` is set so the heap follows a cgroup limit the moment one exists, but none is imposed today.
- **`eureka-server`'s `EurekaServerApplicationTest` still binds 8761 for real**, so `./gradlew build` fails locally while the stack is up. Pre-existing debt, now with a second test class in that module.
- **The engine image ships with the H2 console enabled** (`spring.h2.console.enabled: true`, unchanged from Milestone 1). Port 8081 is not published, so it is unreachable from the host, but it is worth revisiting when images stop being a local-only artefact.

## Follow-up on `main`

- [ ] README: the `docker compose up` run path, the two published ports and why only two, that images build from source inside Docker so the first build takes minutes, and `scripts/smoke.sh` as the way to verify a stack without a browser. The existing "run from the IDE" instructions stay — they are unaffected, which is the point of configuring Docker purely through environment variables.
