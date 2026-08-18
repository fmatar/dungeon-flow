---
name: Quarkus + SvelteKit single container
description: Package a Quarkus backend and a SvelteKit frontend as ONE container image, and make it survive being deployed behind a path prefix on a read-only non-root platform. Use when building or deploying a Quarkus + Svelte/SvelteKit app, adding a frontend to a Quarkus service, serving a SPA from Quarkus, when a deployed UI shows a blank page or 404s its assets, when assets resolve to the wrong origin, or when a deployed SSE stream silently stops updating.
version: 1.0.0
---

# Quarkus + SvelteKit as one container

One image, one process: Quarkus serves the SPA at `/` and the API under `/api`. No nginx sidecar, no
second container, no reverse proxy.

This is not merely tidier. On managed container platforms (DataRobot's Workload API, most PaaS) the
container runs **non-root on a read-only root filesystem**, and stock nginx cannot start there at all:

```
20-envsubst-on-templates.sh: ERROR: /etc/nginx/conf.d is not writable
nginx: [emerg] mkdir() "/var/cache/nginx/client_temp" failed (13: Permission denied)
```

No configuration fixes that. Deleting the sidecar is the fix, and it also makes the image smaller and
the deploy simpler.

## The five things that will bite you

Work through these in order. Each has failed in production in a way that pointed at the wrong cause.

### 1. The API and the SPA must not fight over `/`

Move every REST endpoint under `/api` with a **build-time** property, leaving `/` for the UI:

```properties
quarkus.rest.path=/api
```

Pick that prefix because it is what the frontend already calls in dev through the Vite proxy — then
the two halves line up with **no rewriting anywhere**. Consequences to handle in the same commit:

- The Vite dev proxy must **not** strip `/api` (`rewrite` removed), or every dev call 404s.
- Any test using REST-assured must use the prefix. `RestAssured.basePath` is **not** usable under
  `@QuarkusTest` — the extension resets it per test from `quarkus.http.root-path`, so a fix that looks
  right fails identically. Put the prefix at each call site.

### 2. The UI build is not committed, so a forgotten copy step ships a blank page

The SPA output is copied into `src/main/resources/META-INF/resources/`, and that path belongs in
`.gitignore`. Forgetting the copy produces a **working API behind a blank page with no error** — the
build succeeds, so people lose evenings.

Do not document the trap. Make it impossible to ship: read the shell at startup and refuse to boot.

```java
if (content == null) {
    throw new IllegalStateException(SHELL + " is missing from the classpath. "
            + "Build the UI first: npm --prefix web run build "
            + "&& cp -R web/build/. src/main/resources/META-INF/resources/");
}
```

A loud startup failure that names the fix beats a silent blank page. Wrap the whole build in one
script so the step cannot be skipped — see `references/build-script.md`.

### 3. Client-side routes need a fallback, but a narrow one

`adapter-static` with `fallback: index.html` puts only one file on disk, so a hard refresh on `/race`
404s. Serve the shell for unmatched paths, and keep the rule tight:

- **GET only** — a mistyped POST should still get a real 404/405.
- **never** `/api` or `/q` (Quarkus management).
- **never a path containing a dot** — a missing `/_app/foo.js` must 404 honestly rather than return
  HTML. That mis-signal is genuinely painful to debug in a browser.

### 4. Mount-point awareness — the one that looks impossible

Platforms mount the app under a prefix (`/api/v2/endpoints/workloads/<id>/`), **strip it inbound**,
and **never re-add it** to responses. So the container sees `/api/dungeon` while the browser sees the
prefixed URL. Every URL the app *emits* must carry the prefix or the browser resolves it against the
origin and leaves the app.

**Do not set `quarkus.http.root-path` to the prefix.** That changes where the app *listens*; the edge
already stripped it, so everything 404s. Inbound routing is already correct — only outbound URLs are
wrong.

**Fixing asset URLs is not sufficient.** SvelteKit compiles `base` into the client bundle. Left as
`''`, the router rejects even the first URL:

```
Not found: /api/v2/endpoints/workloads/<id>/
```

The page stays blank however correct the assets are, and **no HTML `<base href>` can repair a value
baked into JavaScript.**

So build with a sentinel and substitute the real mount path at startup:

| Piece | Role |
|---|---|
| `vite.config.ts` | `kit.paths.base = '/__DR_BASE__'`, **only when `command === 'build'`** |
| a startup route | substitutes the sentinel into the shell and every text asset under `/_app` |
| the env var | derive the prefix from whatever the platform injects (e.g. `WORKLOAD_ID`); absent it, substitute `""` |

Gate the sentinel on `command === 'build'` or `vite dev` serves the app at
`http://localhost:5173/__DR_BASE__` with a 404 at the root — a confusing way to start a morning.

With no prefix the substitution collapses to `""` and every URL is root-absolute, so **local and
deployed share one code path**. Full mechanism and the Java: `references/mount-awareness.md`.

### 5. Two frontend rules that only fail once deployed

Both are invisible locally, where `base` is `''`:

```svelte
<!-- ✗ escapes the mount entirely -->
<a href="/race">race</a>
<!-- ✓ -->
<a href="{base}/race">race</a>
```

SvelteKit does **not** rewrite `href` attributes. The same applies to any comparison against
`page.url.pathname`, which *includes* the base — comparing it to a bare `/race` silently stops
matching, and things like active-nav highlighting quietly break.

And every fetch:

```ts
import { base } from '$app/paths';
const BASE = `${base}/api/things`;
```

A literal `/api/things` resolves against the origin and hits the *platform's* own API, which returns
plausible-looking JSON and is deeply confusing to debug.

## SSE: buffer the stream or it dies silently

If the backend pushes Server-Sent Events from a Mutiny `BroadcastProcessor`, add an overflow strategy.
Two frames emitted **back-to-back from one thread** kill an unbuffered stream:

```
BackPressureFailure: Could not emit item downstream due to lack of requests
```

```java
Multi<Event> live = bus.filter(e -> id.equals(e.instanceId()))
        .onOverflow().buffer(256);
```

The symptom is a UI that **silently stops updating**, which reads as an application-logic bug rather
than a transport one. Also replay enough state for a **late subscriber** (a refresh, a projector
opened mid-session) to render the current situation, not just the latest single frame.

## Native builds find bugs your tests cannot

If a native image is in scope, register every REST response type for reflection:

```java
@RegisterForReflection
public record StartResponse(String id, View view) {}
```

Without it, endpoints compile, routes resolve, the entire test suite passes, and **every response 500s
at runtime only in native**:

```
Jackson was unable to serialize type '...$StartResponse' ... you may need to configure reflection
```

Treat a native build as a static analysis pass. Run it in CI or it finds these for you in production.

## Verify like this, not by opening the root

At the root everything works, which is exactly why these bugs ship. Serve the built image behind a
**path-prefixing proxy** locally and check four things:

1. assets load with correct MIME types (a `.css` returning `text/html` is a 404 page in disguise);
2. the API answers under the prefix;
3. a **hard refresh** on a client route renders;
4. a real end-to-end interaction completes.

`references/verify-behind-prefix.md` has the nginx config and the checks.

## References

- `references/mount-awareness.md` — the sentinel/substitution mechanism, with Java
- `references/build-script.md` — one script that makes the copy step unskippable
- `references/verify-behind-prefix.md` — reproducing a mount prefix locally
