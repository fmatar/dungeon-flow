# Facilitator guide

For whoever runs the lab. Participants use [LAB.md](LAB.md); this is the stuff they shouldn't see —
timings, cues, and what breaks.

---

## The one scheduling decision that matters

**The native amd64 build takes ~10 minutes and cannot be rushed.** It also fails occasionally (see
[Known failures](#known-failures)). So it runs in the background while you teach:

```
Module 5 ends  ─┬─▶  START the amd64 build in a spare terminal, out loud, on screen
                │
Module 6  GHCR  │    (~10 min of teaching happens here)
Module 7  Compose
                │
Module 8  ──────┴─▶  the build is done; deploy with it
```

Kick it off the moment module 5's local native build finishes:

```bash
./scripts/dungeon.sh --native --platform linux/amd64 --push
```

Say what you're doing: *"this one emulates an entire x86 machine to compile, so it takes ten minutes
— that's why native lives in CI, not on your laptop. I'll leave it running."* The wait becomes a
teaching point instead of dead air.

**Have the fallback ready before you start:** a pre-pushed `:native-amd64` image. If the live build
fails, that's a genuine teaching moment — show the error, explain that emulated `native-image` is
fragile and belongs on an amd64 CI runner, then deploy the pre-built one. Don't debug it live.

---

## Timings

| Module | Min | Cumulative |
|---|---|---|
| Intro — why a game | 5 | 5 |
| 1 · Dev mode | 10 | 15 |
| 2 · Package (blank page) | 8 | 23 |
| 3 · UI inside the jar | 8 | 31 |
| 4 · JVM container | 10 | 41 |
| 5 · Native + measure · **start amd64 build** | 12 | 53 |
| 6 · GHCR + make public | 10 | 63 |
| 7 · Compose, both images | 5 | 68 |
| 8 · Workload API | 20 | 88 |
| 9 · Blockers | 10 | 98 |
| Q&A | 5 | 103 |

Runs long in practice. **If you're behind: cut module 7** (it's a nice-to-have; the numbers already
landed in module 5) and shorten module 9 to blockers 1, 2 and 4.

Never cut modules 2→3. That pair is the spine.

---

## Beat-by-beat cues

### Intro (5 min)

Open with the problem, not the app: *"who here can explain a multi-event join to a non-technical
stakeholder?"* Then: every primitive in this app is something you'll physically feel in the next ten
minutes.

### Module 1 — dev mode

**The moment to slow down:** at the fork, tell everyone to *stop touching the keyboard*. Let the
silence run five seconds. *"The engine is parked on an event wait. It's consuming nothing. It'll wait
for an hour."*

Then the join: one lever, nothing. Let that sit too — people expect a reaction. Then the second lever.
This is the beat people remember; don't rush it.

Put the Dev UI diagram on the projector for the whole module.

### Module 2 — the refusal to start

**Do not warn them.** Let them run it and hit the error. Ask what they think happened *before*
explaining. People reach for "the build failed" — it didn't, Maven exited 0.

Then the real lesson: this used to be a **blank page with HTTP 200 and no message**, and people lost
evenings to it. Someone added a startup check, so now the same mistake exits immediately and names the
fix.

**The line to land:** *"when a mistake is easy to make and hard to diagnose, don't document it — make
it impossible to ship."* That generalises far beyond this repo, and it's the most portable idea in the
lab.

> Verified: the app exits with
> `IllegalStateException: META-INF/resources/index.html is missing from the classpath`. If someone
> gets a blank page instead of an exit, they have a **stale container on 8080** — check
> `lsof -nP -iTCP:8080 -sTCP:LISTEN`.

### Module 3 — the fix

Explain *why* the API moved to `/api`: it frees `/` for the UI and matches the prefix the UI already
called, so the two halves line up with no proxy. One `mv`-shaped decision that removed an entire
container.

### Module 4 — JVM container

Have them read out their measured numbers. Write the room's average on a whiteboard — module 5 lands
harder against a number they produced themselves.

Get someone to try `/q/dev-ui` and hit the 404. *"Production images have no dev tooling. If your demo
depends on the diagram, you demo from dev mode."*

### Module 5 — native

Numbers on screen side by side. Then **start the amd64 build** (see above).

### Module 6 — GHCR

The visibility step is where people get stuck, and it's environmental, not technical. Walk the UI path
on screen. Stress the **anonymous** verify: *"your own pull works because you're logged in — that's
why people ship private images and only find out from `ImagePullBackOff`."*

### Module 8 — Workload API

**Lead with positioning, before any YAML.** The Workload API is for agents that use the DataRobot LLM
Gateway and aren't written in Python. Say plainly that Dungeon Flow doesn't call the gateway — it's a
mechanics vehicle. Then show [Appendix A](LAB.md#appendix-a-what-a-real-use-case-adds).

If you skip this, people will leave thinking the Workload API is a general-purpose container host.
That is the single most likely way this lab does harm.

You drive the deploy; they author the spec locally. Share your screen for `dr workload create` and the
polling. While it launches, walk the spec's comments — each one is a scar from module 9.

**Land the payoff:** the app derives its own mount point from `WORKLOAD_ID`. Show the log line. This is
what makes one image deployable anywhere.

### Module 9 — blockers

Ask first: *"what broke for you in the last hour?"* Their failures are better material than yours.
Then run the five, fast.

Close on the takeaway: **every hard problem was an environment problem, not application logic.**

---

## Known failures

| Symptom | Cause | Do this |
|---|---|---|
| Native build OOMs or dies in `[2/8] Performing analysis` | Docker memory too low, or emulation flakiness | 8 GB minimum. If emulated, use the pre-built image and move on |
| `Failed to read .../runner.jar` during emulated native build | Emulation flakiness — happened once here | Retry once with `mvn clean`; otherwise fall back. **Don't debug live** |
| `release version 25 not supported` | Wrong JDK on `PATH` | `export JAVA_HOME=$(/usr/libexec/java_home -v 25)` |
| Someone's `docker compose up` aborts, port in use | Their `quarkus:dev` still running | `DUNGEON_HOST_PORT=8090 docker compose up` |
| `curl localhost:5173` returns nonsense | macOS resolves `localhost` to `::1`; their Vite holds the IPv6 side | Use `127.0.0.1` |
| Push to GHCR denied | Token lacks `write:packages` | `gh auth refresh -h github.com -s write:packages` |
| Workload stuck `ImagePullBackOff` | Package still private | Change visibility; verify anonymously |
| Someone edited `application.properties` group wrong | Typo'd username | The script fails with instructions — read them out; it's a designed error |

**Blanket rule:** if one person is stuck on something environmental, pair them with a neighbour and
keep moving. Don't let the room wait.

---

## Prep checklist

**A week ahead**

- [ ] Send [PREREQUISITES.md](PREREQUISITES.md). Ask for a thumbs-up from each attendee.
- [ ] Confirm the repo is public and clones anonymously.

**The day before**

- [ ] Build and push a **fallback `:native-amd64`** image. Verify it anonymously.
- [ ] Run the whole lab start to finish on a clean clone. Time it.
- [ ] Confirm `dr workload create` works and you have quota headroom (limit is 50 concurrent).
- [ ] Delete leftover workloads from previous runs.

**Ten minutes before**

- [ ] Docker Desktop running, 8 GB memory
- [ ] `docker login ghcr.io` valid
- [ ] Two terminals + browser at a projector-legible font size
- [ ] Dev UI diagram bookmarked
- [ ] Fallback image reference in your paste buffer

**Afterwards**

- [ ] Delete the workloads you created
- [ ] Share the repo, the slides, and [LAB.md](LAB.md) — it's written to be followed alone

---

## If you have 45 minutes instead of 90

Cut to the spine: **module 1** (dev mode, the join) → **module 2+3** merged (blank page, then fix) →
**module 4** (JVM container) → **module 8** (positioning + a pre-built deploy) → **three blockers**
(nginx, the path prefix, exit 143).

Drop native, GHCR and Compose. You lose the performance story; you keep the architecture story, which
is the more useful half.
