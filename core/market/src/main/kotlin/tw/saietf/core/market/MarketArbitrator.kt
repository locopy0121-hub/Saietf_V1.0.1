package tw.saietf.core.market

import java.time.Instant
import java.time.ZoneId

enum class ArbitrationReason {
    ACCEPT_EMPTY,
    ACCEPT_NEWER_SESSION,
    ACCEPT_NEWER_SEQUENCE,
    ACCEPT_NEWER_TIMESTAMP,
    ACCEPT_BETTER_QUALITY,
    ACCEPT_LOWER_FALLBACK_LEVEL,
    ACCEPT_HIGHER_SOURCE_PRIORITY,
    ACCEPT_NEWER_RECEIVED_AT,
    REJECT_INVALID,
    REJECT_OLDER_SESSION,
    REJECT_OUT_OF_ORDER_SEQUENCE,
    REJECT_OLDER_TIMESTAMP,
    KEEP_EXISTING,
}

data class ArbitrationResult(
    val accepted: Boolean,
    val reason: ArbitrationReason,
)

class MarketArbitrator(
    private val sourcePriority: Map<MarketSource, Int> = defaultSourcePriority,
) {
    private val taipeiZone = ZoneId.of("Asia/Taipei")

    fun decide(
        existing: MarketQuote?,
        candidate: MarketQuote,
    ): ArbitrationResult {
        if (!candidate.price.isFinite() || candidate.price <= 0.0 || candidate.isTrial) {
            return ArbitrationResult(false, ArbitrationReason.REJECT_INVALID)
        }
        if (existing == null) {
            return ArbitrationResult(true, ArbitrationReason.ACCEPT_EMPTY)
        }

        val candidateSession = sessionDate(candidate)
        val existingSession = sessionDate(existing)
        if (candidateSession > existingSession) {
            return ArbitrationResult(true, ArbitrationReason.ACCEPT_NEWER_SESSION)
        }
        if (candidateSession < existingSession) {
            return ArbitrationResult(false, ArbitrationReason.REJECT_OLDER_SESSION)
        }

        if (
            candidate.source == existing.source &&
            candidate.sequence != null &&
            existing.sequence != null
        ) {
            if (candidate.sequence > existing.sequence) {
                return ArbitrationResult(true, ArbitrationReason.ACCEPT_NEWER_SEQUENCE)
            }
            if (candidate.sequence < existing.sequence) {
                return ArbitrationResult(false, ArbitrationReason.REJECT_OUT_OF_ORDER_SEQUENCE)
            }
        }

        val candidateTime = candidate.sourceTimestampEpochMillis
        val existingTime = existing.sourceTimestampEpochMillis
        if (candidateTime > existingTime) {
            return ArbitrationResult(true, ArbitrationReason.ACCEPT_NEWER_TIMESTAMP)
        }
        if (candidateTime < existingTime) {
            return ArbitrationResult(false, ArbitrationReason.REJECT_OLDER_TIMESTAMP)
        }

        val candidateQuality = qualityRank(candidate.quality)
        val existingQuality = qualityRank(existing.quality)
        if (candidateQuality > existingQuality) {
            return ArbitrationResult(true, ArbitrationReason.ACCEPT_BETTER_QUALITY)
        }
        if (candidateQuality < existingQuality) {
            return ArbitrationResult(false, ArbitrationReason.KEEP_EXISTING)
        }

        if (candidate.fallbackLevel < existing.fallbackLevel) {
            return ArbitrationResult(true, ArbitrationReason.ACCEPT_LOWER_FALLBACK_LEVEL)
        }
        if (candidate.fallbackLevel > existing.fallbackLevel) {
            return ArbitrationResult(false, ArbitrationReason.KEEP_EXISTING)
        }

        val candidatePriority = sourcePriority[candidate.source] ?: Int.MAX_VALUE
        val existingPriority = sourcePriority[existing.source] ?: Int.MAX_VALUE
        if (candidatePriority < existingPriority) {
            return ArbitrationResult(true, ArbitrationReason.ACCEPT_HIGHER_SOURCE_PRIORITY)
        }
        if (candidatePriority > existingPriority) {
            return ArbitrationResult(false, ArbitrationReason.KEEP_EXISTING)
        }

        if (candidate.receivedAtEpochMillis > existing.receivedAtEpochMillis) {
            return ArbitrationResult(true, ArbitrationReason.ACCEPT_NEWER_RECEIVED_AT)
        }
        return ArbitrationResult(false, ArbitrationReason.KEEP_EXISTING)
    }

    private fun sessionDate(quote: MarketQuote): String =
        quote.sessionDate
            ?.takeIf { it.isNotBlank() }
            ?: Instant.ofEpochMilli(quote.sourceTimestampEpochMillis)
                .atZone(taipeiZone)
                .toLocalDate()
                .toString()

    private fun qualityRank(quality: QuoteQuality): Int =
        when (quality) {
            QuoteQuality.LIVE -> 4
            QuoteQuality.DELAYED -> 3
            QuoteQuality.STALE -> 2
            QuoteQuality.OFFLINE -> 1
        }

    companion object {
        val defaultSourcePriority: Map<MarketSource, Int> = mapOf(
            MarketSource.FUGLE to 0,
            MarketSource.SHIOAJI to 1,
            MarketSource.TWSE_MIS to 2,
            MarketSource.YAHOO to 3,
            MarketSource.CACHE to 4,
        )
    }
}
