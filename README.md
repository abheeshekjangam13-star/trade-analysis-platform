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
