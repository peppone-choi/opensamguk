package opensamguk.gameapi.reserve.v2

import kotlinx.serialization.SerialName
import opensamguk.common.wire.CityGarrisonRecruit
import opensamguk.common.wire.CityTransport
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.common.wire.TurnDaemonCommandEnvelope
import opensamguk.common.wire.decodeCommandEnvelope
import opensamguk.common.wire.encodeCommandPayload
import opensamguk.gameapi.reserve.CommandWireMapper
import opensamguk.logic.v2.command.V2CommandRegistry
import opensamguk.logic.v2.command.V2CityTransportArgs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * OPENSAM-153 (v2 R4) — `v2GarrisonRecruit`의 퍼블리셔 측 매핑 테스트.
 *
 * **왜 `CommandWireMapperTest`에 붙이지 않는가**: 그 파일은 v1 테스트라 격리 게이트 ②(T1 동결,
 * `--diff-filter=MD`)의 수정 금지 대상이다. 게이트가 제외하는 것은 테스트 루트의 `v2` 디렉터리
 * 세그먼트뿐이므로(OPENSAM-190), v2 케이스는 이렇게 별도 파일로 둔다. `roundTrip` 헬퍼는 원본이
 * private 이라 같은 모양으로 다시 적었다 — 검증 대상(데몬이 실제로 읽는 payload 바이트)은 동일하다.
 */
class V2CommandWireMapperTest {

    @Test
    fun `every registered v2 wire command has exactly one canonical schema`() {
        val schemaAliases = V2CommandRegistry.schemas.flatMap { it.legacyAliases }.toSet()
        val v2WireTypes = TurnDaemonCommand::class.sealedSubclasses.mapNotNull { type ->
            type.annotations.filterIsInstance<SerialName>().singleOrNull()?.value?.takeIf { it.startsWith("v2") }
        }.toSet()

        assertEquals(schemaAliases, CommandWireMapper.v2IntakeCodes)
        assertEquals(v2WireTypes, schemaAliases)
        assertEquals(V2CommandRegistry.schemas.size, schemaAliases.size)
    }

    private fun roundTrip(command: TurnDaemonCommand): TurnDaemonCommand {
        val payload = encodeCommandPayload(
            TurnDaemonCommandEnvelope(requestId = "req-1", sentAt = "0200-01-01T00:00:00Z", command = command),
        )
        return decodeCommandEnvelope(payload).command
    }

    @Test
    fun `v2GarrisonRecruit maps cityId amount and threads the resolved generalId`() {
        val cmd = CommandWireMapper.toCommand(
            code = "v2GarrisonRecruit",
            generalId = 42,
            requestId = "req-v2",
            argJson = """{"cityId":5,"amount":100}""",
        )
        assertTrue(CommandWireMapper.isIntakeCommand("v2GarrisonRecruit"))
        val recruit = roundTrip(cmd!!) as CityGarrisonRecruit
        assertEquals("req-v2", recruit.requestId)
        assertEquals(42, recruit.generalId) // resolved id, NOT from the body
        assertEquals(5, recruit.cityId)
        assertEquals(100, recruit.amount)
    }

    @Test
    fun `v2GarrisonRecruit missing args default to zero`() {
        val cmd = CommandWireMapper.toCommand(
            code = "v2GarrisonRecruit",
            generalId = 42,
            requestId = "req-v2-empty",
            argJson = null,
        )
        val recruit = roundTrip(cmd!!) as CityGarrisonRecruit
        assertEquals(0, recruit.cityId)
        assertEquals(0, recruit.amount)
    }

    @Test
    fun `v2CityTransport maps the three resource amounts and both city ids`() {
        val cmd = CommandWireMapper.toCommand(
            code = "v2CityTransport",
            generalId = 42,
            requestId = "req-v2-tr",
            argJson = """{"fromCityId":5,"toCityId":6,"gold":1000,"rice":500,"garrison":300,"routeRevision":9,"topologyRevision":"v3:abc","routePathHash":"path:123"}""",
            expiresAt = "0200-01-01T01:00:00Z",
        )
        assertTrue(CommandWireMapper.isIntakeCommand("v2CityTransport"))
        val tr = roundTrip(cmd!!) as CityTransport
        assertEquals(42, tr.generalId) // resolved id, NOT from the body
        assertEquals(5, tr.fromCityId)
        assertEquals(6, tr.toCityId)
        assertEquals(1000L, tr.gold)
        assertEquals(500L, tr.rice)
        assertEquals(300, tr.garrison)
        assertEquals(9, tr.routeRevision)
        assertEquals("v3:abc", tr.topologyRevision)
        assertEquals("path:123", tr.routePathHash)
        assertEquals("0200-01-01T01:00:00Z", tr.expiresAt)
    }

    @Test
    fun `v2CityTransport missing args default to zero`() {
        val tr = roundTrip(
            CommandWireMapper.toCommand("v2CityTransport", generalId = 42, requestId = "r", argJson = null)!!,
        ) as CityTransport
        assertEquals(0, tr.fromCityId)
        assertEquals(0, tr.toCityId)
        assertEquals(0L, tr.gold)
        assertEquals(0L, tr.rice)
        assertEquals(0, tr.garrison)
    }

    @Test
    fun `canonical mapper uses validated typed args without reparsing json`() {
        val command = CommandWireMapper.toV2Command(
            schema = V2CommandRegistry.cityTransportSchema,
            args = V2CityTransportArgs(5, 6, 1000, 500, 300, 9, "v3:abc", "path:123"),
            generalId = 42,
            requestId = "typed-1",
            expiresAt = "0200-01-01T01:00:00Z",
        )

        val transport = roundTrip(command) as CityTransport
        assertEquals(5, transport.fromCityId)
        assertEquals(6, transport.toCityId)
        assertEquals(1000, transport.gold)
        assertEquals(9, transport.routeRevision)
        assertEquals("v3:abc", transport.topologyRevision)
        assertEquals("path:123", transport.routePathHash)
    }
}
