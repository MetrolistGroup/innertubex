# Releasing

Git tags are validated by `.github/workflows/release.yml`, and JitPack builds
the public artifacts directly from those tags. No registry account, publishing
token, namespace registration, or GPG key is required.

## One-Time Setup

1. Create the public `MetrolistGroup/innertubex` GitHub repository.
2. Enable GitHub private vulnerability reporting.
3. Protect the `main` branch and require the CI workflow.
4. Confirm the repository is visible at https://jitpack.io/#MetrolistGroup/innertubex.

## Release Checklist

1. Move entries from `Unreleased` in `CHANGELOG.md` into a versioned section.
2. If the release intentionally changes public API, regenerate its baseline:

   ```bash
   ./gradlew desktopApiDump
   git diff -- api/desktop/innertubex.api
   ```

   Review the generated signatures and commit the API baseline with the source
   change. Do not edit the baseline manually. If no public API change is
   intended, any baseline diff must be investigated rather than accepted.
3. Run `./gradlew ktlintFormat allTests ktlintCheck apiCheck assemble publishToMavenLocal`.
   Before changing the QuackJS pin, verify a current Web player with QuackJS's
   full-player oracle, then run the opt-in harness against `WEB_REMIX` and
   `WEB_REMIX_SABR` (for example `--id TwCeBl6tDoQ --client WEB_REMIX,WEB_REMIX_SABR --seconds 5`).
   Record the player hash and sanitized result; fixture parity alone does not
   establish live extraction or media delivery. Live checks remain manual.
4. Simulate the tagged JitPack publication locally:

   ```bash
   ./gradlew clean allTests ktlintCheck apiCheck assemble publishToMavenLocal \
       -PGROUP=com.github.MetrolistGroup \
       -PPOM_ARTIFACT_ID=innertubex \
       -PVERSION_NAME=vMAJOR.MINOR.PATCH \
       --no-build-cache --no-configuration-cache
   ```

5. Commit the release changes through normal review.
6. Create and push an annotated `vMAJOR.MINOR.PATCH` tag.
7. Wait for the GitHub release workflow to finish.
8. Wait about a minute after pushing the tag, then request it from JitPack and
   verify its build log, POM, Android AAR, desktop JAR, sources, documentation,
   and Gradle module metadata. If the build fails, follow
   [Failed JitPack Builds](#failed-jitpack-builds).
9. Test the published coordinate in a clean consumer before announcing it.

JitPack exposes `GROUP`, `ARTIFACT`, and `VERSION` to `jitpack.yml`. The build
maps those values to Gradle publication properties so generated publications
are exposed by JitPack as
`com.github.MetrolistGroup.innertubex:innertubex:<tag>` for KMP consumers.

## Failed JitPack Builds

JitPack builds a tag once, on the first request for it, and caches the result.
A failed build stays failed: requesting the tag again returns the cached error,
and only a logged-in JitPack maintainer can clear it. A tag whose JitPack build
failed is usually burned. This has happened four times, each time from a cause
outside the library code:

- v0.8.0 to v0.8.2: JitPack's SDKMAN OpenJDK 21.0.2 could not open jar files on
  fresh-clone workers. `jitpack.yml` now downloads Temurin 21 instead.
- v0.8.5: a timing-sensitive QuickJS idle-release test timed out on the worker.
- v0.9.0: the worker failed to resolve the foojay settings plugin from any
  repository, the first network fetch of the build.

`jitpack.yml` retries the whole build up to three times, 30 seconds apart, so a
transient worker failure no longer burns the tag. A failure on every attempt is
real and must be fixed in the repository.

Check the build without triggering it early. Requesting a tag in the same
command that pushes it can cache `tagNotFound` for several minutes.

```bash
curl -s https://jitpack.io/api/builds/com.github.MetrolistGroup/innertubex/vMAJOR.MINOR.PATCH
curl -s https://jitpack.io/com/github/MetrolistGroup/innertubex/vMAJOR.MINOR.PATCH/build.log
```

`"status" : "ok"` with a module list means it published. `"status" : "Error"`
means it failed, and the build log shows why.

If a tag failed:

1. Never move, delete, or re-push the tag. JitPack and consumers may already
   have cached it.
2. Unblock consumers right away by pinning the tag's 10-character commit hash
   (for example `innerTubeXVersion=5c2f6dc930`). JitPack builds a commit hash
   as its own version, on a fresh request, with no cached failure.
3. Fix the cause if it is in the repository, then release the next patch
   version with a `CHANGELOG.md` entry saying which version it republishes.
4. Move consumers from the commit hash to the new tag once JitPack reports it
   as `ok`.
