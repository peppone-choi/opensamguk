package opensamguk.infra.seed

import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class HistoricalOwnershipTest {
    @TempDir lateinit var temporary: Path
    private val directory = Path.of("../data/map/han-world-artifacts-v1")

    @Test fun `ownership originals match catalog hashes and source domain`() {
        val catalog = ObjectMapper().readTree(Files.readAllBytes(directory.resolve("ownership-catalog.json")))
        for (variant in catalog["variants"]) {
            val bytes = HistoricalOwnership.load(directory, variant["variantId"].asText(), variant["sourceCommit"].asText())
            for (file in variant["files"]) {
                val raw = bytes.getValue(file["path"].asText())
                assertEquals(file["sha256"].asText(), MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it) })
            }
            assertFailsWith<IllegalArgumentException> { HistoricalOwnership.load(directory, variant["variantId"].asText(), "other-commit") }
        }
    }

    @Test fun `corrupt compressed ownership cannot fall back to runtime files`() {
        val raw = Files.readAllBytes(directory.resolve("ownership-catalog.json"))
        Files.write(temporary.resolve("ownership-catalog.json"), raw)
        val variant = ObjectMapper().readTree(raw)["variants"][0]
        val blob = temporary.resolve(variant["files"][0]["blob"].asText())
        Files.createDirectories(blob.parent)
        Files.write(blob, byteArrayOf(1, 2, 3))
        assertFailsWith<IllegalArgumentException> {
            HistoricalOwnership.load(temporary, variant["variantId"].asText(), variant["sourceCommit"].asText())
        }
    }
}
