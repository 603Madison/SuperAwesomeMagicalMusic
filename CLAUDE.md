# Notes for Claude

## Every change ships an APK

The owner installs this app from APKs. **Any change you make must end with a new release**, even
when the request doesn't mention one:

1. Bump `versionCode` (+1) and `versionName` in `app/build.gradle.kts`. Android won't install an
   update whose `versionCode` isn't higher than the installed one. That `versionName` is the whole
   project's version: the server and its container image use it too, and so does the Linux desktop
   app: set the same `version` in `desktop/Cargo.toml` (the release checks they match).
2. Run `./gradlew testDebugUnitTest lintDebug assembleRelease :server:test`; all must pass (it covers
   both apps; `connect`'s tests run the real server in-process). When
   `web/` changed, also `cd web && npm ci && npm run build` (it type-checks the web player).
3. Write the release notes to `release-notes/vX.Y.Z.md`, then commit and push. Start the
   **Release APK** workflow (`.github/workflows/release.yml`, workflow_dispatch) on the branch
   with `version: vX.Y.Z`. It runs the tests, builds the signed APK, creates the tag and publishes
   the GitHub release with both apps attached (`SuperAwesomeMagicalMusic-vX.Y.Z.apk`, the app that
   signs in to the server, from `connect/`; `SuperAwesomeMagicalMusic-Lite-vX.Y.Z.apk`, from `app/`), then (its `fdroid`
   job) republishes the F-Droid repo on GitHub Pages (see `fdroid/README.md`) and (its `image`
   job) pushes the server's container image to `ghcr.io/603madison/superawesomemagicalmusic`,
   and (its `desktop` job) adds the Linux desktop app's packages
   (`SuperAwesomeMagicalMusic-Desktop-vX.Y.Z-amd64.deb` and `-x86_64.rpm`).
   Check that all four jobs succeed. (Claude sessions can't push tags; pushing a `v*` tag also triggers the workflow.)
4. Copy `connect/build/outputs/apk/release/connect-release.apk` to `SuperAwesomeMagicalMusic-vX.Y.Z.apk` and
   `app/build/outputs/apk/release/app-release.apk` to `SuperAwesomeMagicalMusic-Lite-vX.Y.Z.apk` in the
   scratchpad and send them to the owner in chat, and the desktop packages too when `desktop/`
   changed (`desktop/target/debian/*.deb` and `desktop/target/generate-rpm/*.rpm`, renamed like the
   release's).
5. When `desktop/` changed: `cd desktop && cargo test && cargo build --release`, then
   `cargo deb --no-build` and `cargo generate-rpm` (Rust, and `libgtk-4-dev libadwaita-1-dev
   libgstreamer1.0-dev libssl-dev` to build; see `desktop/README.md`).

## Signing

Every release must be signed with the same key, or installed copies can't be updated without
uninstalling. Never generate a replacement key. The key is not in git: CI reads it from the
`RELEASE_KEYSTORE_BASE64` and `RELEASE_KEYSTORE_PASSWORD` repository secrets, and local builds
read `keystore/release.properties` (git-ignored) when it exists. If the key isn't available
locally, build the APK through the release workflow.

R8 shrinking is off for release builds (see `app/build.gradle.kts`) until a shrunk build has been
tried on a device.

## Building in a Claude cloud sandbox

- Android SDK: install command-line tools to `/opt/android-sdk`, then
  `sdkmanager "platforms;android-36" "build-tools;36.0.0" "platform-tools"`, and write
  `sdk.dir=/opt/android-sdk` to `local.properties` (git-ignored).
- `repo.maven.apache.org` may answer HTTP 429. Serve Maven Central from Google's mirror with
  `~/.gradle/init.d/central-mirror.gradle`:

  ```groovy
  def mirror = "https://maven-central.storage-download.googleapis.com/maven2/"
  settingsEvaluated { settings ->
      [settings.pluginManagement.repositories, settings.dependencyResolutionManagement.repositories].each { repos ->
          repos.removeAll { it instanceof MavenArtifactRepository && it.url.toString().contains("repo.maven.apache.org") }
          def r = repos.maven { url mirror; name "CentralMirror" }
          repos.remove(r)
          repos.addFirst(r)
      }
  }
  ```

- Docker Hub may answer HTTP 429; pull base images from `mirror.gcr.io/library/…` and tag them.
  Builds that download need `--network host --build-arg HTTPS_PROXY=$HTTPS_PROXY`.
- Robolectric downloads Android jars itself. Before running tests, add
  `-Drobolectric.dependency.repo.url=https://maven-central.storage-download.googleapis.com/maven2`
  to `JAVA_TOOL_OPTIONS`.
