#!/usr/bin/env bash
# One command → TestFlight for CriticalAsset Maintain (React Native / Expo +
# CocoaPods). Fully headless: no Xcode UI, no Organizer, no "Failed to Use
# Accounts". Auth is the App Store Connect API key (.p8), which never expires
# the way Xcode's Apple-ID login does. See scripts/DEPLOY-TESTFLIGHT.md.
#
# Usage:  scripts/ship-testflight.sh [--bump] [--no-submit]
#   --bump       agvtool-increment the build number (CFBundleVersion) first
#   --no-submit  upload only; don't submit for external Beta Review
#
# Internal testers get the build once it finishes processing; external testers
# after Beta App Review (this script submits it).
#
# Differences from the CAFieldCapture (App #1) pipeline:
#   • builds the .xcworkspace + AtlasCMMS scheme (CocoaPods), NOT a bare
#     .xcodeproj, and there is NO xcodegen step (the project is committed).
#   • bumps the build with `agvtool` (the project uses apple-generic
#     versioning) instead of editing project.yml.
# Everything else — API-key upload, testflight-submit.py — is identical.
set -euo pipefail

cd "$(dirname "$0")/.."          # → mobile/ios
ROOT="$(pwd)"
source scripts/asc.env
KEY_PATH="$HOME/.appstoreconnect/private_keys/AuthKey_${ASC_KEY_ID}.p8"
# Any python with PyJWT. Override with PY=... if the ca-osint venv moves.
PY="${PY:-$HOME/git/criticalasset-osint/backend/.venv/bin/python}"
WORKSPACE="AtlasCMMS.xcworkspace"
SCHEME="AtlasCMMS"

BUMP=0; SUBMIT=1
for a in "$@"; do
  [ "$a" = "--bump" ] && BUMP=1
  [ "$a" = "--no-submit" ] && SUBMIT=0
done

# --- Prereqs the RN Release archive needs (fail loud, not mid-archive) ---
if [ ! -d ../node_modules ]; then
  echo "✗ ../node_modules missing — run 'npm install' (or yarn) in mobile/ first." >&2
  exit 1
fi
if [ ! -d Pods ]; then
  echo "▸ Pods missing — pod install"
  pod install
fi

# --- Version + build ---
# This is an EXPO app: `expo prebuild` writes the marketing version and build
# number LITERALLY into Info.plist (from app.config.ts), NOT via the Xcode
# MARKETING_VERSION / CURRENT_PROJECT_VERSION build settings. So agvtool does
# NOT change what actually uploads — the authoritative source is Info.plist.
# Edit it directly. (If someone runs `expo prebuild` afterward it regenerates
# Info.plist from app.config.ts, so for a durable bump also raise
# expo.ios.buildNumber there — see DEPLOY-TESTFLIGHT.md.)
INFOPLIST="AtlasCMMS/Info.plist"
PB=/usr/libexec/PlistBuddy
if [ "$BUMP" = "1" ]; then
  CUR=$($PB -c "Print :CFBundleVersion" "$INFOPLIST")
  NEXT=$((CUR + 1))
  $PB -c "Set :CFBundleVersion $NEXT" "$INFOPLIST"
  echo "▸ bumped build $CUR → $NEXT (Info.plist)"
fi
BUILD=$($PB -c "Print :CFBundleVersion" "$INFOPLIST")
MKT=$($PB -c "Print :CFBundleShortVersionString" "$INFOPLIST")
ARCHIVE="build/AtlasCMMS-${MKT}b${BUILD}.xcarchive"

echo "▸ archiving ${MKT} (${BUILD})  [scheme ${SCHEME}]"
rm -rf "$ARCHIVE"
xcodebuild -workspace "$WORKSPACE" -scheme "$SCHEME" \
  -configuration Release -destination 'generic/platform=iOS' \
  -archivePath "$ARCHIVE" -allowProvisioningUpdates archive \
  -quiet

echo "▸ uploading via API key (session-proof)"
xcodebuild -exportArchive -archivePath "$ARCHIVE" \
  -exportOptionsPlist scripts/ExportOptions.plist \
  -allowProvisioningUpdates \
  -authenticationKeyID "$ASC_KEY_ID" \
  -authenticationKeyIssuerID "$ASC_ISSUER_ID" \
  -authenticationKeyPath "$KEY_PATH" \
  | grep -E 'Uploaded|EXPORT (SUCCEEDED|FAILED)' || { echo "upload failed"; exit 1; }

if [ "$SUBMIT" = "1" ]; then
  echo "▸ waiting for build ${BUILD} to finish processing, then submitting for Beta Review"
  # MUST run under a python with PyJWT, and MUST pass the marketing version so
  # the lookup is train-aware (else an old build with the same number on a
  # different train gets grabbed).
  "$PY" scripts/testflight-submit.py "$BUILD" "$MKT"
fi
echo "✅ done — internal testers have it now; external testers after review."
