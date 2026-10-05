package tw.saietf.app

import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrl
import tw.saietf.core.market.MarketDataProvider
import tw.saietf.core.market.MarketEvent
import tw.saietf.core.market.MarketProviderCapability
import tw.saietf.core.market.MarketQuote
import tw.saietf.core.market.MarketSource
import tw.saietf.core.market.ProviderAvailability
import tw.saietf.core.market.ProviderCircuitState
import tw.saietf.core.market.ProviderHealth
import tw.saietf.core.market.QuoteQuality

internal interface ShioajiSseConnection {
    fun cancel()
}

internal interface ShioajiSseListener {
    fun onOpen()
    fun onActivity()
    fun onEvent(data: String)
    fun onClosed()
    fun onFailure(error: Throwable)
}

internal interface ShioajiSseTransport {
    fun open(
        url: String,
        bearerToken: String?,
        listener: ShioajiSseListener,
    ): ShioajiSseConnection
}

internal class OkHttpShioajiSseTransport(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .readTimeout(0L, TimeUnit.MILLISECONDS)
        .build(),
) : ShioajiSseTransport {
    override fun open(
        url: String,
        bearerToken: String?,
        listener: ShioajiSseListener,
    ): ShioajiSseConnection {
        val requestBuilder = Request.Builder()
            .url(url)
            .header("Accept", "text/event-stream")
            .header("Cache-Control", "no-cache")
        bearerToken?.takeIf { it.isNotBlank() }?.let {
            requestBuilder.header("Authorization", "Bearer $it")
        }
        val call = client.newCall(requestBuilder.build())
        call.enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: java.io.IOException) {
                    listener.onFailure(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { res ->
                        if (!res.isSuccessful) {
                            listener.onFailure(
                                IllegalStateException("Shioaji Gateway HTTP " + res.code),
                            )
                            return
                        }
                        listener.onOpen()
                        val source = res.body.source()
                        val dataLines = mutableListOf<String>()
                        while (!call.isCanceled()) {
                            val line = source.readUtf8Line() ?: break
                            listener.onActivity()
                            when {
                                line.isBlank() -> {
                                    if (dataLines.isNotEmpty()) {
                                        listener.onEvent(dataLines.joinToString("\n"))
                                        dataLines.clear()
                                    }
                                }
                                line.startsWith("data:") ->
                                    dataLines += line.removePrefix("data:").trimStart()
                            }
                        }
                        if (dataLines.isNotEmpty()) {
                            listener.onEvent(dataLines.joinToString("\n"))
                        }
                        if (!call.isCanceled()) listener.onClosed()
                    }
                }
            },
        )
        return object : ShioajiSseConnection {
            override fun cancel() = call.cancel()
        }
    }
}

internal class ShioajiSseGatewayProvider(
    private val endpointProvider: () -> String?,
    private val bearerTokenProvider: () -> String?,
    private val transport: ShioajiSseTransport = OkHttpShioajiSseTransport(),
    private val clockMillis: () -> Long = System::currentTimeMillis,
) : MarketDataProvider {
    override val source: MarketSource = MarketSource.SHIOAJI
    override val capabilities: Set<MarketProviderCapability> =
        setOf(MarketProviderCapability.STREAM)

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }
    private val taipeiZone = ZoneId.of("Asia/Taipei")
    private val lock = Any()
    private val _events = MutableSharedFlow<MarketEvent>(extraBufferCapacity = 256)
    override val events: Flow<MarketEvent> = _events.asSharedFlow()

    private val desiredSymbols = linkedSetOf<String>()
    private val latestQuotes = linkedMapOf<String, MarketQuote>()
    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "SaiETF-Shioaji-SSE").apply { isDaemon = true }
    }

    private var connection: ShioajiSseConnection? = null
    private var reconnectFuture: ScheduledFuture<*>? = null
    private var connecting = false
    private var manualDisconnect = true
    private var reconnectAttempt = 0
    private var consecutiveFailures = 0
    private var hasConnectedBefore = false
    private var recovering = false
    private var lastAttemptEpochMillis: Long? = null
    private var lastSuccessEpochMillis: Long? = null
    private var lastActivityEpochMillis: Long? = null
    private var nextReconnectEpochMillis = 0L

    init {
        scheduler.scheduleAtFixedRate(
            { watchdogTick() },
            WATCHDOG_PERIOD_MILLIS,
            WATCHDOG_PERIOD_MILLIS,
            TimeUnit.MILLISECONDS,
        )
    }

    override suspend fun connect() {
        connectNow()
    }

    override suspend fun disconnect() {
        val current = synchronized(lock) {
            manualDisconnect = true
            connecting = false
            recovering = false
            reconnectFuture?.cancel(false)
            reconnectFuture = null
            nextReconnectEpochMillis = 0L
            connection.also { connection = null }
        }
        current?.cancel()
        emitHealth()
    }

    override suspend fun subscribe(symbols: Set<String>) {
        replaceSubscriptions(desiredSymbolsSnapshot() + normalizeSymbols(symbols))
    }

    override suspend fun unsubscribe(symbols: Set<String>) {
        replaceSubscriptions(desiredSymbolsSnapshot() - normalizeSymbols(symbols))
    }

    suspend fun replaceSubscriptions(symbols: Set<String>) {
        val normalized = normalizeSymbols(symbols)
        val changed = synchronized(lock) {
            if (desiredSymbols == normalized) false
            else {
                desiredSymbols.clear()
                desiredSymbols += normalized
                true
            }
        }
        if (normalized.isEmpty()) {
            disconnect()
            return
        }
        if (changed && synchronized(lock) { connection != null || connecting }) {
            restartConnection()
        } else {
            connectNow()
        }
    }

    override suspend fun fetchSnapshot(symbols: Set<String>): Map<String, MarketQuote> {
        val requested = normalizeSymbols(symbols)
        return synchronized(lock) {
            latestQuotes.filterKeys { it in requested }.toMap()
        }
    }

    override fun health(nowEpochMillis: Long): ProviderHealth {
        val configured = !endpointProvider().isNullOrBlank()
        return synchronized(lock) {
            val silent = connection != null &&
                (lastActivityEpochMillis?.let { nowEpochMillis - it > ACTIVITY_TIMEOUT_MILLIS } ?: true)
            val circuit = when {
                !configured -> ProviderCircuitState.COOLDOWN
                silent -> ProviderCircuitState.COOLDOWN
                connection != null && recovering -> ProviderCircuitState.RECOVERING
                connection != null -> ProviderCircuitState.HEALTHY
                consecutiveFailures >= 2 -> ProviderCircuitState.COOLDOWN
                connecting || consecutiveFailures > 0 -> ProviderCircuitState.DEGRADED
                else -> ProviderCircuitState.DEGRADED
            }
            val availability = when (circuit) {
                ProviderCircuitState.HEALTHY -> ProviderAvailability.READY
                ProviderCircuitState.DEGRADED,
                ProviderCircuitState.RECOVERING,
                -> ProviderAvailability.THROTTLED
                ProviderCircuitState.COOLDOWN -> ProviderAvailability.COOLDOWN
            }
            ProviderHealth(
                source = source,
                availability = availability,
                consecutiveFailures = consecutiveFailures,
                lastAttemptEpochMillis = lastAttemptEpochMillis,
                lastSuccessEpochMillis = lastSuccessEpochMillis,
                nextAllowedEpochMillis = nextReconnectEpochMillis,
                circuitState = circuit,
            )
        }
    }

    fun onSettingsChanged() {
        synchronized(lock) {
            reconnectAttempt = 0
            consecutiveFailures = 0
            recovering = false
        }
        restartConnection()
    }

    fun desiredSymbolsSnapshot(): Set<String> = synchronized(lock) {
        desiredSymbols.toSet()
    }

    internal fun shutdownForTest() {
        scheduler.shutdownNow()
        synchronized(lock) {
            connection?.cancel()
            connection = null
        }
    }

    private fun connectNow() {
        val endpoint = endpointProvider()?.trim().orEmpty()
        val symbols = desiredSymbolsSnapshot()
        if (endpoint.isBlank() || symbols.isEmpty()) {
            emitHealth()
            return
        }

        synchronized(lock) {
            if (connection != null || connecting || (!manualDisconnect && reconnectFuture?.isDone == false)) {
                return
            }
            manualDisconnect = false
            connecting = true
            lastAttemptEpochMillis = clockMillis()
        }

        val url = endpoint.toHttpUrl()
            .newBuilder()
            .setQueryParameter("symbols", symbols.sorted().joinToString(","))
            .build()
            .toString()

        val listener = object : ShioajiSseListener {
            override fun onOpen() {
                val now = clockMillis()
                synchronized(lock) {
                    connecting = false
                    recovering = hasConnectedBefore && (consecutiveFailures > 0 || reconnectAttempt > 0)
                    hasConnectedBefore = true
                    consecutiveFailures = 0
                    reconnectAttempt = 0
                    lastActivityEpochMillis = now
                    lastSuccessEpochMillis = now
                }
                emitHealth()
            }

            override fun onActivity() {
                synchronized(lock) {
                    lastActivityEpochMillis = clockMillis()
                }
            }

            override fun onEvent(data: String) {
                handleData(data)
            }

            override fun onClosed() {
                handleDisconnect(null)
            }

            override fun onFailure(error: Throwable) {
                handleDisconnect(error)
            }
        }

        val opened = runCatching {
            transport.open(url, bearerTokenProvider(), listener)
        }.getOrElse {
            synchronized(lock) {
                connecting = false
                consecutiveFailures += 1
            }
            scheduleReconnect()
            emitHealth()
            return
        }
        synchronized(lock) {
            if (manualDisconnect) opened.cancel() else connection = opened
        }
    }

    private fun restartConnection() {
        val current = synchronized(lock) {
            connecting = false
            reconnectFuture?.cancel(false)
            reconnectFuture = null
            nextReconnectEpochMillis = 0L
            connection.also { connection = null }
        }
        current?.cancel()
        if (desiredSymbolsSnapshot().isNotEmpty() && !endpointProvider().isNullOrBlank()) {
            connectNow()
        } else {
            emitHealth()
        }
    }

    private fun handleData(data: String) {
        val root = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return
        val type = root.string("type") ?: root.string("event")
        if (type.equals("heartbeat", true)) {
            synchronized(lock) {
                lastActivityEpochMillis = clockMillis()
                lastSuccessEpochMillis = clockMillis()
                recovering = false
            }
            emitHealth()
            return
        }

        val symbol = root.string("symbol")?.normalizeSymbol() ?: return
        if (symbol !in desiredSymbolsSnapshot()) return
        val price = root.double("price")?.takeIf { it.isFinite() && it > 0.0 } ?: return
        val now = clockMillis()
        val sourceTime = normalizeEpochMillis(
            root.long("sourceTimestamp") ?: root.long("timestamp") ?: root.long("time"),
            now,
        )
        val sessionDate = root.string("sessionDate")
            ?.takeIf { it.isNotBlank() }
            ?: Instant.ofEpochMilli(sourceTime).atZone(taipeiZone).toLocalDate().toString()

        val quote = MarketQuote(
            symbol = symbol,
            name = root.string("name") ?: symbol,
            exchange = root.string("exchange"),
            market = root.string("market"),
            price = price,
            previousClose = root.double("previousClose"),
            open = root.double("open"),
            high = root.double("high"),
            low = root.double("low"),
            asOfEpochMillis = sourceTime,
            source = MarketSource.SHIOAJI,
            quality = QuoteQuality.LIVE,
            volume = root.long("volume"),
            bid = root.double("bid"),
            ask = root.double("ask"),
            sourceTimestampEpochMillis = sourceTime,
            receivedAtEpochMillis = now,
            sessionDate = sessionDate,
            fallbackLevel = 1,
            sequence = root.long("sequence"),
            isClose = root.boolean("isClose") == true,
        )

        synchronized(lock) {
            latestQuotes[symbol] = quote
            lastActivityEpochMillis = now
            lastSuccessEpochMillis = now
            consecutiveFailures = 0
            recovering = false
        }
        _events.tryEmit(MarketEvent.Quote(quote))
        emitHealth()
    }

    private fun handleDisconnect(error: Throwable?) {
        val reconnect = synchronized(lock) {
            connection = null
            connecting = false
            recovering = false
            if (error != null) consecutiveFailures += 1
            !manualDisconnect && desiredSymbols.isNotEmpty() && !endpointProvider().isNullOrBlank()
        }
        if (reconnect) scheduleReconnect()
        emitHealth()
    }

    private fun scheduleReconnect() {
        val delay = synchronized(lock) {
            if (manualDisconnect || desiredSymbols.isEmpty() || endpointProvider().isNullOrBlank()) return
            if (reconnectFuture?.isDone == false) return
            val base = min(
                RECONNECT_MAX_MILLIS,
                RECONNECT_BASE_MILLIS * (1L shl reconnectAttempt.coerceIn(0, 5)),
            )
            val jitter = (reconnectAttempt * 211L) % RECONNECT_JITTER_MILLIS
            reconnectAttempt += 1
            val delayMillis = base + jitter
            nextReconnectEpochMillis = clockMillis() + delayMillis
            delayMillis
        }
        reconnectFuture = scheduler.schedule(
            {
                synchronized(lock) {
                    reconnectFuture = null
                    nextReconnectEpochMillis = 0L
                }
                connectNow()
            },
            delay,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun watchdogTick() {
        val now = clockMillis()
        val expired = synchronized(lock) {
            val last = lastActivityEpochMillis ?: return
            connection != null && !manualDisconnect && now - last > ACTIVITY_TIMEOUT_MILLIS
        }
        if (!expired) return

        val current = synchronized(lock) {
            connection.also {
                connection = null
                connecting = false
                recovering = false
                consecutiveFailures += 1
            }
        }
        current?.cancel()
        scheduleReconnect()
        emitHealth()
    }

    private fun emitHealth() {
        _events.tryEmit(MarketEvent.ProviderState(health(clockMillis())))
    }

    private fun normalizeSymbols(symbols: Set<String>): Set<String> =
        symbols.map { it.normalizeSymbol() }.filter { it.isNotBlank() }.toSet()

    private fun String.normalizeSymbol(): String = trim().uppercase(Locale.US)

    private fun normalizeEpochMillis(raw: Long?, receivedAt: Long): Long {
        val value = raw ?: return receivedAt
        return when {
            value >= 100_000_000_000_000L -> value / 1_000L
            value >= 100_000_000_000L -> value
            value >= 100_000_000L -> value * 1_000L
            else -> receivedAt
        }
    }

    private fun JsonObject.string(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.long(key: String): Long? =
        this[key]?.jsonPrimitive?.longOrNull

    private fun JsonObject.double(key: String): Double? =
        this[key]?.jsonPrimitive?.doubleOrNull

    private fun JsonObject.boolean(key: String): Boolean? =
        this[key]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()

    companion object {
        private const val WATCHDOG_PERIOD_MILLIS = 10_000L
        private const val ACTIVITY_TIMEOUT_MILLIS = 45_000L
        private const val RECONNECT_BASE_MILLIS = 1_000L
        private const val RECONNECT_MAX_MILLIS = 30_000L
        private const val RECONNECT_JITTER_MILLIS = 500L
    }
}
