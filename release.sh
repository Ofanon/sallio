#!/usr/bin/env bash
# Publishes a new version on GitHub; installed apps offer it within 6 hours
# (immediately when the app is opened after the check window).
#
#   1. bump versionCode AND versionName in app/build.gradle.kts, commit
#   2. ./release.sh "Ce qui change, en une ou deux phrases pour les élèves."
set -euo pipefail
cd "$(dirname "$0")"

notes="${1:?usage: ./release.sh \"notes de version\"}"
code=$(sed -n 's/^ *versionCode = \([0-9]*\).*/\1/p' app/build.gradle.kts)
name=$(sed -n 's/^ *versionName = "\(.*\)".*/\1/p' app/build.gradle.kts)
gh="gh"
command -v gh >/dev/null || gh="/c/Program Files/GitHub CLI/gh.exe"

[ -f keystore.properties ] || { echo "keystore.properties manquant : impossible de signer." >&2; exit 1; }
[ -z "$(git status --porcelain)" ] || { echo "Des changements ne sont pas commités." >&2; exit 1; }
if "$gh" release view "v$name" >/dev/null 2>&1; then
    echo "v$name existe déjà : augmente versionCode et versionName." >&2; exit 1
fi

./gradlew --quiet testDebugUnitTest assembleRelease

apk=app/build/outputs/apk/release/app-release.apk
apksigner=$(ls -d "$(sed -n 's/^sdk.dir=//p' local.properties)"/build-tools/*/ | sort -V | tail -1)apksigner.bat
# The updater refuses any APK not signed with the installed key: check before shipping.
"$apksigner" verify --print-certs "$apk" | grep -q "CN=Ma Salle" || { echo "APK mal signé." >&2; exit 1; }

mkdir -p dist
out="dist/ma-salle-$code.apk"
cp "$apk" "$out"
sha256sum "$out"

git push origin HEAD
"$gh" release create "v$name" "$out" --title "Ma Salle $name" --notes "$notes"
echo "Publié : v$name (versionCode $code)"
