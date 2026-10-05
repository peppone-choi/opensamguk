package opensamguk.gateway.d101

import com.fasterxml.jackson.databind.node.ObjectNode
import opensamguk.gateway.d101.domain.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.math.BigInteger

class D101ApprovalIntentCodecTest {
    private val f = D101Fixture()
    private fun clone(value: ObjectNode): ObjectNode = f.mapper.readTree(f.mapper.writeValueAsBytes(value)) as ObjectNode
    private fun decode(value: ObjectNode): D101ApprovalIntent = f.intent(value)
    private fun changeTarget(value: ObjectNode, change: (ObjectNode) -> Unit) {
        val bytes = f.json.base64url(value["rootTargetBytesBase64url"].textValue(), 16 * 1024)
        val target = f.mapper.readTree(bytes) as ObjectNode
        change(target)
        val changed = f.mapper.writeValueAsBytes(target)
        value.put("rootTargetBytesBase64url", D101Fixture.b64(changed))
        value.put("targetFingerprint", D101Fixture.hash(changed))
    }

    @Test
    fun `exact original bytes and explicit zeros are preserved`() {
        val wire = f.mapper.writeValueAsBytes(f.intentTree())
        val original = wire.copyOf()
        val intent = f.codec.decode(wire, D101Fixture.hash(wire))
        assertEquals(f.operation, intent.operationId)
        assertEquals("", intent.target.updates["SCENARIO_LOOKUP_DIR"])
        assertEquals("1", intent.target.updates["RESET_EXTEND"])
        assertEquals(BigInteger.ZERO, intent.spaceBudget.backupBytes)
        assertArrayEquals(original, wire)
        val returned = intent.target.originalBytes()
        returned[0] = 0
        assertNotEquals(0.toByte(), intent.target.originalBytes()[0])
        val withLf = wire + byteArrayOf(0x0a)
        assertThrows(D101RequestInvalid::class.java) { f.codec.decode(withLf, D101Fixture.hash(wire)) }
        assertNotEquals(intent.sha256, f.codec.decode(withLf, D101Fixture.hash(withLf)).sha256)
    }

    @Test
    fun `every intent pin and budget key rejects missing and null individually`() {
        val base = f.intentTree()
        val paths = D101ApprovalIntentCodec.INTENT_KEYS.map { listOf(it) } +
            listOf("oldImageDigests", "newImageDigests").flatMap { parent ->
                D101ApprovalIntentCodec.FIVE_IMAGES.map { listOf(parent, it) }
            } + D101ApprovalIntentCodec.SPACE_KEYS.map { listOf("spaceBudget", it) }
        paths.forEach { path ->
            listOf(false, true).forEach { makeNull ->
                val value = clone(base)
                val parent = if (path.size == 1) value else value[path.first()] as ObjectNode
                if (makeNull) parent.putNull(path.last()) else parent.remove(path.last())
                assertThrows(D101RequestInvalid::class.java, { decode(value) }, path.toString() + " null=" + makeNull)
            }
        }
    }

    @Test
    fun `wrong types scope window and proof labels are refused`() {
        val changes: List<(ObjectNode) -> Unit> = listOf(
            { it.put("schemaVersion", 2) }, { it.put("schemaVersion", 1.0) }, { it.put("worldId", "1") },
            { it.put("serverId", "other") }, { it.put("serverName", " 빼섭") }, { it.put("operationId", "A".repeat(32)) },
            { it.put("appSourceSha", "main") }, { it.put("initialPublicRevision", "01") },
            { it.put("initialPublicRevision", "9223372036854775808") }, { it.put("initialPublicRevision", 1) },
            { it.put("windowOpensAtUnix", true) }, { it.put("destructiveCutoffUnix", f.now - 2) },
            { it.put("recoveryDeadlineUnix", f.now + 3600) }, { it.put("approved", true) },
            { it.put("targetFingerprint", "9".repeat(64)) }, { it.put("approvalReceiptSha256", "") },
            { it.put("rootTargetBytesBase64url", it["rootTargetBytesBase64url"].textValue() + "=") },
            { (it["newImageDigests"] as ObjectNode).put("extra", "sha256:" + "c".repeat(64)) },
        )
        changes.forEachIndexed { index, change ->
            val value = f.intentTree(); change(value)
            assertThrows(D101RequestInvalid::class.java, { decode(value) }, "mutation=" + index)
        }
    }

    @Test
    fun `nested root target never defaults generation lookup or required settings`() {
        val changes: List<(ObjectNode) -> Unit> = listOf(
            { it.put("id", "other") }, { (it["target"] as ObjectNode).put("generation", "0") },
            { (it["target"] as ObjectNode).putNull("generation") }, { (it["target"] as ObjectNode).remove("generation") },
            { (it["target"] as ObjectNode).put("scenarioSeedEnabled", false) },
            { (it["target"] as ObjectNode).put("approved", true) },
            { (it["target"]["updates"] as ObjectNode).putNull("SCENARIO_LOOKUP_DIR") },
            { (it["target"]["updates"] as ObjectNode).remove("SCENARIO_LOOKUP_DIR") },
            { (it["target"]["updates"] as ObjectNode).put("RESET_MAXGENERAL", "51") },
            { (it["target"]["updates"] as ObjectNode).put("RESET_FIRST_TURN", "scheduled") },
            { (it["target"]["updates"] as ObjectNode).put("RESET_BLOCK_GENERAL_CREATE", "0") },
            { (it["target"]["updates"] as ObjectNode).remove("RESET_EXTEND") },
            { (it["target"]["updates"] as ObjectNode).putNull("RESET_EXTEND") },
            { (it["target"]["updates"] as ObjectNode).put("RESET_EXTEND", "0") },
            { (it["target"]["updates"] as ObjectNode).put("RESET_EXTEND", 1) },
            { (it["target"]["updates"] as ObjectNode).put("RESET_EXTEND", "") },
            { (it["target"]["updates"] as ObjectNode).put("RESET_EXTEND", "01") },
            { (it["target"]["updates"] as ObjectNode).put("RESET_EXTEND", "2") },
            { (it["target"]["updates"] as ObjectNode).put("INTERNAL_SERVICE_TOKEN", "synthetic-denied-field") },
            { (it["target"]["updates"] as ObjectNode).put("SERVER_NAME", "빼섭\n") },
            { (it["target"]["imageDigests"] as ObjectNode).put("game-api", "sha256:" + "9".repeat(64)) },
        )
        changes.forEachIndexed { index, change ->
            val value = f.intentTree(); changeTarget(value, change)
            assertThrows(D101RequestInvalid::class.java, { decode(value) }, "target=" + index)
        }
    }

    @Test
    fun `unsigned budget supports values above long but rejects u int overflow and decimals`() {
        val value = f.intentTree()
        (value["spaceBudget"] as ObjectNode).put("CandidateUnpackedBytes", BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE))
        assertEquals(BigInteger.ONE.shiftLeft(63), decode(value).spaceBudget.candidateUnpackedBytes)
        val changes: List<(ObjectNode) -> Unit> = listOf(
            { it.put("CandidateUnpackedBytes", D101StrictJson.UINT64_MAX) },
            { it.put("BackupBytes", D101StrictJson.UINT64_MAX.add(BigInteger.ONE)) },
            { it.put("TemporaryBytes", -1) }, { it.put("RecoveryBytes", "0") },
            { it.put("NewFileCount", 0.0) },
            { it.put("NewFileCount", D101StrictJson.UINT64_MAX); it.put("InodeReserve", 1) },
        )
        changes.forEach { change ->
            val root = f.intentTree(); change(root["spaceBudget"] as ObjectNode)
            assertThrows(D101RequestInvalid::class.java) { decode(root) }
        }
    }

    @Test
    fun `strict original json rejects duplicate alias trailing utf8 bom and oversize`() {
        val wire = f.mapper.writeValueAsString(f.intentTree())
        val samples = listOf(
            wire.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1").toByteArray(),
            wire.replace("\"BackupBytes\":0", "\"BackupBytes\":0,\"BackupBytes\":0").toByteArray(),
            wire.replace("\"schemaVersion\"", "\"SchemaVersion\"").toByteArray(),
            (wire + "{}").toByteArray(), byteArrayOf(0xc3.toByte()),
            byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + wire.toByteArray(),
            ByteArray(32 * 1024 + 1) { 0x20 }, byteArrayOf(),
        )
        samples.forEach { assertThrows(D101RequestInvalid::class.java) { f.codec.decode(it, D101Fixture.hash(it)) } }
    }

    @Test
    fun `prepare request uses received payload hash and exact intent original sha`() {
        val wire = f.prepareBody()
        val prepared = f.requestCodec.prepare(wire)
        assertEquals(D101Fixture.hash(wire), prepared.gatewayPayloadSha256)
        assertEquals(f.intent().sha256, prepared.intent.sha256)
        assertNotEquals(prepared.gatewayPayloadSha256, f.requestCodec.prepare(wire + byteArrayOf(0x0a)).gatewayPayloadSha256)
        val value = f.mapper.readTree(wire) as ObjectNode
        value.put("approvalIntentSha256", "9".repeat(64))
        assertThrows(D101RequestInvalid::class.java) { f.requestCodec.prepare(f.mapper.writeValueAsBytes(value)) }
        assertThrows(D101RequestInvalid::class.java) { f.requestCodec.prepare(ByteArray(64 * 1024 + 1) { 0x20 }) }
    }

    @Test
    fun `dispatch and terminal dtos reject unknown keys null revision coercion and trailing data`() {
        val dispatch = f.mapper.valueToTree<ObjectNode>(mapOf(
            "schemaVersion" to 1, "verifyingRevision" to "2", "approvalPlanSha256" to "a".repeat(64),
            "executionReceiptSha256" to "b".repeat(64), "rootRequestFingerprint" to "c".repeat(64),
        ))
        val terminal = f.mapper.valueToTree<ObjectNode>(mapOf(
            "schemaVersion" to 1, "verifyingRevision" to "2", "rootResultReceiptSha256" to "d".repeat(64),
        ))
        assertEquals(2L, f.requestCodec.dispatchIntent(f.mapper.writeValueAsBytes(dispatch)).verifyingRevision)
        assertEquals(2L, f.requestCodec.terminal(f.mapper.writeValueAsBytes(terminal)).verifyingRevision)
        listOf(dispatch, terminal).forEach { base ->
            fun read(raw: ByteArray) {
                if (base === dispatch) f.requestCodec.dispatchIntent(raw) else f.requestCodec.terminal(raw)
            }
            base.fieldNames().asSequence().toList().forEach { key ->
                val missing = clone(base); missing.remove(key)
                val nullValue = clone(base); nullValue.putNull(key)
                listOf(missing, nullValue).forEach { assertThrows(D101RequestInvalid::class.java) { read(f.mapper.writeValueAsBytes(it)) } }
            }
            listOf("0", "01", "+2", "9223372036854775808").forEach { revision ->
                val wrong = clone(base); wrong.put("verifyingRevision", revision)
                assertThrows(D101RequestInvalid::class.java) { read(f.mapper.writeValueAsBytes(wrong)) }
            }
            val number = clone(base); number.put("verifyingRevision", 2)
            val extra = clone(base); extra.put("maintenanceLease", "synthetic-denied")
            listOf(number, extra).forEach { assertThrows(D101RequestInvalid::class.java) { read(f.mapper.writeValueAsBytes(it)) } }
            assertThrows(D101RequestInvalid::class.java) { read(f.mapper.writeValueAsBytes(base) + "{}".toByteArray()) }
        }
    }
}
