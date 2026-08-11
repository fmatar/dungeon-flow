package org.acme.dungeon;

import io.quarkus.runtime.StartupEvent;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.Router;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

/**
 * Serves the SvelteKit SPA's {@code index.html} for client-side routes so the whole game ships as a
 * single container (SRS constraint C-3) with no nginx sidecar.
 *
 * <p>The UI is built with {@code adapter-static} in fallback mode: only {@code /index.html} exists on
 * disk, and routes like {@code /race} are resolved by the browser-side router. A plain static file
 * server therefore 404s a hard refresh on {@code /race}. This route is the missing rewrite.
 *
 * <p>Deliberately narrow - it only rewrites what cannot be anything else:
 *
 * <ul>
 *   <li>GET only, so a mistyped POST still gets a real 405/404 instead of an HTML page;</li>
 *   <li>never {@code /api/*} (the game API) or {@code /q/*} (Quarkus dev/management endpoints);</li>
 *   <li>never a path containing a dot, so a missing {@code /_app/foo.js} 404s honestly instead of
 *       silently returning HTML - that mis-signal is painful to debug in a browser.</li>
 * </ul>
 *
 * This is infrastructure, not game logic: it decides nothing about where a player goes next, so
 * constraint C-1 still holds.
 */
@ApplicationScoped
public class SpaFallbackRoute {

    /** Runs after Quarkus' own static-resource and REST handlers have had their chance. */
    private static final int ORDER = 900_000;

    void install(@Observes StartupEvent startup, Router router) {
        router.route().order(ORDER).handler(ctx -> {
            String path = ctx.normalizedPath();
            if (ctx.request().method() == HttpMethod.GET
                    && !path.startsWith("/api")
                    && !path.startsWith("/q/")
                    && path.indexOf('.') < 0) {
                ctx.reroute("/index.html");
            } else {
                ctx.next();
            }
        });
    }
}
