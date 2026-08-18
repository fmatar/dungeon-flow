# Verifying behind a path prefix, locally

Mount-prefix bugs are invisible at the domain root — which is precisely why they ship. Reproduce the
edge locally before pushing an image.

## Reproduce the edge

The important behaviour is that the prefix is **stripped** before forwarding. With nginx, the trailing
slash on `proxy_pass` does exactly that:

```nginx
server {
    listen 80;
    location /api/v2/endpoints/workloads/FAKE123/ {
        proxy_pass http://app:8080/;   # trailing slash strips the matched prefix
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_buffering off;           # so SSE streams
    }
}
```

```bash
docker network create edgecheck
docker run -d --name app --network edgecheck --network-alias app -e WORKLOAD_ID=FAKE123 <image>
docker run -d --name edge --network edgecheck -p 18080:80 \
  -v "$PWD/edge.conf:/etc/nginx/conf.d/default.conf:ro" nginx:alpine
```

## The four checks

Against `http://127.0.0.1:18080/api/v2/endpoints/workloads/FAKE123/`:

1. **Startup log names the mount point** — the cheapest signal that substitution ran.
2. **Every asset the shell requests returns 200 with the right MIME type.** A `.css` coming back as
   `text/html` is a 404 page in disguise, and it is what produces
   `Refused to apply style ... MIME type ('text/html') is not a supported stylesheet MIME type`.
   ```bash
   for u in $(curl -s "$P/" | grep -oE '"/api/v2/endpoints/workloads/FAKE123/_app/[^"]+"' | tr -d '"' | sort -u); do
     curl -s -o /dev/null -w "%{http_code} %{content_type} $u\n" "http://127.0.0.1:18080$u"
   done
   ```
3. **A hard refresh on a client route** (`$P/race`) returns `200 text/html`.
4. **A real interaction completes** through the prefix, including SSE if used.

Then load it in an actual browser and check the console. Two failures only appear there:

- the client router rejecting the first URL (`Not found: /api/v2/...`) — assets fine, page blank;
- `href`s that escape the mount, which look correct in the HTML and only misbehave when clicked.

## Grep the built output before you even run it

```bash
grep -c '__DR_BASE__' web/build/index.html          # expect > 0 before substitution
grep -rl '__DR_BASE__' web/build/_app | wc -l        # the router's base lives in a chunk
```

If the sentinel is absent from the build, the vite `paths.base` gate is wrong and no amount of
server-side substitution will help. If it is still present in what the **server** returns, the
substitution did not run — check the constant matches on both sides.
