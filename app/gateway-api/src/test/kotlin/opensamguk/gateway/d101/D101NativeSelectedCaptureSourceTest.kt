package opensamguk.gateway.d101

import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.gateway.d101.domain.D101StrictJson
import opensamguk.gateway.d101.infra.*
import opensamguk.gateway.d101.domain.D101PurposeAuthorityUnavailable
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Base64

class D101NativeSelectedCaptureSourceTest {
    private class Fixture {
        val mapper = ObjectMapper()
        val op = "a".repeat(32)
        val bindingsPin = "b".repeat(64)
        val originals = D101NativeSelectedCaptureSource.REQUIRED_IDS.associateWith {
            if (it == "world.json") ByteArray(2 * 1024 * 1024 + 97) { n -> (n % 251).toByte() }
            else "synthetic-only $it".toByteArray()
        }.toMutableMap()
        val scope = D101SelectedCaptureScope(op, hash(originals.getValue("typedTarget")), "c".repeat(40),
            setOf("game-api", "game-engine", "web-game", "game-postgres", "game-redis").associateWith { "sha256:" + "d".repeat(64) },
            "fixture-producer", "e".repeat(64), "sha256:" + "f".repeat(64))
        val refs = originals.mapValues { (id, bytes) -> mutableMapOf<String, Any>(
            "filePath" to "${D101NativeSelectedCaptureSource.ORIGINAL_DIRECTORY}/$op/${hash(id.toByteArray())}.bin",
            "rawSha256" to hash(bytes), "byteLength" to bytes.size,
            "snapshot" to mapOf("device" to 1, "inode" to 2, "byteLength" to bytes.size, "modifiedAtUnixNano" to 3),
            "mediaType" to if (id in setOf("parserClass", "topologyRootClass", "topologyCanonical")) "application/octet-stream" else "application/json") }.toMutableMap()
        val index = mutableMapOf<String, Any>("schemaVersion" to 1, "kind" to "D101_SELECTED_CAPTURE_INDEX_V1",
            "originalOp" to op, "typedTargetFingerprint" to scope.targetFingerprint, "appSourceSha" to scope.appSourceSha,
            "imagePins" to scope.imagePins, "trustedProducerIdentity" to scope.producerIdentity,
            "producerContainerId" to scope.producerContainerId, "producerImageId" to scope.producerImageId,
            "selectedEnvelopeSha256" to hash(originals.getValue("selectedEnvelope")),
            "selectedReceiptSha256" to hash(originals.getValue("selectedReceipt")), "partBytes" to 1024 * 1024,
            "capturedAtUtc" to "2026-10-06T00:00:00Z", "originals" to refs)
        var elapsed = 0L
        var mode = ""
        var indexReads = 0
        fun source(): D101NativeSelectedCaptureSource {
            val wire = mapper.writeValueAsBytes(index)
            val pin = hash(wire)
            return D101NativeSelectedCaptureSource(D101NativeHostReader { action ->
                if (action == D101NativeSelectedCaptureSource.INDEX_ACTION) {
                    indexReads++
                    val actual = if (mode == "index-drift" && indexReads >= 3) wire + ' '.code.toByte() else wire
                    mapper.writeValueAsBytes(mapOf("schemaVersion" to 1, "installationSha256" to bindingsPin,
                        "captureIndexSha256" to pin, "captureIndexBytesBase64url" to b64(actual)))
                } else {
                    val id = action.removePrefix(D101NativeSelectedCaptureSource.PART_ACTION).substringBeforeLast(':')
                    val n = action.substringAfterLast(':').toInt()
                    val offset = n * 1024 * 1024
                    val bytes = originals.getValue(id).copyOfRange(offset, minOf(offset + 1024 * 1024, originals.getValue(id).size))
                    val response = mutableMapOf<String, Any>("schemaVersion" to 1, "installationSha256" to bindingsPin,
                        "captureIndexSha256" to pin, "logicalArtifactId" to id, "pinnedOriginalSha256" to hash(originals.getValue(id)),
                        "originalByteLength" to originals.getValue(id).size, "partIndex" to n, "partOffset" to offset,
                        "partSha256" to hash(bytes), "partBytesBase64url" to b64(bytes), "nativeSnapshot" to refs.getValue(id).getValue("snapshot"))
                    when (mode) {
                        "missing" -> throw D101PurposeAuthorityUnavailable()
                        "part-order" -> response["partIndex"] = n + 1
                        "offset" -> response["partOffset"] = offset + 1
                        "source" -> response["logicalArtifactId"] = "tiles.json"
                        "index-pin" -> response["captureIndexSha256"] = "0".repeat(64)
                        "original-pin" -> response["pinnedOriginalSha256"] = "0".repeat(64)
                        "whole-sha" -> { bytes[0] = (bytes[0].toInt() xor 1).toByte(); response["partBytesBase64url"] = b64(bytes); response["partSha256"] = hash(bytes) }
                        "truncated" -> response["partBytesBase64url"] = b64(bytes.copyOf(bytes.size - 1))
                        "snapshot" -> response["nativeSnapshot"] = mapOf("device" to 1, "inode" to 3, "byteLength" to originals.getValue(id).size, "modifiedAtUnixNano" to 3)
                        "unknown" -> response["callerPath"] = "/caller"
                        "deadline" -> elapsed = 30_000_000_000L
                    }
                    mapper.writeValueAsBytes(response)
                }
            }, bindingsPin, pin, scope, mapper, { Instant.parse("2026-10-06T01:00:00Z") }, { elapsed })
        }
        companion object {
            fun hash(bytes: ByteArray) = D101StrictJson.hash(bytes)
            fun b64(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }
    }

    @Test fun `original larger than helper pipe assembles independent whole SHA and reports actual metrics`() {
        val f = Fixture()
        val source = f.source()
        assertArrayEquals(f.originals.getValue("world.json"), source.readOriginal("world.json"))
        assertEquals(3, f.indexReads)
        val metric = source.metrics().single()
        assertEquals(3, metric.parts)
        assertEquals(f.originals.getValue("world.json").size, metric.bytes)
        assertTrue(metric.completed)
        assertThrows(D101PurposeAuthorityUnavailable::class.java) { source.readOriginal("rootToken") }
        val policy = Fixture()
        val id = "topology-input:dryLandProjectionPolicy"
        val bytes = "SYNTHETIC_ONLY fixed code policy original".toByteArray()
        policy.originals[id] = bytes
        policy.refs[id] = mutableMapOf("filePath" to "${D101NativeSelectedCaptureSource.ORIGINAL_DIRECTORY}/${policy.op}/${D101StrictJson.hash(id.toByteArray())}.bin",
            "rawSha256" to D101StrictJson.hash(bytes), "byteLength" to bytes.size,
            "snapshot" to mapOf("device" to 1, "inode" to 7, "byteLength" to bytes.size, "modifiedAtUnixNano" to 3),
            "mediaType" to "application/octet-stream")
        assertArrayEquals(bytes, policy.source().readOriginal(id))
    }

    @Test fun `missing reordered rebound truncated and mixed parts stay closed`() {
        for (mode in listOf("missing", "part-order", "offset", "source", "index-pin", "original-pin", "whole-sha", "truncated", "snapshot", "unknown", "index-drift")) {
            val f = Fixture().apply { this.mode = mode }
            val source = f.source()
            assertThrows(D101PurposeAuthorityUnavailable::class.java, { source.readOriginal("world.json") }, mode)
            assertFalse(source.metrics().single().completed)
        }
    }

    @Test fun `index rejects future authority fields unsafe paths missing sources and caps`() {
        for (mode in listOf("future", "path", "missing", "cap", "scope", "snapshot", "timestamp")) {
            val f = Fixture()
            when (mode) {
                "future" -> f.index["approvalIntentSha256"] = "0".repeat(64)
                "path" -> f.refs.getValue("world.json")["filePath"] = "/etc/opensamguk/d101/key.json"
                "missing" -> f.index["originals"] = f.refs - "configuration"
                "cap" -> f.refs.getValue("parserClass")["byteLength"] = 2 * 1024 * 1024 + 1
                "scope" -> f.index["originalOp"] = "0".repeat(32)
                "snapshot" -> f.refs.getValue("world.json")["snapshot"] = mapOf("device" to 0, "inode" to 2, "byteLength" to f.originals.getValue("world.json").size, "modifiedAtUnixNano" to 3)
                "timestamp" -> f.index["capturedAtUtc"] = "2027-10-06T00:00:00Z"
            }
            assertThrows(D101PurposeAuthorityUnavailable::class.java, { f.source() }, mode)
        }
    }

    @Test fun `fixed actions bound ordinal and deadline retains failed transfer measurements`() {
        for (action in listOf("read-selected-source-part:world.json:00", "read-selected-source-part:world.json:64",
            "read-selected-source-part:world.json:-1", "read-selected-source-part:rootToken:0", "read-selected-source-part:topology-input:../key:0")) {
            assertFalse(D101NativeSelectedCaptureSource.validAction(action))
        }
        assertTrue(D101NativeSelectedCaptureSource.validAction("read-selected-source-part:topology-input:actual/callback.bin:0"))
        val f = Fixture().apply { mode = "deadline" }
        val source = f.source()
        assertThrows(D101PurposeAuthorityUnavailable::class.java) { source.readOriginal("world.json") }
        val metric = source.metrics().single()
        assertEquals(1, metric.parts)
        assertEquals(1024 * 1024, metric.bytes)
        assertEquals(30_000_000_000L, metric.elapsedNanos)
        assertFalse(metric.completed)
    }
}
