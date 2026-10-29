# Notes for Claude

## Every change ships an APK

The owner installs this app from APKs. **Any change you make must end with a new release**, even
when the request doesn't mention one:

1. Bump `versionCode` (+1) and `versionName` in `app/build.gradle.kts`. Android won't install an
   update whose `versionCode` isn't higher than the installed one.
2. Run `./gradlew testDebugUnitTest lintDebug assembleRelease`; all must pass.
3. Commit and push. Then push an annotated tag whose message is the release notes:
   `git tag -a vX.Y.Z -m "<notes>" && git push origin vX.Y.Z`. The tag triggers
   `.github/workflows/release.yml`, which runs the tests, builds the signed APK and publishes the
   GitHub release `vX.Y.Z` with `Music-vX.Y.Z.apk` attached. Check that the run succeeds.
4. Copy `app/build/outputs/apk/release/app-release.apk` to `Music-vX.Y.Z.apk` in the scratchpad
   and send it to the owner in chat.

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

- Robolectric downloads Android jars itself. Before running tests, add
  `-Drobolectric.dependency.repo.url=https://maven-central.storage-download.googleapis.com/maven2`
  to `JAVA_TOOL_OPTIONS`.
