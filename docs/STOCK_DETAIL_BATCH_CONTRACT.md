# SaiETF StockDetail Batch Data Contract

Version line: V1.0.63

The StockDetail Batch Pipeline is separate from the realtime market-data pipeline.

## Cache-first rule

UI observes Room first. Network refresh is a background concern and must never block the first render when cached data exists.

## Common lineage fields

Every low-frequency entity carries:
- symbol
- dataDate
- period
- source
- fetchedAtEpochMillis
- nullable sourceUpdatedAtEpochMillis
- quality
- freshness
- rawRevision

Quality and freshness are separate concepts. VERIFIED data can become STALE without becoming unverified.

## Room v5 datasets

- institutional_trading
- monthly_revenue
- quarterly_financial
- etf_components
- dividend_reference
- after_hours_market

Market dividend references are deliberately separate from the user-accounting DividendEventEntity.

## Refresh behavior

StockDetailRepository provides per-dataset TTL checks and canonical SHA-256 revisions. V1.0.63 creates the governance/cache foundation; later versions attach verified providers and lazy UI flows.
