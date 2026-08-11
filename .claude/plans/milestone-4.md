# [MILESTONE-4] UI Service — polling

Branch: `milestone-4`. Delivers the third and last `task.md` component: a page
that shows the 3×3 board filling itself as the services play, with status, move
history and error display.

Decisions below were grilled with the user and are final — do not re-litigate.

## Why UI comes before the push channel

The original roadmap had WebSocket wiring at 4 and the UI at 5. They are
swapped. After this milestone all three `task.md` components exist and talk to
each other, so the system is demoable end to end at the earliest possible point;
everything afterwards improves a working system instead of being a prerequisite
for one. It also gives Milestone 5 a real client to develop the stream against,
rather than debugging a publisher with no consumer.

`task.md` supports the split directly: line 59 requires the *effect* ("a board
updated in real time as microservices play"), while line 62 lists WebSockets/SSE
as the **optional** *mechanism*. With `move-delay-ms: 1000`, polling every 500ms
is visually indistinguishable from push. So this milestone closes the required
component **using only the three endpoints `task.md` specifies** — no API surface
is added here.

---

## Decisions (grilled)

**1. `ui-service` serves static files and nothing else.** No controllers, no
server-side logic beyond `UiApplication`. Spring Boot serves
`src/main/resources/static/` on its own, so a controller that returns a file
already being returned is a YAGNI violation. Two consequences: drop
`spring-boot-starter-websocket` (the SSE/WebSocket client runs in the browser,
not in this JVM), and keep `spring-boot-starter-web` (it is what serves the
static files). *Rejected:* making `ui-service` proxy `/sessions` to Session for
same-origin — that duplicates the Gateway arriving in Milestone 6 and would be
thrown away.

**2. The API base URL is one named constant** at the top of `app.js`:

```js
const API_BASE = 'http://localhost:8082';   // Milestone 6: set to ''
```

Milestone 6 changes exactly this line: `''` makes every URL relative, i.e.
same-origin against the Gateway. *Rejected:* inlining the host at each call site
(scattered edits later); relative URLs now (the page is on `:8083`, so
`/sessions` would resolve to a port with no API); auto-detection by port (magic);
serving config from the server (needs the server-side code decision 1 removed).
Known caveat: `localhost` is hardcoded and must be re-checked in Milestone 9
(Docker) — though the browser runs on the host, so published ports keep it
working, and by then the constant is likely already `''`.

**3. Register in Eureka now, not later.** The module resolves nothing through
Eureka in this milestone, so this is knowingly ahead of need. It is added anyway
because Milestone 6 routes `lb://UI-SERVICE`, which only works if the service is
registered — otherwise the Gateway would need a hardcoded `localhost:8083`,
defeating the service discovery it exists to demonstrate. Cost is ~4 lines, and
Eureka is already mandatory at runtime (Session resolves `GAME-ENGINE-SERVICE`
through `@LoadBalanced`), so nothing new has to be running.

**4. Polling mechanics.**

- **500ms interval.** One move per second, so worst-case display lag is half a
  tick. ~18 requests per game per viewer.
- **`setTimeout` chain, never `setInterval`.** `setInterval` fires regardless of
  whether the previous request returned; a slow Session would stack requests and
  let a stale response render after a fresh one. Scheduling the next tick *after*
  each response makes more than one in-flight request impossible.
- **Start after the `202`**, not before. Order: `POST /sessions` → render the
  `CREATED` response immediately → `POST /sessions/{id}/simulate` → on `202`,
  start the loop. If `/simulate` returns 404/409 there is nothing to poll.
  (Unlike SSE, polling has no "missed the first events" race — every tick reads
  full current state — so this order is chosen for simplicity, not necessity.)
- **Stop on `COMPLETED`/`FAILED`, plus a hard cap of 120 iterations (60s).** A
  session wedged in `RUNNING` must not make a tab poll forever. Same principle as
  `MAX_MOVES = 9` in `SessionSimulationRunner`: a loop calling an external system
  gets an iteration cap.
- **Errors do not stop the loop.** Polling *is* retry — the next tick is the
  retry — so no failure counter and no backoff. The 120-iteration cap already
  bounds a permanently dead backend, so one limit covers both cases. The single
  exception is `404` on the session: it is gone, stop immediately.

**5. Two statuses, and which one the user sees.** `SessionStatus`
(`CREATED`/`RUNNING`/`COMPLETED`/`FAILED`) describes the orchestration;
`GameState.status` (`IN_PROGRESS`/`WIN`/`DRAW`) describes the board. They are not
synonyms — `RestGameEngineClient`'s read-timeout path ends a session `FAILED`
while the board in H2 is intact and `IN_PROGRESS`.

| Response | Displayed |
|---|---|
| `gameState == null` (session `CREATED`) | empty 3×3 board, "Not started" |
| `gameState.status == IN_PROGRESS` | board, `IN_PROGRESS` |
| `gameState.status == WIN` | board, `WIN — X` (winner from `gameState.winner`) |
| `gameState.status == DRAW` | board, `DRAW` |
| `SessionStatus == FAILED` | last known board + **error banner**, in addition |

`task.md` line 60 asks for the three game statuses verbatim, so those are
primary; `FAILED` is shown *in addition*, never instead — otherwise we would
either misreport the status or hide the failure.

**`gameState == null` is not hypothetical:** `SessionRecord` is created with a
null game state, so the very first render after `POST /sessions` receives one.
The empty board is a rendering default, not data.

**6. One error banner, three sources.** (a) HTTP error with `ErrorResponse` —
show `message` as-is; the backend already phrases it safely. (b) Network failure
— our own text, there is no body to read. (c) `SessionStatus == FAILED` — arrives
inside a `200 OK`. The banner clears on the next successful render so a transient
blip does not stick. *Rejected:* `alert()` (blocking, cheap-looking) and
console-only logging (`task.md` line 61 says *display*).

**7. CORS lives in Session as a dedicated `WebMvcConfigurer`.** The page is
served from `:8083` and calls `:8082`. `config/CorsConfig implements
WebMvcConfigurer`, allowed origin from a property (default
`http://localhost:8083`), methods `GET`/`POST` only, no credentials. *Rejected:*
`@CrossOrigin` on the controller (cross-origin policy is not the controller's
responsibility, and the annotation is easy to forget when removing it) and `*`
(a habit that migrates to production; an explicit origin costs nothing).
Note most calls here are "simple requests" — `GET` and body-less `POST`, so
`fetch` sets no `Content-Type` and no preflight fires — but the config is still
required for the response headers, and it handles `OPTIONS` automatically if a
JSON body ever appears. **The class javadoc must state it is temporary and is
deleted in Milestone 6**, or nobody will remember why it exists.

**8. Java is tested, JavaScript is not.** `CorsConfig` is production code and
gets a real MockMvc behaviour test (allowed origin gets
`Access-Control-Allow-Origin`, a foreign origin does not) plus Pitest. The
`ui-service` gets one test that `GET /` serves `index.html` as `text/html` —
proving the static resources are actually wired, not merely present. The
JavaScript gets no test toolchain: Vitest/Jest + jsdom would reintroduce npm and
`node_modules`, exactly what was rejected when choosing plain HTML/JS, and
`task.md`'s Testing & Validation section (lines 66–71) is entirely about
inter-service REST, state consistency and the automated flow — nothing about UI.

### Two defaults

- **A page reload loses the session.** `sessionId` lives only in tab memory — no
  `localStorage`, no `?sessionId=`. Session resume is separate functionality
  `task.md` does not ask for.
- **"Start" is disabled while a session runs**, re-enabled on a terminal status.
  Otherwise a second click creates a second session while the first keeps running
  invisibly.

---

## Why plain HTML/JS — and specifically not Thymeleaf

A 3×3 board is nine `<div>`s. React or Angular would drag npm, a bundler and
`node_modules` into a Gradle monorepo for no benefit — a direct KISS/YAGNI
violation per `CLAUDE.md` — and this is a *backend* assignment, where a frontend
toolchain reads as effort spent in the wrong place.

Thymeleaf is wrong for a subtler reason worth writing down, because it is the
tempting Spring-native answer: server-side templating renders **once**, at page
load, but our board changes nine times *after* load. The DOM-patching JavaScript
has to be written either way, so Thymeleaf would render an empty board and then
never participate again.

## The invariant everything rests on

`render(state)` takes the **full** `SessionResponse` and redraws board, status
and history from it — **never** from deltas. That makes it idempotent: rendering
the same state twice changes nothing. This is what makes polling, SSE and
WebSocket interchangeable sources, and it is the entire reason Milestone 5 is
cheap.

The tempting mistake, specifically: on each poll, diff against the previous
response and *append* the new move to the history log. That works under polling
and breaks under push, on reconnects and duplicate events.

## Files

**`ui-service` — new**
- `src/main/resources/application.yml` — `server.port: 8083`, Eureka client,
  actuator `health,info` (the module has **no** config today, so it would boot on
  8080 and collide with the Gateway)
- `src/main/resources/static/index.html` · `app.js` · `app.css`
- `src/test/.../UiApplicationTest` — `GET /` serves the page

**`ui-service` — modified**
- `build.gradle.kts` — drop `spring-boot-starter-websocket`, add
  `spring-cloud-starter-netflix-eureka-client`

**`game-session-service` — new**
- `config/CorsConfig` + `CorsConfigTest`

**`game-session-service` — modified**
- `application.yml` — the allowed-origin property

## Testing

- `CorsConfigTest` — allowed origin receives `Access-Control-Allow-Origin`; a
  foreign origin does not; `GET` and `POST` are permitted.
- `UiApplicationTest` — context loads and `GET /` returns 200 `text/html`.
- Pitest on the new Java code, per the mandatory mutation-testing rule.

### Manual verification checklist

Compensates for the untested JavaScript:

1. Start Eureka, Engine, Session, UI. Open `localhost:8083`.
2. Click Start — the board fills cell by cell, roughly one per second.
3. The final status reads `WIN — X`/`WIN — O`/`DRAW`, and the history lists every
   move in order.
4. Kill Session mid-game — the error banner appears; the board keeps the last
   known state rather than blanking.
5. Restart Session and click Start again — a new session runs cleanly.
6. Reload mid-game — the page resets (documented behaviour, see the defaults).

## Known gaps

- **`render(state)` idempotency is unverified by any automated test.** If someone
  rewrites the history log to append instead of redraw, nothing fails until
  Milestone 5. Revisit in Milestone 7: if a browser-level E2E (Playwright) earns
  its place in the Testing & Validation block, it covers this as a deliberate
  addition rather than npm smuggled in for a unit test.
- `API_BASE` hardcodes `localhost` — re-check in Milestone 9 (Docker).
- No session resume across reloads (deliberate, above).
