#!/usr/bin/env bash
# Deploy a Fordism environment. Run by the self-hosted CI runner on the target,
# or by hand. Pulls the pinned images and brings the stack up, then health-checks.
#
# Usage: deploy/scripts/deploy.sh <uat|prd>   (run from the repo root)
set -euo pipefail

ENV="${1:?usage: deploy/scripts/deploy.sh <uat|prd>}"
ENV_FILE="deploy/envs/${ENV}.env"
PROJECT="fordism-${ENV}"

[ -f "$ENV_FILE" ] || { echo "missing $ENV_FILE"; exit 1; }

echo ">> deploying $PROJECT from $ENV_FILE"
docker compose --project-name "$PROJECT" --env-file "$ENV_FILE" -f docker-compose.yml pull
docker compose --project-name "$PROJECT" --env-file "$ENV_FILE" -f docker-compose.yml up -d

echo ">> waiting for health ..."
for i in $(seq 1 30); do
  if docker compose --project-name "$PROJECT" --env-file "$ENV_FILE" -f docker-compose.yml \
       exec -T fordism-core curl -fsS http://localhost:8080/api/health >/dev/null 2>&1; then
    echo ">> $PROJECT healthy"; exit 0
  fi
  sleep 5
done
echo "!! health check failed for $PROJECT"; exit 1
