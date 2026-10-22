#!/usr/bin/env bash
set -euo pipefail
cd "$HOME/daily-rhythm"
sha256sum -c SHA256SUMS
python3 prepare-api-env.py
# Preserve a logical backup before the first business migration.
python3 backup.py
# Only remove the named, previously verified deployment test table.
# Flyway requires an empty business schema on first migration.
sudo docker compose exec -T db sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysql --protocol=TCP -h127.0.0.1 -u "$MYSQL_USER" "$MYSQL_DATABASE" --execute="DROP TABLE IF EXISTS _deploy_verify_20261021;"'
if ! sudo docker buildx version >/dev/null 2>&1; then
    sudo apt-get update
    sudo apt-get install -y docker-buildx
fi
compose=(sudo docker compose -f compose.yaml -f compose.api.yaml)
if [[ -f compose.direct.yaml ]] && grep -q '^API_PUBLIC_HOST=' .env; then
    compose+=(-f compose.direct.yaml)
fi
"${compose[@]}" config --quiet
"${compose[@]}" build api
"${compose[@]}" up -d --wait --wait-timeout 240 api
"${compose[@]}" ps
curl --fail --silent --show-error http://127.0.0.1:18080/health
printf '\nAPI is ready on server loopback port 18080.\n'
if [[ -f compose.direct.yaml ]] && grep -q '^API_PUBLIC_HOST=' .env; then
    printf '%s\n' 'Public HTTPS is configured separately through the gateway; check the direct connection guide.'
else
    printf '%s\n' 'Use start-direct.sh to enable public HTTPS, or the development SSH tunnel for local debugging.'
fi
