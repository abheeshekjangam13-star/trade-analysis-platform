#!/usr/bin/env bash
# Creates everything the app needs in your GCP project. Run it once in Cloud Shell (console.cloud.google.com →
# the >_ button), from this folder:  bash 01-create-gcp-resources.sh
# Re-running is safe: steps that already exist are skipped.
set -euo pipefail

PROJECT_ID="${PROJECT_ID:-$(gcloud config get-value project 2>/dev/null)}"
REGION=asia-south1          # Mumbai: closest to NSE / Zerodha
ZONE=asia-south1-a
VM=trade-app
IP_NAME=trade-ip
SQL=trade-db
DB_NAME=trade_analysis
DB_USER=trade

[ -n "$PROJECT_ID" ] || { echo "Set a project first: gcloud config set project <PROJECT_ID>"; exit 1; }
echo "Project: $PROJECT_ID   Region: $REGION"
gcloud config set project "$PROJECT_ID" >/dev/null

exists() { "$@" >/dev/null 2>&1; }

echo "== Enabling APIs"
gcloud services enable compute.googleapis.com sqladmin.googleapis.com servicenetworking.googleapis.com

echo "== Static IP (register this with Zerodha; point your domain's A record at it)"
exists gcloud compute addresses describe "$IP_NAME" --region "$REGION" \
  || gcloud compute addresses create "$IP_NAME" --region "$REGION" --network-tier PREMIUM
STATIC_IP=$(gcloud compute addresses describe "$IP_NAME" --region "$REGION" --format='value(address)')

echo "== Firewall: web (80/443) to the VM only; SSH only through Google's IAP"
exists gcloud compute firewall-rules describe trade-allow-web \
  || gcloud compute firewall-rules create trade-allow-web --network default --allow tcp:80,tcp:443,udp:443 \
       --target-tags trade-web --source-ranges 0.0.0.0/0
exists gcloud compute firewall-rules describe trade-allow-iap-ssh \
  || gcloud compute firewall-rules create trade-allow-iap-ssh --network default --allow tcp:22 \
       --target-tags trade-web --source-ranges 35.235.240.0/20

echo "== Private network link for Cloud SQL (so the database has no public IP)"
exists gcloud compute addresses describe google-managed-services-default --global \
  || gcloud compute addresses create google-managed-services-default --global --purpose VPC_PEERING \
       --prefix-length 16 --network default
gcloud services vpc-peerings connect --service servicenetworking.googleapis.com \
  --ranges google-managed-services-default --network default 2>/dev/null \
  || gcloud services vpc-peerings update --service servicenetworking.googleapis.com \
       --ranges google-managed-services-default --network default --force

echo "== Cloud SQL PostgreSQL 16 (private IP, daily backups at 02:00 IST, point-in-time recovery)"
if ! exists gcloud sql instances describe "$SQL"; then
  gcloud sql instances create "$SQL" --database-version POSTGRES_16 --edition ENTERPRISE --tier db-g1-small \
    --region "$REGION" --network default --no-assign-ip --storage-type SSD --storage-size 10 --storage-auto-increase \
    --backup-start-time 20:30 --enable-point-in-time-recovery --retained-backups-count 7 \
    --maintenance-window-day SUN --maintenance-window-hour 21 --deletion-protection
fi
exists gcloud sql databases describe "$DB_NAME" --instance "$SQL" || gcloud sql databases create "$DB_NAME" --instance "$SQL"
if ! gcloud sql users list --instance "$SQL" --format='value(name)' | grep -qx "$DB_USER"; then
  DB_PASSWORD=$(openssl rand -base64 24 | tr -d '/+=' | cut -c1-24)
  gcloud sql users create "$DB_USER" --instance "$SQL" --password "$DB_PASSWORD"
  echo
  echo "   Database password for user '$DB_USER' (put it in .env as DB_PASSWORD; shown only now):"
  echo "   $DB_PASSWORD"
  echo
fi
DB_HOST=$(gcloud sql instances describe "$SQL" --format='value(ipAddresses[0].ipAddress)')

echo "== VM: e2-standard-2 (2 vCPU, 8 GB), Debian 12, 30 GB disk, with the static IP"
exists gcloud compute instances describe "$VM" --zone "$ZONE" \
  || gcloud compute instances create "$VM" --zone "$ZONE" --machine-type e2-standard-2 \
       --image-family debian-12 --image-project debian-cloud --boot-disk-size 30GB --boot-disk-type pd-balanced \
       --address "$STATIC_IP" --tags trade-web --shielded-secure-boot --deletion-protection

cat <<EOF

Done.
  Static IP (domain A record + Zerodha): $STATIC_IP
  Cloud SQL private IP (DB_HOST in .env): $DB_HOST
Next: point your domain at $STATIC_IP, then follow "3. Set up the VM" in deploy/gcp/README.md.
EOF
