# SaiETF Shioaji SSE Gateway Contract

Version line: V1.0.62

SaiETF does not embed the Shioaji Python SDK or broker credentials in the Android APK.
A user-controlled HTTPS gateway may expose authorized market data to SaiETF over Server-Sent Events.

## Request

SaiETF connects to the configured HTTPS endpoint and adds a `symbols` query parameter:

`GET /quotes?symbols=0050,2330`

Headers:
- `Accept: text/event-stream`
- optional `Authorization: Bearer <token>`

The bearer token is encrypted locally with Android Keystore and is not exported in SaiETF backup JSON.

## Event payload

Each SSE market event uses a standard `data:` JSON payload:

```json
{
  "symbol": "2330",
  "price": 1425.0,
  "previousClose": 1400.0,
  "open": 1410.0,
  "high": 1430.0,
  "low": 1405.0,
  "volume": 123456,
  "bid": 1424.0,
  "ask": 1425.0,
  "timestamp": 1800000000000,
  "sequence": 88,
  "sessionDate": "2027-01-15",
  "exchange": "TWSE",
  "market": "TSE",
  "isClose": false
}
```

Heartbeat events may use `{"type":"heartbeat"}`.

## Failover semantics

- Fugle remains Primary.
- Shioaji SSE is activated only while Fugle is not HEALTHY and the gateway is configured.
- Shioaji quotes enter the same MarketDataCenter arbitration path with `fallbackLevel=1`.
- A fresh Shioaji quote remains LIVE; fallback does not imply stale.
- When Fugle returns to HEALTHY, SaiETF closes the secondary SSE connection.
- TWSE MIS and Yahoo remain polling fallback after the streaming sources.
