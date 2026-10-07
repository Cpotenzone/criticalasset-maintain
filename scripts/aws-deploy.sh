#!/usr/bin/env bash
# Deploy CriticalAsset Maintain to AWS (ECS Fargate, cluster ca-prod).
#
#   ./scripts/aws-deploy.sh            # roll api + web onto the current :prod image
#   ./scripts/aws-deploy.sh api        # api only
#   ./scripts/aws-deploy.sh web        # web only
#
# Maintain left Cloud Run on 2026-08-01 and the EC2 box on 2026-08-03; both
# units are ECS Fargate services (`maintain-api`, `maintain-web`) on cluster
# `ca-prod`, behind ALB dr-castle-alb (hosts maintain-api.criticalcopilot.com
# and maintain.criticalcopilot.com). The images come from ECR :prod, pushed
# by .github/workflows/push-docker.yml on a v* tag. A deploy is a forced new
# deployment of each service, which pulls :prod, followed by a wait and a
# measurement at the public hostname — never the exit code alone.
#
# Spring boots in ~2.5 min; the service's health-check grace is 600 s (raised
# 2026-10-07 after `ecs wait services-stable` timed out at the old 180 s).
#
# Runs from a laptop (--profile dr — never the SSO profile, its session
# expires mid-run) or from GitHub Actions (assumed OIDC role) unchanged.
set -euo pipefail

AWS_REGION=us-east-1
CLUSTER=ca-prod
API_SERVICE=maintain-api
WEB_SERVICE=maintain-web
API_URL=${API_URL:-https://maintain-api.criticalcopilot.com}
WEB_URL=${WEB_URL:-https://maintain.criticalcopilot.com}

WHAT="${1:-all}"

PROFILE_ARG=()
if [ -n "${AWS_DEPLOY_PROFILE:-}" ]; then PROFILE_ARG=(--profile "$AWS_DEPLOY_PROFILE")
elif [ -z "${AWS_ACCESS_KEY_ID:-}${AWS_ROLE_ARN:-}${AWS_WEB_IDENTITY_TOKEN_FILE:-}" ]; then PROFILE_ARG=(--profile dr); fi
aws_() { aws "${PROFILE_ARG[@]}" --region "$AWS_REGION" "$@"; }

log() { printf '\033[36m▸ %s\033[0m\n' "$*" >&2; }
ok()  { printf '\033[32m✓ %s\033[0m\n' "$*" >&2; }
die() { printf '\033[31m✗ %s\033[0m\n' "$*" >&2; exit 1; }

roll() {
  local service="$1"
  log "forcing a new deployment of $CLUSTER/$service (pulls ECR :prod)"
  aws_ ecs update-service --cluster "$CLUSTER" --service "$service" \
    --force-new-deployment --query 'service.deployments[0].id' --output text
}

wait_stable() {
  local service="$1" tries=0
  log "waiting for $service to reach steady state (Spring boots in ~2.5 min)"
  # `ecs wait services-stable` gives up at 10 min; loop it so a slow pull is
  # not reported as a failure.
  until aws_ ecs wait services-stable --cluster "$CLUSTER" --services "$service" 2>/dev/null; do
    tries=$((tries + 1))
    [ "$tries" -ge 3 ] && die "$service did not stabilise after ~30 min"
    log "$service still rolling; waiting again"
  done
  aws_ ecs describe-services --cluster "$CLUSTER" --services "$service" \
    --query 'services[0].{running:runningCount,desired:desiredCount,taskDef:taskDefinition}' --output table >&2
}

measure() {
  local url="$1" want="$2" code
  code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 20 "$url" || echo 000)
  [[ ",$want," == *",$code,"* ]] || die "$url answered $code (wanted $want)"
  ok "$url → $code"
}

running_digest() {
  local service="$1" task
  task=$(aws_ ecs list-tasks --cluster "$CLUSTER" --service-name "$service" --desired-status RUNNING \
    --query 'taskArns[0]' --output text)
  [ "$task" = "None" ] && { echo "no running task"; return; }
  aws_ ecs describe-tasks --cluster "$CLUSTER" --tasks "$task" \
    --query 'tasks[0].containers[0].imageDigest' --output text
}

case "$WHAT" in
  api) roll "$API_SERVICE"; wait_stable "$API_SERVICE" ;;
  web) roll "$WEB_SERVICE"; wait_stable "$WEB_SERVICE" ;;
  all) roll "$API_SERVICE"; roll "$WEB_SERVICE"; wait_stable "$API_SERVICE"; wait_stable "$WEB_SERVICE" ;;
  *) die "usage: $0 [api|web|all]" ;;
esac

# Verify at the destination: the public names answer, and the digest the
# container is actually running is printed for the change log.
[ "$WHAT" != "web" ] && { measure "$API_URL/instance-config" 200; log "api running digest: $(running_digest "$API_SERVICE")"; }
[ "$WHAT" != "api" ] && { measure "$WEB_URL/" 200; log "web running digest: $(running_digest "$WEB_SERVICE")"; }
ok "deploy complete"
