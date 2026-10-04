package tw.saietf.core.model

@JvmInline
value class TwdAmount(val value: Long) {
    init {
        require(value >= 0) { "TWD amount cannot be negative" }
    }

    operator fun plus(other: TwdAmount): TwdAmount = TwdAmount(Math.addExact(value, other.value))
}

fun Long.twd(): TwdAmount = TwdAmount(this)
