package opensamguk.common.wire.v2

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import opensamguk.common.wire.CityGarrisonRecruit
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.common.wire.WireJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * OPENSAM-153 (v2 R4) — 설계안 §11 **U9의 컴파일·런타임 확인**.
 *
 * U9가 물은 것: "`@Serializable` sealed 서브클래스를 원 파일 밖 신규 파일에 두어도 wire 직렬화가 성립하는가."
 * `TurnDaemonCommand.kt`(T1, 수정 불가)는 74개 variant를 전부 중첩 선언하고 파일 밖 선언 선례가 0건이라,
 * 성립하지 않으면 R4·R5는 v2 전용 wire sealed 타입 + 어댑터 분기(대안 a)로 갈린다.
 *
 * 이 테스트가 그린이면 대안 (a)는 불필요하다 — 즉 이 파일은 U9의 답 그 자체다.
 */
class V2CityGarrisonRecruitWireTest {

    private val sample = CityGarrisonRecruit(
        requestId = "req-1",
        generalId = 7,
        cityId = 42,
        amount = 3000,
    )

    @Test
    fun `the out-of-file sealed subclass round-trips through the shared wire codec`() {
        val encoded = WireJson.encodeToString(TurnDaemonCommand.serializer(), sample)
        val decoded = WireJson.decodeFromString(TurnDaemonCommand.serializer(), encoded)

        assertEquals(sample, decoded, "파일 밖 서브클래스가 sealed 직렬화기에 등록되지 않았다 (U9 = 대안 a로 전환)")
        assertTrue(decoded is CityGarrisonRecruit)
    }

    /** 판별자가 `@SerialName` 그대로 실려야 인테이크→디스패처 라우팅이 성립한다. */
    @Test
    fun `the discriminator is the declared v2 serial name`() {
        val element = WireJson.parseToJsonElement(
            WireJson.encodeToString(TurnDaemonCommand.serializer(), sample),
        ).jsonObject

        assertEquals("v2GarrisonRecruit", element["type"]?.jsonPrimitive?.content)
        assertEquals("v2GarrisonRecruit", sample.type)
    }

    /** v1 variant 와 같은 스트림을 공유하므로, 서로의 디코딩을 깨지 않는지도 함께 본다. */
    @Test
    fun `a v1 variant still round-trips alongside the new subclass`() {
        val v1 = TurnDaemonCommand.TroopJoin(requestId = "req-2", generalId = 7, troopId = 3)
        val encoded = WireJson.encodeToString(TurnDaemonCommand.serializer(), v1)

        assertEquals(v1, WireJson.decodeFromString(TurnDaemonCommand.serializer(), encoded))
    }
}
