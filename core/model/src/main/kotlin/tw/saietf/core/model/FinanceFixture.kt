package tw.saietf.core.model

/**
 * Provenance-bearing immutable fixture envelope for the frozen TF Asset finance core.
 */
data class FinanceFixture<I, E>(
    val schemaVersion: Int,
    val sourceRepository: String,
    val sourceCommit: String,
    val input: I,
    val expected: E,
)
