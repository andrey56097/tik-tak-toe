# Milestone 11 — Kubernetes readiness

**Goal:** `kubectl apply -k k8s/` on a local cluster brings up the same five services Milestone 9 packaged, each in its own Pod, discovering each other through Eureka exactly as they do under compose, and a full game plays through a single Ingress on `http://localhost/` — including the SSE stream. `kubectl delete -k k8s/` takes it all away.

**Architecture:** The five images built by Milestone 9 are deployed unchanged. Each service is one `Deployment` (1 replica) plus one `ClusterIP` `Service`; the container-only values that `docker-compose.yml` supplies as environment variables are supplied here by a `ConfigMap` and per-Deployment `env` entries, so **no `application.yml` and no Java source changes in this milestone** — the same property Milestone 9 held. Kubernetes provides the Pods, the DNS name `eureka-server`, and the only route in (`Ingress` → `gateway:8080`); Eureka keeps providing service discovery.

**Tech stack:** kubectl v1.36.2 with built-in kustomize v5.8.1, minikube v1.38.1 (Docker driver, macOS), the minikube `ingress` addon (ingress-nginx), and `bash` + `curl` for the smoke script. No Helm, no new binaries to install.

> This document is the milestone's brief and its record. The shipped files —
> `k8s/*.yaml`, `scripts/k8s-smoke.sh` — are the source of truth for *what* was
> built; every one of them carries its reasoning inline, as the Milestone 9 files
> do. This document keeps the decisions, the constraints they came from, and what
> the verification actually showed.

## Constraints this milestone works under

- **Branch:** `milestone-11`, branched from `main`. Milestone 7 (testing) is in flight in parallel; it touches test sources, while this milestone touches `k8s/`, `scripts/` and one CI step — no overlap. Rebase onto `main` before the pull request regardless.
- **Zero production code changes.** Not one Java file, not one `application.yml`. If a value cannot be expressed as an environment variable, that gets reported rather than worked around — the same rule Milestone 9 followed.
- **The images are Milestone 9's, unmodified.** `docker/Dockerfile` is not edited. Its comment about `-XX:MaxRAMPercentage=75.0` following "a Kubernetes resource limit in Milestone 11" is exactly what the `resources` blocks below make true.
- **Ports keep their documented identities** — engine 8081, session 8082, ui 8083, gateway 8080, eureka 8761 — inside the cluster as inside the compose network.
- **Commit only after the user explicitly says so**, commit and PR titles start with `[MILESTONE-11]`, and a reviewer subagent passes before the merge (CLAUDE.md).
- Docs (README, roadmap, this plan) land **directly on `main`**, separately from the branch's pull request.
- **Pushing the CI change requires SSH.** The `gh` token has no `workflow` scope, so any commit touching `.github/workflows/` is rejected over HTTPS.

## Decisions taken in the design interview (2026-08-13)

| Question | Decision | Why |
|---|---|---|
| Does Eureka survive in a cluster that has its own DNS discovery? | **Eureka stays.** Kubernetes gives it a stable `Service` name and nothing else | The milestone is *readiness*: proving the existing system deploys, not redesigning it for Kubernetes. Keeping it costs zero code changes, so `docker compose up` and `kubectl apply` bring up the same system with one README. Dropping it would rewrite the gateway routes and `engine.client.base-url`, leave two divergent configurations, and delete a component `task.md` requires. Recorded as a deliberate trade: in a real cluster this layer would be replaced by `Service` DNS or Spring Cloud Kubernetes. |
| How far does the milestone go? | **A real run on minikube**, ingress and SSE included | "Readiness" asserted from unapplied YAML is an assertion. Probes, startup ordering, image pull policy and nginx buffering only reveal themselves in a cluster. |
| How do images reach the cluster? | `docker compose build` → `minikube image load` ×5, with `imagePullPolicy: IfNotPresent` written **explicitly** | One build path for compose and for Kubernetes (DRY). The explicit policy is the point: nothing pushes these images anywhere, so a Pod that decides to pull fails with `ErrImagePull`. Building inside `minikube docker-env` was rejected — it repeats the ~10-minute serialized Gradle build inside the VM and evaporates on `minikube delete`. |
| Flat manifests or a tool? | **kustomize**, `kubectl apply -k k8s/` | `kubectl apply -f k8s/` applies files in *alphabetical* order, so `configmap.yaml` lands before `namespace.yaml` and the apply fails. kustomize sorts by resource kind, and puts the namespace, the shared labels and the image tags in one place. It ships inside kubectl — no new dependency. Helm was rejected: five services differing by a name and a port do not need a templating engine, and the roadmap asks for `k8s/` manifests. |
| `secret.yaml` from the roadmap | **`k8s/secret.yaml.example`** — present, documented, never applied | There is nothing to put in a Secret: H2 is in-memory with no password, and the gateway has no credentials. An applied but empty Secret would be decoration, which this repo does not ship. The example file keeps the mechanism visible for the day H2 becomes a real database. |
| Where does the OpenRouter key fit? | **Nowhere in the cluster** | Corrected during the interview: OpenRouter *is* used — `.github/scripts/ai-review.py` posts PR diffs to it — but from GitHub Actions, reading `secrets.OPENROUTER_API_KEY`. No deployed service calls it, so it belongs to GitHub Secrets, a different store. The roadmap line that lists it among cluster secrets is corrected rather than deleted. |
| Which health endpoints back the probes? | `startupProbe` and `livenessProbe` → `/actuator/health/liveness`; `readinessProbe` → `/actuator/health/readiness` | Spring Boot enables these groups automatically once it detects Kubernetes, and `exposure: health,info` already publishes them — so the "no yml changes" rule holds. Pointing `livenessProbe` at the composite `/actuator/health` is the classic trap: that endpoint aggregates the datasource and discovery client, so a brief Eureka outage would restart every Pod instead of merely holding traffic back. |
| Startup ordering without `depends_on` | **Nothing.** No initContainers, no wait-for scripts | Kubernetes has no `depends_on` and does not need one here: the Eureka client retries registration on its own, and `readinessProbe` keeps traffic off a Pod until it can serve. Ordering emerges from retries, which is how a cluster is supposed to reach a steady state. |
| External access | **Hostless `Ingress`** (`/` → `gateway:8080`) reached through `minikube tunnel` at `http://localhost/` | Keeps the gateway the single door (Milestone 6) with no `/etc/hosts` edit. `minikube tunnel` needs sudo, so `kubectl port-forward svc/gateway 8080:8080` is documented as the sudo-free path and is what the smoke script uses. NodePort was rejected: the roadmap asks for an Ingress, and only an Ingress exercises the nginx behaviour SSE depends on. |
| SSE through nginx | `nginx.ingress.kubernetes.io/proxy-buffering: "off"` and `proxy-read-timeout: "130"` | ingress-nginx buffers upstream responses by default, which would hold the whole `text/event-stream` until the game ends — turning a live board into one delivery at the end, or a timeout. 130s sits just above the gateway's 125s stream route timeout, which sits above the emitter's own 120s, so the innermost timeout always fires first. |
| Replicas and resources | 1 replica each; requests `256Mi`/`100m`, limits `512Mi`/`1000m` | Eureka has no peers to replicate with and the session store is in-memory, so a second replica would be incorrect, not just unnecessary. The limits are what make the image's `-XX:MaxRAMPercentage=75.0` meaningful — the heap now sizes from the cgroup rather than from the whole host. |
| Do the manifests get unit tests? | **No — and this is deliberate** | CLAUDE.md's TDD and mutation-testing rules govern production Java. These are configuration files; their executable checks are `scripts/k8s-smoke.sh` (a real game in a real cluster) and the CI dry-run. Stated here so the review does not read the absence of Pitest as an omission. |

## What will ship

```
k8s/
  kustomization.yaml        namespace, common labels, image tags, resource order
  namespace.yaml            namespace tik-tak-toe
  configmap.yaml            the shared Eureka client environment
  eureka-server.yaml        Deployment + Service (8761)
  game-engine-service.yaml  Deployment + Service (8081)
  game-session-service.yaml Deployment + Service (8082)
  ui-service.yaml           Deployment + Service (8083)
  gateway.yaml              Deployment + Service (8080)
  ingress.yaml              / -> gateway:8080, SSE annotations
  secret.yaml.example       documented, never applied
scripts/
  k8s-smoke.sh              load images, apply, play a game, tear down
```

The `ConfigMap` carries only what every client shares — `EUREKA_CLIENT_SERVICEURL_DEFAULTZONE: http://eureka-server:8761/eureka/`, `EUREKA_INSTANCE_PREFER_IP_ADDRESS: "true"`, `EUREKA_CLIENT_REGISTRYFETCHINTERVALSECONDS: "5"` — the same three values compose keeps in its `x-eureka-client` anchor, and for the same reasons (a Pod IP is always routable; the default 30s registry fetch would make the first game after startup fail to resolve `GAME-ENGINE-SERVICE`). `SERVER_PORT` stays per-Deployment because it differs per service, and the Eureka server's own `EUREKA_SERVER_RESPONSECACHEUPDATEINTERVALMS` stays on its Deployment because it is server-side — the registry server must not consume the client ConfigMap.

## Tasks

### Task 0 — prerequisites and the one assumption worth checking first
- [ ] `minikube start` (Docker driver) with enough headroom for five JVMs — `--memory=6g --cpus=4` if the host allows; `minikube addons enable ingress`.
- [ ] **Verify the probe assumption before writing five copies of it:** run one image in the cluster and `curl /actuator/health/liveness`. Spring Boot should auto-enable the groups on detecting Kubernetes. If Boot 4.1 does not, the fallback keeps the "no yml changes" rule intact — add `MANAGEMENT_ENDPOINT_HEALTH_PROBES_ENABLED: "true"` to the ConfigMap, one line, no source change.

### Task 1 — the base: namespace, config, Eureka
- [ ] `namespace.yaml`, `configmap.yaml`, `kustomization.yaml`, `eureka-server.yaml`.
- [ ] Apply and confirm the Eureka Pod reaches `Ready` and its dashboard answers through `kubectl port-forward`.

### Task 2 — the four clients
- [ ] `game-engine-service.yaml`, `game-session-service.yaml`, `ui-service.yaml`, `gateway.yaml` — each with the three probes, the resource block, and a `securityContext` (`runAsNonRoot`, `allowPrivilegeEscalation: false`, all capabilities dropped; the root filesystem stays writable because the JVM needs its temp directory).
- [ ] Confirm all four appear in the Eureka registry **by Pod IP**, and that the ordering claim holds — apply everything at once and watch it converge without initContainers.

### Task 3 — the way in
- [ ] `ingress.yaml` with the two SSE annotations.
- [ ] `minikube tunnel`, then a game played from `http://localhost/` in a real browser, watching the board update event by event.

### Task 4 — the documented non-secret
- [ ] `k8s/secret.yaml.example`, explaining what would go in it, how it would be wired (`secretKeyRef` on the engine's datasource), and why the cluster has none today.

### Task 5 — a repeatable proof
- [ ] `scripts/k8s-smoke.sh`: build → `minikube image load` ×5 → `apply -k` → `rollout status` → port-forward → create a session → simulate → poll to a terminal status → tear down. Non-zero exit on anything else; `KEEP_UP=true` leaves the cluster up, matching `smoke.sh`'s contract.
- [ ] **DRY:** the game-playing half is identical to `scripts/smoke.sh`. Extract it into a sourced helper used by both. If that turns out to complicate `smoke.sh` rather than simplify it, keep the duplication and say so here rather than forcing the abstraction.

### Task 6 — the merge gate learns about manifests
- [ ] Add a step to the existing `build` job in `.github/workflows/ci.yml`: `kubectl kustomize k8s/ | kubectl apply --dry-run=client -f -`. Seconds, no cluster required, catches broken YAML and misspelled fields before merge.
- [ ] A **step**, not a new job: the job id `build` is the branch-protection context name, and a second job would need the protection rule updated to be a gate at all.
- [ ] Confirm `kubectl` is present on the `ubuntu-latest` runner; if not, `azure/setup-kubectl@v4` is the one-line fallback.
- [ ] Push over SSH (the token lacks `workflow` scope).

### Task 7 — docs, straight to `main`
- [ ] README: a Kubernetes run path beside the Docker one, honest about the sudo `minikube tunnel` needs and about how long loading five ~600 MB images takes; the port-forward alternative; the known limitations below.
- [ ] `docs/tic-tac-toe-plan.md`: tick the milestone's checkboxes, and correct the `secret.yaml` line — the OpenRouter key is a **CI** secret living in GitHub Secrets, and the cluster holds no secrets until H2 becomes a real database.

### Task 8 — verification and review
- [ ] Full run from a cold `minikube delete && minikube start`, filling the table below.
- [ ] Reviewer subagent pass over manifests, script and docs, then ask the user for the commit.

## Verified on (to be filled)

| Check | Result |
|---|---|
| `kubectl apply -k k8s/` on a cold cluster | |
| All five Deployments `Available`, without initContainers | |
| Eureka registry lists four clients, by Pod IP | |
| Full game through the Ingress on `http://localhost/` | |
| SSE arrives event by event, not buffered to the end | |
| UI in a browser through the same door | |
| `scripts/k8s-smoke.sh` exits 0 | |
| `kubectl delete -k k8s/` leaves nothing behind | |
| CI dry-run step green | |

## Known limitations — recorded, not hidden

- **Eureka holds Pod IPs.** When a Pod is rescheduled its registry entry is stale until the lease expires, so a request can be routed at an address that no longer exists. Harmless at one replica with a 5s lease renewal, and it is the price of keeping client-side discovery in a cluster that offers its own.
- **Sessions live in memory.** `InMemorySessionStore` means a rolling update or an evicted Pod drops every game in flight. Consistent with the existing recorded gap about crash recovery; a real fix is a shared store, not a Kubernetes setting.
- **`scripts/k8s-smoke.sh` verifies through port-forward, not the Ingress**, so that it never needs sudo. The Ingress path — and therefore the SSE-through-nginx behaviour — stays a manual check, recorded in the table above.
- **No images are published.** `minikube image load` is a local-cluster mechanism; a real deployment needs a registry, which belongs with CI rather than here.
- **One cluster, one environment.** No kustomize overlays for dev/prod. There is no second environment to differentiate from, and inventing one would be exactly the speculative extensibility CLAUDE.md rules out.
