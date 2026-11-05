# Trade Analysis Platform

A microservice-based trading analysis tool built with **Java 21** and **Spring Boot 3.5**.

## Architecture

```
                      ┌──────────────────┐
                      │   API Gateway    │  :8080
                      │  (Spring Cloud)  │
                      └────────┬─────────┘
                               │  lb://  (Eureka)
          ┌────────────────────┼────────────────────┐
          ▼                    ▼                    ▼
  ┌───────────────┐   ┌───────────────┐   ┌──────────────────┐
  │  market-data  │   │  indicator    │   │    strategy      │
  │   service     │   │  service      │   │    service       │
  │   :8081       │   │  :8082        │   │    :8083         │
  └───────────────┘   └───────────────┘   └──────────────────┘
          │                                        │
          │                             (Feign)    │
          └────────────────────────────────────────┘
                                │
                        ┌───────────────┐
                        │ discovery     │
                        │ server        │  :8761
                        └───────────────┘
```

## Services

| Service              | Port | Description |
|----------------------|------|-------------|
| `discovery-server`   | 8761 | Eureka service registry |
| `api-gateway`        | 8080 | Spring Cloud Gateway |
| `market-data-service`| 8081 | OHLCV data (Yahoo Finance, H2 cache, auto-refresh) |
| `indicator-service`  | 8082 | SMA · EMA · RSI · MACD · Bollinger Bands · VWAP |
| `strategy-service`   | 8083 | Strategy CRUD + back-test engine |
| `common`             | —    | Shared DTOs, exception handlers |

## Quick Start

### Prerequisites
- Java 21+, Maven 3.9+

```bash
# Build all modules
mvn clean package -DskipTests

# Start in order:
java -jar discovery-server/target/discovery-server-1.0.0-SNAPSHOT.jar &
java -jar api-gateway/target/api-gateway-1.0.0-SNAPSHOT.jar &
java -jar market-data-service/target/market-data-service-1.0.0-SNAPSHOT.jar &
java -jar indicator-service/target/indicator-service-1.0.0-SNAPSHOT.jar &
java -jar strategy-service/target/strategy-service-1.0.0-SNAPSHOT.jar &
```

## Database (PostgreSQL)

All services share one PostgreSQL database, `trade_analysis`, in one schema. Tables are created and
versioned by **Flyway** (`*/src/main/resources/db/migration`); Hibernate only validates against them
(`ddl-auto: validate`). market-data-service and strategy-service each keep their own Flyway history table
(`flyway_history_market_data`, `flyway_history_strategy`). H2 is used only in tests (PostgreSQL mode).

### 1. Install PostgreSQL and create the database

Postgres.app (https://postgresapp.com) or Homebrew (`brew install postgresql@16 && brew services start postgresql@16`), then:

```bash
psql postgres -c "CREATE ROLE trade WITH LOGIN PASSWORD 'choose-a-password'"
psql postgres -c "CREATE DATABASE trade_analysis OWNER trade"
```

### 2. Point the services at it

Connection settings come from environment variables (set them where you start the services, e.g. the IDE run
configurations); only `DB_PASSWORD` has no default:

| Variable | Default |
|---|---|
| `DB_HOST` / `DB_PORT` | `localhost` / `5432` |
| `DB_NAME` | `trade_analysis` |
| `DB_USER` | `trade` |
| `DB_PASSWORD` | — |

Start market-data-service and strategy-service once: Flyway creates every table.

### 3. Copy the old H2 data (one time)

The earlier data lives in `data/market-data.mv.db` and `data/strategy.mv.db`. With the services connected to
PostgreSQL (so the H2 files are no longer in use) and before calling any API:

```bash
DB_PASSWORD=choose-a-password ./mvnw -q -pl tools/h2-to-postgres exec:java
```

It copies candles, strategies (with parameters), backtests, trade logs and position state with their original ids,
moves each id sequence past the copied rows and checks the row counts. It refuses to copy into a table that already
has rows. Keep `data/` as a backup until you are happy with the result.

### Tables

| Area | Tables |
|---|---|
| Market data | `candle` |
| Strategies & backtests | `strategy`, `strategy_param`, `backtest_result`, `nifty_trade_log`, `nifty_position_state` |
| Stored reports (for the UI) | `backtest_daily_report` (every trading day: traded or why not), `backtest_monthly_stat`, `portfolio`, `portfolio_strategy`, `portfolio_report`, `portfolio_report_item` |
| Users & audit | `app_user`, `app_role` (ADMIN, TRADER, VIEWER), `app_user_role`, `access_log` (written by the gateway for every `/api/**` call) |

Strategies, backtests, portfolios and portfolio reports record the user who created or ran them
(`created_by_user_id`, `run_by_user_id`).

## Trading (paper and live orders through Kite)

strategy-service has a trading module (`/api/trading/**`, UI page **Trading**). Every order — manual or from a
strategy — goes through one order service that records it in `trade_order` and applies the safety rules.

**Modes.** `PAPER` orders are simulated and fill at once at the option's last traded price. `LIVE` orders go to Kite.
Each strategy is `OFF` (default), `PAPER` or `LIVE`.

**Safety rules** (exits that close a position are always allowed):
- Live orders are refused unless strategy-service runs with `TRADING_LIVE_ENABLED=true` (default false).
- Switching a strategy to LIVE, placing manual LIVE orders, changing limits and releasing the kill switch need an
  **ADMIN** user (see Login below).
- Kill switch (`POST /api/trading/kill-switch`, anyone): all strategies OFF, open live orders cancelled, open strategy
  positions closed, no new orders until an ADMIN releases it.
- Limits (`trading_control`): max orders per day (per mode), max quantity per order (default 130 = 2 lots), daily
  loss limit (default ₹5,000 realized).
- Manual orders need `confirm=true` (the UI shows a review step).

**Live runner.** Every minute from 09:20 to 15:31 IST on weekdays, for each PAPER/LIVE strategy (type
`NIFTY_OPENING_BREAKOUT`) it reruns the backtest engine on today's completed candles and follows it: a new engine
entry buys the option (nearest weekly expiry, engine strike, lots × Kite lot size), an engine exit sells it. Live
orders are marketable LIMIT orders 3% through the last price. Signals older than 5 minutes are never entered (late
start). Positions are in `live_position`, with real option P&L once fills have prices (otherwise the engine estimate).
Decisions are made on completed 1-minute/5-minute candles, so exits can lag a tick-based stop by up to a minute.

**Kite session.** market-data-service stores the session in `kite_session` after login (so restarts keep it and
strategy-service can trade with it). One-click login: run the UI with `npm run dev:https`, set your Kite app's
redirect URL to `https://localhost:5173/kite/callback` (or `http://…` with plain `npm run dev`) and use
**Connect to Kite** in the UI. Tokens are never returned by the API and
are masked in the access log.

**Before going live:** Zerodha requires API orders to come from a static IP registered with them (SEBI retail-algo
rules); enable the F&O segment; log in to Kite every trading day; run strategies in PAPER first and compare with
the backtests; start live with the smallest quantity.

| Endpoint | Purpose |
|---|---|
| `GET /api/trading/status` | Kite connection, live switch, market hours, limits, today's orders and realized P&L |
| `GET/PUT /api/trading/strategies[/{id}]` | Strategy modes (`{"mode":"PAPER","product":"MIS"}`) |
| `POST /api/trading/runner/run` | Run the live runner once now |
| `GET/POST /api/trading/orders`, `POST /api/trading/orders/{id}/cancel` | Platform orders; manual order `{tradingsymbol, transactionType, quantity, product, orderType, price, triggerPrice, mode, confirm}` |
| `GET /api/trading/positions` | Strategy positions today |
| `GET /api/trading/broker/margins|positions|orders` | Kite account |
| `GET /api/trading/options/expiries`, `/options/chain?expiry=`, `/ltp?symbols=` | NIFTY option contracts and prices |
| `POST/DELETE /api/trading/kill-switch`, `GET/PUT /api/trading/controls` | Kill switch and limits |
| `GET /api/market-data/admin/kite/login-url`, `/admin/kite/status` | One-click Kite login and session status |

### Master strategies (always restored)

`strategy-service/src/main/resources/master-data/strategies.json` holds the strategies that must never be lost:
**#55** (PE gap-down breakdown) and **#56** (CE gap-up breakout), with their exact names, descriptions and parameters.
On every start strategy-service recreates any of them that are missing, with the same id, and moves the id sequence
past them. Rows that exist are never changed (a different name under the same id is only logged). To protect another
strategy, add it to the file; to change a master strategy, create a new strategy instead (strategies are immutable).
Set `master-data.enabled=false` to switch this off.

### Login

Everyone logs in with a username and password; every `/api` call then needs `Authorization: Bearer <token>`.

- **First time:** the UI opens on *Create the admin account* (or call `POST /api/auth/setup` once). That user gets
  ADMIN and TRADER. If the username already exists (e.g. `abheeshek` from earlier X-User use), it keeps its history and
  gets a password and ADMIN. Setup works only while no admin has a password.
- **Sign-up:** new users click *Create an account* on the login screen (`POST /api/auth/signup`): username, password,
  **email and phone (required)**, optional name, and the plan they want. Phones are stored as `+91XXXXXXXXXX` (other
  countries need the +code); email, phone and username must be unique. The account is `PENDING` and can't log in until
  an ADMIN approves it on the Users page (`POST /api/users/{id}/approve`) or rejects it (`/reject`).
- **Plans** (`app_user.plan`, role kept in step):

  | Plan | Price | May do | Role |
  |---|---|---|---|
  | FREE | ₹0 | View strategies, backtests, reports, trades, market data | VIEWER |
  | GOLD | ₹999/month | + create strategies and portfolios, run backtests/reports, paper trading | TRADER |
  | PREMIUM | ₹1,999/month | + live trading on the user's own Kite account (**not built yet**: LIVE stays admin-only) | PREMIUM |

  There is no payment gateway: the admin confirms payment (UPI etc.), then approves with the plan and a *paid until*
  date (default one month; blank = no expiry). After that date the user counts as FREE until the admin renews it
  (`PUT /api/users/{id}/plan`). Plan changes apply on the next request. ADMINs can do everything.
- **Admin-only whatever the plan:** user management (`/api/users/**` except `/me`), the access log
  (`/api/access-log`: who called what, from which IP; hidden from everyone else in the UI), the Kite broker account
  (`/api/trading/broker/**`), trading limits, kill switch, running the live runner, LIVE strategies and live orders,
  and Kite session/instrument admin (`/api/market-data/admin/**`, checked at the gateway). Non-admins see only PAPER
  orders and positions, can cancel only their own paper orders, and don't see whose Kite account is connected.
- **Whose data:** every user sees only their own data; ADMINs see everyone's (with an Owner/User column in the UI).
  Private per user: strategies they created, backtests they ran (with daily/monthly reports and trades), portfolios
  and portfolio reports, paper trading (each user's own OFF/PAPER setting per strategy, positions, orders, and paper
  order/loss limits). Another user's rows answer 404, as if they didn't exist. Shared, read-only:
  the master strategies #55 and #56 (`GET /api/strategies/shared`); anyone may backtest or paper trade them, and those
  results belong to whoever ran them. Strategy and portfolio names only need to be unique per user. Rows from before
  users existed belong to the first admin. The live runner runs each strategy's engine once and trades it separately
  for every user who switched it on.
- **Other users:** an ADMIN can also add users directly with a password (or set a password for existing users); they
  change it under *My account*. An admin can't disable themself or remove their own ADMIN role.
- **Passwords:** 8–128 characters with a letter and a digit, stored as BCrypt hashes (`app_user.password_hash`), never
  returned by the API. 5 wrong passwords lock the account for 15 minutes. Wrong username and wrong password give the
  same message.
- **Tokens:** HS256 JWTs signed by strategy-service, valid 8 hours (`auth.token-hours`). The signing key is
  `AUTH_JWT_SECRET` (32+ characters, set the same value on api-gateway and strategy-service) or, if unset, a random key
  strategy-service creates once in the `auth_key` table, which the gateway reads. Changing or deleting it logs
  everyone out. Disabling a user blocks their existing tokens at once; a password change does not end other sessions
  (they expire within 8 hours).
- **Where it is checked:** api-gateway rejects requests without a valid token (401) and replaces any client `X-User`
  header with the verified username (the access log records only verified users). strategy-service checks the token
  too, so calling port 8083 directly doesn't skip login. Open without a token: `GET /api/auth/status`,
  `GET /api/auth/plans`, `POST /api/auth/signup`, `POST /api/auth/setup`, `POST /api/auth/login`. Only ADMINs can read the access log.
- **Not yet covered:** market-data-service (8081) and indicator-service (8082) don't check tokens when called
  directly. Only expose port 8080 (the gateway) outside your machine. The kill switch, max quantity per order and the live
  limits are platform-wide.
  Sign-up has no CAPTCHA or email/phone verification yet.
- **Bruno:** set `user` and the secret `password` in the environment, run *auth/Login* (or *auth/Setup* the first
  time); it stores the token that every request sends.
- **Development without login:** `AUTH_ENABLED=false` on the gateway and `AUTH_TRUST_USER_HEADER=true` (plus
  optionally `AUTH_AUTO_REGISTER_USERS=true`) on strategy-service restores the old trusted `X-User` header. Never do
  this on a reachable machine.

### Clone a strategy with your own lots

`POST /api/strategies/{id}/clone` with `{"lots": 5, "name": "optional"}` copies a strategy you can see (your own or a
shared master strategy) into a new strategy you own, changing only `lots` (1–50). The copy records
`clonedFromStrategyId`; the default name is "<source> (5 lots)", numbered if you already have one. Needs the Gold plan.

### Create a strategy from a description

`POST /api/strategies/parse` with `{"description": "CE only on gap-up days above 0.2%, 3 lots, 1 trade per day, trail on
5-min lows, target 80 points, no entry after 14:00, skip Thursdays"}` returns the strategy type, the parameters it read
(each with the words it came from), warnings for out-of-range values, and sentences it did not understand. Nothing is
saved: the UI shows the result, the user edits and confirms, then the strategy is created with `POST /api/strategies`.
The parser is rule based (no AI); it knows every `NIFTY_OPENING_BREAKOUT` parameter plus the SMA, RSI and MACD types.

### Email notifications

strategy-service emails users when they sign up (admins get an alert too), when an admin creates their account, and
when their sign-up is approved or rejected. The admin's approve/reject note is included as **Remarks**. Emails are
written to the `email_notification` table in the same transaction as the change and sent every 15 s; failures are
retried up to 5 times (`GET /api/notifications/emails`, `POST /api/notifications/emails/{id}/retry`, admin only).

| Env var | Example | Meaning |
|---|---|---|
| `MAIL_HOST` | `smtp.gmail.com` | SMTP server. **Unset = emails are only logged** (status `LOGGED`) |
| `MAIL_PORT` | `587` | SMTP port (STARTTLS) |
| `MAIL_USERNAME` / `MAIL_PASSWORD` | `you@gmail.com` / app password | SMTP login (Gmail needs an [app password](https://myaccount.google.com/apppasswords)) |
| `MAIL_FROM` | `Trade Analysis <you@gmail.com>` | Sender; defaults to `MAIL_USERNAME` |
| `APP_URL` | `https://trade.example.com` | UI address used in email links |

### Report and user APIs

| Endpoint | Purpose |
|---|---|
| `GET /api/backtests/{backtestId}/daily` | Stored day-by-day report of a backtest |
| `GET /api/backtests/{backtestId}/monthly` | Stored month-wise statistics |
| `POST /api/portfolios` | Create a portfolio `{name, description, strategyIds}` |
| `POST /api/portfolios/{id}/reports?windows=30,90,180,360&capital=100000` | Backtest each strategy per window and store the combined report |
| `GET /api/portfolios/{id}/reports` | Stored portfolio reports, newest first |
| `GET/POST /api/users`, `GET /api/users/me`, `PATCH /api/users/{id}`, `GET /api/users/roles` | Users and roles |
| `GET /api/access-log?username=&limit=100` | Recent API calls |

### Docker Compose

Compose also starts PostgreSQL; set `DB_PASSWORD` first.

```bash
mvn clean package -DskipTests
docker compose up --build
```

## API Usage

All requests go through the gateway on port 8080.

### Market Data
```bash
# Get 200 daily candles for AAPL
GET /api/market-data/AAPL?interval=DAY_1&limit=200

# Get candles in a range
GET /api/market-data/AAPL/range?interval=DAY_1&from=2024-01-01T00:00:00Z&to=2024-06-01T00:00:00Z
```

### Indicators
```bash
# List supported indicators
GET /api/indicators

# Compute RSI(14) — POST the PriceSeries in the body
POST /api/indicators/compute
{
  "indicator": "RSI",
  "series": { ... },
  "parameters": { "period": 14 }
}
```

### Strategy & Backtest
```bash
# Create a strategy
POST /api/strategies
{
  "name": "SMA 10/30",
  "type": "SMA_CROSSOVER",
  "parameters": { "fast": "10", "slow": "30" }
}

# Run a back-test
POST /api/backtests?strategyId=1&symbol=AAPL&lookbackDays=365&capital=100000

# Get results
GET /api/backtests/1
```

### Nifty Opening Breakout (`NIFTY_OPENING_BREAKOUT`)

Underlying NIFTY 50 spot, 5-minute candles, IST throughout.

| Rule | Behaviour |
|---|---|
| Reference candle | 09:15–09:20; record high/low |
| Entry | Completed candle closes above ref high → buy CE at ATM−50; below ref low → buy PE at ATM+50 (ATM = nearest 50). Fresh breakout only |
| Limits | 1 open position, 2 entries per day |
| Initial SL | `(refHigh + refLow) / 2` for both directions, set when the order is placed (not the breakout candle) |
| Trailing SL | After entry, on each **completed 1-minute** candle: CE `sl = max(sl, prev 1-min low)`, PE `sl = min(sl, prev 1-min high)`; never moves back. 1-min bars inside the breakout candle are not used |
| SL hit | CE when spot ≤ SL, PE when spot ≥ SL |
| EOD | Square-off at 15:20 |

Strategy parameters (defaults reproduce the rules above):

| Parameter | Default | Meaning |
|---|---|---|
| `lots` / `lotSize` | 2 / 75 | Quantity = lots × lotSize |
| `directions` | `BOTH` | `BOTH`, `CE` (bullish only) or `PE` (bearish only) |
| `maxTradesPerDay` | 2 | Entries per day |
| `trailMinutes` | 1 | Trail on N-minute blocks of 1-min candles, aligned to 09:15 |
| `targetPoints` | 0 | Exit when spot moves this many points in favour (0 = no target; SL checked first) |
| `gapFilter` | `NONE` | Trade only on `GAP_UP`, `GAP_DOWN`, `ANY_GAP` or `FLAT` days (09:15 open vs previous close) |
| `gapPct` | 0.2 | Gap threshold in % |
| `costPerTrade` | 0 | ₹ per round trip for the whole quantity, deducted from estimated P&L |
| `entryFrom` | unset | `HH:mm` IST. Only 5-min candles starting at or after this time may open a trade; the breakout must still be fresh on that candle (a day whose breakout happened earlier trades only if spot dips back to ≤ refHigh and breaks out again). Unset = no limit |
| `entryUntil` | unset | `HH:mm` IST. Only 5-min candles starting before this time may open a trade. Unset = no limit |
| `maxGapPct` | 0 (off) | Skip the day when the absolute gap is ≥ this many % (e.g. `2.0` skips crash/blow-off opens) |
| `trailAfterPoints` | 0 (off) | Keep the stop at the reference midpoint until spot has moved this many points in favour of the entry (checked on 1-min highs/lows after the order); only then trail on completed blocks |
| `breakevenOnTrail` | `false` | With `trailAfterPoints`, also lift the stop to the entry price at that moment |
| `breakevenLockPoints` | 0 | With `breakevenOnTrail`, put that stop this many points beyond entry in the trade's favour (CE entry + x, PE entry − x), so a trade that comes back still closes in profit. Must be below `trailAfterPoints` |
| `failedBreakoutPoints` | 0 (off) | Needs `trailAfterPoints`. Before the stop starts trailing, exit at the close of a 5-min candle that closes back inside the reference range by more than this many points (CE: close < refHigh − x, PE: close > refLow + x). Exit reason `FAILED_BREAKOUT` |
| `maxStopPoints` | 0 (off) | Initial stop is the closer of the reference midpoint and entry ∓ this many points |
| `skipDays` | none | Comma-separated weekdays not to trade, e.g. `THU` or `MON,FRIDAY` (case-insensitive, 3-letter or full names). A skipped day still provides the previous close for the next day's gap |

The CE gap-up variant (`bruno/strategies/Create Nifty CE Gap-Up Breakout Strategy.bru`) uses
`directions=CE, maxTradesPerDay=1, trailMinutes=5, targetPoints=120, gapFilter=GAP_UP, gapPct=0.2, costPerTrade=85`.
`bruno/strategies/Create Nifty CE Gap-Up Breakout 1030 Strategy.bru` is the same with `targetPoints=80` and `entryFrom=10:30`.
`bruno/strategies/CE Gap-Up Delayed Trail - skip THU.bru` uses `targetPoints=80` with
`maxGapPct=2.0, entryUntil=14:00, trailAfterPoints=20, breakevenOnTrail=true, skipDays=THU`.
`trailAfterPoints`/`breakevenOnTrail` apply to backtests only; the live stop-loss monitor still trails from entry.
`bruno/strategies/PE Gap-Down Delayed Trail Lock5 - skip THU.bru` is the independent PE strategy:
`directions=PE, gapFilter=GAP_DOWN`, the same rules as the CE one plus `failedBreakoutPoints=10, maxStopPoints=40, breakevenLockPoints=5` (also `skipDays=THU`).
These two PE-only rules make the CE strategy worse in backtests, so the CE request does not use them.

Backtest notes: there are no ticks, so the SL is checked against each 1-minute candle's low/high and filled at the SL (or at the open if the candle gapped through it). If 1-minute data is missing for a 5-minute window, that window is checked on the 5-minute candle without trailing. P&L is an estimate: spot move × lots × lotSize × 0.5 delta, with `lotSize` taken from strategy parameters. The engine requests 5-minute and 1-minute candles from `/api/market-data/{symbol}/range`, which backfills missing history from Kite (split into ≤100-day / ≤60-day requests), so the Kite access token must be valid.

Live/paper stop-loss management is in `strategy-service/.../live/NiftyStopLossMonitor` (state persisted in `nifty_position_state`). It handles trailing, gap recovery, restart restore, single exit per stop hit, and fill verification. Only `PaperExitOrderGateway` (dry run) exists. **No Kite WebSocket feed, entry order flow, live order gateway or broker reconciliation is wired up yet**, so nothing places real orders.

## Adding Features

* **New indicator** — add a `@Component` that implements `IndicatorEngine` in `indicator-service`. It is auto-discovered at startup.
* **New strategy type** — add a branch in `BacktestEngine.generateSignals()`.
* **New service** — create a Maven module, register it in the parent POM and add a route in `api-gateway/application.yml`.

## Swagger UI (per service, direct)

| Service            | URL |
|--------------------|-----|
| market-data        | http://localhost:8081/swagger-ui.html |
| indicator          | http://localhost:8082/swagger-ui.html |
| strategy           | http://localhost:8083/swagger-ui.html |
