# SaiETF Finance Core Lock

Lock version: **V3.7.8-1**

SaiETF V1.0.1 ports the accounting behavior from the reviewed TF Asset source and treats this module as immutable infrastructure.

## Source provenance

- Repository: `locopy0121-hub/ETF-Finance-Manager-V3.7`
- Commit: `a872644c572d24fc5ffe597a97eb6d7d8bd7283f`
- Canonical source files:
  - `src/utils/etfCalculators.ts`
  - `src/data/brokerProfiles.ts`
  - `src/types/etf.ts`

## Immutable accounting rules

1. Trade amount is rounded according to the broker profile; the frozen default is floor.
2. Commission uses amount × 0.001425 × 0.65, then broker rounding, with minimum 20 TWD for round lot and 1 TWD for odd lot.
3. Executed `actualFee` and `actualTax` are historical truth and override estimates.
4. ETF sell tax is 0.001; stock sell tax is 0.003.
5. Remaining-position cost uses moving-average cost including buy fees. A sale releases only the proportional cost of shares sold; sale proceeds never reduce the cost of remaining shares.
6. Ledger oversells are rejected before persistence/projection.
7. Current market value is gross market value. Unrealized profit uses net liquidation value minus remaining investment cost.
8. Comprehensive P&L is unrealized P&L + realized net P&L + received net dividends.
9. Dividend gross amount is floored. At gross dividend >= 20,000 TWD, supplementary health premium is floor(gross × 0.0211). A positive dividend has a 10 TWD transfer fee.
10. Portfolio totals aggregate canonical instrument summaries; UI layers must not recalculate accounting fields.

## Public result schema

`InstrumentCalculationResult`:
`symbol, name, currentPrice, totalShares, totalInvestmentCost, averageCostPerShare, currentMarketValue, estimatedSellCommission, estimatedSellTax, netLiquidationValue, unrealizedProfit, unrealizedROI, realizedNetPnL, comprehensivePnL, totalDividendsReceived, nextEstimatedDividend, singlePeriodYield, annualizedYield, portfolioWeight`.

`PortfolioCalculationResult`:
`totalMarketValue, totalInvestmentCost, totalNetLiquidationValue, totalEstimatedSellCommission, totalEstimatedSellTax, totalUnrealizedProfit, totalUnrealizedROI, realizedNetPnL, comprehensivePnL, totalPnl, totalDividendsReceived, nextEstimatedDividendTotal, instrumentSummaries`.

`DividendCalculationResult`:
`grossDividend, supplementaryHealthPremium, transferFee, netDividend`.

Any intentional accounting-rule or result-schema change requires a new lock version, reviewed source provenance, regenerated fixtures, and updated golden tests. Feature work must not silently update this file or the frozen fixture manifest.
