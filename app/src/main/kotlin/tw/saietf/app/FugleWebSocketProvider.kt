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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import tw.saietf.core.market.MarketDataProvider
import tw.saietf.core.market.MarketEvent
import tw.saietf.core.market.MarketProviderCapability
import tw.saietf.core.market.MarketQuote
import tw.saietf.core.market.MarketSource
import tw.saietf.core.market.ProviderAvailability
import tw.saietf.core.market.ProviderHealth
import tw.saietf.core.market.QuoteQuality

internal interface FugleSocket {
    fun send(text: String): Boolean
    fun close(code: Int, reason: String): Boolean
    fun cancel()
}

internal interface FugleSocketListener {
    fun onOpen(socket: FugleSocket)
    fun onText(socket: FugleSocket, text: String)
    fun onClosed(socket: FugleSocket, code: Int, reason: String)
    fun onFailure(socket: FugleSocket, error: Throwable)
}

internal interface FugleSocketTransport {
    fun connect(url: String, listener: FugleSocketListener): FugleSocket
}

internal class OkHttpFugleSocketTransport(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(25L, TimeUnit.SECONDS)
        .build(),
) : FugleSocketTransport {
    override fun connect(url: String, listener: FugleSocketListener): FugleSocket {
        lateinit var wrapper: OkHttpFugleSocket
        val request = Request.Builder().url(url).build()
        val socket = client.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    listener.onOpen(wrapper)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    listener.onText(wrapper, text)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    listener.onClosed(wrapper, code, reason)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    listener.onFailure(wrapper, t)
                }
            },
        )
        wrapper = OkHttpFugleSocket(socket)
        return wrapper
    }

    private class OkHttpFugleSocket(
        private val delegate: WebSocket,
    ) : FugleSocket {
        override fun send(text: String): Boolean = delegate.send(text)

        override fun close(code: Int, reason: String): Boolean =
            delegate.close(code, reason)

        override fun cancel() = delegate.cancel()
    }
}

internal class FugleWebSocketProvider(
    private val apiKeyProvider: () -> String?,
    private val transport: FugleSocketTransport = OkHttpFugleSocketTransport(),
    private val clockMillis: () -> Long = System::currentTimeMillis,
) : MarketDataProvider {
    override val source: MarketSource = MarketSource.FUGLE
    override val capabilities: Set<MarketProviderCapability> =
        setOf(MarketProviderCapability.STREAM)

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }
    private val taipeiZone = ZoneId.of("Asia/Taipei")
    private val lock = Any()
    private val _events = MutableSharedFlow<MarketEvent>(extraBufferCapacity = 512)
    override val events: Flow<MarketEvent> = _events.asSharedFlow()

    private val desiredSymbols = linkedSetOf<String>()
    private val activeChannelIds = linkedMapOf<String, String>()
    private val pendingSubscribeSymbols = linkedSetOf<String>()
    private val pendingUnsubscribeIds = linkedSetOf<String>()
    private val latestQuotes = linkedMapOf<String, MarketQuote>()

    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "SaiETF-Fugle-WS").apply { isDaemon = true }
    }
    private var reconnectFuture: ScheduledFuture<*>? = null
    private var socket: FugleSocket? = null
    private var authenticated: Boolean = false
    private var connecting: Boolean = false
    private var manualDisconnect: Boolean = true
    private var credentialRejected: Boolean = false
    private var reconnectAttempt: Int = 0
    private var consecutiveFailures: Int = 0
    private var lastAttemptEpochMillis: Long? = null
    private var lastSuccessEpochMillis: Long? = null
    private var lastMessageEpochMillis: Long? = null
    private var lastHeartbeatEpochMillis: Long? = null
    private var nextReconnectEpochMillis: Long = 0L

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
            authenticated = false
            connecting = false
            reconnectFuture?.cancel(false)
            reconnectFuture = null
            nextReconnectEpochMillis = 0L
            activeChannelIds.clear()
            pendingSubscribeSymbols.clear()
            pendingUnsubscribeIds.clear()
            socket.also { socket = null }
        }
        current?.close(NORMAL_CLOSE_CODE, "SaiETF background/disconnect")
        emitHealth()
    }

    override suspend fun subscribe(symbols: Set<String>) {
        synchronized(lock) {
            desiredSymbols += normalizeSymbols(symbols)
        }
        connectNow()
        syncSubscriptions()
    }

    override suspend fun unsubscribe(symbols: Set<String>) {
        synchronized(lock) {
            desiredSymbols -= normalizeSymbols(symbols)
        }
        syncSubscriptions()
    }

    suspend fun replaceSubscriptions(symbols: Set<String>) {
        synchronized(lock) {
            desiredSymbols.clear()
            desiredSymbols += normalizeSymbols(symbols)
        }
        if (desiredSymbolsSnapshot().isNotEmpty()) {
            connectNow()
        }
        syncSubscriptions()
    }

    override suspend fun fetchSnapshot(symbols: Set<String>): Map<String, MarketQuote> {
        val requested = normalizeSymbols(symbols)
        return synchronized(lock) {
            latestQuotes.filterKeys { it in requested }.toMap()
        }
    }

    override fun health(nowEpochMillis: Long): ProviderHealth {
        val hasCredential = !apiKeyProvider().isNullOrBlank()
        return synchronized(lock) {
            val availability = when {
                !hasCredential || credentialRejected -> ProviderAvailability.COOLDOWN
                authenticated && !heartbeatIsHealthy(nowEpochMillis) -> ProviderAvailability.COOLDOWN
                authenticated -> ProviderAvailability.READY
                socket != null || connecting -> ProviderAvailability.THROTTLED
                else -> ProviderAvailability.COOLDOWN
            }
            ProviderHealth(
                source = source,
                availability = availability,
                consecutiveFailures = consecutiveFailures,
                lastAttemptEpochMillis = lastAttemptEpochMillis,
                lastSuccessEpochMillis = lastSuccessEpochMillis,
                nextAllowedEpochMillis = nextReconnectEpochMillis,
            )
        }
    }

    fun onCredentialChanged() {
        val old = synchronized(lock) {
            credentialRejected = false
            reconnectAttempt = 0
            consecutiveFailures = 0
            manualDisconnect = true
            authenticated = false
            connecting = false
            reconnectFuture?.cancel(false)
            reconnectFuture = null
            nextReconnectEpochMillis = 0L
            activeChannelIds.clear()
            pendingSubscribeSymbols.clear()
            pendingUnsubscribeIds.clear()
            socket.also { socket = null }
        }
        old?.close(NORMAL_CLOSE_CODE, "SaiETF credential changed")
        if (!apiKeyProvider().isNullOrBlank() && desiredSymbolsSnapshot().isNotEmpty()) {
            connectNow()
        } else {
            emitHealth()
        }
    }

    fun desiredSymbolsSnapshot(): Set<String> = synchronized(lock) {
        desiredSymbols.toSet()
    }

    internal fun handleSocketMessageForTest(text: String) {
        handleMessage(text)
    }

    internal fun shutdownForTest() {
        scheduler.shutdownNow()
        synchronized(lock) {
            socket?.cancel()
            socket = null
        }
    }

    private fun connectNow() {
        val apiKey = apiKeyProvider()?.trim().orEmpty()
        if (apiKey.isBlank()) {
            emitHealth()
            return
        }

        synchronized(lock) {
            if (credentialRejected || socket != null || connecting) return
            manualDisconnect = false
            connecting = true
            lastAttemptEpochMillis = clockMillis()
        }

        val listener = object : FugleSocketListener {
            override fun onOpen(socket: FugleSocket) {
                synchronized(lock) {
                    if (this@FugleWebSocketProvider.socket !== socket) return
                    connecting = false
                    lastMessageEpochMillis = clockMillis()
                }
                socket.send(authMessage(apiKey))
                emitHealth()
            }

            override fun onText(socket: FugleSocket, text: String) {
                if (synchronized(lock) { this@FugleWebSocketProvider.socket !== socket }) return
                handleMessage(text)
            }

            override fun onClosed(socket: FugleSocket, code: Int, reason: String) {
                handleDisconnect(socket, null)
            }

            override fun onFailure(socket: FugleSocket, error: Throwable) {
                handleDisconnect(socket, error)
            }
        }

        val created = runCatching { transport.connect(STREAMING_URL, listener) }
            .getOrElse { error ->
                synchronized(lock) {
                    connecting = false
                    consecutiveFailures += 1
                }
                scheduleReconnect()
                emitHealth()
                return
            }

        synchronized(lock) {
            if (manualDisconnect) {
                created.close(NORMAL_CLOSE_CODE, "SaiETF no longer needs stream")
            } else {
                socket = created
            }
        }
    }

    private fun handleMessage(text: String) {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        val event = root.string("event") ?: return
        val now = clockMillis()
        synchronized(lock) {
            lastMessageEpochMillis = now
        }

        when (event) {
            "authenticated" -> {
                synchronized(lock) {
                    authenticated = true
                    connecting = false
                    credentialRejected = false
                    reconnectAttempt = 0
                    consecutiveFailures = 0
                    lastSuccessEpochMillis = now
                    lastHeartbeatEpochMillis = now
                }
                syncSubscriptions()
                emitHealth()
            }

            "heartbeat", "pong" -> {
                synchronized(lock) {
                    lastHeartbeatEpochMillis = now
                    lastSuccessEpochMillis = now
                }
                emitHealth()
            }

            "subscribed" -> handleSubscribed(root["data"])
            "unsubscribed" -> handleUnsubscribed(root["data"])
            "data" -> handleMarketData(root)
            "error" -> handleServerError(root)
        }
    }

    private fun handleSubscribed(data: JsonElement?) {
        subscriptionObjects(data).forEach { row ->
            val id = row.string("id") ?: return@forEach
            val symbol = row.string("symbol")?.normalizeSymbol() ?: return@forEach
            val channel = row.string("channel")
            if (channel != TRADE_CHANNEL) return@forEach

            val unsubscribeImmediately = synchronized(lock) {
                pendingSubscribeSymbols.remove(symbol)
                activeChannelIds[symbol] = id
                symbol !in desiredSymbols
            }
            if (unsubscribeImmediately) {
                sendUnsubscribe(listOf(id))
            }
        }
        syncSubscriptions()
    }

    private fun handleUnsubscribed(data: JsonElement?) {
        val ids = subscriptionObjects(data).mapNotNull { it.string("id") }.toSet()
        if (ids.isEmpty()) return
        synchronized(lock) {
            pendingUnsubscribeIds.removeAll(ids)
            activeChannelIds.entries.removeAll { it.value in ids }
        }
        syncSubscriptions()
    }

    private fun handleMarketData(root: JsonObject) {
        if (root.string("channel") != TRADE_CHANNEL) return
        val data = root["data"] as? JsonObject ?: return
        if (data.boolean("isTrial") == true) return

        val symbol = data.string("symbol")?.normalizeSymbol() ?: return
        val price = data.double("price")?.takeIf { it.isFinite() && it > 0.0 } ?: return
        val now = clockMillis()
        val sourceTime = normalizeEpochMillis(data.long("time"), now)
        val sessionDate = Instant.ofEpochMilli(sourceTime)
            .atZone(taipeiZone)
            .toLocalDate()
            .toString()

        val quote = MarketQuote(
            symbol = symbol,
            exchange = data.string("exchange"),
            market = data.string("market"),
            price = price,
            volume = data.long("volume")?.coerceAtLeast(0L),
            bid = data.double("bid")?.positiveOrNull(),
            ask = data.double("ask")?.positiveOrNull(),
            asOfEpochMillis = sourceTime,
            source = MarketSource.FUGLE,
            quality = QuoteQuality.LIVE,
            sourceTimestampEpochMillis = sourceTime,
            receivedAtEpochMillis = now,
            sessionDate = sessionDate,
            fallbackLevel = 0,
            sequence = data.long("serial"),
            isTrial = false,
            isClose = data.boolean("isClose") == true,
        )

        synchronized(lock) {
            latestQuotes[symbol] = quote
            lastSuccessEpochMillis = now
            consecutiveFailures = 0
        }
        _events.tryEmit(MarketEvent.Quote(quote))
        emitHealth()
    }

    private fun handleServerError(root: JsonObject) {
        val message = (root["data"] as? JsonObject)?.string("message").orEmpty()
        val authFailure = message.contains("auth", ignoreCase = true) ||
            message.contains("credential", ignoreCase = true) ||
            message.contains("api key", ignoreCase = true)

        val current = synchronized(lock) {
            consecutiveFailures += 1
            if (authFailure) {
                credentialRejected = true
                manualDisconnect = true
            }
            authenticated = false
            activeChannelIds.clear()
            pendingSubscribeSymbols.clear()
            pendingUnsubscribeIds.clear()
            socket.also { socket = null }
        }
        current?.close(POLICY_CLOSE_CODE, "Fugle server error")
        if (!authFailure) scheduleReconnect()
        emitHealth()
    }

    private fun handleDisconnect(disconnected: FugleSocket, error: Throwable?) {
        val shouldReconnect = synchronized(lock) {
            if (socket !== disconnected) return
            socket = null
            authenticated = false
            connecting = false
            activeChannelIds.clear()
            pendingSubscribeSymbols.clear()
            pendingUnsubscribeIds.clear()
            if (error != null) consecutiveFailures += 1
            !manualDisconnect && !credentialRejected && desiredSymbols.isNotEmpty()
        }
        if (shouldReconnect) scheduleReconnect()
        emitHealth()
    }

    private fun syncSubscriptions() {
        val currentSocket: FugleSocket
        val subscribeSymbols: List<String>
        val unsubscribeIds: List<String>

        synchronized(lock) {
            if (!authenticated) return
            currentSocket = socket ?: return

            subscribeSymbols = desiredSymbols
                .filter { it !in activeChannelIds && it !in pendingSubscribeSymbols }
                .sorted()
            pendingSubscribeSymbols += subscribeSymbols

            val removeSymbols = activeChannelIds.keys.filter { it !in desiredSymbols }
            unsubscribeIds = removeSymbols
                .mapNotNull { activeChannelIds[it] }
                .filter { it !in pendingUnsubscribeIds }
            pendingUnsubscribeIds += unsubscribeIds
        }

        if (subscribeSymbols.isNotEmpty()) {
            val sent = currentSocket.send(subscribeMessage(subscribeSymbols))
            if (!sent) {
                synchronized(lock) { pendingSubscribeSymbols.removeAll(subscribeSymbols.toSet()) }
                scheduleReconnect()
            }
        }
        if (unsubscribeIds.isNotEmpty()) {
            val sent = currentSocket.send(unsubscribeMessage(unsubscribeIds))
            if (!sent) {
                synchronized(lock) { pendingUnsubscribeIds.removeAll(unsubscribeIds.toSet()) }
                scheduleReconnect()
            }
        }
    }

    private fun sendUnsubscribe(ids: List<String>) {
        if (ids.isEmpty()) return
        val current = synchronized(lock) {
            pendingUnsubscribeIds += ids
            socket
        } ?: return
        if (!current.send(unsubscribeMessage(ids))) {
            synchronized(lock) { pendingUnsubscribeIds.removeAll(ids.toSet()) }
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        val delay = synchronized(lock) {
            if (manualDisconnect || credentialRejected || desiredSymbols.isEmpty()) return
            if (reconnectFuture?.isDone == false) return

            val exponential = min(
                RECONNECT_MAX_MILLIS,
                RECONNECT_BASE_MILLIS * (1L shl reconnectAttempt.coerceIn(0, 5)),
            )
            val jitter = (reconnectAttempt * 137L) % RECONNECT_JITTER_MILLIS
            reconnectAttempt += 1
            val delayMillis = exponential + jitter
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
        val action = synchronized(lock) {
            if (!authenticated || socket == null || manualDisconnect) return
            val last = lastMessageEpochMillis ?: lastHeartbeatEpochMillis ?: now
            when {
                now - last >= HEARTBEAT_TIMEOUT_MILLIS -> 2
                now - last >= PING_AFTER_SILENCE_MILLIS -> 1
                else -> 0
            }
        }

        when (action) {
            1 -> {
                synchronized(lock) { socket }?.send(pingMessage(now))
            }

            2 -> {
                val current = synchronized(lock) {
                    authenticated = false
                    connecting = false
                    consecutiveFailures += 1
                    activeChannelIds.clear()
                    pendingSubscribeSymbols.clear()
                    pendingUnsubscribeIds.clear()
                    socket.also { socket = null }
                }
                current?.cancel()
                scheduleReconnect()
                emitHealth()
            }
        }
    }

    private fun heartbeatIsHealthy(nowEpochMillis: Long): Boolean {
        val last = lastMessageEpochMillis ?: lastHeartbeatEpochMillis ?: return false
        return nowEpochMillis - last <= HEARTBEAT_TIMEOUT_MILLIS
    }

    private fun emitHealth() {
        _events.tryEmit(MarketEvent.ProviderState(health(clockMillis())))
    }

    private fun authMessage(apiKey: String): String =
        buildJsonObject {
            put("event", "auth")
            put(
                "data",
                buildJsonObject {
                    put("apikey", apiKey)
                },
            )
        }.toString()

    private fun subscribeMessage(symbols: List<String>): String =
        buildJsonObject {
            put("event", "subscribe")
            put(
                "data",
                buildJsonObject {
                    put("channel", TRADE_CHANNEL)
                    put(
                        "symbols",
                        buildJsonArray {
                            symbols.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
                        },
                    )
                },
            )
        }.toString()

    private fun unsubscribeMessage(ids: List<String>): String =
        buildJsonObject {
            put("event", "unsubscribe")
            put(
                "data",
                buildJsonObject {
                    put(
                        "ids",
                        buildJsonArray {
                            ids.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
                        },
                    )
                },
            )
        }.toString()

    private fun pingMessage(state: Long): String =
        buildJsonObject {
            put("event", "ping")
            put(
                "data",
                buildJsonObject {
                    put("state", state)
                },
            )
        }.toString()

    private fun subscriptionObjects(data: JsonElement?): List<JsonObject> =
        when (data) {
            is JsonObject -> listOf(data)
            is JsonArray -> data.mapNotNull { it as? JsonObject }
            else -> emptyList()
        }

    private fun JsonObject.string(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.long(key: String): Long? =
        this[key]?.jsonPrimitive?.longOrNull

    private fun JsonObject.double(key: String): Double? =
        this[key]?.jsonPrimitive?.doubleOrNull

    private fun JsonObject.boolean(key: String): Boolean? =
        this[key]?.jsonPrimitive?.booleanOrNull

    private fun String.normalizeSymbol(): String =
        trim().uppercase(Locale.US)

    private fun normalizeSymbols(symbols: Set<String>): Set<String> =
        symbols.map { it.normalizeSymbol() }
            .filter { it.isNotBlank() }
            .toSet()

    private fun normalizeEpochMillis(raw: Long?, receivedAt: Long): Long {
        val value = raw ?: return receivedAt
        return when {
            value >= 100_000_000_000_000L -> value / 1_000L
            value >= 100_000_000_000L -> value
            value >= 100_000_000L -> value * 1_000L
            else -> receivedAt
        }
    }

    private fun Double.positiveOrNull(): Double? =
        takeIf { it.isFinite() && it > 0.0 }

    companion object {
        const val STREAMING_URL =
            "wss://api.fugle.tw/marketdata/v1.0/stock/streaming"
        private const val TRADE_CHANNEL = "trades"
        private const val WATCHDOG_PERIOD_MILLIS = 15_000L
        private const val PING_AFTER_SILENCE_MILLIS = 40_000L
        private const val HEARTBEAT_TIMEOUT_MILLIS = 75_000L
        private const val RECONNECT_BASE_MILLIS = 1_000L
        private const val RECONNECT_MAX_MILLIS = 30_000L
        private const val RECONNECT_JITTER_MILLIS = 500L
        private const val NORMAL_CLOSE_CODE = 1000
        private const val POLICY_CLOSE_CODE = 1008
    }
}
