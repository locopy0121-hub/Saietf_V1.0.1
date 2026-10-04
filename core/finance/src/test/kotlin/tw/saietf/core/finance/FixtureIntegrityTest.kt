package tw.saietf.core.finance

import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FixtureIntegrityTest {
    private val sourceCommit = "a872644c572d24fc5ffe597a97eb6d7d8bd7283f"
    private val sourceRepository = "locopy0121-hub/ETF-Finance-Manager-V3.7"
    private val fixtureFiles = listOf("trades.json", "portfolios.json", "dividends.json")

    @Test
    fun `fixture manifest pins the reviewed source and schema`() {
        val manifest = readText("manifest.json")
        assertTrue(manifest.contains("\"schemaVersion\": 1"))
        assertTrue(manifest.contains("\"sourceRepository\": \"$sourceRepository\""))
        assertTrue(manifest.contains("\"sourceCommit\": \"$sourceCommit\""))
        assertTrue(manifest.contains("src/utils/etfCalculators.ts"))
        assertTrue(manifest.contains("src/data/brokerProfiles.ts"))
        assertTrue(manifest.contains("src/types/etf.ts"))
    }

    @Test
    fun `every fixture has immutable provenance and matching sha256`() {
        val manifest = readText("manifest.json")
        fixtureFiles.forEach { name ->
            val bytes = readBytes(name)
            val text = bytes.toString(Charsets.UTF_8)
            assertTrue("$name schema drifted", text.contains("\"schemaVersion\": 1"))
            assertTrue("$name source repository drifted", text.contains("\"sourceRepository\": \"$sourceRepository\""))
            assertTrue("$name source commit drifted", text.contains("\"sourceCommit\": \"$sourceCommit\""))
            val expected = Regex("\\\"${Regex.escape(name)}\\\"\\s*:\\s*\\\"([a-f0-9]{64})\\\"")
                .find(manifest)?.groupValues?.get(1)
            assertNotNull("missing manifest hash for $name", expected)
            assertEquals("fixture hash mismatch for $name", expected, sha256(bytes))
        }
    }

    @Test
    fun `frozen contract contains every required golden case`() {
        val trades = readText("trades.json")
        val portfolios = readText("portfolios.json")
        val dividends = readText("dividends.json")

        listOf(
            "0050-golden-executed-buy",
            "round-lot-minimum-fee",
            "odd-lot-minimum-fee",
            "recurring-uses-odd-lot-minimum",
            "stock-sell-tax",
            "actual-fee-tax-preserved",
        ).forEach { assertTrue("missing trade fixture $it", trades.contains("\"id\": \"$it\"")) }

        listOf(
            "partial-sell-moving-average",
            "00878-canonical-summary",
            "two-instrument-portfolio",
        ).forEach { assertTrue("missing portfolio fixture $it", portfolios.contains("\"id\": \"$it\"")) }

        listOf(
            "below-health-premium-threshold",
            "at-health-premium-threshold",
            "zero-dividend",
        ).forEach { assertTrue("missing dividend fixture $it", dividends.contains("\"id\": \"$it\"")) }
    }

    private fun readText(name: String): String = readBytes(name).toString(Charsets.UTF_8)

    private fun readBytes(name: String): ByteArray {
        val path = "/v378/$name"
        val stream = javaClass.getResourceAsStream(path)
        assertNotNull("missing fixture resource $path", stream)
        return stream!!.use { it.readBytes() }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { "%02x".format(it) }
}
