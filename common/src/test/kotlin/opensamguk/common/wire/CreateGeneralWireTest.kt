package opensamguk.common.wire

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CreateGeneralWireTest {
    private val requestId = "92d9244b-6eb5-4f89-971d-d1b1247e0ff6"

    @Test fun `밖으로 옮긴 생성 명령은 기존 판별자와 필드를 그대로 왕복한다`() {
        val command = CreateGeneral(7, 1, requestId, "CUSTOM", custom = CreationCustomChoice(
            "새 장수", 10, 60, 60, 60, 60, 60, "WANGDO", "DISCIPLINE", role = "RETAINER"))
        val encoded = WireJson.encodeToString(TurnDaemonCommand.serializer(), command)
        val tree = WireJson.parseToJsonElement(encoded).jsonObject
        assertEquals("createGeneral", tree.getValue("type").jsonPrimitive.content)
        assertEquals("7", tree.getValue("accountId").jsonPrimitive.content)
        assertEquals("1", tree.getValue("worldId").jsonPrimitive.content)
        assertEquals(requestId, tree.getValue("clientRequestId").jsonPrimitive.content)
        assertEquals("CUSTOM", tree.getValue("choiceKind").jsonPrimitive.content)
        assertEquals("RETAINER", tree.getValue("custom").jsonObject.getValue("role").jsonPrimitive.content)
        assertEquals(command, WireJson.decodeFromString(TurnDaemonCommand.serializer(), encoded))
        assertEquals("createGeneral", command.type)
        val envelope = TurnDaemonCommandEnvelope("internal-1", "2026-10-05T00:00:00Z", command)
        assertEquals(envelope, decodeCommandEnvelope(encodeCommandPayload(envelope)))
    }

    @Test fun `역사 선택의 생략 가능한 필드와 기존 만들기 명령도 왕복한다`() {
        val historical = CreateGeneral(8, 1, requestId, "HISTORICAL", historicalGeneralId = 1001)
        val encoded = WireJson.encodeToString(TurnDaemonCommand.serializer(), historical)
        val decoded = assertIs<CreateGeneral>(WireJson.decodeFromString(TurnDaemonCommand.serializer(), encoded))
        assertEquals(historical, decoded)
        assertEquals(null, decoded.custom)
        assertEquals(1001, decoded.historicalGeneralId)
        val legacy = TurnDaemonCommand.MakeGeneral(userId = 7, name = "기존 장수",
            leadership = 60, strength = 60, intel = 60)
        assertEquals(legacy, WireJson.decodeFromString(TurnDaemonCommand.serializer(),
            WireJson.encodeToString(TurnDaemonCommand.serializer(), legacy)))
    }
}
