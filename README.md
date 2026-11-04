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

### Docker Compose

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
