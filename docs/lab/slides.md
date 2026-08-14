---
marp: true
theme: default
paginate: true
header: 'Dungeon Flow — source to deployed container'
style: |
  section { font-size: 26px; }
  section.lead { text-align: center; }
  h1 { color: #1a7f5a; }
  code { font-size: 0.85em; }
  pre { font-size: 0.7em; }
  table { font-size: 0.8em; }
  .small { font-size: 0.7em; color: #666; }
---

<!-- _class: lead -->

# Dungeon Flow

## From source to a deployed container

A hands-on lab — **you build it with me**

<span class="small">Java · Quarkus Flow · SvelteKit · GraalVM · Workload API</span>

---

## Who can explain a multi-event join?

To a stakeholder. Without a whiteboard.

Workflow primitives are abstract:

- event waits
- data switches
- multi-event joins
- bounded retries + compensation
- event timeouts

Order-processing demos don't land. **So we made them a game you play.**

---

## The map *is* the workflow

```
 Entrance ─▶ Fork ──(left)──▶ Lever Room ──(A & B)──▶ Trap Corridor ──▶ Treasure ✦
              │  \─(right)──────────────────────────▶ Trap Corridor
              │  \─(unknown / torch out)────────────▶ respawn
              ▲                                            │
              └────────── respawn on retry exhaustion ──────┘
```

| Room | Primitive | What you feel |
|---|---|---|
| Fork | `listen` + `switch` + `timeout` | It waits for *you*. Dawdle and the torch dies. |
| Lever Room | `listen…all(A,B)` — **join** | One lever: nothing. Both: the gate opens. |
| Trap Corridor | **retry** + compensation | Jams at random. Three fails, thrown back. |
| Treasure | terminal state | `victory: true` |

---

## One file. No Java decides anything.

`DungeonWorkflow.java` — the entire map.

The Java beans either **observe** (`GameStore`) or **roll dice** (`LockService`).

> **They never decide where the player goes.**

That constraint is what makes the demo honest — and what makes the workflow
definition worth reading.

---

## Today

| | Module | |
|---|---|---|
| 1 | Dev mode — watch the engine | 10m |
| 2 | Package the app → **blank page** | 8m |
| 3 | Package the UI *inside* the app | 8m |
| 4 | JVM container | 10m |
| 5 | Native container + measure | 12m |
| 6 | Publish to ghcr.io | 10m |
| 7 | Run both from Compose | 5m |
| 8 | Deploy to the Workload API | 20m |
| 9 | **What went wrong** | 10m |

---

<!-- _class: lead -->

# 1 · Dev mode

Two processes. Both hot-reload.

```bash
mvn quarkus:dev
```
```bash
pnpm --dir web dev
```

<span class="small">The diagram: :8080/q/dev-ui → Quarkus Flow → Workflows</span>

---

## Do this deliberately

1. At the fork — **stop typing.** The engine is parked on an event wait. It consumes nothing. It'll
   wait an hour.
2. Go left. Pull **only lever A**. Nothing happens — the join is *holding*.
3. Pull B. The gate opens.
4. Trap Corridor: the counter ticks. A retry, running server-side, with no click from you.

Then open the workflow file and point at the `all(...)` that did it.

**✅ One lever: nothing. Two: the gate.**

---

<!-- _class: lead -->

# 2 · Package it

```bash
rm -rf src/main/resources/META-INF/resources
mvn clean package -DskipTests
java -jar target/quarkus-app/quarkus-run.jar
```

What happens?

---

## It refuses to start

```
Failed to start application: IllegalStateException:
META-INF/resources/index.html is missing from the classpath.
Build the UI first: npm --prefix web run build && cp -R web/build/. ...
```

**The build succeeded.** Maven was happy. The *application* stopped and named the fix.

---

## Why that's the interesting part

The UI is gitignored — it must always be produced. Forgetting it used to give you:

| | Before | Now |
|---|---|---|
| Symptom | **blank page**, HTTP 200 | process exits |
| Message | none | names the file *and* the fix |
| Found by | a human, eventually | the app, immediately |

<br>

> When a mistake is easy to make and hard to diagnose — don't document it. Make it impossible to ship.

---

<!-- _class: lead -->

# 3 · Put the UI *inside* the app

```bash
pnpm --dir web build
cp -R web/build/. src/main/resources/META-INF/resources/
mvn clean package -DskipTests
```

**One process. Both halves.**

---

## Why this works

Quarkus serves `META-INF/resources` as static files → the **UI lands at `/`**

The API moves to **`/api`** — which is the prefix the UI already called

<br>

| Before | After |
|---|---|
| nginx + Quarkus, 2 containers | Quarkus, **1 container** |
| reverse proxy config | none |
| a sidecar to break | nothing to break |

One decision deleted an entire container. Module 9 explains why it *had* to.

---

<!-- _class: lead -->

# 4 · Containerize

```bash
./scripts/dungeon.sh
```

Builds the UI · stages it · builds the image · runs it ·
**plays a full game to prove it works**

---

## Write your numbers down

```
==> Measured footprint
    mode:          JVM
    image:         661MB
    startup:       ~2s
    memory:        ~177MiB
```

Now try `:8080/q/dev-ui` → **404**

> Production images have no dev tooling. If your demo needs the diagram, demo from dev mode.

---

<!-- _class: lead -->

# 5 · Native

```bash
./scripts/dungeon.sh --native
```

GraalVM, compiled inside a Linux builder container.
**Nothing installed on your machine.**

---

## 88× faster. 15× smaller.

| | JVM | Native |
|---|---|---|
| Image | 661 MB | **253 MB** |
| Startup | 2.018s | **0.023s** |
| Memory | 176.7 MiB | **12.0 MiB** |

<br>

The runtime doesn't care — it starts once.

But *"the whole dungeon, workflow engine included, boots in 23ms on 12MB"* — **that** is the demo.

---

## The catch

**Native does not cross-compile.** The binary targets the arch it was built on.

You just built `linux/arm64`. Most platforms are `amd64`. There it dies instantly:

```
exec format error
```

<br>

`--platform linux/amd64` emulates the whole builder → **~10 minutes**

> I'm starting that build now. We'll come back to it.

---

<!-- _class: lead -->

# 6 · Publish

```bash
# application.properties
quarkus.container-image.group=YOUR_USERNAME
```
```bash
./scripts/dungeon.sh --push --no-run
```

Multi-arch: `linux/amd64,linux/arm64` — one tag, both machines

---

## Then make it public

New GHCR packages are **private**.

A private image is the #1 cause of `ImagePullBackOff` — and it rarely says so.

Verify **anonymously**:

```bash
docker manifest inspect ghcr.io/YOU/dungeon-flow:latest | grep architecture
```

> Your own `docker pull` works either way — you're logged in. That's the trap.

---

<!-- _class: lead -->

# 8 · The Workload API

## First: when should you use it?

---

## Use it for this

> **Agents that use the DataRobot LLM Gateway, written in a language other than Python.**

That's what it's for. Python agents have a more direct path. A container is what you reach for when
your agent is **Java, Go, Rust, TypeScript**.

<br>

**Being honest:** Dungeon Flow is a *mechanics vehicle*. Java, containerized, non-Python — same path
exactly — but it does **not** call the LLM Gateway.

Not a website host. Not a database. Not a Python agent.

---

## What a real one adds

```java
@RegisterRestClient(configKey = "llm-gateway")
public interface LlmGateway {
    @POST @Path("/genai/llmgw/chat/completions")
    ChatResponse chat(ChatRequest request);   // OpenAI-shaped
}
```

`DATAROBOT_ENDPOINT` + `DATAROBOT_API_TOKEN` are injected into the workload.

<br>

Everything else in this lab — container, probes, single replica, path prefix, architecture — is
**identical**.

> The deployment mechanics are what you just learned. The agent is the part you write.

---

## The runtime contract

| | |
|---|---|
| Port | `8080` — **must be ≥ 1024** |
| Health | `GET /` (static file — no warm engine needed) |
| Replicas | **1** (this app holds state in memory) |
| Filesystem | **read-only**, **non-root** |
| Architecture | **linux/amd64** |

<br>

Two of these decided the entire architecture. Read them again.

---

## Deploy

```bash
DATAROBOT_CLI_FEATURE_WORKLOAD=true \
  dr workload create --spec-file deploy/datarobot/workload-native.yaml
```

<span class="small">Without the env var the CLI prints top-level help and looks broken. It's
feature-flagged.</span>

<br>

Then open the endpoint and **play it**.

**✅ One lever holds the join. Both levers win.**

---

## The thing nobody expects

Your workload is served under a **path prefix**:

```
/api/v2/endpoints/workloads/<id>/
```

The gateway **strips it inbound** and **never re-adds it** to your responses.

- Container sees `/api/dungeon`
- Browser is at the prefixed URL
- Every URL you *emit* must carry the prefix

<br>

Miss it → **blank page, every asset 404**

---

## How this app solves it

Build with a **sentinel** base → substitute the real mount path at **startup**

Derived from `WORKLOAD_ID`, which DataRobot injects into every container:

```
Serving the UI under mount point
'/api/v2/endpoints/workloads/<id>' (derived from WORKLOAD_ID)
```

<br>

No prefix? Substitution collapses to `""`. **One image, deployable anywhere.**

---

<!-- _class: lead -->

# 9 · What went wrong

Five failures. Hours each.
**None announced itself clearly.**

---

## 1 · The image that lied about itself

`exec format error` — arm64 on amd64. Obvious once you know.

The nasty version:

- binary built for **amd64** ✅
- `docker build` on a Mac → **arm64 manifest** ❌

<br>

Image size won't show it. A local run won't show it.

**Fix:** verify three facts independently — manifest arch, layout, real ELF arch. The script now
refuses to push on mismatch.

---

## 2 · nginx simply cannot start there

```
ERROR: /etc/nginx/conf.d is not writable
nginx: [emerg] mkdir "/var/cache/nginx/client_temp" failed (13: Permission denied)
```

**Non-root. Read-only filesystem.** No configuration fixes that.

Meanwhile Quarkus beside it: healthy, 0 restarts, uid 185, writes nothing.

<br>

> The constraint didn't need working around. It needed the sidecar **deleted**.

Simpler, smaller, faster. The best fix removed code.

---

## 3 · Blank page — and fixing the assets wasn't enough

We fixed every asset URL. Still blank:

```
Not found: /api/v2/endpoints/workloads/<id>/
```

SvelteKit compiles `base` into the **client bundle**. Empty → its router rejects the very first URL.

<br>

> No amount of HTML fixes a value baked into JavaScript.

The substitution has to reach the JS too.

---

## 4 · Exit 143, from a perfectly healthy app

SIGTERMed ~60s after every start. The logs, every time:

```
dungeon-flow started in 2.018s. Listening on: http://0.0.0.0:8080
```

Healthy app. Killed by the platform. **Probe configuration.**

<br>

**Honest footnote:** several parameters changed at once to recover it. The precise trigger was never
isolated — and the spec says so instead of pretending.

---

## 5 · A bug only the native build could find

```
Jackson was unable to serialize type 'DungeonResource$StartResponse'
```

Native strips reflection metadata unless told otherwise. Two records unregistered → **every endpoint
500'd**.

<br>

Invisible to the JVM build. Invisible to all 16 tests — they pass either way.

> A native build is a static analysis of your app. Build native in CI, or it finds these for you in
> production.

---

## 30 seconds each

**`sh script.sh` ≠ `bash script.sh`** — macOS `/bin/sh` *is* bash in POSIX mode: sets
`BASH_VERSION`, rejects process substitution.

**`localhost` ≠ `127.0.0.1`** — macOS tries `::1` first. A dev server on the IPv6 side answers
instead of your container.

**Mutable tags don't redeploy** — pushing `:latest` changes nothing until you roll the workload. Then
verify it actually landed.

---

<!-- _class: lead -->

## The takeaway

Every hard problem today was an **environment** problem.

Architecture mismatch · read-only filesystem · stripped path prefix ·
probe timeout · missing reflection metadata

<br>

**None was in the application logic.**

That's what deploying containers is actually like — which is why we built it together instead of
showing you a finished thing.

---

<!-- _class: lead -->

# Questions?

**The lab, to redo alone:** `docs/lab/LAB.md`

**The repo:** github.com/fmatar/dungeon-flow

<span class="small">README · web/README · deploy/README · deploy/datarobot/README</span>
