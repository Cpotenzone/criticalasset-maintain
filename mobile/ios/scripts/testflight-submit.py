#!/usr/bin/env python3
"""Poll App Store Connect until the given build number finishes processing,
then submit it for external Beta App Review. Idempotent — re-running when a
submission already exists is a no-op. Auth via the ASC API key in asc.env.

Usage: testflight-submit.py <build_number>
"""
import os
import sys
import time
import json
import subprocess
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
ENV = dict(
    line.split("=", 1)
    for line in open(os.path.join(HERE, "asc.env"))
    if "=" in line and not line.startswith("#")
)
ENV = {k.strip(): v.strip() for k, v in ENV.items()}
KEY_ID, ISSUER, APP_ID = ENV["ASC_KEY_ID"], ENV["ASC_ISSUER_ID"], ENV["ASC_APP_ID"]
KEY_PATH = os.path.expanduser(f"~/.appstoreconnect/private_keys/AuthKey_{KEY_ID}.p8")


def token() -> str:
    import jwt  # from the ca-osint venv this runs under
    return jwt.encode(
        {"iss": ISSUER, "iat": int(time.time()), "exp": int(time.time()) + 1200,
         "aud": "appstoreconnect-v1"},
        open(KEY_PATH).read(), algorithm="ES256",
        headers={"kid": KEY_ID, "typ": "JWT"},
    )


def api(path: str, method="GET", body=None):
    req = urllib.request.Request(
        f"https://api.appstoreconnect.apple.com{path}", method=method,
        data=json.dumps(body).encode() if body else None,
        headers={"Authorization": f"Bearer {token()}",
                 "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req) as r:
            return r.status, (json.loads(r.read() or "{}"))
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read() or "{}")


def build_train(bid: str):
    """Marketing version (e.g. '0.5') this build belongs to."""
    _, d = api(f"/v1/builds/{bid}/preReleaseVersion")
    return (d.get("data") or {}).get("attributes", {}).get("version")


def find_build(num: str, train: str | None = None):
    q = f"/v1/builds?filter[app]={APP_ID}&filter[version]={num}&limit=1"
    if train:
        q += f"&filter[preReleaseVersion.version]={train}"
    _, d = api(q)
    return (d.get("data") or [None])[0]


def main():
    num = sys.argv[1]
    # The marketing version (train) MUST scope the lookup — otherwise an old
    # build with the same CFBundleVersion on a DIFFERENT train (e.g. an old
    # 0.3(6)) matches first and gets assigned/submitted instead of the build we
    # just uploaded. Pass it as argv[2] (ship-testflight.sh sends $MKT).
    train = sys.argv[2] if len(sys.argv) > 2 else None
    build = None
    for _ in range(70):  # up to ~35 min
        build = find_build(num, train)
        state = build and build["attributes"]["processingState"]
        if state == "VALID":
            break
        print(f"  build {train or ''}({num}): {state or 'not visible yet'} …", flush=True)
        time.sleep(30)
    if not build or build["attributes"]["processingState"] != "VALID":
        sys.exit(f"build {num} never became VALID")
    bid = build["id"]
    train = build_train(bid)
    print(f"  build {num} VALID on train {train} (id={bid})", flush=True)

    # 1) Assign to every EXTERNAL beta group so those testers get it after review.
    _, groups = api(f"/v1/betaGroups?filter[app]={APP_ID}&limit=50")
    for g in [g for g in groups.get("data", []) if not g["attributes"].get("isInternalGroup")]:
        st, _ = api(f"/v1/betaGroups/{g['id']}/relationships/builds", "POST",
                    {"data": [{"type": "builds", "id": bid}]})
        print(f"  assign → '{g['attributes'].get('name')}': {st}", flush=True)

    # 2) Expire older, un-expired builds on the SAME train so this one reads as
    #    the latest and the beta-review slot is free.
    if train:
        _, older = api(f"/v1/builds?filter[app]={APP_ID}"
                       f"&filter[preReleaseVersion.version]={train}&limit=50")
        for b in older.get("data", []):
            v = b["attributes"].get("version")
            if b["id"] != bid and not b["attributes"].get("expired") and v and v.isdigit() \
                    and int(v) < int(num):
                st, _ = api(f"/v1/builds/{b['id']}", "PATCH",
                            {"data": {"type": "builds", "id": b["id"],
                                      "attributes": {"expired": True}}})
                print(f"  expire {train}({v}): {st}", flush=True)

    # 3) Submit for Beta App Review (idempotent).
    _, existing = api(f"/v1/builds/{bid}/betaAppReviewSubmission")
    if existing.get("data"):
        print(f"  build {num} already submitted "
              f"({existing['data']['attributes'].get('betaReviewState')})", flush=True)
        return
    st, resp = api("/v1/betaAppReviewSubmissions", "POST", {
        "data": {"type": "betaAppReviewSubmissions",
                 "relationships": {"build": {"data": {"type": "builds", "id": bid}}}}})
    if st == 201:
        print(f"  submitted build {num} for Beta Review → "
              f"{resp['data']['attributes']['betaReviewState']}", flush=True)
    else:
        sys.exit(f"submit failed ({st}): {json.dumps(resp)[:300]}")


if __name__ == "__main__":
    main()
