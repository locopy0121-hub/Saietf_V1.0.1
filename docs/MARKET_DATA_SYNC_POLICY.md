# SaiETF Market Data Synchronization Policy

Version line: V1.0.62

## Goal

SaiETF keeps the UI synchronized with the freshest verified Taiwan-market data while avoiding abusive polling, source-limit evasion, stale-session contamination, and unnecessary duplicate requests.

The app refresh loop may run every 1 second during the trading session, but that does **not** mean every external HTTP source is called every second. The MarketDataCenter owns network pacing and serves synchronized cached data between source fetches.

## Current source path

1. Fugle WebSocket `trades`: preferred live stream when the user has configured a valid API key in Android Keystore.
2. TWSE MIS: primary HTTP polling fallback for listed and OTC symbols.
3. Yahoo: secondary HTTP fallback when upstream sources do not resolve a requested symbol.
4. MarketDataCenter same-session cache / Memory Hot Store: latest accepted quote used between source updates.
5. Prior-session cache: diagnostics only during an active session; it must not freeze today's valuation.

## Synchronization rules

- One MarketDataCenter is the single source of truth for dashboard, holdings, market wall, and instrument pages.
- UI refresh during the trading session: 1 second.
- TWSE MIS minimum network interval: 1 second.
- Yahoo minimum network interval: 15 seconds.
- Only unresolved symbols flow to the next provider.
- Requests are batched where the provider supports batching.
- Same-session cache is reused between network fetch windows.
- Quote timestamp and Taipei session date are validated before publishing.
- Partial quote coverage never masquerades as a complete portfolio valuation.

## Provider protection

SaiETF does not attempt to bypass provider restrictions.

- HTTP 429: enter cooldown; honor Retry-After when supplied.
- HTTP 403: enter a longer cooldown rather than retry aggressively.
- Network or 5xx failures: exponential backoff capped by source policy.
- A source in cooldown is skipped while fallback sources and synchronized cache continue serving the app.
- No multi-account rotation, identity spoofing, proxy rotation, request flooding, or other rate-limit evasion is part of the design.

No client can guarantee that a third-party service will never throttle or block requests. The implementation instead minimizes that risk by respecting service signals and failing over cleanly.

## Streaming upgrade path

Authenticated streaming is preferred over high-frequency REST polling when an authorized provider is configured.

Planned adapters:
- Fugle WebSocket: trades / aggregates / candles / books.
- Shioaji streaming or officially supported realtime interfaces where account authorization and usage terms permit.

API keys or broker credentials must not be committed to the repository. An adapter is enabled only after the user configures valid credentials through an approved secret/configuration path.

## Data classes

Every published quote must retain:
- symbol
- source
- source timestamp
- session date derived in Asia/Taipei
- quality: LIVE / DELAYED / STALE
- OHLC when supplied by the source

Provider runtime diagnostics retain:
- availability: READY / THROTTLED / COOLDOWN
- consecutive failure count
- last attempt
- last success
- next allowed attempt

## Failure semantics

If all live sources fail:
- same-session cached data may remain visible with its real timestamp and quality;
- prior-session data is marked stale and excluded from an active-session complete valuation;
- unresolved symbols remain explicit;
- the UI must not invent, extrapolate, or silently substitute a fake price.


## V1.0.59 Fugle WebSocket contract

- Endpoint: `wss://api.fugle.tw/marketdata/v1.0/stock/streaming`.
- Authenticate only after the socket opens; API keys are never committed to GitHub and are stored locally using Android Keystore AES/GCM.
- The first live channel is `trades`; received trade events are normalized into `MarketQuote` and published to the Memory Hot Store.
- Server heartbeat is expected roughly every 30 seconds. SaiETF sends an application-level ping after prolonged silence and reconnects if the stream remains silent beyond the watchdog timeout.
- Reconnect uses bounded exponential backoff; an authentication rejection stops automatic retry until credentials change.
- Subscription updates are diffed. Adding/removing holdings or watched symbols sends only the required subscribe/unsubscribe operations and does not intentionally rebuild the whole connection.
- Fresh Fugle quotes are used before polling. If no fresh stream quote is available, the existing TWSE MIS → Yahoo fallback path remains active.
- Trial messages are not accepted into portfolio valuation.

- Android WebSocket transport uses OkHttp 5.3.2, selected to keep the current compileSdk 36 contract intact.
- Provider health is considered unhealthy after 75 seconds without a server message; the watchdog then reconnects instead of treating a quiet symbol as a provider failure.


## V1.0.60 Arbitration and Circuit Breaker contract

- Every candidate quote is normalized before it can replace the Memory Hot Store or same-session cache.
- Arbitration order: session date → same-source sequence → source timestamp → quality → fallback level → source priority → received-at.
- A prior-session quote can never overwrite a newer session quote.
- For the same streaming provider and session, a lower sequence is rejected even when it arrives later.
- A fresher fallback quote may replace an older primary quote; fallback level does not imply stale quality.
- Equal-timestamp ties prefer FUGLE → SHIOAJI → TWSE MIS → YAHOO → CACHE.
- Polling providers use a circuit breaker: HEALTHY → DEGRADED → COOLDOWN → RECOVERING → HEALTHY.
- Two consecutive failures open the circuit by default; recovery requires two successful probes.
- HTTP 429 honors Retry-After where available; HTTP 403 uses a longer cooldown; network/5xx backoff includes bounded jitter.
- Fugle diagnostics expose HEALTHY / DEGRADED / COOLDOWN / RECOVERING separately from quote freshness.


## V1.0.61 Room persistence and StateFlow contract

- Memory Hot Store remains the intraday SSOT; UI subscribes directly to `StateFlow<Map<String, MarketQuote>>`.
- Room is a persistence layer only, never the tick-by-tick SSOT.
- Latest quote snapshots are persisted to `market_quote_snapshots`.
- One-minute candles are persisted to `market_minute_candles`.
- Persistence is throttled to a 5-second batch cadence and flushed when the Activity moves to background.
- Room schema advances from v3 to v4 with an explicit 3→4 migration; destructive migration is not allowed.
- The existing Finance Lock and append-only Ledger schema remain unchanged.


## V1.0.62 Shioaji streaming failover contract

- Realtime source order is Fugle WebSocket → Shioaji SSE Gateway → TWSE MIS → Yahoo → same-session cache.
- Shioaji is a Secondary stream and is activated only when Fugle is not HEALTHY.
- Android never embeds Shioaji broker credentials or the Python SDK; it connects only to a user-configured HTTPS SSE Gateway.
- Gateway bearer tokens are encrypted with Android Keystore and excluded from backup JSON.
- Shioaji events normalize into MarketQuote with source=SHIOAJI and fallbackLevel=1.
- Fresh secondary quotes remain LIVE and pass through the same Session/Timestamp/Sequence arbitration.
- When Fugle returns to HEALTHY, the secondary SSE stream is closed to minimize traffic and resource use.
