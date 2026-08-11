# Dungeon Flow on the DataRobot Workload API

Everything needed to deploy, update, verify and debug the game as a managed container workload.
The spec itself is [`workload.yaml`](workload.yaml).

Currently deployed: workload `6a7b6224ecdfd1a7d5af88dd`
([console](https://app.datarobot.com/console-nextgen/workloads/6a7b6224ecdfd1a7d5af88dd/overview)).

---

## Shape of the deployment

**One image, one container, one workload.** Quarkus serves the SvelteKit UI at `/` and the game API
under `/api` (`quarkus.rest.path=/api`). There is no nginx sidecar and no separate UI image — SRS
constraint C-3 taken literally.

```
browser ──► DataRobot edge ──(prefix stripped)──► Quarkus :8080 ──┬─► /        SvelteKit UI
            (authenticates)                                       └─► /api/*   game API + SSE
```

`replicaCount` **must stay 1** and autoscaling **must stay off**: `GameStore` is an in-memory map
with no persistence, so a second replica would 404 any session started on the other pod. This also
means a redeploy drops in-flight games — acceptable for a demo, but say so before a live session.

---

## Deploy

The `dr workload` command group is still feature-flagged client-side, so every `dr workload`
invocation needs the env var. Without it the CLI just prints top-level help, which reads like a
broken install:

```bash
DATAROBOT_CLI_FEATURE_WORKLOAD=true dr workload create --spec-file deploy/datarobot/workload.yaml
```

Build and publish the image first — `--push` runs the UI build and the copy into the jar, so it
cannot publish a stale UI:

```bash
scripts/run-local.sh --push --no-run
```

## Update a running workload

The artifact points at a mutable `:latest`, so a new image needs a rolling replacement to be picked
up. Confirm no replacement is already in flight first — `POST` is **not** idempotent, and calling it
twice queues a second swap:

```bash
curl -sS -H "Authorization: Bearer $DATAROBOT_API_TOKEN" "$DATAROBOT_ENDPOINT/workloads/$WID/replacement/" -o /dev/null -w '%{http_code}\n'
```

`404` means "none in progress" and is the normal, healthy answer. Then:

```bash
curl -sS -X POST -H "Authorization: Bearer $DATAROBOT_API_TOKEN" -H 'Content-Type: application/json' -d '{"artifactId":"'"$AID"'","strategy":"rolling"}' "$DATAROBOT_ENDPOINT/workloads/$WID/replacement/"
```

The replacement is finished when the workload is `running` **and** the replacement endpoint returns
`404` again. Verify the new image actually landed rather than assuming it — with a mutable tag a
cached pull is a real possibility. The proof is the mount point in the served HTML (below).

---

## The four edge behaviours that shape this app

The gateway that publishes the endpoint is not a transparent proxy. These four behaviours drove most
of the design, and three of them cost real debugging time:

| Behaviour | Consequence here |
|---|---|
| 1. The URL prefix is **stripped inbound** | The container sees `/api/dungeon`, never the prefixed path. Inbound routing needs no changes — and `quarkus.http.root-path` would **break** it. |
| 2. The edge **authenticates** | The workload root requires a DataRobot login. The app has no auth of its own, which is correct: the edge is the gate. |
| 3. An inbound `Authorization` header is **consumed by the platform** | Because the endpoint lives under `/api/v2/`, the edge treats it as a DataRobot API key. The SPA sends none, so we're fine — but adding app-level bearer auth would fail with `401 Invalid API key` **and never appear in the workload logs**. |
| 4. Responses are **not rewritten** | The edge never re-adds the prefix to asset URLs and supplies no trustworthy `X-Forwarded-Prefix`. The app must emit prefixed URLs itself. |

### How the app becomes mount-point aware

Behaviours 1 and 4 together mean the app must **emit** URLs carrying the prefix while continuing to
**match** requests that arrive without it.

The prefix is derived at startup from **`WORKLOAD_ID`**, which DataRobot injects into every workload
container — nothing is plumbed in, and it survives image rebuilds and rolling replacements because
the workload id is stable. With no `WORKLOAD_ID` (local Compose, `quarkus:dev`, tests) it collapses
to the empty string and every URL is root-absolute, so there is a single code path everywhere.

Fixing asset URLs alone is **not** sufficient, which is the non-obvious part. SvelteKit compiles its
`base` into the client bundle; left as `''` the router rejects even the initial URL with
`Not found: /api/v2/endpoints/workloads/<id>/` and the page stays blank however correct the assets
are. No HTML-level `<base href>` can repair a value baked into JS.

So the UI is built with a **sentinel base** and the backend substitutes the real mount path at
startup:

| Piece | Role |
|---|---|
| [`web/vite.config.ts`](../../web/vite.config.ts) | `kit.paths.base = '/__DR_BASE__'` — the sentinel |
| [`SpaFallbackRoute`](../../src/main/java/org/acme/dungeon/SpaFallbackRoute.java) | Substitutes the sentinel into the shell and every text asset at startup; serves the SPA fallback for client routes |
| [`web/src/lib/api.ts`](../../web/src/lib/api.ts) | Calls `${base}/api/dungeon`, never a literal path |
| [`web/src/routes/+layout.svelte`](../../web/src/routes/+layout.svelte) | Prefixes `href`s with `base`; compares `page.url.pathname` against the prefixed path |

> The sentinel string is duplicated in `vite.config.ts` and `SpaFallbackRoute`. **Keep them in
> sync** — a mismatch leaves `/__DR_BASE__` in the served HTML and every asset 404s.

Building the prefix in at build time was rejected: it needs the workload id before the workload
exists, and it would bind one image to one deployment.

### Known limit

Only `/` and `/race` exist, and both work. `SpaFallbackRoute` deliberately does not rewrite anything
outside the shell and `/_app`, so any **new** hardcoded root-absolute link (`href="/foo"`,
`fetch('/bar')`) will silently escape the mount. Always route new URLs through `base`.

---

## Verify a deployment

Do not trust `running` alone — the first deploy of this app was `running` while the browser showed a
blank page. Check these four:

```bash
EP="$DATAROBOT_ENDPOINT/endpoints/workloads/$WID"
AUTH="Authorization: Bearer $DATAROBOT_API_TOKEN"
```

1. **The mount point is the real workload id** (this is also the proof a rolling replacement actually
   pulled the new image):
   ```bash
   curl -sS -H "$AUTH" "$EP/" | grep -o 'href="[^"]*_app[^"]*"' | head -3
   ```
   Expect `/api/v2/endpoints/workloads/<real-id>/_app/…`, and **no** `__DR_BASE__` anywhere.
2. **Assets return their real content types** — a `text/html` content type on a `.css` means a 404
   page is being served in its place.
3. **A hard refresh on `/race`** returns `200 text/html` (exercises the SPA fallback).
4. **A game completes**, including the join: after one lever the instance must still be `WAITING` in
   `LEVER_ROOM`; after both it reaches `TREASURE_ROOM` / `COMPLETED`.

The startup log states the derived mount point, which is the fastest single check:

```bash
DATAROBOT_CLI_FEATURE_WORKLOAD=true dr workload logs $WID --limit 100 | grep "Serving the UI"
```

### Verify locally, behind a fake prefix

The prefix bugs are invisible at the domain root — which is exactly how they shipped. Reproduce the
edge locally instead of learning from a deploy round trip: run the image with
`-e WORKLOAD_ID=FAKE123` behind an nginx `proxy_pass http://app:8080/;` under
`location /api/v2/endpoints/workloads/FAKE123/` (the trailing slash is what strips the prefix), then
run the four checks above against `http://127.0.0.1:<port>/api/v2/endpoints/workloads/FAKE123/`.

---

## Probes

```yaml
readinessProbe: { path: /, port: 8080, initialDelaySeconds: 15, periodSeconds: 10, timeoutSeconds: 5, failureThreshold: 30 }
```

This exact config is the one verified running, and there is **no liveness probe**.

An earlier attempt used `path: /api/dungeon` with `failureThreshold: 3` plus a liveness probe at
`initialDelaySeconds: 40`. The pod was SIGTERMed (**exit 143**) roughly 60s after every start and
crash-looped — while the app logged a clean `started in 2.0s` each time and that path returned `200`.
Several parameters were relaxed in one step to recover it, so **the precise trigger was never
isolated**: treat these values as known-good rather than minimal.

`/` is a static file from `META-INF/resources`, so readiness does not depend on the REST layer or a
warm workflow engine. The absent liveness probe means a wedged JVM will **not** be auto-restarted —
an accepted trade for a low-importance demo. If you add one back, be generous
(`initialDelaySeconds >= 90`) and deploy it behind a replacement so it can be rolled back.

---

## Diagnostics

| Symptom | Cause | Fix |
|---|---|---|
| Blank page; assets 404 at `https://app.datarobot.com/_app/…`; `MIME type ('text/html')` on the CSS | Emitted URLs missing the prefix | The mount-awareness chain above; check `__DR_BASE__` is substituted |
| `__DR_BASE__` visible in the served HTML | Sentinel mismatch between `vite.config.ts` and `SpaFallbackRoute` | Re-sync the constant |
| Console `Not found: /api/v2/endpoints/workloads/<id>/` | SvelteKit `base` is `''` in the bundle — assets alone were fixed | Sentinel substitution must cover `/_app` JS, not just the HTML |
| Blank `/` but a working API | The UI was never copied into `META-INF/resources` before `mvn package` | `scripts/run-local.sh`, or the copy step by hand |
| `exec format error`, instant crash-loop | arm64-only image | Build multi-arch `linux/amd64,linux/arm64` |
| `CrashLoopBackOff`, nginx `/etc/nginx/conf.d is not writable` / `mkdir /var/cache/nginx` denied | Containers run **non-root on a read-only root filesystem**; stock nginx cannot start | Don't add an nginx sidecar — Quarkus (uid 185) serves the UI |
| Exit **143** ~60s after each start, app logs look clean | Probe config (see above) | Use the verified probe block |
| Stuck in `launching` | Readiness never passed | `dr workload logs`; check the probe path returns 200 inside the container |
| `401 Invalid API key`, request **absent** from workload logs | The edge consumed an `Authorization` header (behaviour 3) | Don't send one to the app's own API |
| Sessions 404 intermittently | `replicaCount > 1` with in-memory state | Set it back to 1 |

Rule of thumb: if a failing request does **not** appear in `dr workload logs`, the edge rejected it
before the container — that's an edge/auth problem, not an app problem.

---

## Access

The endpoint is **authenticated**: open it in a browser already logged into DataRobot (a session
cookie is enough; it will not work in a private window). Unauthenticated requests get a `401` JSON
with no login redirect, so pasting the URL into a fresh browser looks like a broken app.

To make it publicly playable for a workshop, declare a route on the primary container — this is a
deliberate exposure decision, not a default:

```yaml
routes:
  - { path: /, auth: disabled }
```
