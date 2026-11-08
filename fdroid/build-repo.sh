#!/usr/bin/env bash
# Builds this project's F-Droid repo in fdroid/repo from the newest GitHub releases.
#
#   fdroid/build-repo.sh [releases]     (default: the 5 newest)
#
# Needs: fdroidserver, Android build-tools (aapt2) on PATH or in $ANDROID_HOME, the GitHub CLI
# (gh, with GH_TOKEN) unless APKs are already in fdroid/repo, the signing keystore at
# fdroid/keystore.jks, and FDROID_KEY_ALIAS / FDROID_KEYSTORE_PASS in the environment.
set -euo pipefail

releases="${1:-5}"
root="$(cd "$(dirname "$0")/.." && pwd)"
fdroid_dir="$root/fdroid"
app_id="com.music.player"
cd "$fdroid_dir"

# Addresses follow the GitHub repository's name (GITHUB_REPOSITORY in Actions), so renaming the
# repository moves them along with it.
slug="${GITHUB_REPOSITORY:-603Madison/SuperAwesomeMagicalMusic}"
owner="${slug%%/*}"
name="${slug#*/}"
export SOURCE_URL="https://github.com/$slug"
site_url="https://${owner,,}.github.io/$name"
repo_url="$site_url/repo"
fill() { sed -e "s#@REPO_URL@#$repo_url#g" -e "s#@REPO_URL_NO_SCHEME@#${repo_url#https://}#g" -e "s#@SOURCE_URL@#$SOURCE_URL#g" -e "s#@SITE_URL@#$site_url#g" "$1"; }
fill config.yml.in > config.yml

aapt2="$(command -v aapt2 || ls -d "${ANDROID_HOME:-/opt/android-sdk}"/build-tools/*/aapt2 2>/dev/null | sort -V | tail -1)"
[ -x "$aapt2" ] || { echo "aapt2 not found" >&2; exit 1; }

mkdir -p repo
if [ -n "${SKIP_DOWNLOAD:-}" ]; then
  echo "Using the APKs already in fdroid/repo"
else
  rm -f repo/*.apk
  gh release list --limit "$releases" --exclude-drafts --exclude-pre-releases --json tagName --jq '.[].tagName' |
    while read -r tag; do
      gh release download "$tag" --pattern '*.apk' --dir repo --clobber || echo "No APK in $tag"
    done
fi
ls repo/*.apk >/dev/null 2>&1 || { echo "No APKs to publish" >&2; exit 1; }

# Store listing: the fastlane metadata, plus each version's release notes, condensed, as its
# changelog; the newest version is the one F-Droid suggests.
localized="metadata/$app_id/en-US"
rm -rf "metadata/$app_id"
mkdir -p "$localized/changelogs"
cp -r "$root/fastlane/metadata/android/en-US/." "$localized/"
newest_code=0
newest_name=""
for apk in repo/*.apk; do
  badging="$("$aapt2" dump badging "$apk")"
  badging="${badging%%$'\n'*}"
  code="$(sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p" <<<"$badging")"
  name="$(sed -n "s/.*versionName='\([^']*\)'.*/\1/p" <<<"$badging")"
  [ -n "$code" ] || continue
  if [ "$code" -gt "$newest_code" ]; then newest_code="$code"; newest_name="$name"; fi
  notes="$root/release-notes/v$name.md"
  [ -f "$notes" ] && python3 "$fdroid_dir/changelog.py" "$notes" "$name" > "$localized/changelogs/$code.txt"
done
{
  fill app-metadata.yml
  echo
  echo "CurrentVersion: $newest_name"
  echo "CurrentVersionCode: $newest_code"
} > "metadata/$app_id.yml"

# The repo's own icon (fdroid copies it into repo/icons), and the private config's permissions.
cp "$root/fastlane/metadata/android/en-US/images/icon.png" icon.png
chmod 600 config.yml

# Lists the APKs and signs the index with the repo key.
fdroid update --rename-apks --use-date-from-apk --pretty

# The site: the repo, plus a page on how to add it.
rm -rf site-out
mkdir -p site-out
cp -r repo site-out/repo
fill site/index.html > site-out/index.html
touch site-out/.nojekyll
echo "Site for $site_url in fdroid/site-out"
echo "Repo built: $(ls repo/*.apk | wc -l) APKs"
