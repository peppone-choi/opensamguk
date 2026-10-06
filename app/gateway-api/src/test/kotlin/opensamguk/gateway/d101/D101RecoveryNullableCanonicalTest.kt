package opensamguk.gateway.d101

import opensamguk.gateway.d101.domain.*
import opensamguk.gateway.d101.security.*
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.*

class D101RecoveryNullableCanonicalTest {
    private val f = D101Fixture()
    private val beginSha = "1".repeat(64)
    private val databaseSha = "3".repeat(64)
    private val runtimeSha = "4".repeat(64)

    @Test
    fun `canonical nullable fields retain original nulls while restored actual values stay nonnull`() {
        for ((generation, scenario) in listOf(null to null, 9 to null, null to "scenario_180")) {
            val registry = canonical(generation, scenario)
            val world = restoredWorld()
            val decoded = decode(registry, world)
            assertEquals(generation, decoded.oldCanonicalRegistry.generation)
            assertEquals(scenario, decoded.oldCanonicalRegistry.scenarioCode)
            assertEquals(9, decoded.restoredWorld.generation)
            assertEquals("scenario_180", decoded.restoredWorld.scenarioCode)
            assertContentEquals(registry, decoded.oldRegistryOriginalBytes())
            assertContentEquals(world, decoded.oldWorldOriginalBytes())
        }
    }

    @Test
    fun `canonical still requires exact seven present fields and strict optional types`() {
        val valid = canonical(null, null).toString(Charsets.UTF_8)
        val invalid = listOf(
            valid.replace("\"generation\":null,", ""),
            valid.replace("\"scenarioCode\":null", "\"scenarioCode\":null,\"extra\":1"),
            valid.replace("\"generation\":null", "\"generation\":null,\"generation\":null"),
            valid + " {}",
            valid.replace("\"generation\":null", "\"generation\":\"9\""),
            valid.replace("\"generation\":null", "\"generation\":9.0"),
            valid.replace("\"generation\":null", "\"generation\":-1"),
            valid.replace("\"generation\":null", "\"generation\":2147483648"),
            valid.replace("\"scenarioCode\":null", "\"scenarioCode\":9"),
            valid.replace("\"scenarioCode\":null", "\"scenarioCode\":\"wrong\""),
            valid.replace("\"name\":\"old-name\"", "\"name\":null"),
            valid.replace("\"generation\":null", "\"generation\":8"),
            valid.replace("\"scenarioCode\":null", "\"scenarioCode\":\"scenario_181\""),
        )
        invalid.forEach { original ->
            assertFailsWith<RuntimeException>(original) {
                D101RecoverySnapshots.decodeCanonicalRegistry(f.json, original.toByteArray(), 9, "scenario_180")
            }
        }
        assertFailsWith<RuntimeException> {
            D101RecoverySnapshots.decodeCanonicalRegistry(f.json, byteArrayOf(0xc3.toByte(), 0x28), 9, "scenario_180")
        }
    }

    @Test
    fun `null canonical never relaxes restored world generation scenario or receipt hash`() {
        val registry = canonical(null, null)
        val world = restoredWorld()
        assertFailsWith<D101ObservationUnavailable> { decode(registry, restoredWorld(generation = 8)) }
        assertFailsWith<D101ObservationUnavailable> { decode(registry, restoredWorld(scenario = "scenario_181")) }
        assertFailsWith<D101ObservationUnavailable> {
            D101RecoverySnapshots.decode(f.json, execution(), beginSha, registry, D101Fixture.hash(registry),
                world, "f".repeat(64), 9, "scenario_180", databaseSha, runtimeSha,
                Instant.parse("2026-10-06T09:00:01Z"), Instant.parse("2026-10-06T09:00:03Z"))
        }
    }

    @Test
    fun `verified close accepts nullable canonical and keeps actual and replay bindings`() {
        val registry = canonical(null, null)
        val world = restoredWorld()
        val snapshots = decode(registry, world)
        val execution = execution()
        val result = """{"status":"RECOVERED"}""".toByteArray()
        val resultSha = D101Fixture.hash(result)
        fun verified(generation: Int = 9, scenario: String = "scenario_180") = D101VerifiedRecoveryClose(
            execution, beginSha, resultSha, "2".repeat(64), "5".repeat(64), generation, scenario,
            execution.intent.oldImageDigests, D101Fixture.hash(registry), "6".repeat(64),
            D101Fixture.hash(world), snapshots, result,
        )
        val close = verified()
        close.requireMatches(execution(), beginSha, resultSha)
        close.requireMatches(execution(), beginSha, resultSha)
        assertFailsWith<D101OperationConflict> { close.requireMatches(execution(), "f".repeat(64), resultSha) }
        assertFailsWith<D101OperationConflict> { close.requireMatches(execution(), beginSha, "f".repeat(64)) }
        assertFailsWith<IllegalArgumentException> { verified(generation = 8) }
        assertFailsWith<IllegalArgumentException> { verified(scenario = "scenario_181") }
    }

    private fun canonical(generation: Int?, scenario: String?): ByteArray = f.mapper.writeValueAsBytes(linkedMapOf(
        "id" to "pep", "name" to "old-name", "gameApiUrl" to "http://spep-game-api:8081",
        "gameEngineUrl" to "http://spep-game-engine:8082", "deployProject" to "opensamguk-spep",
        "generation" to generation, "scenarioCode" to scenario,
    ))

    private fun restoredWorld(generation: Int = 9, scenario: String = "scenario_180"): ByteArray {
        val execution = execution()
        return f.mapper.writeValueAsBytes(linkedMapOf(
            "schemaVersion" to 1, "kind" to "D101_RESTORED_OLD_WORLD_V1", "operationId" to f.operation,
            "approvalIntentSha256" to execution.intent.sha256,
            "targetFingerprint" to execution.intent.targetFingerprint, "verifyingRevision" to "2",
            "recoveryBeginReceiptSha256" to beginSha, "worldId" to 1,
            "generation" to generation, "scenarioCode" to scenario, "tickSeconds" to 60,
            "oldImageDigests" to execution.intent.oldImageDigests,
            "databaseReceiptSha256" to databaseSha, "runtimeReceiptSha256" to runtimeSha,
            "observedAtUtc" to "2026-10-06T09:00:02Z",
        ))
    }

    private fun decode(registry: ByteArray, world: ByteArray): D101RecoverySnapshots =
        D101RecoverySnapshots.decode(f.json, execution(), beginSha, registry, D101Fixture.hash(registry),
            world, D101Fixture.hash(world), 9, "scenario_180", databaseSha, runtimeSha,
            Instant.parse("2026-10-06T09:00:01Z"), Instant.parse("2026-10-06T09:00:03Z"))

    private fun execution(): D101Execution {
        val intentBytes = f.mapper.writeValueAsBytes(f.intentTree())
        val prepare = f.prepareBody()
        return D101Execution(f.intent(), intentBytes, prepare, D101Fixture.hash(prepare),
            D101ExecutionState.RECOVERY_REQUIRED, D101ExecutionState.DISPATCH_INTENT, 2,
            D101DispatchIntentCandidate(2, "4".repeat(64), "5".repeat(64), "6".repeat(64)),
            null, null, null, Instant.parse("2026-10-06T09:00:00Z"), Instant.parse("2026-10-06T09:00:00Z"))
    }
}
