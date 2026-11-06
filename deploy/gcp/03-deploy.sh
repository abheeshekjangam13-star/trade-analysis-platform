#!/usr/bin/env bash
# Run ON THE VM to deploy, and again for every update:  bash /opt/trade/trade-analysis-platform/deploy/gcp/03-deploy.sh
# Pulls the latest code of both repositories, rebuilds what changed and restarts it.
set -euo pipefail
cd "$(dirname "$0")"
[ -f .env ] || { echo "Create .env first (cp .env.example .env)"; exit 1; }
[ "$(stat -c %a .env)" = "600" ] || chmod 600 .env

git -C ../.. pull --ff-only
git -C ../../../trade-analysis-ui pull --ff-only

docker compose -f docker-compose.prod.yml --env-file .env up -d --build --remove-orphans
docker image prune -f >/dev/null

echo "Waiting for the app to answer…"
DOMAIN=$(grep -E '^DOMAIN=' .env | cut -d= -f2-)
for i in $(seq 1 60); do
  if curl -fsS "https://$DOMAIN/api/auth/status" >/dev/null 2>&1; then echo "Up: https://$DOMAIN"; exit 0; fi
  sleep 5
done
echo "Not answering yet. Check: docker compose -f docker-compose.prod.yml ps   and   docker compose -f docker-compose.prod.yml logs --tail 100"
exit 1
