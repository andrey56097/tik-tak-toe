# [MILESTONE-6] Gateway — one entry point

Branch: `milestone-6`, cut from `main` after Milestone 5 merged (PR #15). Puts
Spring Cloud Gateway on `:8080` in front of everything, so the browser stops
knowing internal service ports — and, as a direct consequence, the temporary
CORS configuration added in Milestone 4 is deleted rather than maintained.

Decisions below were grilled with the user and are final — do not re-litigate.

`task.md` marks "Service Discovery / API Gateway" optional; Milestone 2 already
covered the discovery half. This closes the other half and is the milestone that
makes the system reachable the way it would actually be deployed.

---

## Verified before deciding (not assumptions)

Checked against the resolved artifact — Gateway **5.0.0**, from Spring Cloud
2025.1.2 — not from memory:

- Property namespace is `spring.cloud.gateway.server.webflux.*`, per the jar's
  own `spring-configuration-metadata.json`. The legacy `spring.cloud.gateway.*`
  form is a sign of an outdated BOM.
- `streaming-media-types` **already contains `text/event-stream` by default** —
  confirmed in `GatewayProperties`' bytecode. SSE therefore streams out of the
  box; no setting makes it stream, which turns the plan's "confirm the route
  streams rather than buffers" into something to *prove with a test*, not to
  configure.
- `response-timeout` is **unset by default**, so nothing currently truncates a
  long response.
- `NettyRoutingFilter` reads per-route metadata keys `connect-timeout` and
  `response-timeout`, so one route can legitimately override the global values.
- `gateway/build.gradle.kts` today carries **only**
  `spring-cloud-starter-gateway-server-webflux`: no Eureka client (so `lb://`
  has nothing to resolve names with) and no actuator (which CLAUDE.md requires
  of every service). Both get added; that is not a decision, it is a gap.

---

## Decisions (grilled)

**1. The Engine is not routed through the gateway.** Only `/sessions/**` and the
`/**` catch-all are exposed. The Engine stays reachable to the Session service
via `lb://GAME-ENGINE-SERVICE` and to a developer directly on `:8081`. The plan
lists `/games/**` as "optional, for direct access/debugging", so omitting it
contradicts nothing. *Rejected:* routing `/games/**` too — it would put a
move-submitting endpoint on the public entry point, giving the browser a second
way to make a move that bypasses `GameSessionOrchestrator` entirely. The Engine
would validate each such move, but a game being driven by auto-play could then
change under the orchestrator's feet. The cost of omitting it is that the
Engine's Swagger UI is not reachable from `:8080` — acceptable, since docs are
per-service by design.

**2. Routes are declared in YAML, not built by a `RouteLocator` bean.** No Java
production code is added to this module at all. *Rejected:* a
`RouteLocatorBuilder` configuration class — it satisfies "mutation testing is
mandatory" only in the letter: the mutants it generates test that a builder
chain was called, not that the gateway routes correctly, while deployment
topology moves into code that needs recompiling to change a path. The repo
already has the honest answer to this shape of problem: `...session.config.*` is
excluded from Pitest, with `RestClientConfigTest` and `CorsConfigTest` gating
those decisions against a booted context instead. This module follows that
precedent — Pitest is not configured here, because there is nothing to mutate.

**3. Timeouts are set globally, which forces the stream onto its own route.**
CLAUDE.md's "timeouts are mandatory on every outbound client — a call must never
block indefinitely" applies to the gateway, which is an outbound client to two
services. So `connect-timeout: 2000` and `response-timeout: 5000` are set
globally: every request that passes through is short (a `GET /sessions/{id}`
snapshot, a `POST /simulate` that returns 202 immediately). That timeout would
kill the SSE stream, so `GET /sessions/*/stream` becomes a separate route with
its own `metadata.response-timeout`. The split is therefore *load-bearing*, not
speculative structure — which is exactly the argument the plan already recorded
when it justified giving the stream its own URL (streams and snapshots need
different timeouts, buffering, caching and metrics). *Rejected:* no timeouts and
one `/sessions/**` route — simpler, and the defaults do stream correctly, but a
hung Session would hold a gateway connection forever, breaking a mandatory rule
we honoured in `RestGameEngineClient`.

**4. The stream route's timeout is a real backstop, not "disabled".** It is set
to **125 s**, just above the emitter's own 120 s (`session.stream.timeout-ms`).
The emitter always closes first, so the gateway value never fires in normal
operation, yet a Session that hangs without closing is still cut. *Rejected:*
disabling the timeout for that route — it depends on unverified semantics for
negative values, and it re-opens the hole that decision 3 exists to close.

**5. The UI's API calls become relative, and `CorsConfig` is deleted.**
`API_BASE` becomes `''`, so `fetch('/sessions')` and
`EventSource('/sessions/{id}/stream')` go to whatever origin served the page.
`CorsConfig` and `CorsConfigTest` are removed — that class has carried
"**TEMPORARY** — delete this class in Milestone 6" in its javadoc since it was
written, and `app.js` documents `API_BASE = ''` as the only change it needs.
Accepted consequence: opening `:8083` directly stops working — the page loads
but its API calls 404 against `ui-service`. `localhost:8080` becomes the single
supported entry point, which is the whole point of the milestone.
*Rejected:* computing `API_BASE` client-side so both entry points keep working —
it keeps a live CORS configuration the plan ordered removed, and leaves two ways
to run the system, one of which nothing tests.

---

## Settled by the plan, not re-opened

- Gateway on port **8080**; it resolves `lb://` service ids through Eureka.
- Route order matters and is expressed by declaration order: the specific
  stream route first, then `/sessions/**`, then `/**` last. A catch-all placed
  anywhere but last swallows the routes below it.
- No Swagger aggregation at the gateway. Docs stay per-service (KISS); the
  gateway would need the `-webflux-ui` springdoc variant to host them.

## To decide while implementing (no user input needed)

- Exact YAML shape of the per-route `metadata` block.
- How Eureka is switched off in the gateway's own tests so they need no running
  registry.
- How the second test points a route at the embedded stub instead of `lb://`.

## Testing (mandatory, per CLAUDE.md)

Tests first. Two levels, because they prove different things and neither
substitutes for the other.

**Level 1 — the routing table, on a booted context.** Assert against the
`Route` objects the application actually assembled:

- all three routes exist, in the declared order, with the expected `lb://` uris;
- predicates match the right URLs — `/sessions/abc/stream` selects the stream
  route and not the general one, `/sessions/abc` selects the general one, `/`
  and `/app.js` fall through to `UI-SERVICE`;
- the stream route carries the overridden `response-timeout` and the others do
  not;
- the gateway's own `/actuator/health` is **not** swallowed by the `/**`
  catch-all. Whether the actuator's handler mapping outranks
  `RoutePredicateHandlerMapping` is decided by an order property, so this is
  settled by a test rather than by reasoning about defaults.

**Level 2 — proof that the stream is not buffered.** A small WebFlux endpoint
stood up inside the test serves `text/event-stream` from a `Sinks.many`, and a
route points at it. The test subscribes, waits for the first event, and **only
then** emits the second — so a gateway that buffered the response would hang the
test deterministically instead of failing once in a while. No `sleep`, no timing
assertions. This is the one claim of the milestone that Level 1 cannot reach.

No Pitest in this module — see decision 2.

## Out of scope

- Rate limiting, circuit breaking, auth at the gateway. None is asked for by
  `task.md`, and adding a filter with no requirement behind it is exactly the
  speculative extensibility CLAUDE.md forbids.
- Browser-side tests. Still no JS test infrastructure; Playwright remains the
  agreed, deferred direction.
- The known SSE gaps carried from Milestone 5 (`completeWithError` in
  `subscribe`; no cap on subscribers per session). Tracked, not addressed here.

## Documentation follow-up (goes to `main`, not this branch)

- README: the run instructions become "start five services, open
  `localhost:8080`" — the `:8083` address disappears from the document as it
  disappears from the system.
