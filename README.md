# Algo Trade Backend

A Spring Boot service that receives trading signals by webhook (for example from TradingView) and sends them to Binance as market orders. The main goal is to be **safe**: the same signal must never create two orders, and the app must handle timeouts and unknown order states correctly.

> It uses the **Binance Testnet** by default. Test everything there before you think about real money.

## How it works

1. A client sends a `POST` request to `/api/webhook` with a signal and a secret header.
2. The app checks the secret and validates the signal.
3. The signal is saved to the database first. The `signalId` is the primary key, so a second signal with the same ID is rejected (idempotency).
4. The app sends a `MARKET` order to Binance and uses the `signalId` as the order's client order ID.
5. If Binance times out (error `-1007`) or the network fails, the app does not guess. It asks Binance for the order using the same client order ID and saves the real status.
6. The client can ask for the latest status at any time with `GET /status/{signalId}`.

## Features

- Webhook protected by a secret header (`X-Webhook-Secret`)
- Input validation: `signalId` must be a UUID, `action` must be `BUY` or `SELL`, `quantity` must be greater than 0 and not more than 10
- Duplicate protection with a database primary key (also tested with concurrent requests)
- Unknown order states are saved as `UNKNOWN` and can be checked again later
- Clear HTTP status codes for each case
- File-based H2 database, so no extra database installation is needed

## Tech stack

- Java 17
- Spring Boot 4.1 (Web MVC, Data JPA)
- H2 database
- Binance Connector Java 3.3.0
- Lombok
- Maven (Maven Wrapper included)

## Project structure

```
src/main/java/com/example/demo/
├── controller/   # WebhookController (POST /api/webhook, GET /status/{signalId})
├── service/      # SignalProcessingService (validation, idempotency, Binance calls)
├── repository/   # SignalRepository
├── entity/       # SignalRecord
├── dto/          # TradingSignalRequest, SignalResponse
└── config/       # Binance client and settings
```

## Getting started

### Requirements

- JDK 17
- A Binance Spot Testnet API key and secret (https://testnet.binance.vision)

### Steps

1. Copy the example settings file to the resources folder:

   ```bash
   mkdir -p src/main/resources
   cp application.properties.example src/main/resources/application.properties
   ```

   This file is ignored by Git, so your keys stay private.

2. Open `src/main/resources/application.properties` and fill in your values:

   ```properties
   binance.api.key=YOUR_BINANCE_API_KEY
   binance.api.secret=YOUR_BINANCE_SECRET_KEY
   webhook.secret=A_LONG_RANDOM_STRING
   ```

   If you do not set `binance.api.base-url`, the app uses `https://testnet.binance.vision`.

3. Start the app:

   ```bash
   ./mvnw spring-boot:run
   ```

   The service runs at `http://localhost:8080`.

## API

### `POST /api/webhook`

Header: `X-Webhook-Secret: <your webhook.secret>`

```json
{
  "signalId": "3f2b8c1e-5a47-4d9e-9a8b-1c2d3e4f5a6b",
  "symbol": "BTCUSDT",
  "action": "BUY",
  "price": 65000.0,
  "quantity": 0.001
}
```

Example with curl:

```bash
curl -X POST http://localhost:8080/api/webhook \
  -H "Content-Type: application/json" \
  -H "X-Webhook-Secret: YOUR_WEBHOOK_SECRET" \
  -d '{"signalId":"3f2b8c1e-5a47-4d9e-9a8b-1c2d3e4f5a6b","symbol":"BTCUSDT","action":"BUY","price":65000,"quantity":0.001}'
```

Response:

```json
{ "signalId": "3f2b8c1e-...", "status": "FILLED", "message": "..." }
```

### `GET /status/{signalId}`

Same secret header. Asks Binance again if the saved status is `UNKNOWN`, `NEW`, or `PARTIALLY_FILLED`, and returns the latest status.

### Status codes

| Code | Meaning |
|------|---------|
| 200 | Order handled (`FILLED`, `NEW`, `PARTIALLY_FILLED`, or another Binance status) |
| 202 | Order state is unknown. Check later with `GET /status/{signalId}` |
| 400 | Bad request, or Binance rejected the order |
| 401 | Missing or wrong secret header |
| 409 | Duplicate `signalId`, the signal was already processed |
| 500 | Unexpected error |

## Tests

```bash
./mvnw test
```

The tests in `SignalProcessingServiceTest` cover these cases:

- Many identical signals at the same time: only one goes through
- Duplicate signals sent one after another are rejected
- An `UNKNOWN` record stays in the database after an error
- Re-checking an order: `UNKNOWN` to `FILLED`
- Re-checking an order that Binance cannot find stays `UNKNOWN`

## Quick manual test

`test.py` sends one test signal to the local server.

```bash
pip install requests
export WEBHOOK_SECRET=YOUR_WEBHOOK_SECRET
python test.py
```

## Security notes

- Never commit your API keys or webhook secret. `application.properties` is already in `.gitignore`.
- Use a long random value for `webhook.secret`.
- Create your Binance API keys without withdrawal permission.
