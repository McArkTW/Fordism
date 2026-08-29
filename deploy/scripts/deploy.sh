#!/usr/bin/env bash
# Deploy a Fordism environment. Run by hand or from your own runner, on the target.
# DEPLOY_MODE in the env file picks pull (registry images) or build (on the box).
#
# Usage: deploy/scripts/deploy.sh <uat|prd>   (run from the repo root)
set -euo pipefail

DEPLOY_ENV="${1:?usage: deploy/scripts/deploy.sh <uat|prd>}"
ENV_FILE="deploy/envs/${DEPLOY_ENV}.env"
PROJECT="fordism-${DEPLOY_ENV}"

[ -f "$ENV_FILE" ] || { echo "missing $ENV_FILE"; exit 1; }

DEPLOY_MODE="$(sed -n 's/^DEPLOY_MODE=[[:space:]]*//p' "$ENV_FILE" | tail -1 | awk '{print $1}')"
DEPLOY_MODE="${DEPLOY_MODE:-pull}"

DC=(docker compose --project-name "$PROJECT" --env-file "$ENV_FILE" -f docker-compose.yml)

echo ">> deploying $PROJECT from $ENV_FILE (mode: $DEPLOY_MODE)"
case "$DEPLOY_MODE" in
  pull)
    "${DC[@]}" pull ;;
  build)
    # the override holds the build: sections (an explicit -f turns auto-merge off)
    "${DC[@]}" -f docker-compose.override.yml --profile build-only build
    "${DC[@]}" -f docker-compose.override.yml build ;;
  *)
    echo "!! DEPLOY_MODE must be pull or build (got: $DEPLOY_MODE)"; exit 1 ;;
esac
"${DC[@]}" up -d

echo ">> waiting for health ..."
for i in $(seq 1 30); do
  if "${DC[@]}" exec -T fordism-core curl -fsS http://localhost:8080/api/health >/dev/null 2>&1; then
    echo ">> $PROJECT healthy"; exit 0
  fi
  sleep 5
done
echo "!! health check failed for $PROJECT"; exit 1
