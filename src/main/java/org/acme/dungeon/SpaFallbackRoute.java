package org.acme.dungeon;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.quarkus.runtime.StartupEvent;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

/**
 * Serves the SvelteKit SPA, made <em>mount-point aware</em>, so the whole game ships as one container
 * (SRS constraint C-3) and still works when it is not served from the domain root.
 *
 * <h2>The problem</h2>
 *
 * The DataRobot Workload API publishes a workload under a path prefix
 * ({@code /api/v2/endpoints/workloads/<id>/}) and its edge gateway:
 *
 * <ol>
 *   <li><b>strips the prefix inbound</b> - the container receives {@code /api/dungeon}; and</li>
 *   <li><b>does not rewrite responses</b> - it never re-adds the prefix to asset URLs, and supplies
 *       no trustworthy {@code X-Forwarded-Prefix}.</li>
 * </ol>
 *
 * So the app must <b>emit</b> URLs carrying the prefix while continuing to <b>match</b> requests that
 * arrive without it.
 *
 * <p>Note what this class deliberately does <b>not</b> do: it never touches inbound routing. Setting
 * {@code quarkus.http.root-path} to the prefix is the tempting fix and is wrong - Quarkus would then
 * expect a prefix the edge has already stripped, and everything would 404. Inbound is already
 * correct; only outbound URLs were broken.
 *
 * <h2>Why a startup substitution, and not a {@code <base href>}</h2>
 *
 * A {@code <base>} tag fixes relative URLs, but SvelteKit compiles its own {@code base} into the
 * client bundle, and the router compares the real pathname against it. Left as {@code ""} the router
 * rejects even the first URL with {@code "Not found: /api/v2/endpoints/workloads/<id>/"} - the page
 * stays blank however correct the assets are. Injecting HTML cannot fix a value baked into JS.
 *
 * <p>So the UI is built with a sentinel base ({@code /__DR_BASE__}, see {@code web/vite.config.ts})
 * and this class substitutes the real mount path into every text asset at startup. That keeps
 * <b>one</b> image deployable anywhere - the alternative, baking the prefix in at build time, needs
 * the workload id before the workload exists and would bind an image to a single deployment.
 *
 * <p>The prefix is derived from {@code WORKLOAD_ID}, which DataRobot injects into every workload
 * container, so nothing has to be plumbed in and it survives rebuilds and rolling replacements. With
 * no {@code WORKLOAD_ID} (local Compose, {@code quarkus:dev}, tests) the substitution collapses to the
 * empty string and every URL is root-absolute exactly as before - one code path everywhere.
 *
 * <p>This is infrastructure, not game logic: it decides nothing about where a player goes next, so
 * constraint C-1 still holds.
 */
@ApplicationScoped
public class SpaFallbackRoute {

    private static final Logger LOG = LoggerFactory.getLogger(SpaFallbackRoute.class);

    /** Classpath root that Quarkus also serves static resources from. */
    private static final String RESOURCE_ROOT = "META-INF/resources";
    private static final String SHELL = RESOURCE_ROOT + "/index.html";

    /**
     * Build-time placeholder for the mount path, with a leading slash and no trailing slash, matching
     * {@code kit.paths.base} in {@code web/vite.config.ts}. Must stay in sync with it.
     */
    private static final String SENTINEL = "/__DR_BASE__";

    /** Serve ahead of Quarkus' static-resource handler so the substituted copies win. */
    private static final int REWRITE_ORDER = -1_000;

    /** The catch-all runs last, after static resources and REST have had their chance. */
    private static final int FALLBACK_ORDER = 900_000;

    /**
     * Explicit override for the mount path, without a trailing slash. Needed only for the proton-id
     * endpoint, whose prefix differs from the workload-id one and changes on every replacement, so it
     * cannot be derived. Leave unset for the normal browser-facing endpoint.
     */
    @ConfigProperty(name = "dungeon.base-path")
    Optional<String> explicitBasePath;

    /** Injected by DataRobot into every workload container; absent locally. */
    @ConfigProperty(name = "workload.id")
    Optional<String> workloadId;

    /** Substituted text assets, keyed by request path. Bounded by the number of files shipped. */
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    private String prefix;
    private String shell;

    void install(@Observes StartupEvent startup, Router router) {
        prefix = resolvePrefix();
        shell = substitute(read(SHELL));

        if (prefix.isEmpty()) {
            LOG.info("Serving the UI from the domain root (no WORKLOAD_ID; local or dev run)");
        } else {
            LOG.info("Serving the UI under mount point '{}' (derived from WORKLOAD_ID)", prefix);
        }

        // Exact hits on the shell, ahead of the static handler so the substituted copy wins.
        router.get("/").order(REWRITE_ORDER).handler(this::sendShell);
        router.get("/index.html").order(REWRITE_ORDER).handler(this::sendShell);

        // Text assets carry the sentinel too - the router's `base` lives in one of the JS chunks -
        // so they cannot be served straight off the classpath.
        router.get("/_app/*").order(REWRITE_ORDER).handler(this::sendAsset);

        // Client-side routes (e.g. /race) have no file on disk. Deliberately narrow:
        //  - GET only, so a mistyped POST still gets a real 404/405;
        //  - never /api (the game API) or /q (Quarkus management endpoints);
        //  - never a path containing a dot, so a missing /_app/foo.js 404s honestly instead of
        //    silently returning HTML - that mis-signal is painful to debug in a browser.
        router.route().order(FALLBACK_ORDER).handler(ctx -> {
            String path = ctx.normalizedPath();
            if (ctx.request().method() == HttpMethod.GET
                    && !path.startsWith("/api")
                    && !path.startsWith("/q/")
                    && path.indexOf('.') < 0) {
                sendShell(ctx);
            } else {
                ctx.next();
            }
        });
    }

    /**
     * The workload's external mount path, or {@code ""} when serving from the root. Never has a
     * trailing slash, so callers can append {@code "/..."} unconditionally.
     */
    private String resolvePrefix() {
        String resolved = explicitBasePath
                .filter(s -> !s.isBlank())
                .or(() -> workloadId
                        .filter(id -> !id.isBlank())
                        .map(id -> "/api/v2/endpoints/workloads/" + id))
                .orElse("")
                .trim();
        while (resolved.endsWith("/")) {
            resolved = resolved.substring(0, resolved.length() - 1);
        }
        return resolved;
    }

    /** Replace the build-time sentinel with the real mount path (or nothing, at the root). */
    private String substitute(String content) {
        return content.replace(SENTINEL, prefix);
    }

    /** Read a required classpath resource, failing loudly with a fix-it message if absent. */
    private String read(String resource) {
        String content = readOrNull(resource);
        if (content == null) {
            // The UI is copied into META-INF/resources by the build; see scripts/run-local.sh.
            // Failing at startup beats serving a blank page with no explanation.
            throw new IllegalStateException(resource + " is missing from the classpath. "
                    + "Build the UI first: npm --prefix web run build "
                    + "&& cp -R web/build/. src/main/resources/META-INF/resources/");
        }
        return content;
    }

    private String readOrNull(String resource) {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        try (InputStream in = cl.getResourceAsStream(resource)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + resource, e);
        }
    }

    private void sendShell(RoutingContext ctx) {
        ctx.response()
                .setStatusCode(200)
                .putHeader("content-type", "text/html;charset=UTF-8")
                // The shell embeds content-hashed asset URLs, so it must never be cached itself.
                .putHeader("cache-control", "no-cache")
                .end(shell);
    }

    /**
     * Serve a text asset with the sentinel substituted. Binary assets (fonts, images) cannot contain
     * the sentinel and are handed to Quarkus' static handler untouched, so they are never read into
     * memory here.
     */
    private void sendAsset(RoutingContext ctx) {
        String path = ctx.normalizedPath();
        String contentType = textContentType(path);
        if (contentType == null || path.contains("..")) {
            ctx.next();
            return;
        }
        // A miss must fall through to a real 404, not the loud "build the UI first" error: that
        // message is right for a missing shell at startup, but for one absent asset it would turn a
        // clean 404 into a 500 and hide the actual problem.
        String body = cache.computeIfAbsent(path, p -> {
            String content = readOrNull(RESOURCE_ROOT + p);
            return content == null ? "" : substitute(content);
        });
        if (body.isEmpty()) {
            cache.remove(path);
            ctx.next();
            return;
        }
        ctx.response()
                .setStatusCode(200)
                .putHeader("content-type", contentType)
                // Everything under /_app is content-hashed by Vite, so it is safe to cache hard.
                .putHeader("cache-control", "public, max-age=31536000, immutable")
                .end(body);
    }

    /** Content type for the text asset kinds that can carry the sentinel, else {@code null}. */
    private String textContentType(String path) {
        if (path.endsWith(".js")) {
            return "text/javascript;charset=UTF-8";
        }
        if (path.endsWith(".css")) {
            return "text/css;charset=UTF-8";
        }
        if (path.endsWith(".json") || path.endsWith(".map")) {
            return "application/json;charset=UTF-8";
        }
        return null;
    }
}
