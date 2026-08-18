# Mount-point awareness: sentinel base + startup substitution

The problem, precisely: the platform mounts the app under a prefix, **strips it inbound**, and **never
re-adds it** to responses. The app must therefore *emit* prefixed URLs while continuing to *match*
unprefixed requests.

## Why the obvious fixes fail

| Attempt | Why it fails |
|---|---|
| `quarkus.http.root-path=<prefix>` | Changes where the app **listens**. The edge already stripped the prefix, so everything 404s. Inbound is already correct. |
| `<base href="/prefix/">` in the HTML | Fixes relative URLs only. SvelteKit emits **root-absolute** asset URLs, and `<base>` has no effect on those. |
| Rewriting only the asset URLs | Assets load, page still blank. SvelteKit compiles `base` into the **client bundle**; left as `''` its router rejects the first URL: `Not found: /api/v2/endpoints/workloads/<id>/`. HTML cannot repair a value baked into JS. |
| `kit.paths.base = '<prefix>'` at build time | The prefix contains a deployment id that does not exist until the deployment does — and it would bind one image to one deployment. |
| Trusting `X-Forwarded-Prefix` | Not supplied by every edge. Verify before depending on it. |

## The mechanism

Build with a sentinel, substitute at container startup from an env var the platform injects.

```ts
// vite.config.ts — a FUNCTION of command, so dev is unaffected
export default defineConfig(({ command }) => ({
  plugins: [sveltekit({
    paths: { base: command === 'build' ? '/__DR_BASE__' : '' },
    adapter: adapter({ fallback: 'index.html' })
  })],
  server: { proxy: { '/api': { target: 'http://localhost:8080', changeOrigin: true } } }
}));
```

Gating on `command === 'build'` is required: set unconditionally, `vite dev` serves the app at
`http://localhost:5173/__DR_BASE__` and 404s the root.

Then substitute server-side at startup, and serve the rewritten copies ahead of the static handler.

```java
private static final String SENTINEL = "/__DR_BASE__";   // must match vite.config.ts
private static final int REWRITE_ORDER = -1_000;         // beat the static-resource handler
private static final int FALLBACK_ORDER = 900_000;       // after REST and static

void install(@Observes StartupEvent startup, Router router) {
    prefix = resolvePrefix();                 // "" when the env var is absent
    shell  = read("META-INF/resources/index.html").replace(SENTINEL, prefix);

    router.get("/").order(REWRITE_ORDER).handler(this::sendShell);
    router.get("/index.html").order(REWRITE_ORDER).handler(this::sendShell);

    // Text assets carry the sentinel too — the router's `base` lives in one of the JS chunks —
    // so they cannot be served straight off the classpath.
    router.get("/_app/*").order(REWRITE_ORDER).handler(this::sendAsset);

    router.route().order(FALLBACK_ORDER).handler(ctx -> {
        String path = ctx.normalizedPath();
        if (ctx.request().method() == HttpMethod.GET
                && !path.startsWith("/api") && !path.startsWith("/q/")
                && path.indexOf('.') < 0) {   // a missing /_app/x.js must 404 honestly
            sendShell(ctx);
        } else {
            ctx.next();
        }
    });
}
```

Derive the prefix from whatever the platform injects, and allow an explicit override for the cases the
derivation cannot cover:

```java
private String resolvePrefix() {
    String resolved = explicitBasePath.filter(s -> !s.isBlank())
            .or(() -> workloadId.filter(id -> !id.isBlank())
                    .map(id -> "/api/v2/endpoints/workloads/" + id))
            .orElse("").trim();
    while (resolved.endsWith("/")) resolved = resolved.substring(0, resolved.length() - 1);
    return resolved;   // "" == serving from the domain root
}
```

## Rules that follow

- **Cache the substituted copies**, keyed by request path — substitution is per-startup, not per-request.
- **Never cache the shell in the browser** (`cache-control: no-cache`): it embeds content-hashed asset
  URLs. The `/_app` assets themselves are hashed, so cache those hard.
- **Serve, don't reroute.** `ctx.reroute("/index.html")` can preserve a 404 status on the response;
  writing the string directly lets you set `200` explicitly.
- **Log the resolved mount point at startup.** It is the fastest possible check that a deploy is
  actually mount-aware:
  `Serving the UI under mount point '/api/v2/endpoints/workloads/<id>' (derived from WORKLOAD_ID)`
- **Keep the sentinel string in exactly two places** (vite config + the Java constant) and say so in a
  comment on both. A mismatch leaves `/__DR_BASE__` in the served HTML and every asset 404s.
