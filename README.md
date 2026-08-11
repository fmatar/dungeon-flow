# Dungeon Flow

A text-adventure dungeon whose **map *is* a workflow**. Rooms are workflow states, doors are
transitions, and every player move is a CloudEvent. Powered by
[**Quarkus Flow**](https://docs.quarkiverse.io/quarkus-flow/dev/index.html) (the CNCF Serverless
Workflow engine embedded in Quarkus) — not SonataFlow.

This is the **Crawl** phase: a 5-room dungeon playable entirely over HTTP (curl), with the workflow
diagram viewable in the Quarkus Dev UI as a projector view. See `specs/PRD_Dungeon_Flow.md` and
`specs/SRS_Dungeon_Flow.md` for the full product/spec.

> **Why this teaches workflows:** every hard-to-explain primitive is a game mechanic you *play* —
> an event wait (the fork), a data switch (left/right), a multi-event join (the two levers), a
> bounded retry with compensation (the trap), and an event timeout (the torch).

## The map

```
 Entrance ─▶ Fork ──(left)──▶ Lever Room ──(pull A & B)──▶ Trap Corridor ──(pick lock)──▶ Treasure Room ✦END
              │  \─(right)────────────────────────────────▶ Trap Corridor
              │  \─(unknown, or torch times out)──────────▶ respawn
              ▲                                                    │
              └───────────────── respawn on retry exhaustion ──────┘
```

| Room | Workflow primitive | Requirement |
|------|--------------------|-------------|
| Fork | `listen` (event wait) + `switch` (data-based routing) + `timeout` | REQ-FUNC-002, 005 |
| Lever Room | `listen … all(A, B)` (multi-event **join**, any order) | REQ-FUNC-003 |
| Trap Corridor | bounded **retry** loop + **respawn** compensation | REQ-FUNC-004 |
| Treasure Room | terminal state (`end`) with `victory: true` | REQ-FUNC-006 |

All of this lives in one file — [`DungeonWorkflow.java`](src/main/java/org/acme/dungeon/DungeonWorkflow.java).
The Java beans it calls only observe (`GameStore`) or roll dice (`LockService`); **they never decide
where the player goes next** (SRS constraint C-1).

## Run it

Prerequisites: **Java 25** (the project compiles with `maven.compiler.release=25`) and Maven (or the
`mvnw` wrapper). First run downloads Quarkus 3.38.1 + quarkus-flow 0.15.1 from Maven Central.

```bash
mvn quarkus:dev        # hot-reload dev mode on http://localhost:8080
```

Press `d` in the dev console (or open http://localhost:8080/q/dev-ui) → **Quarkus Flow → Workflows**
to watch the diagram while you play — this is the projector view for demos (PRD-FEAT-09).

## Play it (curl)

```bash
# 1) Start a game — returns your instance id and the entrance narrative
curl -s -XPOST http://localhost:8080/api/dungeon | jq
#   { "instanceId": "01J…", "entrance": { "room": "ENTRANCE", … } }
ID=<paste instanceId>

# 2) At the fork, choose a path (don't dawdle — the torch is burning)
curl -s -XPOST http://localhost:8080/api/dungeon/$ID/choice \
     -H 'Content-Type: application/json' -d '{"direction":"left"}'

# 3a) LEFT — the Lever Room: pull BOTH levers, in any order (the join)
curl -s -XPOST http://localhost:8080/api/dungeon/$ID/lever-a
curl -s -XPOST http://localhost:8080/api/dungeon/$ID/lever-b

# 3b) …then the Trap Corridor picks the lock automatically (retries on a jam)

# 4) Check where you are at any time
curl -s http://localhost:8080/api/dungeon/$ID | jq

# List every active session (facilitator race view) / clean one up
curl -s http://localhost:8080/api/dungeon | jq
curl -s -XDELETE http://localhost:8080/api/dungeon/$ID
```

**Choosing `right`** skips the levers and goes straight to the Trap Corridor.
**Idling** at the fork past the torch timeout (60s in prod, 2s in tests) respawns you at the
entrance. **A jammed lock** that never opens respawns you at the fork after 3 attempts.

## HTTP API

| Method & path | Purpose | Req |
|---|---|---|
| `POST /api/dungeon` | Start a session; returns `instanceId` + entrance view | REQ-FUNC-001 |
| `POST /api/dungeon/{id}/choice` `{"direction":"left"\|"right"}` | Fork choice | REQ-FUNC-002 |
| `POST /api/dungeon/{id}/lever-a`, `POST /api/dungeon/{id}/lever-b` | Pull a lever | REQ-FUNC-003 |
| `GET /api/dungeon/{id}` | Inspect current room/narrative/status | REQ-FUNC-007 |
| `GET /api/dungeon/{id}/stream` | SSE stream of room transitions + lock attempts (drives the web UI) | REQ-FUNC-007 |
| `GET /api/dungeon` | List all sessions | REQ-FUNC-008/012 |
| `DELETE /api/dungeon/{id}` | Cancel + forget a session | REQ-FUNC-012 |

Player moves become CloudEvents published **directly into the engine's in-process event broker**,
correlated to your instance via the `dungeoninstance` extension attribute. There is **no Kafka and
no message broker** — the game is a single self-contained process (SRS constraint C-3).

## Test it

```bash
mvn test
```

- [`DungeonWorkflowTest`](src/test/java/org/acme/dungeon/DungeonWorkflowTest.java) drives the engine
  directly (no HTTP) and asserts each REQ-FUNC acceptance criterion: start, left/right routing, the
  two-lever join (both orders, and that one lever alone does **not** open the gate), trap retry
  (exactly 3 attempts) → respawn, torch timeout → respawn without faulting, victory, and 10
  concurrent isolated instances.
- [`DungeonResourceTest`](src/test/java/org/acme/dungeon/DungeonResourceTest.java) runs the full
  PRD-CUJ-01 journey over the real REST API.

## Demo script (PRD-FEAT-10)

1. `mvn quarkus:dev`, open the Dev UI diagram on the projector.
2. Start a game; narrate the token sitting at the **fork** (an event wait).
3. Send `left`; watch it move to the **Lever Room**. Pull only lever A — nothing happens (the
   **join** is holding). Pull B — the gate opens.
4. In the **Trap Corridor**, set `dungeon.lock.mode=ALWAYS_JAM` (or just get unlucky) to show the
   **retry** loop and the **respawn** back to the fork.
5. Start a fresh game and simply wait 60s to show the **torch timeout** firing on its own.
6. Open `DungeonWorkflow.java` and point at the exact `listen` / `switch` / `all(...)` / retry loop
   that produced each behaviour.

## Containerization & Docker Compose (REQ-FUNC-009 / PRD-FEAT-08)

The game ships as **one image**. Quarkus serves the SvelteKit build at `/` and the game API under
`/api` (`quarkus.rest.path=/api`), so there is no nginx sidecar and no second container — SRS
constraint C-3 taken literally. Client-side routes like `/race` are rewritten to `index.html` by
[`SpaFallbackRoute`](src/main/java/org/acme/dungeon/SpaFallbackRoute.java).

### 1. Building the image locally

**The easy way** — one command that does every step below, picks a free host port, waits for the app
to come up and plays a full game to prove the workflow engine really runs:

```bash
scripts/run-local.sh
```

`--port 9000` pins the port, `--no-run` builds only, `--push` publishes multi-arch to GHCR,
`--with-tests` runs the suite, `--stop` tears it down, `--help` explains itself. Every run ends with
the measured image size, startup time and RSS.

**Native (GraalVM) builds** — same script, `--native`:

```bash
scripts/run-local.sh --native
```

Measured on an M-series Mac (arm64, like for like):

| | JVM | Native |
|---|---|---|
| Image | 661 MB | **253 MB** |
| Startup | 2.018s | **0.023s** |
| RSS | 176.7 MiB | **12.0 MiB** |

Native does **not** cross-compile — the binary targets the build architecture, and the build always
runs inside a Linux builder container so no local GraalVM is needed. On an ARM Mac plain `--native`
produces `linux/arm64`, which is ideal for testing the native path but **cannot run on DataRobot**.
For the deployable artifact add `--platform linux/amd64`; that forces emulation and is much slower.
Native images are tagged `native-<arch>` so they never clobber the JVM `:latest`.

<details>
<summary><strong>The manual steps</strong> (what the script automates)</summary>

The UI build is **not** committed, so it has to be produced and copied into the jar's static
resources before packaging:

```bash
npm --prefix web run build
```

```bash
mkdir -p src/main/resources/META-INF/resources && cp -R web/build/. src/main/resources/META-INF/resources/
```

```bash
mvn clean package -DskipTests -Dquarkus.container-image.build=true
```

Skipping the copy is the failure mode to watch for: you get a working API and a blank `/`, with no
error explaining why. It also needs **JDK 25** (`maven.compiler.release=25`) — an older JDK fails
with `release version 25 not supported`, which reads like a Maven bug rather than a JDK mismatch.

</details>

*(Quarkus uses the [`src/main/docker/Dockerfile.jvm`](src/main/docker/Dockerfile.jvm) recipe. Image
coordinates come from `quarkus.container-image.*` in
[`application.properties`](src/main/resources/application.properties); `build`/`push` are
deliberately **not** set there, so a plain `mvn package` never touches Docker or a registry.)*

---

### 2. Running locally with Docker Compose

```bash
docker compose up
```

* **Play the game**: [http://localhost:8080](http://localhost:8080) (also mapped to
  [:5173](http://localhost:5173) for muscle memory)
* **API**: [http://localhost:8080/api/dungeon](http://localhost:8080/api/dungeon)

There is no `build:` context — the image is assembled by Maven, not Docker
([`Dockerfile.jvm`](src/main/docker/Dockerfile.jvm) copies a prebuilt `target/quarkus-app/`). Run
section 1 first; it tags the exact name Compose references, so your local build is picked up
automatically.

> This is a **production JVM image, so it has no Quarkus Dev UI** — there is no workflow diagram at
> `/q/dev-ui` here. The projector view needs `mvn quarkus:dev` (see [Run it](#run-it)). Compose is
> for playing the game, not for demoing the diagram.

---

### 3. Deploying on the DataRobot Workload API

**[`deploy/datarobot/README.md`](deploy/datarobot/README.md) is the full operator guide** — deploy,
rolling update, verification checklist and a symptom→cause→fix table.
[`workload.yaml`](deploy/datarobot/workload.yaml) is the spec. The `dr workload` command group is
still feature-flagged client-side (without the env var the CLI just prints top-level help, which
reads like a broken install):

```bash
DATAROBOT_CLI_FEATURE_WORKLOAD=true dr workload create --spec-file deploy/datarobot/workload.yaml
```

Four platform constraints are worth knowing before you change that spec:

* **Images must include a `linux/amd64` manifest.** Apple Silicon defaults to arm64 and the container
  crash-loops with `exec format error`. Build multi-arch:
  `-Dquarkus.docker.buildx.platform=linux/amd64,linux/arm64`.
* **Containers run non-root on a read-only root filesystem.** This is why the UI is served by Quarkus
  (uid 185, no writable paths needed) instead of nginx, which cannot start under either constraint.
* **The workload is served under a URL prefix** (`/api/v2/endpoints/workloads/<id>/`) that the edge
  **strips inbound** and never re-adds to responses. The app makes itself mount-point aware at
  startup from the injected `WORKLOAD_ID`; every new URL must go through SvelteKit's `base` or it
  will silently escape the mount. See the operator guide for the mechanism.
* **`replicaCount` must stay 1.** `GameStore` is an in-memory map with no persistence, so a second
  replica would 404 any session started on the other pod. Autoscaling must stay off.

---

### 4. Publishing to GitHub Container Registry (GHCR)

First, authenticate with `ghcr.io` (ensure your GitHub CLI token has `write:packages` scope):
```bash
gh auth refresh -h github.com -s write:packages
```

```bash
gh auth token | docker login ghcr.io -u fmatar --password-stdin
```

Then build and push in one step. Do the `npm run build` + copy from section 1 first, or you will
publish an image with a stale UI. **`linux/amd64` is required** for the Workload API; keeping
`arm64` in the same manifest means the same tag still runs natively on Apple Silicon:

```bash
mvn clean package -DskipTests -Dquarkus.container-image.build=true -Dquarkus.container-image.push=true -Dquarkus.docker.buildx.platform=linux/amd64,linux/arm64
```

> The old `ghcr.io/fmatar/dungeon-flow-ui` image is no longer used — the UI ships inside the backend
> image now. Nothing references it; delete it from GHCR when convenient.

## Design decisions

- **Engine:** Quarkus Flow `0.15.1`, pinned to Quarkus platform `3.38.1`, Java 25. Pinning the
  platform resolves **PRD Open Question #2 / SRS constraint C-2**.
- **Event routing (PRD Open Question #1):** Crawl correlates moves to sessions on the **raw workflow
  instance id** (simplest for Crawl), via the `dungeoninstance` CloudEvent extension. Moving to a
  `playerid` correlation for the Run phase is a change in `GameEvents` + `DungeonResource` only.
- **No broker (C-3):** moves are published into the engine's default in-process `InMemoryEvents`
  broker through `WorkflowApplication.eventPublishers()`. No messaging extension is on the classpath.
- **Inspection read-model:** `GameStore` mirrors the current room per instance as the workflow
  enters it. This is projection/observation, not game logic — routing/joins/retries/timeouts all
  stay in the workflow definition (C-1).
- **Completion vs. inspection:** `GET /api/dungeon/{id}` returns `200` with the victory view once an
  instance completes, so the player actually sees the win (REQ-FUNC-006), and `404` only when the id
  is unknown or cleaned up. Strict REQ-FUNC-007 ("`404` after completion") is a one-line change in
  `DungeonResource.inspect`.
- **One container, UI included (C-3):** Quarkus serves the SvelteKit build from
  `META-INF/resources` at `/` and moves the API under `/api` (`quarkus.rest.path`), so the two halves
  line up with no reverse proxy. An nginx sidecar was tried first and is *impossible* on the Workload
  API, which runs containers non-root on a read-only filesystem. The UI build is not committed, so
  `npm run build` + copy is a prerequisite of `mvn package` — [`scripts/run-local.sh`](scripts/run-local.sh)
  exists so that step cannot be forgotten.
- **Mount-point awareness:** the app must work both at `/` and under a deployment prefix. It is built
  with a sentinel `kit.paths.base` that
  [`SpaFallbackRoute`](src/main/java/org/acme/dungeon/SpaFallbackRoute.java) substitutes at startup
  from `WORKLOAD_ID`; absent that env var the substitution collapses to `""` and everything is
  root-absolute, so local and deployed share one code path. Fixing asset URLs alone is not enough —
  SvelteKit compiles `base` into the client bundle, and a stale `''` makes its router reject the very
  first URL. Details in [`deploy/datarobot/README.md`](deploy/datarobot/README.md).

### Verify-first checklist (things to sanity-check on first `mvn test`)

These use Quarkus Flow features that are correct per the docs/source but worth confirming on your
platform build:

1. **Torch timeout** — the fork `listen` has a task `timeout` wrapped in a `try/catch` on the CNCF
   `…/errors/timeout` type; on timeout it jumps to `Entrance` (respawn). If the exact error type or
   cross-scope jump needs adjustment, it's isolated to the `ForkWait` block. (`torch_timeout_*` tests
   cover it.)
2. **Choice payload flow** — `WhichWay` switches on the output of the `try`-wrapped choice `listen`.
   If the wrapper changes the output shape, add `.outputAs(...)` to the listen. (`choice_*` tests
   cover it.)
```
