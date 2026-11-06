#!/usr/bin/env bash
# Run once ON THE VM (after: gcloud compute ssh trade-app --zone asia-south1-a --tunnel-through-iap):
#   bash 02-setup-vm.sh
# Installs Docker (from Docker's official apt repository), git and the PostgreSQL client, adds swap, and
# clones both repositories into /opt/trade.
set -euo pipefail

sudo apt-get update -qq
sudo apt-get install -y -qq ca-certificates curl gnupg git postgresql-client unattended-upgrades

# Docker Engine + Compose plugin (https://docs.docker.com/engine/install/debian/)
sudo install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/debian/gpg | sudo gpg --dearmor --yes -o /etc/apt/keyrings/docker.gpg
sudo chmod a+r /etc/apt/keyrings/docker.gpg
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/debian $(. /etc/os-release && echo "$VERSION_CODENAME") stable" \
  | sudo tee /etc/apt/sources.list.d/docker.list >/dev/null
sudo apt-get update -qq
sudo apt-get install -y -qq docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
sudo usermod -aG docker "$USER"

# 2 GB swap: the Maven build and five JVMs fit in 8 GB, swap is a safety net.
if ! swapon --show | grep -q /swapfile; then
  sudo fallocate -l 2G /swapfile && sudo chmod 600 /swapfile && sudo mkswap /swapfile && sudo swapon /swapfile
  echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab >/dev/null
fi

# Security updates install themselves.
sudo dpkg-reconfigure -f noninteractive unattended-upgrades

sudo mkdir -p /opt/trade && sudo chown "$USER" /opt/trade
cd /opt/trade
# Private repositories: GitHub asks for your username and a personal access token (read-only "Contents" access).
[ -d trade-analysis-platform ] || git clone https://github.com/abheeshekjangam13-star/trade-analysis-platform.git
[ -d trade-analysis-ui ] || git clone https://github.com/abheeshekjangam13-star/trade-analysis-ui.git

echo
echo "Done. Log out and back in (so 'docker' works without sudo), then:"
echo "  cd /opt/trade/trade-analysis-platform/deploy/gcp && cp .env.example .env && chmod 600 .env && nano .env"
