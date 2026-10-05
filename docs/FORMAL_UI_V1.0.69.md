# SaiETF Formal UI Completion Contract

Version line: V1.0.69

V1.0.69 closes the first formal native Android UI build.

## Top-level navigation

The app exposes one five-tab shell:
- 首頁
- 行情
- 交易
- 股息
- 設定

The previous engineering verification dashboard is no longer the primary navigation model.

## Page completion

### 首頁
- total asset / investment cost / previous-day P&L / today P&L / total holding P&L
- holdings count
- inline holdings snapshot
- entry to performance history, holdings, market wall and instrument detail

### 行情
- one MarketDataCenter / StateFlow source
- sortable live quote list
- market wall modes
- instrument entry
- manual refresh that still respects provider pacing and circuit-breaker policy

### 交易
- inline ledger summary
- effective transaction count
- holdings count
- investment cost
- realized P&L
- recent five transactions
- create / edit / delete flows remain append-only corrections

### 股息
- current-month confirmed / announced cash summary
- current-year confirmed cash summary
- recent dividend events
- dividend center and calendar remain available

### 設定
Grouped UI:
- 介面
- 行情
- 資料與系統

The page surfaces app version, Room schema, Fugle configuration state and provider health.

## Instrument page

The formal instrument page keeps eight lazy-loaded tabs:
明細 / 走勢 / 技術 / 成分 / 法人 / 財務 / 盤後 / 數據

No tab fabricates missing market or fundamental data.

## Invariants

- Finance Lock remains unchanged.
- Ledger remains append-only.
- Market UI reads one MarketDataCenter StateFlow.
- Room market data is persistence, not tick-level SSOT.
- Broker-account-only Shioaji integration stays removed.
