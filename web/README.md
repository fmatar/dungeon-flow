# Dungeon Flow — web UI

A retro-terminal front end for the [Dungeon Flow](../README.md) backend, built with **SvelteKit +
Skeleton** (Svelte 5, Tailwind 4, Skeleton v3 on a phosphor-green `terminus` theme).

Its job isn't "click instead of curl" — it's to make the invisible workflow primitives **visible**:
a draining **torch ring** for the event timeout, two lit **levers** for the join, an animated
**retry counter** for the trap, and a live **primitive spotlight** naming what the engine is doing.

## Layout

- **`/`** — split screen. Left: the terminal you play in (narrative + contextual actions). Right:
  the teaching panel — a live spotlight (`⏳ event wait → 🔀 switch → 🔗 join → 🔁 retry → ✦ end`),
  the workflow construct behind it, and a 5-room map with a moving token.
- **`/race`** — facilitator view. Every active instance as a card racing across the 5 rooms; first to
  the Treasure Room wins. Poll-based scoreboard with quick-nudge controls.

Live updates arrive over **SSE** (`GET /dungeon/{id}/stream`) — the token moves the instant the
workflow transitions, including server-side trap retries and torch respawns. No polling on the play
screen.

## Run it

Start the backend first (from the repo root):

```bash
mvn quarkus:dev          # Quarkus on :8080
```

Then the UI:

```bash
cd web
npm install
npm run dev              # SvelteKit on :5173
```

Open **http://localhost:5173**. The Vite dev server proxies `/api/*` → `http://localhost:8080`, so
there's no CORS to configure and the browser stays same-origin.

## Build

```bash
npm run build            # static SPA in web/build/  (adapter-static)
npm run preview
```

Because it builds to static files, the SPA can later be served straight from the Quarkus backend
(copy `build/` into `src/main/resources/META-INF/resources`), keeping the whole game a single
container.

## How it's wired

- `src/lib/api.ts` — the REST calls (start / choice / levers / inspect / list / cleanup).
- `src/lib/game.svelte.ts` — a Svelte 5 runes class holding one session's live state; subscribes to
  the SSE stream, tracks lever/attempt state, and derives the primitive spotlight.
- `src/lib/components/` — `RoomMap`, `PrimitiveSpotlight`, `TorchTimer` (custom SVG ring).
- Styling is Skeleton's `terminus` theme with the primary ramp recolored to phosphor green in
  `src/app.css`, plus a few CRT flourishes (scanlines, glow, blinking cursor).
