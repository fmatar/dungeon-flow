# Lab: from source to a deployed container

**Build a Java workflow app, package a web UI inside it, containerize it two ways, publish it, and
deploy it — in about 90 minutes.**

You'll be typing. Every module ends with a **✅ Checkpoint** so you can tell whether you're with the
group. If a checkpoint fails, say so — the failure is usually more interesting than the success.

> Finish [PREREQUISITES.md](PREREQUISITES.md) first. `java -version` must say 25.

**The app:** a text-adventure dungeon whose map *is* a running workflow. Rooms are workflow states,
doors are transitions, player moves are CloudEvents. It exists because workflow primitives — event
waits, joins, retries, timeouts — are abstract, and here you *play* them.

| Module | You will | Min |
|---|---|---|
| [1](#1-run-it-in-dev-mode) | Run it in dev mode and watch the engine | 10 |
| [2](#2-package-the-application) | Package the jar — and get a blank page | 8 |
| [3](#3-package-the-ui-inside-the-application) | Put the UI *inside* the jar | 8 |
| [4](#4-containerize-jvm) | Build a JVM container | 10 |
| [5](#5-containerize-native) | Build a native container and measure both | 12 |
| [6](#6-publish-to-ghcrio) | Publish to your own registry | 10 |
| [7](#7-run-it-from-compose) | Run both images from Compose | 5 |
| [8](#8-deploy-to-the-workload-api) | Author a deployment spec and watch it go live | 20 |
| [9](#9-what-went-wrong-and-why) | Compare notes on what breaks | 10 |

---

## 1. Run it in dev mode

Two processes, both hot-reloading. **Terminal 1:**

```bash
mvn quarkus:dev
```

**Terminal 2:**

```bash
pnpm --dir web dev
```

Open <http://localhost:5173> and start a game.

Now the part that matters. Open the workflow diagram — **<http://localhost:8080/q/dev-ui> → Quarkus
Flow → Workflows** — and put it next to the game.

Play deliberately:

1. At the fork, **don't click anything.** The engine is parked on an event wait, consuming nothing,
   waiting for a human. Watch the torch ring drain.
2. Go **left**, then pull **only lever A**. Nothing happens. That's a multi-event join *holding*.
3. Pull **lever B**. The gate opens.
4. In the Trap Corridor, watch the attempt counter. That's a bounded retry running server-side, with
   no click from you.

Then open [`src/main/java/org/acme/dungeon/DungeonWorkflow.java`](../../src/main/java/org/acme/dungeon/DungeonWorkflow.java).
The whole map is one file. `listen`, `switch`, `all(...)`, the retry loop — every behaviour you just
felt is right there, and **no Java decides where the player goes**.

✅ **Checkpoint** — one lever does nothing; two levers open the gate; you can point at the `all(...)`
call that caused it.

> **Dev mode only:** that diagram does not exist in the container images. Remember this in module 4.

---

## 2. Package the application

Stop `quarkus:dev` (Ctrl-C). Make sure the UI is **not** staged, so we see what a fresh clone does:

```bash
rm -rf src/main/resources/META-INF/resources
```

Build the jar and run it:

```bash
mvn clean package -DskipTests && java -jar target/quarkus-app/quarkus-run.jar
```

**It refuses to start:**

```
Failed to start application: java.lang.IllegalStateException:
META-INF/resources/index.html is missing from the classpath.
Build the UI first: npm --prefix web run build && cp -R web/build/. src/main/resources/META-INF/resources/
```

The build succeeded. Maven was perfectly happy. The *application* stopped and told you exactly what
to do about it.

✅ **Checkpoint** — the process exits, and the error names the fix.

### Why this matters more than it looks

This app serves its UI from inside the jar, and that UI is **gitignored** — so it is never committed
and always has to be produced. Originally forgetting it produced something far worse: the API worked,
`/` returned a **blank page**, and nothing anywhere said why. People lost evenings to it.

So the app now checks at startup and refuses to run. Same bug, different failure:

| | Before | Now |
|---|---|---|
| Symptom | blank page, HTTP 200 | process exits |
| Message | none | names the missing file *and* the fix |
| Found | eventually, by a human | immediately, by the app |

> **Take this one home:** when a mistake is easy to make and hard to diagnose, don't document it —
> make it impossible to ship. A loud failure at startup beats a silent one in production.

---

## 3. Package the UI inside the application

Quarkus serves anything in `META-INF/resources` as static files. So the UI goes *there*, and the API
moves under `/api` — which is exactly the prefix the UI already calls. Two halves, one process, no
reverse proxy.

Build the UI:

```bash
pnpm --dir web build
```

Copy it into the jar's static resources:

```bash
mkdir -p src/main/resources/META-INF/resources && cp -R web/build/. src/main/resources/META-INF/resources/
```

Repackage and run:

```bash
mvn clean package -DskipTests && java -jar target/quarkus-app/quarkus-run.jar
```

Open <http://localhost:8080>. The game is there — **one process serving both halves**.

```bash
curl -s -o /dev/null -w 'UI  %{http_code}\n' http://localhost:8080/ && curl -s -o /dev/null -w 'API %{http_code}\n' http://localhost:8080/api/dungeon
```

✅ **Checkpoint** — both return `200`, and you can play at `:8080` with no Vite running.

> That copy target is **gitignored**, so it is never committed and always has to be produced. From
> here on we use [`scripts/dungeon.sh`](../../scripts/dungeon.sh), which does it for you. This module
> existed so you know what the script is doing and why forgetting it looks like a broken UI rather
> than a build error.

Stop the jar.

---

## 4. Containerize: JVM

```bash
./scripts/dungeon.sh
```

One command: preflights your toolchain, picks a JDK, builds the UI, stages it, builds the image,
starts it on a free port, waits for health, and **plays a full game** to prove the workflow engine
actually runs inside the container.

Read the last block it prints:

```
==> Measured footprint
    mode:          JVM
    image:         661MB
    startup:       ~2s
    memory:        ~177MiB
```

Write those three numbers down. Module 5 is the comparison.

✅ **Checkpoint** — `victory - reached TREASURE_ROOM`, and you can play at the URL it printed.

Try <http://localhost:8080/q/dev-ui>. **404** — this is a production image. The diagram lives only in
dev mode.

---

## 5. Containerize: native

Same script, one flag:

```bash
./scripts/dungeon.sh --native
```

This compiles a **GraalVM native binary** inside a Linux builder container — no local GraalVM needed,
and nothing installed on your machine. Expect ~1 minute.

Compare:

| | JVM | Native |
|---|---|---|
| Image | 661 MB | **253 MB** |
| Startup | ~2.0s | **~0.02s** |
| Memory | ~177 MiB | **~12 MiB** |

An 88× faster start and 15× less memory. For a demo app the runtime doesn't care — but "the entire
dungeon, workflow engine included, boots in 23ms on 12MB" is itself the point.

✅ **Checkpoint** — `mode: native`, startup under 0.1s, memory under 20 MiB.

### The catch: native does not cross-compile

The binary targets the architecture it was **built on**. On an Apple Silicon Mac you just built
`linux/arm64` — excellent for local testing, and it **will not run** on the amd64 machines most
platforms use. There it dies instantly with `exec format error`.

For a deployable artifact you need `--platform linux/amd64`, which emulates the whole builder and
takes ~10 minutes. Your facilitator is starting that build now, in the background, and we'll come back
to it in module 8.

There's a subtler trap here too, which module 9 covers: the **binary's** architecture and the
**image manifest's** architecture are set separately, so it's entirely possible to produce an image
that lies about itself.

---

## 6. Publish to ghcr.io

Point the project at **your** namespace. Edit
[`src/main/resources/application.properties`](../../src/main/resources/application.properties):

```properties
quarkus.container-image.registry=ghcr.io
quarkus.container-image.group=YOUR_GITHUB_USERNAME
```

Build and push:

```bash
./scripts/dungeon.sh --push --no-run
```

That publishes a **multi-arch** image (`linux/amd64,linux/arm64`) — so the same tag runs on your Mac
*and* on an amd64 platform. This is why the JVM path avoids the whole architecture problem.

### Make the package public

New GHCR packages are **private**, and a private image is the most common cause of a deployment stuck
in `ImagePullBackOff` — the platform can't pull it and rarely says so plainly.

1. Open `https://github.com/users/YOUR_USERNAME/packages/container/dungeon-flow/settings`
2. **Danger Zone → Change visibility → Public**

Verify **anonymously** — your own `docker pull` succeeds either way because you're logged in:

```bash
docker manifest inspect ghcr.io/YOUR_USERNAME/dungeon-flow:latest | grep architecture
```

✅ **Checkpoint** — you see both `amd64` and `arm64`, from a command that uses no credentials.

---

## 7. Run it from Compose

```bash
docker compose up
```

Open <http://localhost:8080>. Then run the native image instead, same Compose file:

```bash
DUNGEON_IMAGE=ghcr.io/YOUR_USERNAME/dungeon-flow:native-arm64 DUNGEON_HOST_PORT=8090 docker compose up
```

Both on screen at once — identical game, wildly different footprint:

```bash
docker stats --no-stream --format 'table {{.Name}}\t{{.MemUsage}}'
```

✅ **Checkpoint** — two containers, same game, ~177 MiB vs ~12 MiB.

```bash
./scripts/dungeon.sh --stop
```

---

## 8. Deploy to the Workload API

### First: when should you actually use this?

**The Workload API is for deploying agents that use the DataRobot LLM Gateway but are written in a
language other than Python.** That's the use case it exists for. Python agents have a more direct
path; a container is what you reach for when your agent is Java, Go, Rust, TypeScript.

Be honest about what we're doing here: **Dungeon Flow is a mechanics vehicle.** It's Java, it's
containerized, it's non-Python — so it exercises exactly the same path — but it does *not* call the
LLM Gateway. [Appendix A](#appendix-a-what-a-real-use-case-adds) shows the ~20 lines that would make
it a real one.

If you're reaching for the Workload API to host a website, a database, or a Python agent, stop and
pick a different tool.

### The runtime contract

Small, and worth knowing before you write any YAML:

| | |
|---|---|
| Port | `8080`, and it **must be ≥ 1024** |
| Health | `GET /` — a static file, so readiness doesn't wait for a warm engine |
| Replicas | exactly **1** (this app holds state in memory) |
| Filesystem | **read-only**, running **non-root** |
| Architecture | **linux/amd64** |

Two of those decided this app's architecture. Read them again — non-root and read-only is why there's
no nginx sidecar, and why the UI is served by Quarkus.

### Author the spec

Open [`deploy/datarobot/workload.yaml`](../../deploy/datarobot/workload.yaml) and read it top to
bottom — every comment in it is a scar. The shape:

```yaml
name: dungeon-flow
importance: low
artifact:
  name: dungeon-flow-artifact
  spec:
    type: service
    containerGroups:
      - name: default
        containers:
          - name: app
            imageUri: ghcr.io/YOUR_USERNAME/dungeon-flow:native-amd64
            primary: true
            port: 8080
            readinessProbe: { path: /, port: 8080, initialDelaySeconds: 15, periodSeconds: 10, failureThreshold: 30 }
runtime:
  containerGroups:
    - name: default
      replicaCount: 1
      containers:
        - name: app
          resourceAllocation: { cpu: 0.5, memory: "256MB" }
```

Change `imageUri` to your own image. Note **256MB** — sized for a native binary that idles at 12 MiB.
The JVM variant asks for 1GB.

Notice what's *absent*: no liveness probe. Module 9 explains what happened when there was one.

### Deploy (facilitator drives)

```bash
DATAROBOT_CLI_FEATURE_WORKLOAD=true dr workload create --spec-file deploy/datarobot/workload-native.yaml
```

> Without that env var the CLI prints top-level help and looks broken. It's feature-flagged.

Watch it reach `running`, then open the endpoint in a browser and play.

✅ **Checkpoint** — a full playthrough on the platform: one lever holds the join, both levers win.

### The thing nobody expects

The workload is served under a **path prefix** — `/api/v2/endpoints/workloads/<id>/` — which the
gateway **strips** before your container sees it, and never re-adds to your responses.

So the app receives `/api/dungeon` while the browser is at the prefixed URL. Every URL your app
*emits* must carry the prefix, or the browser resolves it against the origin root and leaves your app
entirely. The symptom is a blank page with every asset 404ing.

This app solves it by building the UI with a **sentinel** base and substituting the real mount path at
startup, derived from the `WORKLOAD_ID` that DataRobot injects into every container. Check the log:

```
Serving the UI under mount point '/api/v2/endpoints/workloads/<id>' (derived from WORKLOAD_ID)
```

Full mechanism: [`deploy/datarobot/README.md`](../../deploy/datarobot/README.md).

---

## 9. What went wrong, and why

Five failures from building this. Each one cost hours and none of them announced itself clearly.

### 1. `exec format error` — and the image that lied

An arm64 image on an amd64 platform crash-loops instantly. Obvious once you know.

The nasty version: build the *binary* for amd64, then run `docker build` on your Mac, and you get an
**arm64 manifest wrapped around an x86-64 binary**. Neither the image size nor a local run reveals it.
The fix is to verify three facts independently — manifest arch, native layout, real ELF arch — which
[`scripts/dungeon.sh`](../../scripts/dungeon.sh) now does automatically and refuses to push on
mismatch.

### 2. nginx simply cannot start there

The first design was two containers: nginx for the UI, Quarkus for the API. It crash-looped:

```
20-envsubst-on-templates.sh: ERROR: /etc/nginx/conf.d is not writable
nginx: [emerg] mkdir() "/var/cache/nginx/client_temp" failed (13: Permission denied)
```

Containers run **non-root on a read-only filesystem**. Stock nginx needs root and a writable config
dir, so no amount of configuration would fix it. Meanwhile the Quarkus container beside it was healthy
— its base image already runs as uid 185 and writes nothing.

**The lesson:** the constraint didn't need working around, it needed the sidecar deleted. That's how
this became one container — which is also simpler, smaller and faster.

### 3. A blank page, and why fixing the assets wasn't enough

Path prefix, as in module 8. We fixed every asset URL. Still blank:

```
Not found: /api/v2/endpoints/workloads/<id>/
```

SvelteKit compiles its own `base` into the **client bundle**. Left empty, its router compares the real
pathname against `''` and rejects the very first URL. No amount of HTML can fix a value baked into
JavaScript — the substitution has to reach the JS too.

### 4. Exit 143, from an app whose logs looked perfect

The pod was SIGTERMed ~60 seconds after every start, while logging:

```
dungeon-flow started in 2.018s. Listening on: http://0.0.0.0:8080
```

Healthy app, killed by the platform. Probe configuration: readiness with too few retries, plus a
liveness probe. Relaxing readiness onto a static path with a generous threshold, and dropping liveness
entirely, fixed it.

**Honest note:** several parameters changed at once to recover it, so the precise trigger was never
isolated. The spec says so rather than pretending otherwise.

### 5. A bug only the native build could find

Going native surfaced this:

```
Jackson was unable to serialize type 'DungeonResource$StartResponse'
```

Native images strip reflection metadata unless told not to. Two response records weren't registered,
so **every endpoint 500'd** — invisible to the JVM build and to all 16 tests, which pass either way.

**The lesson:** a native build is a static analysis of your app. It finds real problems, and it finds
them at the worst time unless you build native in CI.

### Also worth 30 seconds each

- **`sh script.sh` ≠ `bash script.sh`.** macOS `/bin/sh` *is* bash in POSIX mode: it sets
  `BASH_VERSION` while rejecting process substitution, so guarding on that variable doesn't work.
- **`localhost` is not `127.0.0.1`.** macOS resolves it to `::1` first. A Vite dev server on the IPv6
  side of a port will silently answer instead of your container, and you'll debug the wrong process.
- **Mutable tags don't redeploy themselves.** Pushing a new `:latest` changes nothing until you roll
  the workload — and then you must verify the new image actually landed.

---

## Appendix A: what a real use case adds

Dungeon Flow doesn't call the LLM Gateway. A real Workload API agent would. The gateway is
OpenAI-compatible, so in Quarkus it's a REST client and nothing more:

```java
@RegisterRestClient(configKey = "llm-gateway")
public interface LlmGateway {
    @POST
    @Path("/genai/llmgw/chat/completions")
    @ClientHeaderParam(name = "Authorization", value = "Bearer ${datarobot.api-token}")
    ChatResponse chat(ChatRequest request);   // OpenAI-shaped request/response
}
```

```properties
quarkus.rest-client.llm-gateway.url=${DATAROBOT_ENDPOINT}
```

`DATAROBOT_ENDPOINT` and `DATAROBOT_API_TOKEN` are injected into the workload. Everything else in this
lab — the container, the probes, the single replica, the path prefix, the architecture — is identical.
That's the point: **the deployment mechanics are what you just learned; the agent is the part you
write.**

> Check the current gateway path in your DataRobot docs before relying on it — and prefer a stored
> credential over a token in a config file.

---

## Where to go next

| | |
|---|---|
| The workflow itself | [`DungeonWorkflow.java`](../../src/main/java/org/acme/dungeon/DungeonWorkflow.java) |
| Full project docs | [`README.md`](../../README.md) |
| The UI, and the two rules that bite | [`web/README.md`](../../web/README.md) |
| Deploying anywhere | [`deploy/README.md`](../../deploy/README.md) |
| Workload API operations | [`deploy/datarobot/README.md`](../../deploy/datarobot/README.md) |

**One thing to take away:** every hard problem in this lab was an *environment* problem — an
architecture mismatch, a read-only filesystem, a stripped path prefix, a probe timeout, missing
reflection metadata. None was in the application logic. That's what deploying containers is actually
like, and it's why we built it together rather than showing you a finished thing.
