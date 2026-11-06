# Deploying on Google Cloud (GCP)

```
            your domain (HTTPS)                      Zerodha sees this static IP
                    │                                            ▲
                    ▼                                            │
 ┌──────────────── VM "trade-app" · e2-standard-2 · asia-south1 (Mumbai) · static IP ───────────────┐
 │  web (Caddy): UI + free HTTPS certificate ── /api/* ──► api-gateway ──► strategy / market-data /  │
 │                                                          (Eureka)      indicator services          │
 └──────────────────────────────────────────────┬───────────────────────────────────────────────────┘
                                                 │ private network (no public IP)
                                   Cloud SQL PostgreSQL 16 "trade-db" (daily backups)
```

- **One VM** runs everything in Docker. Only ports 80/443 are open to the internet; SSH goes through Google's
  IAP tunnel, so port 22 is not open to the world.
- **Cloud SQL** keeps the data, with automatic daily backups (7 kept) and point-in-time recovery.
- The VM's **static IP** is the one address Zerodha sees for API orders (SEBI requires a registered static IP).
- Files here: `01-create-gcp-resources.sh` (GCP resources), `02-setup-vm.sh` (Docker on the VM),
  `03-deploy.sh` (deploy/update), `04-copy-local-data.sh` (optional: copy your local data),
  `docker-compose.prod.yml`, `services.Dockerfile`, `web.Dockerfile`, `Caddyfile`, `.env.example`.

**Cost (approx., Mumbai):** VM ~$59, Cloud SQL db-g1-small ~$30, disk + static IP ~$8 → **about $95–100
(₹8,000–8,500) a month**. The $300 free credit covers roughly the first 3 months. Set a budget alert (step 1).

## 0. Before you start

1. **Commit and push both repositories** – the VM deploys what is on GitHub (`main`).
2. Have your domain's DNS settings at hand (you'll add one `A` record).
3. Decide the subdomain, e.g. `trade.yourdomain.com`.

## 1. GCP account and project (in the browser)

1. Create the account at cloud.google.com (free trial), create a project, e.g. `trade-analysis`.
2. Billing → **Budgets & alerts** → budget ₹10,000/month with alerts at 50 %, 90 %, 100 %.

## 2. Create the resources (Cloud Shell, ~10 minutes)

Open Cloud Shell (the `>_` button in the console), then:

```bash
gcloud config set project YOUR_PROJECT_ID
git clone https://github.com/abheeshekjangam13-star/trade-analysis-platform.git
cd trade-analysis-platform/deploy/gcp && bash 01-create-gcp-resources.sh
```

It prints three things — **save them**:
- the **static IP**,
- the **Cloud SQL private IP** (`DB_HOST`),
- the **database password** (shown only once).

Then, at your domain registrar, add a DNS record: **`A`  `trade` → the static IP** (TTL 300).

## 3. Set up the VM

```bash
gcloud compute ssh trade-app --zone asia-south1-a --tunnel-through-iap
# on the VM:
curl -fsSLO https://raw.githubusercontent.com/abheeshekjangam13-star/trade-analysis-platform/main/deploy/gcp/02-setup-vm.sh
bash 02-setup-vm.sh        # private repos: GitHub username + a read-only personal access token
exit                       # log out and back in so docker works without sudo
```

(If the repository is private, the `curl` line needs a token too; or paste the script's contents into
`nano 02-setup-vm.sh` instead.)

## 4. Settings (.env on the VM)

```bash
gcloud compute ssh trade-app --zone asia-south1-a --tunnel-through-iap
cd /opt/trade/trade-analysis-platform/deploy/gcp
cp .env.example .env && chmod 600 .env
openssl rand -base64 48   # → AUTH_JWT_SECRET
openssl rand -base64 32   # → KITE_ENCRYPTION_KEY  (keep it forever: it decrypts users' saved Kite secrets)
nano .env                 # DOMAIN, ACME_EMAIL, DB_HOST, DB_PASSWORD, the two keys above, KITE_API_KEY/SECRET
```

Keep a copy of `.env` somewhere safe (a password manager). It is the only place these secrets live.

## 5. (Optional) Copy your local data

To keep your users, strategies, backtests and candle history, run `04-copy-local-data.sh` on your Mac **before
the first deploy** and follow what it prints. Skip it to start with an empty database (the first visitor then
creates the admin account on the login page).

## 6. Deploy

```bash
bash /opt/trade/trade-analysis-platform/deploy/gcp/03-deploy.sh
```

The first build takes ~10–15 minutes (Maven + npm). It prints `Up: https://trade.yourdomain.com` when ready.
Caddy gets the HTTPS certificate automatically once the DNS record points at the VM.

**Updates later:** push to GitHub, then run the same `03-deploy.sh` again.

## 7. After the first deploy

- **Kite apps:** a Kite Connect app has one redirect URL. Set the platform app's redirect URL to
  `https://trade.yourdomain.com/kite/callback` (or create a separate app for production, so your local setup
  keeps working). Users' own Kite apps need the same redirect URL.
- **Log in to Kite** from Market data (admin) each trading day, as before.
- **Razorpay** (if used): webhook URL `https://trade.yourdomain.com/api/payments/webhook`, then set the three
  `RAZORPAY_*` values in `.env` and run `03-deploy.sh`.
- **Live trading** stays off (`TRADING_LIVE_ENABLED=false`). To turn it on: register the static IP with Zerodha
  (Kite developer console → your app → IP whitelist), run strategies in PAPER first, then set it to `true` and
  redeploy.

## Everyday commands (on the VM, in `deploy/gcp`)

```bash
docker compose -f docker-compose.prod.yml ps                       # what is running
docker compose -f docker-compose.prod.yml logs -f strategy-service # follow one service's log
docker compose -f docker-compose.prod.yml restart strategy-service # restart one service
```

Backups: Cloud SQL → `trade-db` → Backups (daily, 7 kept; restore from there or to a point in time).
