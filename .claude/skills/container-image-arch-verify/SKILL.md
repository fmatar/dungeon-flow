---
name: Container image architecture and publishing
description: Build, verify and publish container images that actually run on the target architecture — multi-arch JVM images, single-arch GraalVM native images, and GHCR publishing. Use when building or pushing container images from an ARM Mac, building a GraalVM native image, when a container crash-loops with "exec format error", when a deployment sits in ImagePullBackOff, or before publishing an image to a registry.
version: 1.0.0
---

# Container image architecture and publishing

Two failure modes dominate, and both produce an image that looks perfectly fine locally:

1. **Wrong architecture.** Apple Silicon builds `arm64`; most platforms run `amd64`. The container
   crash-loops instantly with `exec format error`.
2. **An image that lies about itself.** The *binary's* architecture and the *image manifest's*
   architecture are set **separately**. Get the first right and the second wrong and you ship an image
   whose manifest says `arm64` around an `x86-64` entrypoint. **Neither the image size nor a local run
   reveals this.**

Never conclude an image is correct because it built, because it is the right size, or because it ran on
your machine. Verify the three facts below.

## JVM images: multi-arch, and architecture stops mattering

A JVM image layers jars over a base image, so one tag can carry both architectures:

```bash
mvn clean package -DskipTests \
  -Dquarkus.container-image.build=true \
  -Dquarkus.container-image.push=true \
  -Dquarkus.docker.buildx.platform=linux/amd64,linux/arm64
```

`amd64` is what the platform needs; keeping `arm64` in the same manifest means the tag still runs
natively on an ARM Mac. Prefer this whenever native is not specifically required — it removes the
entire class of problem.

## Native images: single architecture, two separate decisions

**GraalVM does not cross-compile.** The binary targets the architecture it was built on. Two things
must independently be `amd64`:

| # | Thing | How |
|---|---|---|
| 1 | the **binary** | build inside a Linux builder container, emulated for a foreign arch: `-Dquarkus.native.container-build=true -Dquarkus.native.container-runtime-options=--platform=linux/amd64` |
| 2 | the **image manifest** | `docker buildx build --platform linux/amd64` — a plain `docker build` on an ARM host stamps `arm64` |

Letting Maven build the *image* for a native binary is the trap: it tags with the **host** arch. Build
the binary with Maven, then assemble the image yourself:

```bash
docker buildx build --platform linux/amd64 \
  -f src/main/docker/Dockerfile.native \
  -t ghcr.io/OWNER/APP:native-amd64 --load .
```

`--load` (rather than `--push`) puts the exact image in the local daemon so you can verify and
smoke-test *the artifact you will push*, then push that same image.

**Emulated `native-image` is fragile.** Expect ~10× the build time, and it can die mid-analysis:

```
Failed to read /project/APP-runner.jar
Class initialization of io.quarkus.runner.ApplicationImpl failed
```

Retry once with a clean `target/` (a mixed-architecture target dir is a plausible culprit). If it fails
again, **build on an amd64 host (CI)** — do not debug emulation. Also give Docker ≥8 GB; the default
2 GB fails the build.

## The three-way verification — run it before every push

Check the manifest, the layout, and the binary's real ELF arch. Extract the binary with
`docker create` + `docker cp` so it is **never executed** and emulation cannot mask a mismatch:

```bash
IMG=ghcr.io/OWNER/APP:native-amd64
docker image inspect "$IMG" --format 'arch={{.Architecture}}/{{.Os}}'
CID=$(docker create "$IMG")
docker cp "$CID:/work/application" /tmp/bin >/dev/null 2>&1 && echo "layout=NATIVE"
docker rm "$CID" >/dev/null
file -b /tmp/bin | cut -d, -f1-2
```

Expect all three to agree:

```
arch=amd64/linux
layout=NATIVE
ELF 64-bit LSB executable, x86-64
```

`arch=arm64/linux` next to `x86-64` is the lying manifest. Automate this in your build script and
**refuse to push on a mismatch** — a human will not run it every time.

> Do not verify image contents with `unzip`/`which` inside a minimal runtime image. They are often
> absent, so every probe returns "not found" and you conclude something false. Run the container and
> query it over HTTP, or extract files to the host and inspect there.

## Tagging: mutable tags will cost you a deploy

`:latest` is convenient and repeatedly wrong:

- **Never push a native (single-arch) image to a tag a multi-arch JVM deployment uses.** That tag
  becomes single-arch and silently reassigns what the other deployment pulls.
- **Platforms cache mutable tags.** A start or restart can come up on the *old* image while reporting
  healthy. See the amendment notes in `references/publishing.md`.

Tag per commit — `:native-amd64-$(git rev-parse --short HEAD)` — and make deploys reference that. It
turns "hope the cache missed" into a deterministic deploy.

Build scripts should **hard-refuse** to build a native image onto a shared mutable tag rather than
warning about it.

## Publishing to GHCR

New packages are **private**, and a private image is the most common cause of `ImagePullBackOff` — the
error rarely says so. Details and the visibility walkthrough: `references/publishing.md`.

**Verify pullability anonymously.** Your own `docker pull` succeeds either way because you are logged
in, which is exactly how private images reach production unnoticed:

```bash
docker manifest inspect ghcr.io/OWNER/APP:TAG | grep '"architecture"' | sort -u
```

Two architectures for a JVM image, one for native. No credentials involved in that command — that is
the point.

## References

- `references/publishing.md` — GHCR auth, package visibility, forking a project to a new namespace
