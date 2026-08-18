# Dungeon Flow

**A text-adventure dungeon whose map *is* a running workflow.** Rooms are workflow states, doors are
transitions, and every player move is a CloudEvent. Built on
[Quarkus Flow](https://docs.quarkiverse.io/quarkus-flow/dev/index.html) — the
[CNCF Serverless Workflow](https://serverlessworkflow.io/) engine embedded in Quarkus.

Workflow-engine concepts are abstract and famously hard to teach. Order-processing demos don't land.
So every primitive here is a **game mechanic you play**: an event wait is a fork in a corridor, a
multi-event join is two levers, a bounded retry is a jammed lock, an event timeout is a burning
torch. You watch the engine drive the game, then open one file and see exactly which construct
produced what you just felt.

```
 Entrance ─▶ Fork ─(left)─▶ ⛬ Riddle ─▶ Lever Room ─(A & B)─▶ Trap Corridor ─(pick)─▶ Treasure ✦END
              │           └─(STR ≥ 12)─▶ 🛡 bash the gate ─▶ ┘
              │  \─(right)──▶ ⛬ Riddle ────────────────────▶ Trap Corridor
              │  \─(unknown, or torch times out)───────────▶ respawn
              ▲                    │                                  │
              └── gate gives up ───┘── respawn on retry exhaustion ───┘
```

| Room | Workflow primitive | What you experience |
|---|---|---|
| **Fork** | `listen` (event wait) + `switch` (data routing) + `timeout` | The game pauses, waiting for *you*. Dawdle and your torch dies. |
| **Riddle gate** | `listen` + `switch` + bounded **retry** + **compensation** | A door asks a riddle. Wrong answers tell you only *how warm* you were; three and it turns you back. A warrior skips the left one entirely — see [player classes](#player-classes). |
| **Lever Room** | `listen … all(A, B)` — multi-event **join** | Pull one lever: nothing. Pull both, any order: the gate opens. |
| **Trap Corridor** | bounded **retry** + **compensation** | The lock jams at random. Three failures and a trap flings you back. |
| **Treasure Room** | terminal state (`end`) | `victory: true`, instance `COMPLETED`. |

The entire map lives in one file —
**[`DungeonWorkflow.java`](src/main/java/org/acme/dungeon/DungeonWorkflow.java)**. The Java beans it
calls only *observe* ([`GameStore`](src/main/java/org/acme/dungeon/GameStore.java)) or *roll dice*
([`LockService`](src/main/java/org/acme/dungeon/LockService.java)). **They never decide where the
player goes next** — that is the project's central constraint, and it's what makes the workflow
definition worth reading.

---

## Contents

- [Quick start](#quick-start) — pick your path in 30 seconds
- [For developers](#for-developers) — dev mode, hot reload, tests
- [Playing it](#playing-it) — browser, curl, and [player classes](#player-classes)
- [HTTP API](#http-api)
- [For operators](#for-operators) — build, containerize, deploy
- [Publishing to your own registry](#publishing-to-your-own-registry) — fork-friendly
- [Running a demo](#running-a-demo)
- [Architecture and design decisions](#architecture-and-design-decisions)
- [Troubleshooting](#troubleshooting)
- [Project layout](#project-layout)
- **[The hands-on lab](docs/lab/LAB.md)** — learn all of the above by building it

**Module documentation:** [`web/`](web/README.md) (the SvelteKit UI) ·
[`deploy/`](deploy/README.md) (deployment) ·
[`deploy/datarobot/`](deploy/datarobot/README.md) (DataRobot Workload API operator guide)

> 🧪 **Want to learn this by building it?** [`docs/lab/LAB.md`](docs/lab/LAB.md) is a 90-minute
> hands-on lab that goes from a fresh clone to a deployed container — dev mode, packaging the UI into
> the jar, JVM and native images, publishing to your own registry, and deploying. It also covers the
> five things that broke along the way. Designed to be run with an audience
> ([facilitator guide](docs/lab/FACILITATOR.md), [slides](docs/lab/slides.md)), but it works alone.

---

## Quick start

Three ways in, depending on what you want.

### I want to see it run, now

```bash
./scripts/dungeon.sh
```

One command: builds the UI, builds the container, picks a free port, waits for health, and **plays a
full game to prove the workflow engine actually runs**. Ends with measured image size, startup time
and memory. Then open the URL it prints (usually <http://localhost:8080>).

Needs Docker, Node 20+, Maven and **JDK 25**. The script checks all of that up front and tells you
exactly what's missing.

### I want to develop on it

Two processes, both hot-reloading — see [For developers](#for-developers):

```bash
mvn quarkus:dev
```

```bash
pnpm --dir web dev
```

### I want to deploy it

See [For operators](#for-operators), then [`deploy/README.md`](deploy/README.md).

---

## For developers

### Prerequisites

On macOS, [Homebrew](https://brew.sh) covers everything. If you don't have it:

```bash
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
```

Then, in one line:

```bash
brew install --cask graalvm-jdk@25 docker-desktop && brew install maven node pnpm
```

| Tool | Install | Why this one |
|---|---|---|
| **GraalVM 25** | `brew install --cask graalvm-jdk@25` | The project compiles with `maven.compiler.release=25`, and GraalVM is a full JDK — so it runs the JVM build *and* unlocks native builds. Any JDK 25 works if you don't care about native. |
| **Docker Desktop** | `brew install --cask docker-desktop` | Container builds and Compose. Not needed for dev mode. |
| **Maven** | `brew install maven` | 3.9+. |
| **Node 20+** | `brew install node` | Builds the UI. **Required even for a backend-only container build**, because the UI is compiled into the jar. |
| **pnpm** | `brew install pnpm` | Optional — `npm` ships with Node and works identically. See [the note on package managers](#a-note-on-package-managers). |
| **jq** | `brew install jq` | Optional, only for the pretty-printed [curl examples](#with-curl). |

**Point `JAVA_HOME` at GraalVM.** Homebrew casks install JDKs where macOS can find them, so:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 25)
```

Add that to your `~/.zshrc` to make it stick. You can skip it if you like:
[`scripts/dungeon.sh`](scripts/dungeon.sh) locates a JDK 25 itself (checking `JAVA_HOME`,
`/usr/libexec/java_home`, then SDKMAN) and tells you what it picked.

Verify the toolchain:

```bash
java -version && mvn -v && node -v && docker info | head -1
```

> **On GraalVM and native builds.** Having GraalVM locally means `mvn package -Dnative` works without
> a builder container — but on macOS that produces a **macOS** binary, which is useless in a Linux
> container. That's why [`scripts/dungeon.sh --native`](#jvm-or-native) always compiles inside a Linux
> builder image. Local GraalVM is still worth having: it's your JDK, and it makes
> `quarkus:dev`-adjacent native experiments possible.

> `brew install --cask graalvm-jdk@25` installs **Oracle GraalVM** (free for development and
> production under Oracle's
> [GFTC terms](https://www.oracle.com/downloads/licenses/graal-free-license.html)). GraalVM's own tap
> is an alternative (`brew tap graalvm/tap && brew install --cask graalvm-jdk25`), and any JDK 25 is
> fine if you'd rather let the script's container builder handle native.

### Your IDE

Any editor works. The repo ships IntelliJ IDEA configuration in [`.idea/`](.idea/) —
**Checkstyle-IDEA** and **google-java-format** settings — so if you use IntelliJ you inherit the
project's formatting with no setup:

```bash
brew install --cask intellij-idea-ce     # or: brew install --cask visual-studio-code
```

For VS Code, the [Extension Pack for
Java](https://marketplace.visualstudio.com/items?itemName=vscjava.vscode-java-pack) plus
[Svelte for VS Code](https://marketplace.visualstudio.com/items?itemName=svelte.svelte-vscode)
covers both halves of the codebase.

Nothing in the build depends on an IDE — `mvn`, `pnpm` and `scripts/dungeon.sh` are the whole story.

### Dev mode: two processes, both hot-reloading

This is the setup to develop in. The backend and the UI reload independently, and you get the
**workflow diagram** — which the container images do not have.

**Terminal 1 — the Quarkus backend:**

```bash
mvn quarkus:dev
```

Serves the API on <http://localhost:8080/api>. Java changes recompile on the next request; edits to
[`DungeonWorkflow.java`](src/main/java/org/acme/dungeon/DungeonWorkflow.java) take effect on the next
game you start.

**Terminal 2 — the SvelteKit UI:**

```bash
pnpm --dir web install && pnpm --dir web dev
```

Open <http://localhost:5173>. Vite proxies `/api/*` to the backend on `:8080`, so the browser stays
same-origin — no CORS, and SSE streams work. The proxy deliberately does **not** strip the `/api`
prefix, because the backend serves its endpoints under `/api` itself.

**The workflow diagram** (the reason to use dev mode): press `d` in the Quarkus dev console, or open
<http://localhost:8080/q/dev-ui> → **Quarkus Flow → Workflows**. Put it on a projector and watch the
token move as you play. This is the demo centrepiece and it exists **only in dev mode** — the
production images have no Dev UI.

### Tests

```bash
mvn test
```

16 tests, and they are the best description of the intended behaviour:

- **[`DungeonWorkflowTest`](src/test/java/org/acme/dungeon/DungeonWorkflowTest.java)** drives the
  engine directly with no HTTP, asserting each acceptance criterion: left/right routing; the
  two-lever join in **both orders** *and* that one lever alone does **not** open the gate; the trap
  retrying **exactly** three times before respawning; the torch timeout firing without faulting the
  instance; victory; and 10 concurrent instances staying isolated.
- **[`DungeonResourceTest`](src/test/java/org/acme/dungeon/DungeonResourceTest.java)** replays the
  whole player journey over the real REST API.

The test profile makes the game fast and deterministic — the torch burns out in 2s instead of 60s,
and the lock can be forced to always succeed or always jam.

### A note on package managers

`package-lock.json` is the committed lockfile and the container build uses `npm ci`. **`pnpm` works
fine for local development** (`pnpm --dir web dev`) and is what several of us use day to day.

`pnpm-lock.yaml` is gitignored on purpose: carrying two lockfiles for one dependency set caused real
drift here. If you'd rather standardize on pnpm, remove that line from [`.gitignore`](.gitignore),
commit the pnpm lockfile, delete `package-lock.json`, and switch the install step in
[`scripts/dungeon.sh`](scripts/dungeon.sh) — it's a deliberate one-way door, not an accident.

---

## Playing it

### In the browser

The UI's job isn't "click instead of curl" — it makes the invisible primitives **visible**: a
draining torch ring for the timeout, two lit levers for the join, an animated retry counter for the
trap, and a live spotlight naming the construct currently firing. Details in
[`web/README.md`](web/README.md).

- **`/`** — play, with the teaching panel beside it
- **`/race`** — facilitator view: every active instance racing across the five rooms

### With curl

```bash
# 1) Start a game — returns your instance id, the entrance narrative, and your class/stats.
#    Add ?class=warrior|rogue|mage to change how the dungeon treats you (see Player classes).
curl -s -XPOST http://localhost:8080/api/dungeon | jq
ID=<paste instanceId>

# 2) At the fork, choose a path (don't dawdle — the torch is burning)
curl -s -XPOST http://localhost:8080/api/dungeon/$ID/choice \
     -H 'Content-Type: application/json' -d '{"direction":"left"}'

# 3) A riddle now gates the door. Inspect to read it, then answer it.
curl -s http://localhost:8080/api/dungeon/$ID | jq '.riddle.prompt, .riddle.maxAttempts'
curl -s -XPOST http://localhost:8080/api/dungeon/$ID/riddle \
     -H 'Content-Type: application/json' -d '{"answer":"an echo"}'
#   Wrong? Inspect again: .riddle.proximity is how warm you were, and a hint appears.

# 4) Through the gate — the Lever Room. Pull ONE lever and inspect: nothing has moved (the join holds)
curl -s -XPOST http://localhost:8080/api/dungeon/$ID/lever-a
curl -s http://localhost:8080/api/dungeon/$ID | jq '.view.room, .status'

# ...now the second lever. The gate opens and the Trap Corridor picks the lock by itself
curl -s -XPOST http://localhost:8080/api/dungeon/$ID/lever-b

# 5) Where am I?
curl -s http://localhost:8080/api/dungeon/$ID | jq

# Watch transitions live (SSE), including server-side retries and respawns
curl -N http://localhost:8080/api/dungeon/$ID/stream

# Every active session (facilitator view) / clean one up
curl -s http://localhost:8080/api/dungeon | jq
curl -s -XDELETE http://localhost:8080/api/dungeon/$ID
```

Things worth trying deliberately: choose **`right`** to skip the levers; **idle** at the fork past
the torch timeout to be respawned; send an **unknown direction** to watch the `switch` fall through
to its default.

### Tuning the game

In [`application.properties`](src/main/resources/application.properties) — game *balance*, not game
*rules*, which live in the workflow:

| Property | Default | Effect |
|---|---|---|
| `dungeon.lock.success-probability` | `0.5` | Per-attempt chance the lock opens |
| `dungeon.lock.mode` | `RANDOM` | `RANDOM` \| `ALWAYS_SUCCEED` \| `ALWAYS_JAM` — force outcomes for demos |
| `dungeon.trap.max-attempts` | `3` | Retries before the respawn compensation |
| `dungeon.riddle.max-attempts` | `3` | Riddle attempts before the gate turns you back |
| `dungeon.fork.torch-timeout` | `PT60S` | Idle time at the fork before respawn (ISO-8601) |

### Player classes

Pick a class when you start, and your stats change **which primitives you actually meet**. This is
data-driven routing: the workflow switches on the stats, so a class is not a cosmetic label.

```bash
curl -s -XPOST 'http://localhost:8080/api/dungeon?class=warrior' | jq '.playerClass, .stats'
```

| Class | STR | DEX | INT | What its stats unlock |
|---|---|---|---|---|
| `warrior` | **18** | 10 | 8 | **Bashes the left gate open**, bypassing that riddle entirely |
| `rogue` | 10 | **18** | 8 | **Picks the Trap Corridor lock first try**, every time |
| `mage` | 8 | 10 | **18** | **Mage Insight** — a near-miss answer (proximity ≥ 0.5) counts as solved |
| `balanced` *(default)* | 10 | 10 | 10 | Nothing. Every gate and lock behaves normally |

Each bypass triggers on **its stat ≥ 12**, which no class reaches by accident — the threshold is
effectively "this is your speciality". Omit `?class=` and you get `balanced`.

> **This matters when you demo or test.** As a **warrior you never see the riddle input** on the left
> path, and as a **rogue you never see the trap retry loop**. If you are showing the riddle gate or the
> thermometer, start as `mage` or `balanced` — and note that a mage solves near-misses, so use
> `balanced` to show a gate holding firm. The right-hand door always poses a riddle regardless of class.

---

## HTTP API

All endpoints are under `/api` (`quarkus.rest.path=/api`), leaving `/` free for the UI.

| Method & path | Purpose |
|---|---|
| `POST /api/dungeon?class=warrior\|rogue\|mage` | Start a session → `instanceId`, entrance view, torch timeout, `playerClass` + `stats`. Omit `class` for `balanced` |
| `POST /api/dungeon/{id}/choice` `{"direction":"left"\|"right"}` | Fork choice |
| `POST /api/dungeon/{id}/riddle` `{"answer":"…"}` | Answer the riddle gating a door. `409` if no gate is posed |
| `POST /api/dungeon/{id}/lever-a` · `POST /api/dungeon/{id}/lever-b` | Pull a lever |
| `GET /api/dungeon/{id}` | Inspect current room, narrative, status, the posed `riddle` (if any), `playerClass` + `stats` |
| `GET /api/dungeon/{id}/stream` | **SSE** stream of room transitions + lock attempts |
| `GET /api/dungeon` | List all sessions |
| `DELETE /api/dungeon/{id}` | Cancel and forget a session |

Player moves become CloudEvents published **directly into the engine's in-process broker**,
correlated to your instance by the `dungeoninstance` extension attribute. There is **no Kafka and no
message broker** — the whole game is one self-contained process.

---

## For operators

The game ships as **one image**: Quarkus serves the SvelteKit build at `/` and the API under `/api`.
No nginx sidecar, no second container.

> **The one thing to know before building by hand:** the UI build is *not* committed. It must be
> produced and copied into `src/main/resources/META-INF/resources/` before Maven packages the jar.
> Forget it and you get a working API behind a **blank page, with no error explaining why**.
> [`scripts/dungeon.sh`](scripts/dungeon.sh) exists so that cannot happen — prefer it over manual
> steps.

### `scripts/dungeon.sh`

One entry point for every container workflow. It preflights your toolchain, picks a JDK, builds the
UI, stages it, builds the image, runs it, waits for health, plays a real game, and reports measured
numbers.

| Flag | Effect |
|---|---|
| *(none)* | Build the JVM image and run it on a free port |
| `--native` | Build a **GraalVM native** image instead (see below) |
| `--platform linux/amd64` | Target architecture for `--native` |
| `--push` | Also publish to your registry |
| `--no-run` | Build only |
| `--with-tests` | Run the Maven suite as part of the build |
| `--port 9000` | Pin the host port instead of auto-picking |
| `--stop` | Tear down a running stack |
| `--help` | Print usage |

Run it however you like — `./scripts/dungeon.sh`, `bash scripts/dungeon.sh`, or `sh
scripts/dungeon.sh`; it re-execs itself under bash when needed.

<details>
<summary><strong>The manual equivalent</strong>, if you need to drive the steps yourself</summary>

```bash
pnpm --dir web build
```

```bash
mkdir -p src/main/resources/META-INF/resources && cp -R web/build/. src/main/resources/META-INF/resources/
```

```bash
mvn clean package -DskipTests -Dquarkus.container-image.build=true
```

Quarkus uses the [`Dockerfile.jvm`](src/main/docker/Dockerfile.jvm) recipe. Image coordinates come
from `quarkus.container-image.*` in
[`application.properties`](src/main/resources/application.properties). `build` and `push` are
deliberately **not** set there, so a plain `mvn package` never touches Docker or a registry.

</details>

### JVM or native?

```bash
./scripts/dungeon.sh --native
```

Measured on an M-series Mac, same architecture both sides:

| | JVM | Native | |
|---|---|---|---|
| Image | 661 MB | **253 MB** | 2.6× smaller |
| Startup | 2.018s | **0.023s** | ~88× faster |
| Memory (RSS) | 176.7 MiB | **12.0 MiB** | ~15× smaller |

Native matters here less for runtime than for the *demo*: "the entire dungeon, engine included, is a
container that boots in 23ms and idles on 12MB" is itself a teaching point.

Three things to know:

1. **Native does not cross-compile.** The binary targets the architecture it was built on. The build
   always runs inside a Linux builder container, so no local GraalVM is needed — but on an ARM Mac,
   plain `--native` yields `linux/arm64`, which is perfect for testing locally and **will not run on
   an amd64 platform**.
2. **For a deployable amd64 artifact**, add `--platform linux/amd64`. That emulates the builder and
   is much slower — and emulated `native-image` is genuinely fragile. If it dies reading the runner
   jar, build on an amd64 host (CI) instead.
3. **The binary's architecture and the image's manifest are set separately.** Getting the first right
   and the second wrong produces an image that lies about itself: manifest `arm64`, entrypoint
   `x86-64` ELF. Neither image size nor a local run reveals it. The script builds the image with
   `buildx --platform` and then verifies all three facts agree — manifest arch, native layout, and
   the real ELF arch — refusing to push on a mismatch.

Native images are tagged `native-<arch>`, never `:latest`, and the script **hard-refuses** to build a
native image onto `:latest` — that tag is the multi-arch JVM image a deployment may already be
running, and native images are single-architecture.

### Docker Compose

```bash
docker compose up
```

Then <http://localhost:8080>. Two overrides:

```bash
DUNGEON_HOST_PORT=8090 docker compose up      # 8080 busy (often mvn quarkus:dev)
DUNGEON_IMAGE=ghcr.io/<you>/dungeon-flow:native-amd64 docker compose up
```

There is no `build:` context — the image is assembled by Maven, not Docker, so run
`./scripts/dungeon.sh --no-run` first. Compose is for *playing* the game; it's a production image, so
**there is no workflow diagram** at `/q/dev-ui`. That needs `mvn quarkus:dev`.

### Deploying

See **[`deploy/README.md`](deploy/README.md)** for the deployment overview, and
**[`deploy/datarobot/README.md`](deploy/datarobot/README.md)** for the DataRobot Workload API
operator guide — rolling updates, a verification checklist, and a symptom → cause → fix table built
from every failure this project actually hit.

---

## Publishing to your own registry

Everything below assumes GitHub Container Registry (`ghcr.io`); any OCI registry works the same way.

### 1. Point the project at your namespace

Image coordinates live in **one** place. Edit
[`src/main/resources/application.properties`](src/main/resources/application.properties):

```properties
quarkus.container-image.registry=ghcr.io
quarkus.container-image.group=<your-github-username>
quarkus.container-image.name=dungeon-flow
quarkus.container-image.tag=latest
```

`scripts/dungeon.sh` reads these, and fails with instructions if the registry or group is unset — so
a fork can never silently publish under someone else's namespace.

Then update the deployment manifests, which need literal image references:

| File | Field |
|---|---|
| [`deploy/datarobot/workload.yaml`](deploy/datarobot/workload.yaml) | `imageUri` |
| [`deploy/datarobot/workload-native.yaml`](deploy/datarobot/workload-native.yaml) | `imageUri` |
| [`docker-compose.yaml`](docker-compose.yaml) | the `DUNGEON_IMAGE` default |

### 2. Authenticate to GHCR

You need a token with the `write:packages` scope. With the
[GitHub CLI](https://cli.github.com/):

```bash
gh auth refresh -h github.com -s write:packages
```

```bash
gh auth token | docker login ghcr.io -u <your-github-username> --password-stdin
```

Or with a [classic personal access token](https://github.com/settings/tokens) (`write:packages`):

```bash
echo $CR_PAT | docker login ghcr.io -u <your-github-username> --password-stdin
```

### 3. Build and push

```bash
./scripts/dungeon.sh --push
```

Publishes a **multi-arch** (`linux/amd64,linux/arm64`) JVM image. `amd64` is what most platforms
need; keeping `arm64` in the same manifest means the same tag still runs natively on Apple Silicon.

For native:

```bash
./scripts/dungeon.sh --native --platform linux/amd64 --push
```

### 4. Make the package public

New GHCR packages are **private by default**, and a private image is the most common reason a
deployment sits in `ImagePullBackOff` — the platform can't pull it and the error rarely says so
plainly.

1. Go to `https://github.com/users/<your-username>/packages/container/dungeon-flow/settings`
   (or your repo → **Packages** → the package → **Package settings**)
2. **Danger Zone** → **Change visibility** → **Public**

Verify anonymously — this is the check that matters, because your own `docker pull` succeeds either
way thanks to your login:

```bash
docker manifest inspect ghcr.io/<your-username>/dungeon-flow:latest
```

While you're there, **Connect repository** links the package to your repo so it inherits the README
and license.

References: [Working with the Container
registry](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-container-registry)
· [Configuring package
visibility](https://docs.github.com/en/packages/learn-github-packages/configuring-a-packages-access-control-and-visibility)

If you'd rather keep it private, the deployment platform needs image-pull credentials — see
[`deploy/datarobot/README.md`](deploy/datarobot/README.md), which covers that constraint for the
Workload API specifically.

---

## Running a demo

The script that has worked live, in order:

1. `mvn quarkus:dev`, and put the **Dev UI workflow diagram** on the projector.
2. Start a game. Narrate the token *sitting still* at the fork — the engine is waiting for a human.
   That pause is an event wait, and it costs nothing while it waits.
3. Send `left`. Pull **only lever A** and let the silence sit. Nothing happens. That's a join
   holding. Pull B — the gate opens. This is the moment audiences remember.
4. Set `dungeon.lock.mode=ALWAYS_JAM` and enter the Trap Corridor: three attempts, then the
   compensation throws you back to the fork.
5. Start a fresh game and just **wait 60 seconds**. The torch times out and the engine respawns you
   with nobody touching anything.
6. Open [`DungeonWorkflow.java`](src/main/java/org/acme/dungeon/DungeonWorkflow.java) and point at
   the exact `listen`, `switch`, `all(...)` and retry loop that produced each thing they just saw.

For a room full of people playing at once, open `/race` on the projector instead.

> **Want them building rather than watching?** [`docs/lab/LAB.md`](docs/lab/LAB.md) turns this into a
> 90-minute hands-on lab where the audience goes from clone to deployed container themselves, with a
> [facilitator guide](docs/lab/FACILITATOR.md) and [slides](docs/lab/slides.md).

---

## Architecture and design decisions

```
                        ┌──────────────────────────────────────────┐
  browser ──────────────▶  Quarkus (one container, one process)    │
  curl    ──────────────▶                                          │
                        │  /            SvelteKit SPA (static)     │
                        │  /api/*       REST + SSE                 │
                        │                    │                     │
                        │                    ▼                     │
                        │  CloudEvents ─▶ in-process broker        │
                        │                    │                     │
                        │                    ▼                     │
                        │  Quarkus Flow engine ── DungeonWorkflow  │
                        │        │                                 │
                        │        ▼                                 │
                        │  GameStore (in-memory read-model) ─▶ SSE │
                        └──────────────────────────────────────────┘
```

- **Engine:** Quarkus Flow `0.15.1` on Quarkus platform `3.38.2`, Java 25. The platform is pinned
  deliberately.
- **No game logic in Java.** Routing, joins, retries and timeouts live *only* in the workflow
  definition. `GameStore` projects state for display; `LockService` answers "did the pick succeed?".
  Neither decides where the player goes. This is what makes the demo honest.
- **No broker.** Moves are published into the engine's default in-process `InMemoryEvents` publisher.
  No messaging extension is on the classpath, so the game is one self-contained process.
- **Correlation** is on the raw workflow instance id via the `dungeoninstance` CloudEvent extension —
  the simplest thing that works. Moving to a player-id correlation would touch `GameEvents` and
  `DungeonResource` only.
- **In-memory state, so exactly one replica.** `GameStore` is a `ConcurrentHashMap` with no
  persistence: a second replica would 404 any session started on the other one. This is a property of
  the app, not of the JVM — native changes nothing here.
- **One container, UI included.** The API moved under `/api` so the two halves line up with no
  reverse proxy. An nginx sidecar was tried first and is *impossible* on the target platform, which
  runs containers non-root on a read-only filesystem — nginx cannot write its config or cache.
- **Mount-point awareness.** The app must work at `/` *and* under a deployment path prefix. It's
  built with a sentinel `kit.paths.base` that
  [`SpaFallbackRoute`](src/main/java/org/acme/dungeon/SpaFallbackRoute.java) substitutes at startup;
  with no prefix the substitution collapses to `""`, so local and deployed share one code path.
  Fixing asset URLs alone is *not* enough — SvelteKit compiles `base` into the client bundle, and a
  stale `''` makes its router reject the very first URL and render nothing. Full explanation in
  [`deploy/datarobot/README.md`](deploy/datarobot/README.md).
- **Riddle gates reuse the trap's shape.** A gate is a `listen` for the answer, a `switch` on the
  graded result, and a compensation when attempts run out — deliberately the same primitives as the
  Trap Corridor's lock, so an audience sees one shape guard two unrelated fictions.
  [`RiddleService`](src/main/java/org/acme/dungeon/RiddleService.java) only scores *how close* an
  answer was; it never decides whether the door opens. Proximity blends edit distance with token
  overlap so a right idea always reads warm, and drives the animated thermometer in the UI.
- **Classes route in the workflow, not in Java.** A stat check produces a *value*
  (`"warrior"` / `"normal"`) and a `switchCase` routes on it, so the class-conditional path is visible
  in the diagram rather than hidden in an `if`. The threshold lives in one place per bypass.
- **Completion is visible.** `GET /api/dungeon/{id}` returns `200` with the victory view after an
  instance completes, so the player actually sees the win, and `404` only for an unknown id.

Product and requirements background lives in [`specs/`](specs/) (PRD and SRS).

---

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `release version 25 not supported` | JDK older than 25 | Install JDK 25; `./scripts/dungeon.sh` picks a suitable one automatically |
| Blank page, API works | UI never copied into `META-INF/resources` | Use `./scripts/dungeon.sh`, or do the copy step |
| `syntax error near unexpected token` running a script | Old copy invoked with `sh` | Fixed — the script re-execs under bash; `git pull` |
| `docker compose up` aborts, `address already in use` | `mvn quarkus:dev` holds 8080 | `DUNGEON_HOST_PORT=8090 docker compose up` |
| `curl localhost:5173` hits the wrong process | macOS resolves `localhost` to `::1` first; a Vite dev server may hold the IPv6 side | Use `127.0.0.1` explicitly |
| No workflow diagram at `/q/dev-ui` | Production image has no Dev UI | `mvn quarkus:dev` |
| `exec format error` in a container | arm64 image on an amd64 host | Build multi-arch (JVM) or `--platform linux/amd64` (native) |
| Deployment stuck in `ImagePullBackOff` | GHCR package is private | [Make it public](#4-make-the-package-public) or configure pull credentials |
| Sessions 404 intermittently | More than one replica with in-memory state | Set `replicaCount` back to 1 |

More, specific to deployment: [`deploy/datarobot/README.md`](deploy/datarobot/README.md#diagnostics).

---

## Project layout

```
├── src/main/java/org/acme/dungeon/
│   ├── DungeonWorkflow.java     ← the dungeon. The whole map, one file.
│   ├── DungeonResource.java     ← HTTP API: moves in, CloudEvents out
│   ├── GameStore.java           ← in-memory read-model + SSE fan-out
│   ├── LockService.java         ← the random lock (no game rules)
│   ├── SpaFallbackRoute.java    ← serves the SPA, mount-point aware
│   └── Narratives.java          ← all player-facing prose
├── src/main/docker/             ← Dockerfile.jvm, Dockerfile.native, …
├── src/main/resources/
│   └── application.properties   ← image coordinates + game balance
├── src/test/java/…              ← workflow tests + REST journey
├── web/                         ← SvelteKit UI  → web/README.md
├── deploy/                      ← deployment    → deploy/README.md
│   └── datarobot/               ← Workload API  → deploy/datarobot/README.md
├── docs/lab/                    ← hands-on lab → docs/lab/LAB.md
│   ├── PREREQUISITES.md         ← send to participants beforehand
│   ├── LAB.md                   ← the tutorial, 9 modules
│   ├── FACILITATOR.md           ← timings, cues, known failures
│   └── slides.md                ← Marp deck
├── scripts/dungeon.sh           ← build · run · push · native · stop
├── docker-compose.yaml
└── specs/                       ← PRD + SRS
```

---

## Contributing

Two conventions matter more than style here:

1. **Game rules belong in the workflow, not in Java.** If you find yourself writing an `if` in Java
   that decides where a player goes, it belongs in
   [`DungeonWorkflow.java`](src/main/java/org/acme/dungeon/DungeonWorkflow.java) instead. The demo's
   credibility rests on this.
2. **Every URL the UI emits must go through SvelteKit's `base`.** A literal `href="/race"` or
   `fetch('/api/…')` works locally and silently escapes the mount when deployed under a path prefix.
   See [`web/README.md`](web/README.md).

Before opening a PR: `mvn test` and `./scripts/dungeon.sh --native` (the native path catches
reflection problems that the JVM build and the whole test suite cannot see).
