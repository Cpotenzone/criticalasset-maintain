#!/usr/bin/env bash
# Federated (Google / Apple) Cognito sign-in mode for maintain-api: an env-only
# task-definition roll on the same image (sign-in migration, docs/AWS_SSO.md).
#
#   ./scripts/aws-sso-mode.sh show
#   ./scripts/aws-sso-mode.sh preview [--emails a@x.com,b@y.com]   # default; allowlist only
#   ./scripts/aws-sso-mode.sh open                                  # cutover: everyone
#
# Copies the LIVE maintain-api task definition, changes ONLY SSO_FEDERATED_MODE
# (and SSO_FEDERATED_PREVIEW_EMAILS with --emails), refuses if anything else
# would differ, registers it, rolls the service and verifies at the destination
# that /oauth2/authorize/cognito still 302s with PKCE (S256). Cognito
# username/password sign-in is never affected by either mode.
set -euo pipefail
AWS_REGION=us-east-1
CLUSTER=ca-prod
SERVICE=maintain-api
API_URL=${API_URL:-https://maintain-api.criticalcopilot.com}
PROFILE="${AWS_DEPLOY_PROFILE:-dr}"
[ "$PROFILE" = dr-admin ] && { echo "refusing the dr-admin SSO profile" >&2; exit 1; }
aws_() { aws --profile "$PROFILE" --region "$AWS_REGION" "$@"; }
ok()  { printf '\033[32m✓ %s\033[0m\n' "$*" >&2; }
die() { printf '\033[31m✗ %s\033[0m\n' "$*" >&2; exit 1; }
CMD="${1:-}"; shift || true
EMAILS=""
while [ $# -gt 0 ]; do case "$1" in --emails) EMAILS="$2"; shift 2 ;; *) die "unknown $1" ;; esac; done
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT

arn=$(aws_ ecs describe-services --cluster "$CLUSTER" --services "$SERVICE" --query 'services[0].taskDefinition' --output text)
aws_ ecs describe-task-definition --task-definition "$arn" --query taskDefinition > "$TMP/live.json"

case "$CMD" in
  show)
    python3 - "$TMP/live.json" <<'PY'
import json, sys
env = {e["name"]: e["value"] for e in json.load(open(sys.argv[1]))["containerDefinitions"][0].get("environment", [])}
print("SSO_FEDERATED_MODE=" + env.get("SSO_FEDERATED_MODE", "<unset: preview>"))
n = len([e for e in env.get("SSO_FEDERATED_PREVIEW_EMAILS", "").split(",") if e.strip()])
print(f"SSO_FEDERATED_PREVIEW_EMAILS: {n or '<unset: casey@criticalasset.com>'} entr(ies)")
PY
    exit 0 ;;
  preview|open) ;;
  *) die "usage: $0 show|preview [--emails …]|open" ;;
esac

python3 - "$TMP/live.json" "$TMP/next.json" "$CMD" "$EMAILS" <<'PY'
import copy, json, sys
live, out, mode, emails = sys.argv[1:]
td = json.load(open(live))
for k in ("taskDefinitionArn", "revision", "status", "requiresAttributes", "compatibilities",
          "registeredAt", "registeredBy", "deregisteredAt"):
    td.pop(k, None)
if len(td["containerDefinitions"]) != 1:
    sys.exit("expected one container")
new = copy.deepcopy(td)
c = new["containerDefinitions"][0]
env = {e["name"]: e["value"] for e in c.get("environment", [])}
env["SSO_FEDERATED_MODE"] = mode
if emails:
    env["SSO_FEDERATED_PREVIEW_EMAILS"] = emails
c["environment"] = [{"name": k, "value": v} for k, v in env.items()]
def strip(t):
    t = copy.deepcopy(t)
    t["containerDefinitions"][0]["environment"] = sorted(
        (e for e in t["containerDefinitions"][0].get("environment", [])
         if e["name"] not in ("SSO_FEDERATED_MODE", "SSO_FEDERATED_PREVIEW_EMAILS")), key=lambda e: e["name"])
    return t
assert strip(td) == strip(new), "refusing: the roll would change more than the SSO mode"
json.dump(new, open(out, "w"))
print(f"~ env SSO_FEDERATED_MODE={mode}" + (" ~ env SSO_FEDERATED_PREVIEW_EMAILS" if emails else ""))
PY

new=$(aws_ ecs register-task-definition --cli-input-json "file://$TMP/next.json" \
        --query 'taskDefinition.taskDefinitionArn' --output text)
aws_ ecs update-service --cluster "$CLUSTER" --service "$SERVICE" --task-definition "$new" >/dev/null
aws_ ecs wait services-stable --cluster "$CLUSTER" --services "$SERVICE"
loc=$(curl -s -o /dev/null -w '%{redirect_url}' --max-time 20 "$API_URL/oauth2/authorize/cognito" || true)
case "$loc" in *code_challenge_method=S256*) ok "live: $API_URL/oauth2/authorize/cognito 302 with PKCE" ;;
  *) die "live verification failed: ${loc:-no redirect}. Undo: aws ecs update-service --cluster $CLUSTER --service $SERVICE --task-definition $arn" ;; esac
ok "maintain-api runs $new (SSO_FEDERATED_MODE=$CMD, image unchanged). Undo: --task-definition $arn"
