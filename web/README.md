# Dungeon Flow — web UI

The retro-terminal front end for [Dungeon Flow](../README.md). **SvelteKit 2 · Svelte 5 (runes) ·
Tailwind 4 · Skeleton 5**, on a phosphor-green `terminus` theme.

Its job isn't "click instead of curl". It's to make the invisible workflow primitives **visible**:

| What the engine is doing | What you see |
|---|---|
| `listen` with a `timeout` | a **torch ring** draining in real time |
| multi-event **join** | two levers, lighting one at a time, held at `1/2` |
| bounded **retry** | an animated attempt counter, ticking as the engine retries |
| whichever construct is live | a **primitive spotlight** naming it, with the DSL snippet |

That spotlight is the teaching payload — see [`game.svelte.ts`](src/lib/game.svelte.ts).

---

## Contents

- [Run it](#run-it)
- [Routes](#routes)
- [Two rules that will bite you](#two-rules-that-will-bite-you)
- [How it's wired](#how-its-wired)
- [Build](#build)
- [Theming](#theming)
- [Troubleshooting](#troubleshooting)

---

## Run it

The UI needs the backend for anything beyond the landing screen. **Start the backend first**, from
the repo root:

```bash
mvn quarkus:dev
```

Then the UI:

```bash
pnpm install && pnpm dev
```

Open <http://localhost:5173>.

`npm` works identically (`npm install && npm run dev`) — `package-lock.json` is the committed
lockfile and the container build uses `npm ci`. `pnpm-lock.yaml` is gitignored on purpose; see the
[note in the root README](../README.md#a-note-on-package-managers).

**Vite proxies `/api/*` to `http://localhost:8080`**, so the browser stays same-origin: no CORS
config, and SSE streams pass through. The proxy deliberately does **not** rewrite the path — the
backend serves its endpoints under `/api` itself (`quarkus.rest.path=/api`), so stripping the prefix
would 404 every call.

Other scripts:

```bash
pnpm check          # svelte-check — type-checks .svelte and .ts together
pnpm build          # production SPA into build/
pnpm preview        # serve the production build locally
```

---

## Routes

- **`/`** — split screen. Left: the terminal you play in (narrative + contextual actions). Right: the
  teaching panel — live spotlight (`⏳ event wait → 🔀 switch → 🔗 join → 🔁 retry → ✦ end`), the
  workflow construct behind it, and a five-room map with a moving token.
- **`/race`** — facilitator view. Every active instance as a card racing across the five rooms; first
  to the Treasure Room wins. Ideal on a projector for a room full of players.

Live updates arrive over **SSE** (`GET /api/dungeon/{id}/stream`), so the token moves the instant the
workflow transitions — including server-side trap retries and torch respawns that no click caused.
No polling on the play screen.

---

## Two rules that will bite you

The app is **not always served from the domain root**. Deployed on the DataRobot Workload API it is
mounted under `/api/v2/endpoints/workloads/<id>/`, and the gateway strips that prefix inbound while
never re-adding it to responses. So every URL the app emits must carry the prefix, and the prefix
comes from SvelteKit's `base`.

Both rules below are invisible locally, where `base` is `''`. They only fail once deployed.

### 1. Links must go through `base`

SvelteKit does **not** rewrite `href` attributes. A literal `href="/race"` resolves against the
origin root and escapes the app entirely.

```svelte
<script lang="ts">
  import { base } from '$app/paths';
</script>

<!-- ✗ escapes the mount -->
<a href="/race">race</a>

<!-- ✓ -->
<a href="{base}/race">race</a>
```

The same applies to **any comparison against `page.url.pathname`** — that value *includes* the base,
so comparing it to a bare `/race` silently stops matching and things like active-nav highlighting
quietly break:

```svelte
{@const active = page.url.pathname === `${base}${item.path}`}
```

### 2. Fetches must go through `base`

[`api.ts`](src/lib/api.ts) prefixes every call:

```ts
import { base } from '$app/paths';
const BASE = `${base}/api/dungeon`;
```

A literal `/api/dungeon` would resolve against the origin and hit the *platform's* own API rather
than this app — which returns plausible-looking JSON and is deeply confusing to debug.

### How `base` gets its value

Built with a **sentinel** (`/__DR_BASE__`, in [`vite.config.ts`](vite.config.ts)) which the backend
substitutes for the real mount path at container startup. That keeps one image deployable anywhere,
since the mount path isn't known until the deployment exists.

The sentinel applies to **production builds only** — the Vite config is a function of `command`, so
`pnpm dev` serves from `/` as you'd expect. Setting it unconditionally serves the dev server at
`http://localhost:5173/__DR_BASE__` with a 404 at the root, which is a thoroughly confusing way to
start your morning.

Mechanism in full: [`deploy/datarobot/README.md`](../deploy/datarobot/README.md) and
[`SpaFallbackRoute.java`](../src/main/java/org/acme/dungeon/SpaFallbackRoute.java).

---

## How it's wired

| File | Role |
|---|---|
| [`src/lib/api.ts`](src/lib/api.ts) | Every REST call (start, choice, levers, inspect, list, cleanup) plus the SSE URL — all `base`-prefixed |
| [`src/lib/game.svelte.ts`](src/lib/game.svelte.ts) | A Svelte 5 runes class holding one session's live state: subscribes to SSE, tracks lever/attempt state, derives the primitive spotlight |
| [`src/lib/types.ts`](src/lib/types.ts) | Shapes mirrored from the Java records — keep in sync with `org.acme.dungeon` |
| [`src/lib/rooms.ts`](src/lib/rooms.ts) | Room order, labels and glyphs for the map and race track |
| [`src/lib/components/`](src/lib/components/) | `RoomMap`, `PrimitiveSpotlight`, `TorchTimer` (custom SVG ring) |
| [`src/routes/+layout.ts`](src/routes/+layout.ts) | `ssr = false`, `prerender = false` — a pure client-side SPA |

**Lever state is optimistic on the client.** Only this browser pulls this instance's levers, so the
UI can show "join — waiting for lever B" before the server confirms. Lock attempts are *not*
optimistic: they arrive as real `attempt` events from the engine, because the whole point is showing
what the engine actually did.

---

## Build

```bash
pnpm build
```

Produces a static SPA in `build/` via
[`adapter-static`](https://svelte.dev/docs/kit/adapter-static) with `fallback: index.html` — no SSR,
one shell, client-side routing.

In production that output **is** served by the Quarkus backend: `build/` is copied into
`src/main/resources/META-INF/resources/`, which is what makes the whole game a single container.
Don't do that by hand — use [`scripts/dungeon.sh`](../scripts/dungeon.sh) from the repo root, because
the copy target is gitignored and forgetting it yields a working API behind a blank page with no
error.

Client-side routes like `/race` have no file on disk, so the backend rewrites unmatched paths to
`index.html` ([`SpaFallbackRoute`](../src/main/java/org/acme/dungeon/SpaFallbackRoute.java)). That
rewrite deliberately excludes any path containing a dot, so a missing `/_app/foo.js` still 404s
honestly instead of returning HTML — a mis-signal that is painful to debug in a browser.

---

## Theming

Skeleton's `terminus` theme with the primary ramp recoloured to phosphor green in
[`src/app.css`](src/app.css), plus CRT flourishes: scanlines, glow, a blinking cursor. Room glyphs
and labels are centralised in [`rooms.ts`](src/lib/rooms.ts) so the map and the race track can't drift
apart.

---

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| Every API call 404s in dev | Stale Vite process from before the proxy fix (it used to strip `/api`) | Restart `pnpm dev` |
| Dev server serves `/__DR_BASE__` and 404s at `/` | Sentinel base leaking into dev | Fixed in [`vite.config.ts`](vite.config.ts); `git pull` and restart |
| UI loads but every action fails | Backend not running | `mvn quarkus:dev` from the repo root |
| `curl localhost:5173` hits the wrong process | macOS resolves `localhost` to `::1` first; Docker may hold the IPv4 side of the same port | Use `127.0.0.1` explicitly |
| Deployed: assets 404 at the origin root, CSS reported as `text/html` | A URL bypassed `base` | See [the two rules](#two-rules-that-will-bite-you) |
| Deployed: blank page, console says `Not found: /api/v2/…` | SvelteKit's `base` is `''` in the bundle — fixing asset URLs alone isn't enough | Sentinel substitution must cover the `_app` JS; see [`deploy/datarobot/README.md`](../deploy/datarobot/README.md) |

---

Root docs: [`../README.md`](../README.md) · Deployment: [`../deploy/README.md`](../deploy/README.md)
