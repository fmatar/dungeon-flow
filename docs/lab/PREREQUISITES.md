# Lab prerequisites — do this *before* the session

**Time needed: ~15 minutes, mostly downloads.** Please finish this beforehand. Installing a JDK
during the session costs the whole room 20 minutes.

Everything is macOS/Homebrew below. On Linux, use your package manager plus
[SDKMAN](https://sdkman.io) for the JDK; on Windows use WSL2.

---

## 1. Install the toolchain

```bash
brew install --cask graalvm-jdk@25 docker-desktop && brew install maven node pnpm jq
```

| Tool | Why |
|---|---|
| **GraalVM 25** | The project compiles with `release=25`. GraalVM is a full JDK *and* unlocks native builds. |
| **Docker Desktop** | Container builds + Compose. **Start it** after installing. |
| Maven, Node, pnpm | Build the backend and the UI. Node is required even for backend-only builds — the UI is compiled into the jar. |
| jq | Pretty-prints the curl output we'll use. |

Point `JAVA_HOME` at GraalVM and add it to your shell profile:

```bash
echo 'export JAVA_HOME=$(/usr/libexec/java_home -v 25)' >> ~/.zshrc && source ~/.zshrc
```

## 2. Give Docker enough memory

Native compilation is memory-hungry. **Docker Desktop → Settings → Resources → Memory: 8 GB
minimum.** The default 2 GB will fail the native build in module 6.

## 3. A GitHub account with a container registry

You'll publish an image to your own `ghcr.io` namespace in module 7.

```bash
brew install gh && gh auth login
```

```bash
gh auth refresh -h github.com -s write:packages
```

```bash
gh auth token | docker login ghcr.io -u YOUR_GITHUB_USERNAME --password-stdin
```

Expect `Login Succeeded`.

## 4. Clone the repo

```bash
git clone https://github.com/fmatar/dungeon-flow.git && cd dungeon-flow
```

## 5. Pre-warm the caches — the step people skip

This downloads Quarkus, the npm tree and the native builder image. On conference wifi it is the
difference between a smooth lab and 20 minutes of watching progress bars.

```bash
mvn -q dependency:go-offline && pnpm --dir web install
```

```bash
docker pull quay.io/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25
```

---

## Verify you're ready

```bash
java -version 2>&1 | head -1 && mvn -v | head -1 && node -v && docker info --format '{{.ServerVersion}}' && docker login ghcr.io 2>&1 | tail -1
```

You should see **Java 25**, Maven 3.9+, Node 20+, a Docker server version, and an existing GHCR
login. If any line is missing or errors, fix it before the session or flag it early.

**Bring:** your laptop, a terminal, an IDE (optional — the repo ships IntelliJ Checkstyle and
google-java-format config), and a browser.

**You do not need** a DataRobot account. Module 9 is driven from the facilitator's machine; you'll
author the deployment spec locally and watch it go live.
