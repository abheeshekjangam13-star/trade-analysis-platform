#!/usr/bin/env bash
# Optional, once: copies your local database (users, strategies, backtests, candles, …) into Cloud SQL.
# Run ON YOUR MAC from this folder, BEFORE the first `03-deploy.sh` (Cloud SQL must still be empty):
#   bash 04-copy-local-data.sh
# Needs the gcloud CLI on the Mac (brew install --cask google-cloud-sdk; gcloud auth login).
set -euo pipefail
ZONE=asia-south1-a
VM=trade-app
DUMP=trade_analysis.sql.gz

echo "== Dumping the local database"
pg_dump -h localhost -U trade -d trade_analysis --no-owner --no-privileges --format plain | gzip > "$DUMP"
ls -lh "$DUMP"

echo "== Copying it to the VM"
gcloud compute scp "$DUMP" "$VM:/tmp/$DUMP" --zone "$ZONE" --tunnel-through-iap

cat <<EOF

Now on the VM (gcloud compute ssh $VM --zone $ZONE --tunnel-through-iap):
  cd /opt/trade/trade-analysis-platform/deploy/gcp
  set -a; . ./.env; set +a
  gunzip -c /tmp/$DUMP | PGPASSWORD="\$DB_PASSWORD" psql -h "\$DB_HOST" -U "\$DB_USER" -d "\$DB_NAME" -v ON_ERROR_STOP=1 -q
  rm /tmp/$DUMP
Then run 03-deploy.sh. (Flyway sees the copied tables and continues from there.)
EOF
rm -f "$DUMP"
