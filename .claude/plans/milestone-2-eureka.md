# Milestone 2 — Eureka Server + registration

Branch: `milestone-2` (already created from `main`, currently checked out — work directly on it, do not create another branch).

## Context

`eureka-server` already has `@EnableEurekaServer` on `EurekaServerApplication` and the
`spring-cloud-starter-netflix-eureka-server` dependency with Spring Cloud BOM `2025.1.2`
pinned in `eureka-server/build.gradle.kts` (do not change the BOM version — see
CLAUDE.md's "Version gotchas": 2025.1.2 is required for Spring Boot 4.1.x compatibility).
It has no `application.yml` and no test source set yet.

`game-engine-service` has no Eureka dependency yet. It already has a working
`application.yml` (H2, port 8081) and a full test suite (unit + `@DataJpaTest` +
`@SpringBootTest`/MockMvc integration tests) that must keep passing unchanged in behavior.

These decisions were already grilled with the user — do not re-litigate them, implement as specified:

## Task

1. **`eureka-server/src/main/resources/application.yml`** (new file):
   - `server.port: 8761`
   - `spring.application.name: eureka-server`
   - `eureka.client.register-with-eureka: false`
   - `eureka.client.fetch-registry: false`
   - `eureka.server.enable-self-preservation: false`
   - `eureka.server.eviction-interval-timer-in-ms: 5000`

   Standalone single-node local dev config — the server must not try to register with
   itself or peer with other Eureka nodes, and must evict stale instances quickly for
   fast local feedback (not the default slow production-tuned intervals).

2. **`eureka-server` test** (create the test source set, it doesn't exist yet):
   TDD — write a `@SpringBootTest` first that verifies `EurekaServerApplication`'s
   context loads successfully, confirm it fails/is meaningless before the yml exists,
   then add the yml above to make it pass.

3. **`game-engine-service/build.gradle.kts`**: add
   `implementation("org.springframework.cloud:spring-cloud-starter-netflix-eureka-client")`
   and a `dependencyManagement { imports { mavenBom("org.springframework.cloud:spring-cloud-dependencies:2025.1.2") } }`
   block — mirror the exact pattern already used in `eureka-server/build.gradle.kts`.

4. **`game-engine-service/src/main/resources/application.yml`**: add
   - `eureka.client.service-url.defaultZone: http://localhost:8761/eureka/`
   - `eureka.instance.lease-renewal-interval-in-seconds: 5`

   Leave every existing key (datasource, h2, jpa, server.port) untouched.

5. **`game-engine-service/src/test/resources/application.yml`** (new file):
   `eureka.client.enabled: false` — so the existing `@SpringBootTest`-based tests
   (`GameControllerIntegrationTest`, etc.) don't attempt real Eureka registration,
   don't get slow/noisy, and don't flake on a Eureka server that isn't running during
   `./gradlew test`. Run the full existing `game-engine-service` test suite after this
   change and confirm nothing broke and nothing got slower/flakier.

6. **New test in `game-engine-service`** asserting the app's Eureka instance/app name
   resolves to `GAME-ENGINE-SERVICE` — configuration correctness only, no live
   registration handshake with a second process (no real Eureka server is running
   during this test). Suggested approach: a dedicated `@SpringBootTest` that
   re-enables the Eureka client via `@TestPropertySource` (or a nested test config)
   while keeping `register-with-eureka`/`fetch-registry` false so it never actually
   attempts network I/O, then asserts on `EurekaInstanceConfigBean.getAppname()`
   (or equivalent) directly. Use your judgment on the cleanest way to get this
   assertion without real network calls — the goal is proving our config is correct,
   not proving live registration (that's manual verification + a later milestone).

## Explicitly out of scope — do not add

- `spring-boot-starter-actuator` / Eureka health-aware status reporting.
- `eureka.instance.prefer-ip-address` or any other Docker/Kubernetes-oriented
  networking config (revisit in Milestone 8).
- WireMock or any other stub proving a live registration handshake (revisit in
  Milestone 7 — already recorded in project memory as a deferred idea).
- Anything in `game-session-service`, `ui-service`, or `gateway` — those are later milestones.

## Constraints

- Follow CLAUDE.md fully: SOLID/DRY/KISS/YAGNI, TDD (test first, watch it fail, then
  make it pass), the `code-quality` skill checklist applied to your own work before
  you consider it done.
- Run `./gradlew :eureka-server:test :game-engine-service:test` (and `:common:test`
  only if you touch `common`, which you should not need to) and confirm everything is
  green before finishing.
- Do not touch files outside: `eureka-server/**`, `game-engine-service/build.gradle.kts`,
  `game-engine-service/src/main/resources/application.yml`,
  `game-engine-service/src/test/resources/application.yml`, and the one new test file
  for the instance-name assertion.
- **Do not commit or push.** Leave everything as uncommitted working-tree changes —
  the orchestrating session will handle commit/push after user confirmation.

## Report back

Return: status (done/blocked), list of files changed/created, one-line summary of
test results (counts, pass/fail), and any concerns or deviations from this brief
(with rationale) you had to make.
