# Publishing: auth, visibility, and pointing a fork at your own namespace

## Authenticate

A token with `write:packages`:

```bash
gh auth refresh -h github.com -s write:packages
gh auth token | docker login ghcr.io -u YOUR_USERNAME --password-stdin
```

Or a [classic PAT](https://github.com/settings/tokens) with `write:packages`:

```bash
echo "$CR_PAT" | docker login ghcr.io -u YOUR_USERNAME --password-stdin
```

## Make the package public

New GHCR packages are private. A private image the platform cannot pull is the single most common
deployment failure, and it surfaces as `ImagePullBackOff` with no mention of permissions.

1. `https://github.com/users/YOUR_USERNAME/packages/container/APP/settings`
2. **Danger Zone → Change visibility → Public**
3. **Connect repository** while you are there, so the package inherits the README and licence.

Then verify with **no credentials**:

```bash
docker manifest inspect ghcr.io/YOUR_USERNAME/APP:latest
```

If you must keep it private, the platform needs image-pull credentials — and note that some managed
platforms **cannot accept pull credentials at workload creation**, in which case the registry must be
one the org pre-configured. Check before you plan around a private image.

References: [Working with the Container registry](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-container-registry)
· [Configuring package visibility](https://docs.github.com/en/packages/learn-github-packages/configuring-a-packages-access-control-and-visibility)

## Pointing a fork at your own namespace

Keep image coordinates in **one** place and pass them explicitly on the command line so the build is
self-describing:

```properties
quarkus.container-image.registry=ghcr.io
quarkus.container-image.group=YOUR_USERNAME
quarkus.container-image.name=APP
quarkus.container-image.tag=latest
```

**Do not default the registry namespace to a hardcoded owner in a build script.** On a fork that
silently builds and pushes under someone else's account. Fail with instructions instead:

```bash
if [[ -z $IMG_REG || -z $IMG_GRP ]]; then
    die "image coordinates are not set in application.properties. Add:
      quarkus.container-image.registry=ghcr.io
      quarkus.container-image.group=<your-github-username>"
fi
```

Also set `build`/`push` **only on the command line**, never in committed properties. Setting them in
`application.properties` means a plain `mvn package` builds a Docker image and pushes it to a public
registry as a side effect — a nasty surprise for anyone cloning the repo.

Then update every manifest that carries a literal image reference: deployment specs, compose files,
CI workflows. Grep for the registry host to find them all.

## Deploying a new image: mutable tags do not redeploy themselves

Pushing a tag changes nothing about what is running. Two behaviours to plan around:

- **A restart may reuse a cached image.** A platform `start`/`stop` cycle can come up on the *old*
  image while reporting `running` and healthy. Do not treat "running" as "updated".
- **Verify what is actually serving**, rather than assuming. The strongest cheap signal is a
  content-derived value baked into the artifact — a hashed frontend asset filename, a build-info
  endpoint, a startup log line. Compare it to the image you just pushed:

```bash
# expected, from the image you pushed
CID=$(docker create ghcr.io/OWNER/APP:TAG); docker cp "$CID:/path/index.html" /tmp/i.html; docker rm "$CID"
grep -oE 'entry/start\.[^.]+\.js' /tmp/i.html | head -1
# actual, from the deployment
curl -s "$ENDPOINT/" | grep -oE 'entry/start\.[^.]+\.js' | head -1
```

Identical hashes mean byte-identical builds. Different ones mean a cached pull, and no amount of
"status: running" changes that.

The durable fix is an **immutable tag per commit**, so the deployment references a tag that can only
ever mean one image.
