# Deploying Dungeon Flow

Everything needed to run Dungeon Flow somewhere other than a laptop.

The game deploys as **one container**: Quarkus serves the SvelteKit UI at `/` and the game API under
`/api`. There is no sidecar, no reverse proxy, no database and no message broker. If your platform can
run a single OCI image with one HTTP port, it can run this.

---

## Targets

| Target | Guide | Status |
|---|---|---|
| **DataRobot Workload API** | [`datarobot/README.md`](datarobot/README.md) | Two deployments live (JVM + native) |
| Docker Compose | [root README](../README.md#docker-compose) | For local play, not production |
| Anything else (Kubernetes, ECS, Cloud Run, …) | [Deploying anywhere else](#deploying-anywhere-else) | Untested, but the contract is small |

---

## Before you deploy: the four things that matter

These come from things that actually broke here, not from a checklist.

### 1. The image must match the target architecture

Apple Silicon builds `arm64` by default; most platforms run `amd64`. The mismatch shows up as an
instant crash-loop with `exec format error`.

- **JVM images** are built multi-arch (`linux/amd64,linux/arm64`) by
  `./scripts/dungeon.sh --push`, so the same tag runs everywhere.
- **Native images are single-architecture** — GraalVM does not cross-compile. Worse, the *binary's*
  architecture and the *image manifest's* architecture are set separately, so it's possible to
  produce an image whose manifest says `arm64` while its entrypoint is an `x86-64` ELF. Nothing about
  the image size or a local run reveals that. `./scripts/dungeon.sh --native --platform linux/amd64`
  builds it correctly and refuses to push unless all three facts agree.

### 2. Exactly one replica

`GameStore` is an in-memory map with no persistence. A second replica would `404` any session started
on the other one, because the workflow instance lives in *one* process. Autoscaling must stay off.

This is a property of the app, not of the JVM — going native changes nothing. It also means **any
redeploy drops in-flight games**, which is fine for a demo but worth saying out loud before a live
session.

### 3. The image must be pullable

A private registry package that the platform can't pull is the most common deployment failure, and
the error rarely says so plainly — you get `ImagePullBackOff` and a lot of guessing. Either make the
package public or configure pull credentials. See
[Publishing to your own registry](../README.md#publishing-to-your-own-registry).

Verify anonymously, because your own `docker pull` succeeds either way thanks to your login:

```bash
docker manifest inspect ghcr.io/<your-username>/dungeon-flow:latest
```

### 4. If it's served under a path prefix, the app must know

Many platforms mount an app under a prefix (`/apps/<id>/`, `/api/v2/endpoints/workloads/<id>/`) and
strip that prefix before forwarding. The container then sees `/api/dungeon` while the browser sees the
prefixed URL — so every URL the app *emits* must carry the prefix, or the browser resolves it against
the origin root and leaves the app.

Dungeon Flow handles this by building the UI with a sentinel base and substituting the real mount path
at startup, derived from an environment variable. With no such variable the substitution collapses to
`""` and everything is root-absolute, so the same image works both ways.

If your platform mounts at the root, there is nothing to do. If it mounts under a prefix, set it
explicitly:

```
dungeon.base-path=/your/prefix        # no trailing slash
```

Full mechanism, and why an HTML `<base href>` alone is not sufficient:
[`datarobot/README.md`](datarobot/README.md).

---

## Deploying anywhere else

The runtime contract is deliberately tiny:

| | |
|---|---|
| **Image** | `ghcr.io/<owner>/dungeon-flow:latest` (JVM, multi-arch) or `:native-amd64` |
| **Port** | `8080` (HTTP) |
| **Health** | `GET /` — a static file, so it doesn't depend on the REST layer or a warm engine |
| **Replicas** | exactly 1 |
| **Resources** | JVM: 1 CPU / 1GB. Native: 0.5 CPU / 256MB is generous (measured 12 MiB RSS) |
| **State** | none — no volumes, no database, no broker |
| **Env** | none required. Optional: `dungeon.base-path`, and the game-balance properties |
| **User** | runs as non-root (uid 185); needs no writable paths |

A minimal Kubernetes deployment is therefore unremarkable — one `Deployment` with `replicas: 1`, one
`Service` on 8080, a readiness probe on `/`. The only non-obvious parts are the replica count and, if
you're behind an ingress that rewrites paths, `dungeon.base-path`.

> **Serving under a sub-path via ingress?** Set `dungeon.base-path` to that sub-path. Do *not* try to
> solve it with `quarkus.http.root-path` — that changes where the app *listens*, and if your ingress
> already strips the prefix, everything will 404. Inbound routing needs no changes; only outbound
> URLs do.

### Tuning the game in a deployment

All optional, all settable as environment variables using the usual MicroProfile Config mapping
(`dungeon.lock.mode` → `DUNGEON_LOCK_MODE`):

| Property | Default | Effect |
|---|---|---|
| `dungeon.lock.mode` | `RANDOM` | `ALWAYS_JAM` to demo the retry/respawn on demand |
| `dungeon.lock.success-probability` | `0.5` | Per-attempt chance the lock opens |
| `dungeon.trap.max-attempts` | `3` | Retries before the respawn compensation |
| `dungeon.fork.torch-timeout` | `PT60S` | Idle time at the fork before respawn |
| `dungeon.base-path` | *(derived)* | Explicit mount prefix, if not at the root |

---

## What's in this directory

```
deploy/
├── README.md                      ← you are here
└── datarobot/
    ├── README.md                  ← Workload API operator guide
    ├── workload.yaml              ← JVM deployment spec
    └── workload-native.yaml       ← native deployment spec
```

---

Root docs: [`../README.md`](../README.md) · UI: [`../web/README.md`](../web/README.md)
