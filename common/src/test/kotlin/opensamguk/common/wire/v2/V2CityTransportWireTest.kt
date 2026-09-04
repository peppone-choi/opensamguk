package opensamguk.common.wire.v2

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import opensamguk.common.wire.CityTransport
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.common.wire.WireJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * OPENSAM-154 (v2 R5) — 두 번째 파일-밖 sealed 서브클래스의 wire 왕복.
 *
 * R4가 U9를 닫았으므로 여기서는 "그 결론이 두 번째 variant 에도 성립한다"만 고정한다. 이 테스트가
 * 빨개지면 인테이크→디스패처 라우팅이 통째로 끊긴다(판별자 문자열이 곧 라우팅 키다).
 */
class V2CityTransportWireTest {

    @Test
    fun `V3 topology pins survive the daemon wire without reusing numeric route revision`() {
        val decoded = WireJson.decodeFromString<TurnDaemonCommand>(
            """{"type":"v2CityTransport","generalId":7,"fromCityId":5,"toCityId":6,"gold":1,"routeRevision":9,"topologyRevision":"han-v3:abc","routePathHash":"path:123"}""",
        )
        val encoded = WireJson.parseToJsonElement(WireJson.encodeToString(TurnDaemonCommand.serializer(), decoded)).jsonObject
        assertEquals("han-v3:abc", encoded["topologyRevision"]?.jsonPrimitive?.content)
        assertEquals("path:123", encoded["routePathHash"]?.jsonPrimitive?.content)
        assertEquals("9", encoded["routeRevision"]?.jsonPrimitive?.content)
    }

    private val sample = CityTransport(
        requestId = "req-1",
        generalId = 7,
        fromCityId = 5,
        toCityId = 6,
        gold = 50_000,
        rice = 1_234,
        garrison = 300,
    )

    @Test
    fun `the transport subclass round-trips through the shared wire codec`() {
        val encoded = WireJson.encodeToString(TurnDaemonCommand.serializer(), sample)
        val decoded = WireJson.decodeFromString(TurnDaemonCommand.serializer(), encoded)

        assertEquals(sample, decoded)
        assertTrue(decoded is CityTransport)
    }

    @Test
    fun `the discriminator is the declared v2 serial name`() {
        val element = WireJson.parseToJsonElement(
            WireJson.encodeToString(TurnDaemonCommand.serializer(), sample),
        ).jsonObject

        assertEquals("v2CityTransport", element["type"]?.jsonPrimitive?.content)
        assertEquals("v2CityTransport", sample.type)
    }
}
