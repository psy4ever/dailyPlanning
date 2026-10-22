#!/usr/bin/env bash
# Bootstrap Ubuntu's packaged Docker and Compose; run on the server with sudo.
set -Eeuo pipefail

if [[ "${EUID}" -ne 0 ]]; then
  printf '%s\n' 'Run this script with sudo bash deploy/bootstrap-ubuntu.sh' >&2
  exit 1
fi

. /etc/os-release
if [[ "${ID}" != "ubuntu" || "${VERSION_ID}" != "24.04" ]]; then
  printf '%s\n' 'This script is prepared for Ubuntu 24.04 only.' >&2
  exit 1
fi

apt-get update
apt-get install -y docker.io docker-compose-v2
systemctl enable --now docker

docker --version
docker compose version
systemctl is-active docker
dpkg-query -W -f='${Package}=${Version}\n' docker.io docker-compose-v2
