# TestFlight deploy — CriticalAsset Maintain (App #2)

Headless one-command ship to TestFlight. No Xcode UI, no login-expiry — auth is
an **App Store Connect API key (.p8)**. Internal testers get the build the moment
it finishes processing; external testers after Beta App Review (this pipeline
submits it). Ported from the CAFieldCapture (App #1) pipeline and adapted for
this app's **React Native / Expo + CocoaPods** setup.

## App facts
| | |
|---|---|
| App | CriticalAsset Maintain |
| Bundle ID | `com.criticalasset.maintain` |
| ASC Apple ID (`ASC_APP_ID`) | `6794545308` |
| Apple team | `C7GCEESE2V` (same as App #1 → same API key works) |
| Build | `.xcworkspace` **AtlasCMMS.xcworkspace**, scheme **AtlasCMMS**, CocoaPods |
| Versioning | apple-generic (build bumps via `agvtool`) |

## One-time setup
1. `cp scripts/asc.env.example scripts/asc.env` and fill it in (already done here:
   key `4P7KR7ARNT`, the shared issuer, `ASC_APP_ID=6794545308`). **`asc.env` is
   gitignored — keep the private key out of the repo.**
2. Confirm the API private key is at `~/.appstoreconnect/private_keys/AuthKey_4P7KR7ARNT.p8`.
3. `PY` must point at a Python that has **PyJWT** (default: the ca-osint venv).
   `pip install pyjwt` in any venv and `export PY=/path/to/python` if that venv
   moves.

## Run it
```bash
cd mobile/ios
scripts/ship-testflight.sh --bump              # bump build, archive, upload, submit for review
scripts/ship-testflight.sh --bump --no-submit  # upload only (internal testers still get it)
```
Prereqs the script checks before archiving: **`mobile/node_modules`** (run
`npm install` / `yarn` in `mobile/` — the RN "bundle JS" archive phase needs it)
and **`Pods/`** (auto-runs `pod install` if missing).

## Gotchas (every one of these bit App #1 — all handled here)
1. **Build numbers must strictly increase *within a marketing version (train)*.**
   `--bump` does `agvtool current+1`. If ASC already has a higher build on the
   `1.0` train, set it explicitly: `xcrun agvtool new-version -all <n>` first.
   (Different marketing versions have independent build numbers.)
2. **`testflight-submit.py` runs under `$PY` (PyJWT), not system `python3`.**
   Wrong interpreter → the submit step crashes silently *after* a successful
   upload (a half-ship). The script already uses `$PY`.
3. **The submit lookup is train-aware** — it takes the marketing version as a
   2nd arg and filters builds by `preReleaseVersion.version`. Without it, an old
   build with the same `CFBundleVersion` on a different train gets grabbed and
   assigned/submitted. (Handled — `ship-testflight.sh` passes `$MKT`.)
4. **Disk space.** `ld: write() failed, errno=28` during archive = disk full,
   not a code error. Each `.xcarchive` is hundreds of MB and DerivedData
   balloons. Clean: `rm -rf build/*.xcarchive ~/Library/Developer/Xcode/DerivedData/*`.
   Keep `df -h /` above a few GB.
5. **One build per app in Beta Review at a time** — the submit script expires
   the prior on-train build to free the slot.
6. **Expo/prebuild caveat:** the `.xcodeproj` is committed and we archive it
   directly, so `agvtool` bumping the pbxproj is correct. If someone later runs
   `expo prebuild` / EAS, the source of truth for version+build becomes
   `app.json` (`expo.version`, `expo.ios.buildNumber`) and prebuild will
   overwrite the pbxproj — bump there instead.

## Verify / recover (same API key)
```bash
PY=$HOME/git/criticalasset-osint/backend/.venv/bin/python   # has PyJWT
# Recent builds + train + state:
#   GET /v1/builds?filter[app]=6794545308&sort=-uploadedDate&include=preReleaseVersion
# Assign to a group:   POST   /v1/betaGroups/<gid>/relationships/builds
# Un-assign:           DELETE /v1/betaGroups/<gid>/relationships/builds
# Expire a build:      PATCH  /v1/builds/<bid>  {"data":{"attributes":{"expired":true}}}
# Submit for review:   POST   /v1/betaAppReviewSubmissions  {build relationship}
```
JWT: ES256 · `iss`=issuer · `aud`=`appstoreconnect-v1` · header `kid`=key id · `exp` ≤ 20 min.

## Files
- `ship-testflight.sh` — the command (adapted: workspace build, agvtool, no xcodegen)
- `testflight-submit.py` — waits for VALID, assigns external group(s), expires older-on-train, submits (identical to App #1)
- `ExportOptions.plist` — app-store-connect / upload / team C7GCEESE2V (team-generic)
- `asc.env` — ids (gitignored); `asc.env.example` — template
