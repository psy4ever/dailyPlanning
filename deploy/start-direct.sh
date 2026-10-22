#!/usr/bin/env bash
set -euo pipefail
cd "$HOME/daily-rhythm"

if [[ $# -ne 1 ]]; then
    printf '%s\n' 'Usage: bash start-direct.sh PUBLIC_IPV4' >&2
    exit 1
fi

# The release checksum is verified before any configuration or service changes.
sha256sum -c SHA256SUMS
python3 prepare-direct.py "$1"
public_ip="$1"

# Existing helper preserves credentials and creates an encrypted DB backup before updating.
bash start-api.sh

compose=(sudo docker compose -f compose.yaml -f compose.api.yaml -f compose.direct.yaml)
"${compose[@]}" config --quiet
"${compose[@]}" run --rm --no-deps gateway caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile

# Leave SSH and all existing firewall rules unchanged; do not enable an inactive UFW.
if command -v ufw >/dev/null 2>&1 && sudo ufw status | grep -q '^Status: active'; then
    sudo ufw allow 80/tcp
    sudo ufw allow 443/tcp
fi

"${compose[@]}" up -d --wait --wait-timeout 240 api gateway
"${compose[@]}" exec -T gateway caddy reload --config /etc/caddy/Caddyfile --adapter caddyfile
"${compose[@]}" ps

printf '\nWaiting for a publicly trusted IP certificate. Cloud firewall must permit TCP 80/443.\n'
https_ready=0
for attempt in $(seq 1 30); do
    # Resolve to loopback locally while still validating the public-IP certificate and host.
    if curl --fail --silent --connect-timeout 3 --max-time 6 \
        --resolve "${public_ip}:443:127.0.0.1" "https://${public_ip}/health"; then
        https_ready=1
        break
    fi
    printf 'Certificate/HTTPS not ready yet (%s/30).\n' "$attempt"
    sleep 5
done

if [[ "$https_ready" != 1 ]]; then
    "${compose[@]}" logs --tail 40 gateway
    printf '%s\n' 'HTTPS has not passed verification. Check the cloud firewall TCP 80/443 rules and the certificate errors above, then rerun this script.' >&2
    exit 1
fi

printf '\nHTTPS is ready at https://%s. Certificates renew automatically in the persistent Caddy volume.\n' "$public_ip"
printf '%s\n' 'Personal deployment: existing accounts can log in; new account registration defaults to disabled.'
printf '%s\n' 'SSH can now be disconnected. Verify /health from the computer and the phone cellular network.'
