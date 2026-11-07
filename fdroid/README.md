# F-Droid repository

The app has its own F-Droid repository, published to GitHub Pages:

- Address: `https://603madison.github.io/SuperAwesomeMagicalMusic-Standalone/repo`
- Fingerprint: `ED2C0523BC9120F63952EAE882FA4C0FBD3153967130C0A4ADF1AF98D07B5283`
- Page with an "add" link and QR code: https://603madison.github.io/SuperAwesomeMagicalMusic-Standalone/

It lists the same signed APKs as the GitHub releases (the newest five), so an app installed from a
release is updated by F-Droid too.

## How it's built

`.github/workflows/fdroid.yml` runs after every release (release.yml calls it) and can be started
from the Actions tab. It:

1. downloads the newest releases' APKs (`build-repo.sh`),
2. builds F-Droid's store listing from `fastlane/metadata/android/en-US` (name, descriptions,
   icon, screenshots) and `app-metadata.yml` (licence, links, anti-features), turning each
   release's notes into its "What's new" (`changelog.py`),
3. runs `fdroid update`, which signs the index with the app's signing key (the
   `RELEASE_KEYSTORE_*` secrets), and
4. force-pushes the result to the `gh-pages` branch as a single commit, so APKs don't pile up in
   history.

`config.yml` holds the repo's settings; its passwords come from the environment.

## Building it locally

With fdroidserver (`pip install fdroidserver`) and Android build-tools on the PATH:

```sh
cp keystore/release.jks fdroid/keystore.jks
FDROID_KEY_ALIAS=music FDROID_KEYSTORE_PASS=… fdroid/build-repo.sh      # needs gh, signed in
```

`SKIP_DOWNLOAD=1` uses APKs already placed in `fdroid/repo` instead of downloading them.
