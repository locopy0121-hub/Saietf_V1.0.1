package tw.saietf.core.market

data class CircuitBreakerPolicy(
    val failureThreshold: Int = 2,
    val recoverySuccessThreshold: Int = 2,
    val defaultCooldownMillis: Long = 5_000L,
    val maxCooldownMillis: Long = 15L * 60L * 1_000L,
)

data class CircuitBreakerSnapshot(
    val state: ProviderCircuitState,
    val consecutiveFailures: Int,
    val recoverySuccesses: Int,
    val cooldownUntilEpochMillis: Long,
)

class ProviderCircuitBreaker(
    private val policy: CircuitBreakerPolicy = CircuitBreakerPolicy(),
) {
    private var state: ProviderCircuitState = ProviderCircuitState.HEALTHY
    private var consecutiveFailures: Int = 0
    private var recoverySuccesses: Int = 0
    private var cooldownUntilEpochMillis: Long = 0L

    @Synchronized
    fun canAttempt(nowEpochMillis: Long): Boolean =
        when (state) {
            ProviderCircuitState.HEALTHY,
            ProviderCircuitState.DEGRADED,
            ProviderCircuitState.RECOVERING,
            -> true

            ProviderCircuitState.COOLDOWN -> {
                if (nowEpochMillis >= cooldownUntilEpochMillis) {
                    state = ProviderCircuitState.RECOVERING
                    recoverySuccesses = 0
                    true
                } else {
                    false
                }
            }
        }

    @Synchronized
    fun recordSuccess(nowEpochMillis: Long) {
        when (state) {
            ProviderCircuitState.RECOVERING -> {
                recoverySuccesses += 1
                if (recoverySuccesses >= policy.recoverySuccessThreshold) {
                    state = ProviderCircuitState.HEALTHY
                    consecutiveFailures = 0
                    recoverySuccesses = 0
                    cooldownUntilEpochMillis = 0L
                }
            }

            ProviderCircuitState.DEGRADED,
            ProviderCircuitState.HEALTHY,
            -> {
                state = ProviderCircuitState.HEALTHY
                consecutiveFailures = 0
                recoverySuccesses = 0
                cooldownUntilEpochMillis = 0L
            }

            ProviderCircuitState.COOLDOWN -> {
                if (nowEpochMillis >= cooldownUntilEpochMillis) {
                    state = ProviderCircuitState.RECOVERING
                    recoverySuccesses = 1
                }
            }
        }
    }

    @Synchronized
    fun recordFailure(
        nowEpochMillis: Long,
        cooldownMillis: Long? = null,
    ) {
        consecutiveFailures += 1
        recoverySuccesses = 0

        val shouldOpen = state == ProviderCircuitState.RECOVERING ||
            consecutiveFailures >= policy.failureThreshold

        if (shouldOpen) {
            state = ProviderCircuitState.COOLDOWN
            val requested = cooldownMillis ?: policy.defaultCooldownMillis
            cooldownUntilEpochMillis = nowEpochMillis +
                requested.coerceIn(1_000L, policy.maxCooldownMillis)
        } else {
            state = ProviderCircuitState.DEGRADED
        }
    }

    @Synchronized
    fun forceCooldown(
        nowEpochMillis: Long,
        cooldownMillis: Long,
    ) {
        consecutiveFailures = (consecutiveFailures + 1).coerceAtLeast(policy.failureThreshold)
        recoverySuccesses = 0
        state = ProviderCircuitState.COOLDOWN
        cooldownUntilEpochMillis = nowEpochMillis +
            cooldownMillis.coerceIn(1_000L, policy.maxCooldownMillis)
    }

    @Synchronized
    fun snapshot(nowEpochMillis: Long): CircuitBreakerSnapshot {
        if (state == ProviderCircuitState.COOLDOWN && nowEpochMillis >= cooldownUntilEpochMillis) {
            state = ProviderCircuitState.RECOVERING
            recoverySuccesses = 0
        }
        return CircuitBreakerSnapshot(
            state = state,
            consecutiveFailures = consecutiveFailures,
            recoverySuccesses = recoverySuccesses,
            cooldownUntilEpochMillis = cooldownUntilEpochMillis,
        )
    }
}
