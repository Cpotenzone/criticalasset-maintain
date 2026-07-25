#!/usr/bin/env bash
#
# Cut a CriticalAsset Maintain TestFlight build, end to end. Recreatable.
# Mirrors criticalasset-osint/ios/CAFieldCapture/scripts/testflight.sh,
# adapted for CocoaPods (this project has no xcodegen/project.yml — it's a
# committed native project, not regenerated).
#
#   ./scripts/testflight.sh            # auto-increment build from App Store Connect
#   ./scripts/testflight.sh 16         # force a specific build number
#
# Prereqs: Xcode, CocoaPods, and a python3 with `pyjwt` + `cryptography`
# (pip3 install pyjwt cryptography). Secrets: the ASC API key .p8 in
# ~/.appstoreconnect/private_keys/AuthKey_<KEY_ID>.p8 and scripts/asc.env
# (copy asc.env.example and fill in — it is gitignored). ASC_APP_ID can only
# be created via the App Store Connect web UI (New App, bundle id
# com.criticalasset.maintain, already registered) — the API has no
# "create app" endpoint. Do that once, then paste the resulting app id.
set -euo pipefail

cd "$(dirname "$0")/.."                      # -> mobile
# shellcheck disable=SC1091
source scripts/asc.env                       # ASC_KEY_ID, ASC_ISSUER_ID, ASC_APP_ID

KEY_P8="$HOME/.appstoreconnect/private_keys/AuthKey_${ASC_KEY_ID}.p8"
TEAM="C7GCEESE2V"
SCHEME="AtlasCMMS"                            # internal Xcode project/scheme name, unchanged (see NOTICE.md)
WORKSPACE="ios/AtlasCMMS.xcworkspace"

[ -f "$KEY_P8" ] || { echo "ERROR: ASC key not found at $KEY_P8"; exit 1; }

# PyJWT must actually be importable by whatever $PY resolves to — silently
# falling back to a bare `python3` that lacks it fails *after* a successful
# upload (the build-number resolver runs first and works fine standalone,
# but a later JWT-signing step would crash — a "silent half-ship"). Prefer
# an explicit $PYTHON if set, else probe python3, else fail loudly up front.
PY="${PYTHON:-python3}"
if ! "$PY" -c "import jwt, cryptography" >/dev/null 2>&1; then
  echo "ERROR: '$PY' can't import pyjwt/cryptography." >&2
  echo "  Fix: pip3 install pyjwt cryptography (or) export PYTHON=/path/to/a/python/with/them" >&2
  exit 1
fi

# Each .xcarchive is ~300MB+ and DerivedData balloons across builds — a
# disk-full mid-archive fails with an opaque `ld: write() failed, errno=28`,
# not an obvious "out of space" message. Bail early with a clear reason
# instead, and clear this project's own DerivedData first (pure cache).
rm -rf "$HOME/Library/Developer/Xcode/DerivedData/AtlasCMMS-"* 2>/dev/null || true
AVAIL_GB=$(df -g / | awk 'NR==2 {print $4}')
if [ "$AVAIL_GB" -lt 3 ]; then
  echo "ERROR: only ${AVAIL_GB}GiB free on / — need a few GiB of headroom for the archive." >&2
  echo "  Free up space (e.g. rm -rf ~/Library/Developer/Xcode/DerivedData/*) and retry." >&2
  exit 1
fi

SHORT=$(grep -A1 'MARKETING_VERSION' ios/AtlasCMMS.xcodeproj/project.pbxproj | grep -oE '[0-9]+\.[0-9]+(\.[0-9]+)?' | head -1)

resolve_next_build() {
  "$PY" - "$ASC_KEY_ID" "$ASC_ISSUER_ID" "$ASC_APP_ID" "$SHORT" "$KEY_P8" <<'PYX'
import sys, time, json, urllib.request
import jwt
kid, iss, app, short, p8 = sys.argv[1:6]
tok = jwt.encode(
    {"iss": iss, "iat": int(time.time()), "exp": int(time.time()) + 900, "aud": "appstoreconnect-v1"},
    open(p8).read(), algorithm="ES256", headers={"kid": kid, "typ": "JWT"})
url = (f"https://api.appstoreconnect.apple.com/v1/builds?filter[app]={app}&limit=200"
       "&include=preReleaseVersion&fields[builds]=version&fields[preReleaseVersions]=version")
d = json.load(urllib.request.urlopen(urllib.request.Request(url, headers={"Authorization": f"Bearer {tok}"})))
prv = {i["id"]: i["attributes"]["version"] for i in d.get("included", []) if i["type"] == "preReleaseVersions"}
mx = 0
for b in d["data"]:
    v = prv.get((b.get("relationships", {}).get("preReleaseVersion", {}).get("data") or {}).get("id"))
    if v == short:
        try: mx = max(mx, int(b["attributes"]["version"]))
        except ValueError: pass
print(mx + 1)
PYX
}

BUILD="${1:-$(resolve_next_build)}"
echo "==> CriticalAsset Maintain $SHORT (build $BUILD) -> TestFlight (app $ASC_APP_ID)"

# CURRENT_PROJECT_VERSION lives directly in the pbxproj (no project.yml here).
/usr/bin/sed -i '' -E "s/(CURRENT_PROJECT_VERSION = )[0-9]+;/\1$BUILD;/" ios/AtlasCMMS.xcodeproj/project.pbxproj

echo "==> pod install…"
(cd ios && LANG=en_US.UTF-8 LC_ALL=en_US.UTF-8 pod install)

WORK="$(mktemp -d)"; ARCHIVE="$WORK/AtlasCMMS.xcarchive"
AUTH=(-allowProvisioningUpdates -authenticationKeyPath "$KEY_P8"
      -authenticationKeyID "$ASC_KEY_ID" -authenticationKeyIssuerID "$ASC_ISSUER_ID")

echo "==> Archiving (Release, cloud signing)…"
xcodebuild archive -workspace "$WORKSPACE" -scheme "$SCHEME" -configuration Release \
  -destination 'generic/platform=iOS' -archivePath "$ARCHIVE" "${AUTH[@]}"

cat > "$WORK/ExportOptions.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>method</key><string>app-store-connect</string>
  <key>teamID</key><string>$TEAM</string>
  <key>destination</key><string>upload</string>
  <key>signingStyle</key><string>automatic</string>
  <key>uploadSymbols</key><true/>
</dict></plist>
PLIST

echo "==> Exporting + uploading to TestFlight…"
xcodebuild -exportArchive -archivePath "$ARCHIVE" -exportPath "$WORK/out" \
  -exportOptionsPlist "$WORK/ExportOptions.plist" "${AUTH[@]}"

echo "==> Done. $SHORT ($BUILD) uploaded — processing on App Store Connect."
