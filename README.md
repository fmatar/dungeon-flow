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

Prerequisites: **Java 17+** and Maven (or the `mvnw` wrapper). First run downloads Quarkus 3.33.3 +
quarkus-flow 0.15.1 from Maven Central.

```bash
mvn quarkus:dev        # hot-reload dev mode on http://localhost:8080
```

Press `d` in the dev console (or open http://localhost:8080/q/dev-ui) → **Quarkus Flow → Workflows**
to watch the diagram while you play — this is the projector view for demos (PRD-FEAT-09).

## Play it (curl)

```bash
# 1) Start a game — returns your instance id and the entrance narrative
curl -s -XPOST http://localhost:8080/dungeon | jq
#   { "instanceId": "01J…", "entrance": { "room": "ENTRANCE", … } }
ID=<paste instanceId>

# 2) At the fork, choose a path (don't dawdle — the torch is burning)
curl -s -XPOST http://localhost:8080/dungeon/$ID/choice \
     -H 'Content-Type: application/json' -d '{"direction":"left"}'

# 3a) LEFT — the Lever Room: pull BOTH levers, in any order (the join)
curl -s -XPOST http://localhost:8080/dungeon/$ID/lever-a
curl -s -XPOST http://localhost:8080/dungeon/$ID/lever-b

# 3b) …then the Trap Corridor picks the lock automatically (retries on a jam)

# 4) Check where you are at any time
curl -s http://localhost:8080/dungeon/$ID | jq

# List every active session (facilitator race view) / clean one up
curl -s http://localhost:8080/dungeon | jq
curl -s -XDELETE http://localhost:8080/dungeon/$ID
```

**Choosing `right`** skips the levers and goes straight to the Trap Corridor.
**Idling** at the fork past the torch timeout (60s in prod, 2s in tests) respawns you at the
entrance. **A jammed lock** that never opens respawns you at the fork after 3 attempts.

## HTTP API

| Method & path | Purpose | Req |
|---|---|---|
| `POST /dungeon` | Start a session; returns `instanceId` + entrance view | REQ-FUNC-001 |
| `POST /dungeon/{id}/choice` `{"direction":"left"\|"right"}` | Fork choice | REQ-FUNC-002 |
| `POST /dungeon/{id}/lever-a`, `POST /dungeon/{id}/lever-b` | Pull a lever | REQ-FUNC-003 |
| `GET /dungeon/{id}` | Inspect current room/narrative/status | REQ-FUNC-007 |
| `GET /dungeon` | List all sessions | REQ-FUNC-008/012 |
| `DELETE /dungeon/{id}` | Cancel + forget a session | REQ-FUNC-012 |

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

This project is fully containerized and configured for both local development and remote registry publishing.

### 1. Building Container Images Locally

You can build local versions of both images directly from source without downloading anything:

* **Backend (Quarkus JVM)**: Build via the Quarkus Maven extension using the local Docker daemon:
  ```bash
  # Packages the jar and builds the local image fady/dungeon-flow:1.0.0-SNAPSHOT
  mvn clean package -DskipTests -Dquarkus.container-image.build=true
  ```
  *(Under the hood, Quarkus will use the [`src/main/docker/Dockerfile.jvm`](file:///Users/fady/workspace/datarobot/dungeon-flow/src/main/docker/Dockerfile.jvm) recipe).*

* **Frontend (SvelteKit + Nginx)**: Build the multi-stage static asset + Nginx server image:
  ```bash
  # Build the UI image locally
  docker build -t dungeon-flow-ui:latest -f web/Dockerfile web/
  ```

---

### 2. Running Locally with Docker Compose (No Downloading)

The [`docker-compose.yaml`](file:///Users/fady/workspace/datarobot/dungeon-flow/docker-compose.yaml) is configured with local build contexts. To build and spin up the complete stack locally using your local source directories instead of downloading remote registry images:

```bash
# Rebuilds and launches both backend & frontend locally
docker compose up --build
```

* **Play Game (UI)**: [http://localhost:5173](http://localhost:5173) or [http://localhost:80](http://localhost:80)
* **Backend API / Dev UI**: [http://localhost:8080](http://localhost:8080)

---

### 3. Publishing to GitHub Container Registry (GHCR)

First, authenticate with `ghcr.io` (ensure your GitHub CLI token has `write:packages` scope):
```bash
# Refresh token with packaging scopes if needed
gh auth refresh -h github.com -s write:packages

# Authenticate Docker daemon to GHCR
gh auth token | docker login ghcr.io -u fmatar --password-stdin
```

* **Publish the Backend**: Build and push in one step directly through Maven properties:
  ```bash
  mvn clean package -DskipTests -Pnative \
    -Dquarkus.container-image.build=true \
    -Dquarkus.container-image.push=true \
    -Dquarkus.container-image.registry=ghcr.io \
    -Dquarkus.container-image.group=fmatar \
    -Dquarkus.container-image.name=dungeon-flow \
    -Dquarkus.docker.buildx.platform=linux/amd64 \
    -Dquarkus.container-image.tag=latest
  ```

* **Publish the Frontend UI**: Tag and push the UI image:
  ```bash
  docker tag dungeon-flow-ui:latest ghcr.io/fmatar/dungeon-flow-ui:latest
  docker push ghcr.io/fmatar/dungeon-flow-ui:latest
  ```

## Design decisions

- **Engine:** Quarkus Flow `0.15.1`, pinned to Quarkus platform `3.33.3`, Java 17. Pinning the
  platform resolves **PRD Open Question #2 / SRS constraint C-2**.
- **Event routing (PRD Open Question #1):** Crawl correlates moves to sessions on the **raw workflow
  instance id** (simplest for Crawl), via the `dungeoninstance` CloudEvent extension. Moving to a
  `playerid` correlation for the Run phase is a change in `GameEvents` + `DungeonResource` only.
- **No broker (C-3):** moves are published into the engine's default in-process `InMemoryEvents`
  broker through `WorkflowApplication.eventPublishers()`. No messaging extension is on the classpath.
- **Inspection read-model:** `GameStore` mirrors the current room per instance as the workflow
  enters it. This is projection/observation, not game logic — routing/joins/retries/timeouts all
  stay in the workflow definition (C-1).
- **Completion vs. inspection:** `GET /dungeon/{id}` returns `200` with the victory view once an
  instance completes, so the player actually sees the win (REQ-FUNC-006), and `404` only when the id
  is unknown or cleaned up. Strict REQ-FUNC-007 ("`404` after completion") is a one-line change in
  `DungeonResource.inspect`.

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
