# SaiETF UI Recovery Contract — V1.1.15

This version freezes the visual recovery direction after V1.1.14 acceptance.

## Runtime visual invariants
- One shared light-blue SaiETF surface language across Home, Market, Trade, Dividend, Settings and instrument detail.
- Shared hero, card, section and border tokens live in SaiTheme; page-local ad-hoc replacements are not accepted as final UI.
- The runtime implementation must preserve Finance Lock, Ledger, Room and MarketDataCenter behavior while presentation is rebuilt.
- Generated SaiETF overview and market-wall concepts are the visual reference; professional quote/K-line/ETF component screenshots are the information-density reference.
- A green build is necessary but not sufficient for visual completion.

## Version sequence
- V1.1.15: visual tokens and shared shell
- V1.1.16: home recovery
- V1.1.17: market + instrument recovery
- V1.1.18: ETF components + professional chart information architecture
- V1.1.19: cross-page convergence and visual QA contract
