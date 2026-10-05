package tw.saietf.app

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import tw.saietf.core.market.MarketDataCenter
import tw.saietf.core.market.MarketEvent
import tw.saietf.core.market.ProviderCircuitState
import tw.saietf.core.market.ProviderHealth

internal class RealtimeStreamingController(
    private val scope: CoroutineScope,
    private val fugleController: FugleStreamingController,
    private val fugleProvider: FugleWebSocketProvider,
    private val shioajiProvider: ShioajiSseGatewayProvider,
    private val shioajiSettings: ShioajiGatewaySettingsStore,
    private val marketDataCenter: MarketDataCenter,
) {
    private val desiredSymbols = AtomicReference<Set<String>>(emptySet())

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            shioajiProvider.events.collect { event ->
                when (event) {
                    is MarketEvent.Quote ->
                        marketDataCenter.acceptStreamingQuote(event.quote)
                    is MarketEvent.ProviderState -> Unit
                }
            }
        }
        scope.launch {
            while (isActive) {
                reconcileSecondary()
                delay(RECONCILE_INTERVAL_MILLIS)
            }
        }
    }

    fun updateSymbols(symbols: Set<String>) {
        val normalized = symbols.map { it.trim().uppercase() }.filter { it.isNotBlank() }.toSet()
        desiredSymbols.set(normalized)
        fugleController.updateSymbols(normalized)
        scope.launch {
            reconcileSecondary()
        }
    }

    fun pause() {
        fugleController.pause()
        scope.launch {
            shioajiProvider.disconnect()
        }
    }

    fun onFugleCredentialChanged(symbols: Set<String>) {
        desiredSymbols.set(symbols)
        fugleController.onCredentialChanged(symbols)
        scope.launch {
            reconcileSecondary()
        }
    }

    fun onShioajiSettingsChanged() {
        shioajiProvider.onSettingsChanged()
        scope.launch {
            reconcileSecondary()
        }
    }

    fun fugleHealth(): ProviderHealth =
        fugleProvider.health(System.currentTimeMillis())

    fun shioajiHealth(): ProviderHealth =
        shioajiProvider.health(System.currentTimeMillis())

    fun desiredSymbols(): Set<String> = desiredSymbols.get()

    private suspend fun reconcileSecondary() {
        val symbols = desiredSymbols.get()
        if (symbols.isEmpty()) {
            shioajiProvider.disconnect()
            return
        }

        val fugleState = fugleHealth().circuitState
        val shouldUseSecondary =
            shioajiSettings.isConfigured() &&
                fugleState != ProviderCircuitState.HEALTHY

        if (shouldUseSecondary) {
            shioajiProvider.replaceSubscriptions(symbols)
        } else {
            shioajiProvider.disconnect()
        }
    }

    companion object {
        private const val RECONCILE_INTERVAL_MILLIS = 1_000L
    }
}
