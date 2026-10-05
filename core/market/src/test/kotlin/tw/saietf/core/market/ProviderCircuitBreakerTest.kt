package tw.saietf.core.market

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderCircuitBreakerTest {
    @Test
    fun `failure cooldown recovery and healthy transition are deterministic`() {
        val breaker = ProviderCircuitBreaker(
            CircuitBreakerPolicy(
                failureThreshold = 2,
                recoverySuccessThreshold = 2,
                defaultCooldownMillis = 5_000L,
                maxCooldownMillis = 60_000L,
            ),
        )
        val t0 = 1_000L

        breaker.recordFailure(t0, 5_000L)
        assertEquals(ProviderCircuitState.DEGRADED, breaker.snapshot(t0).state)
        assertTrue(breaker.canAttempt(t0 + 100L))

        breaker.recordFailure(t0 + 200L, 5_000L)
        assertEquals(ProviderCircuitState.COOLDOWN, breaker.snapshot(t0 + 200L).state)
        assertFalse(breaker.canAttempt(t0 + 4_000L))

        assertTrue(breaker.canAttempt(t0 + 5_200L))
        assertEquals(ProviderCircuitState.RECOVERING, breaker.snapshot(t0 + 5_200L).state)

        breaker.recordSuccess(t0 + 5_300L)
        assertEquals(ProviderCircuitState.RECOVERING, breaker.snapshot(t0 + 5_300L).state)

        breaker.recordSuccess(t0 + 5_400L)
        val recovered = breaker.snapshot(t0 + 5_400L)
        assertEquals(ProviderCircuitState.HEALTHY, recovered.state)
        assertEquals(0, recovered.consecutiveFailures)
    }

    @Test
    fun `failure while recovering reopens cooldown`() {
        val breaker = ProviderCircuitBreaker(
            CircuitBreakerPolicy(
                failureThreshold = 1,
                recoverySuccessThreshold = 2,
                defaultCooldownMillis = 1_000L,
                maxCooldownMillis = 10_000L,
            ),
        )

        breaker.recordFailure(0L, 1_000L)
        assertFalse(breaker.canAttempt(500L))
        assertTrue(breaker.canAttempt(1_000L))
        assertEquals(ProviderCircuitState.RECOVERING, breaker.snapshot(1_000L).state)

        breaker.recordFailure(1_100L, 2_000L)
        assertEquals(ProviderCircuitState.COOLDOWN, breaker.snapshot(1_100L).state)
        assertFalse(breaker.canAttempt(2_000L))
    }
}
