package tw.saietf.app

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

data class TaiwanSessionWindow(
    val sessionDate: LocalDate,
    val openEpochMillis: Long,
    val closeEpochMillis: Long,
    val closeGraceEndEpochMillis: Long,
)

object TaiwanIntradaySessionPolicy {
    private val zone = ZoneId.of("Asia/Taipei")
    private val stockOpen = LocalTime.of(9, 0)
    private val stockClose = LocalTime.of(13, 30)
    private const val CLOSE_GRACE_MILLIS = 4L * 60L * 1_000L

    fun stockWindow(epochMillis: Long): TaiwanSessionWindow {
        val local = Instant.ofEpochMilli(epochMillis).atZone(zone)
        val date = local.toLocalDate()
        val open = ZonedDateTime.of(date, stockOpen, zone).toInstant().toEpochMilli()
        val close = ZonedDateTime.of(date, stockClose, zone).toInstant().toEpochMilli()
        return TaiwanSessionWindow(
            sessionDate = date,
            openEpochMillis = open,
            closeEpochMillis = close,
            closeGraceEndEpochMillis = close + CLOSE_GRACE_MILLIS,
        )
    }

    fun isRegularSession(epochMillis: Long): Boolean {
        val window = stockWindow(epochMillis)
        return epochMillis in window.openEpochMillis..window.closeEpochMillis
    }

    fun isAcceptedClosePrint(epochMillis: Long): Boolean {
        val window = stockWindow(epochMillis)
        return epochMillis in window.openEpochMillis..window.closeGraceEndEpochMillis
    }

    fun minuteBucketEnd(epochMillis: Long): Long =
        ((epochMillis / 60_000L) + 1L) * 60_000L

    fun sameSession(leftEpochMillis: Long, rightEpochMillis: Long): Boolean =
        stockWindow(leftEpochMillis).sessionDate == stockWindow(rightEpochMillis).sessionDate

    fun clampToSession(epochMillis: Long): Long {
        val window = stockWindow(epochMillis)
        return epochMillis.coerceIn(window.openEpochMillis, window.closeGraceEndEpochMillis)
    }
}
