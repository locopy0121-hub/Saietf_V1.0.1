# shioaji-pro-app Reference Review for SaiETF

Reviewed repository: Sinotrade/shioaji-pro-app
License: GNU AGPL-3.0

## Decision

SaiETF will not embed Shioaji API access, Shioaji broker credentials, the Shioaji Python SDK, or the project's AGPL source code.

The project remains useful as a design reference. We may independently implement public ideas and standard financial formulas in Kotlin, without copying AGPL implementation code.

## High-value concepts for SaiETF

### 1. Monotonic market snapshot store
The project keeps a per-symbol snapshot store, coalesces pending requests, and refuses an older market timestamp from replacing a newer snapshot.

SaiETF status: already covered by MarketDataCenter + MarketArbitrator + Memory Hot Store. Keep this rule.

### 2. One streaming owner
The web project coordinates one SSE owner so multiple windows do not each open duplicate streams.

SaiETF translation: keep one application-scoped Fugle connection owned by the market-data layer. Screens subscribe to StateFlow; screens never own external connections.

### 3. Intraday session policy
Useful concepts:
- explicit Taiwan trading-session windows
- minute-end bucket semantics
- close-grace handling for final prints
- separating live session data from review/history session data
- preventing a new session from contaminating the prior session chart

SaiETF action: independently implement these rules in Kotlin for Taiwan stock / ETF charts and 1-minute candle aggregation.

### 4. Technical indicator registry
The project demonstrates a clean registry of indicator definitions and parameters. Its indicator set includes common formulas such as:
- SMA / EMA / WMA
- BOLL
- VWAP
- SAR
- SuperTrend
- Donchian / Keltner
- MACD
- RSI / KD / StochRSI
- CCI / ATR / OBV / MFI / Williams %R
- DMI / ADX
- ROC / BIAS

SaiETF action: implement the formulas independently in Kotlin from public mathematical definitions and validate with unit-test fixtures. Do not copy AGPL source.

### 5. K-line UX ideas
Useful product patterns:
- 1m / 5m / 15m / 60m / 1D timeframes
- live tick updates the current candle
- paged history loading
- gap detection / self-healing history refresh
- volume sub-chart
- crosshair and current-value legend
- per-indicator settings
- session-aware chart rendering

SaiETF action: adapt these UX concepts to the native Android chart engine.

### 6. Watchlist and market-wall ideas
Useful patterns:
- sortable watchlist
- mini sparkline per symbol
- real-trade flash only, excluding trial/simulated ticks
- sector / contribution views
- scanner/radar concepts
- diagnostics panel for source / connection / gap state

SaiETF action: reuse the product concepts, not the React source.

## Not suitable for SaiETF today

- Shioaji account / order / position / broker server management
- flash order, order ladder, OCO, bracket order and other broker execution code
- option/futures trading terminal workflows
- React 19 components, react-grid-layout, and lightweight-charts code as direct Android dependencies
- desktop Tauri shell
- any code that would force SaiETF into an AGPL derivative without an explicit licensing decision

## License boundary

The repository is AGPL-3.0. Directly copying or adapting its source into SaiETF can create strong copyleft obligations for the combined/derivative work. Therefore the default SaiETF policy is:

1. study architecture and behavior;
2. write an independent Kotlin specification;
3. implement independently;
4. use public financial formulas / official exchange rules as primary references;
5. do not copy source unless SaiETF intentionally adopts AGPL-3.0 or obtains a compatible commercial license.
