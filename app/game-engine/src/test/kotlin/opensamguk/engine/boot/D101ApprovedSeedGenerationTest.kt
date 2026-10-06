package opensamguk.engine.boot

import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.infra.seed.SelectedSourceUnavailable
import java.security.MessageDigest
import java.util.Base64
import java.util.HexFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** A structural original-byte check. C8's independent signer/authority remains required. */
class D101ApprovedSeedGenerationTest {
    private val mapper = ObjectMapper()
    private val pins = listOf("game-api", "game-engine", "web-game", "game-postgres", "game-redis")
        .associateWith { "sha256:" + "a".repeat(64) }
    private val options = mapOf(
        "SCENARIO_CODE" to "scenario_3190", "SCENARIO_SEED_ENABLED" to "true",
        "SCENARIO_LOOKUP_DIR" to "", "RESET_MAXGENERAL" to "50",
        "RESET_FIRST_TURN" to "immediate", "RESET_EXTEND" to "1", "RESET_TURNTERM" to "60",
        "RESET_BLOCK_GENERAL_CREATE" to "1", "RESET_NPCMODE" to "0",
        "RESET_SHOW_IMG_LEVEL" to "3", "RESET_FICTION" to "1",
        "SERVER_NAME" to "빼섭", "SERVER_GENERATION" to "0",
    )

    private fun sha(bytes: ByteArray) =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun original(generation: Int = 0): ByteArray {
        val target = mapper.writeValueAsBytes(mapOf(
            "id" to "pep",
            "target" to mapOf(
                "scenarioCode" to "scenario_3190", "generation" to generation,
                "scenarioSeedEnabled" to true, "updates" to options,
                "imageDigests" to pins.filterKeys { it !in setOf("game-postgres", "game-redis") },
                "storageImageDigests" to pins.filterKeys { it in setOf("game-postgres", "game-redis") },
            ),
        ))
        return mapper.writeValueAsBytes(mapOf(
            "targetFingerprint" to sha(target),
            "rootTargetBytesBase64url" to Base64.getUrlEncoder().withoutPadding().encodeToString(target),
            "operationId" to "b".repeat(32), "appSourceSha" to "c".repeat(40),
            "selectedSourceReceiptSha256" to "d".repeat(64),
            "serverId" to "pep", "serverName" to "빼섭", "worldId" to 1,
            "newImageDigests" to pins,
        ))
    }

    @Test
    fun `original approval target generation is bound to zero and parsed options`() {
        val raw = original()
        val observed = D101ApprovedSeedGeneration(raw, sha(raw))
        assertEquals(0, observed.generation)
        assertEquals(sha(raw), observed.approvalIntentSha256)
        assertEquals(options, observed.options)
        assertEquals(pins, observed.imagePins)
    }

    @Test
    fun `different generation or original hash is unavailable`() {
        val raw = original()
        assertFailsWith<SelectedSourceUnavailable> { D101ApprovedSeedGeneration(raw, "0".repeat(64)) }
        val other = original(generation = 1)
        assertFailsWith<SelectedSourceUnavailable> { D101ApprovedSeedGeneration(other, sha(other)) }
    }
}
