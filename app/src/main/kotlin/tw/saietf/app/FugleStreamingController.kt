package tw.saietf.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import tw.saietf.core.market.MarketDataCenter
import tw.saietf.core.market.MarketEvent
import tw.saietf.core.market.ProviderHealth

internal class FugleStreamingController(
    private val scope: CoroutineScope,
    private val provider: FugleWebSocketProvider,
    private val marketDataCenter: MarketDataCenter,
) {
    init {
        scope.launch {
            provider.events.collect { event ->
                when (event) {
                    is MarketEvent.Quote -> {
                        marketDataCenter.acceptStreamingQuote(event.quote)
                    }

                    is MarketEvent.ProviderState -> Unit
                }
            }
        }
    }

    fun updateSymbols(symbols: Set<String>) {
        scope.launch {
            provider.replaceSubscriptions(symbols)
            if (symbols.isEmpty()) {
                provider.disconnect()
            }
        }
    }

    fun pause() {
        scope.launch {
            provider.disconnect()
        }
    }

    fun onCredentialChanged(symbols: Set<String>) {
        provider.onCredentialChanged()
        updateSymbols(symbols)
    }

    fun health(): ProviderHealth =
        provider.health(System.currentTimeMillis())

    fun desiredSymbols(): Set<String> =
        provider.desiredSymbolsSnapshot()
}
