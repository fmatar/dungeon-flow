# One script so the copy step cannot be skipped

The UI output must be copied into `src/main/resources/META-INF/resources/` before Maven packages the
jar, and that path is gitignored. A human will forget. Put the whole sequence in one script and make
that the documented path.

## The sequence

```bash
npm --prefix web run build                                  # or pnpm
mkdir -p src/main/resources/META-INF/resources
rm -rf src/main/resources/META-INF/resources/*              # see "wipe first" below
cp -R web/build/. src/main/resources/META-INF/resources/
mvn clean package -DskipTests -Dquarkus.container-image.build=true
```

## Non-obvious details, each learned the hard way

**Wipe the target before copying.** Asset filenames are content-hashed, so a plain copy leaves every
previous build's orphaned JS/CSS behind and the image grows forever. Guard the path so a mangled
variable can never `rm` something else:

```bash
case $STATIC_DIR in
    "$REPO_ROOT"/src/main/resources/META-INF/resources) rm -rf "$STATIC_DIR" ;;
    *) die "refusing to delete unexpected path: $STATIC_DIR" ;;
esac
```

**Re-exec under bash.** `sh script.sh` bypasses the shebang. Testing `BASH_VERSION` is **not enough**:
macOS `/bin/sh` *is* bash in POSIX mode, so it sets `BASH_VERSION` while still rejecting process
substitution. Use a sentinel, above `set -o pipefail` (not POSIX), in POSIX syntax:

```sh
if [ -z "${MYSCRIPT_REEXECED:-}" ]; then
    if command -v bash >/dev/null 2>&1; then
        MYSCRIPT_REEXECED=1; export MYSCRIPT_REEXECED
        exec bash "$0" "$@"
    fi
    echo "error: this script needs bash." >&2; exit 1
fi
```

**Verify over `127.0.0.1`, never `localhost`.** macOS resolves `localhost` to `::1` first. A dev server
holding the IPv6 side of the same port will answer instead of your container, and you will debug the
wrong process.

**Auto-pick a free host port.** `mvn quarkus:dev` usually holds 8080, and publishing a busy port aborts
`docker compose up` before any container starts. Then ask Docker where it *actually* published
(`docker compose port`) rather than trusting your own variable.

**Assert the JDK the pom demands.** Read `maven.compiler.release` from the pom and locate a matching
JDK (`JAVA_HOME`, `/usr/libexec/java_home`, SDKMAN). A lower JDK fails deep in the build with
`release version 25 not supported`, which reads like a Maven bug rather than a JDK mismatch.

**Smoke-test by exercising the app, not by curling `/`.** A 200 on the root proves a file was served.
Drive one real end-to-end interaction and assert the outcome — that proves the backend actually works
inside the container. Report measured image size, startup time and RSS at the end; those numbers are
what people quote.
