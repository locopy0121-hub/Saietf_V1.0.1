package tw.saietf.app

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import tw.saietf.core.database.SaiEtfDatabase
import tw.saietf.core.database.entity.MarketMinuteCandleEntity
import tw.saietf.core.database.entity.MarketQuoteSnapshotEntity
import tw.saietf.core.market.MarketQuote
import tw.saietf.core.market.MarketSource
import tw.saietf.core.market.QuoteQuality

class MarketPersistenceRepository(
    private val database: SaiEtfDatabase,
) {
    private val dao = database.marketCacheDao()

    suspend fun persist(
        quotes: Collection<MarketQuote>,
        persistedAtEpochMillis: Long = System.currentTimeMillis(),
    ) {
        if (quotes.isEmpty()) return
        val snapshots = quotes.map { quote ->
            MarketQuoteSnapshotEntity(
                symbol = quote.symbol,
                name = quote.name,
                exchange = quote.exchange,
                market = quote.market,
                price = quote.price,
                previousClose = quote.previousClose,
                open = quote.open,
                high = quote.high,
                low = quote.low,
                volume = quote.volume,
                bid = quote.bid,
                ask = quote.ask,
                source = quote.source.name,
                quality = quote.quality.name,
                sourceTimestampEpochMillis = quote.sourceTimestampEpochMillis,
                receivedAtEpochMillis = quote.receivedAtEpochMillis,
                sessionDate = quote.sessionDate.orEmpty(),
                fallbackLevel = quote.fallbackLevel,
                sequence = quote.sequence,
                isClose = quote.isClose,
                persistedAtEpochMillis = persistedAtEpochMillis,
            )
        }
        dao.upsertSnapshots(snapshots)

        val candles = quotes.mapNotNull { quote ->
            val sourceTime = quote.sourceTimestampEpochMillis
            if (sourceTime <= 0L) return@mapNotNull null
            val sessionDate = quote.sessionDate?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val bucket = sourceTime - sourceTime % MINUTE_MILLIS
            val existing = dao.findMinuteCandle(quote.symbol, bucket)
            val volume = quote.volume ?: existing?.volume ?: 0L
            MarketMinuteCandleEntity(
                symbol = quote.symbol,
                sessionDate = sessionDate,
                bucketEpochMillis = bucket,
                open = existing?.open ?: quote.price,
                high = maxOf(existing?.high ?: quote.price, quote.price),
                low = minOf(existing?.low ?: quote.price, quote.price),
                close = quote.price,
                volume = maxOf(existing?.volume ?: 0L, volume),
                source = quote.source.name,
                updatedAtEpochMillis = persistedAtEpochMillis,
            )
        }
        if (candles.isNotEmpty()) {
            dao.upsertCandles(candles)
        }
    }

    fun observeSnapshots(symbols: Set<String>): Flow<Map<String, MarketQuote>> =
        dao.observeSnapshots(symbols).map { rows ->
            rows.associate { row ->
                row.symbol to MarketQuote(
                    symbol = row.symbol,
                    name = row.name,
                    exchange = row.exchange,
                    market = row.market,
                    price = row.price,
                    previousClose = row.previousClose,
                    open = row.open,
                    high = row.high,
                    low = row.low,
                    asOfEpochMillis = row.sourceTimestampEpochMillis,
                    source = runCatching { MarketSource.valueOf(row.source) }
                        .getOrDefault(MarketSource.CACHE),
                    quality = runCatching { QuoteQuality.valueOf(row.quality) }
                        .getOrDefault(QuoteQuality.OFFLINE),
                    volume = row.volume,
                    bid = row.bid,
                    ask = row.ask,
                    sourceTimestampEpochMillis = row.sourceTimestampEpochMillis,
                    receivedAtEpochMillis = row.receivedAtEpochMillis,
                    sessionDate = row.sessionDate,
                    fallbackLevel = row.fallbackLevel,
                    sequence = row.sequence,
                    isClose = row.isClose,
                )
            }
        }

    fun observeMinuteCandles(
        symbol: String,
        sessionDate: String,
    ): Flow<List<MarketMinuteCandleEntity>> =
        dao.observeMinuteCandles(symbol, sessionDate)

    companion object {
        private const val MINUTE_MILLIS = 60_000L
    }
}
