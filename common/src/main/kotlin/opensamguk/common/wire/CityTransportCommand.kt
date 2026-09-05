package opensamguk.common.wire

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * OPENSAM-154 (v2 R5) — 도시 자원 수송 커맨드의 wire variant. **v2 샌드박스 전용이다.**
 *
 * 파일이 따로인 이유와 이름에 `V2` 접두사가 없는 이유는 [CityGarrisonRecruit](R4)와 같다 —
 * `TurnDaemonCommand.kt`가 T1 동결이라 중첩 선언을 추가할 수 없고, sealed 서브클래스는 부모와 같은
 * 패키지여야 해서 `.v2` 패키지로 옮길 수 없다. v2 소속은 이 KDoc · `@SerialName`의 `v2` 접두사 ·
 * 파일명으로 드러낸다(R4 리뷰 §7이 같은 사안을 기록했다).
 *
 * @property fromCityId 출발 도시. 명령을 낸 장수가 **현재 있는 도시**여야 한다.
 * @property toCityId 도착 도시. V3는 검증된 strategic LAND 1구간, 기존 맵은 CityConst 인접 1홉만 허용한다.
 * @property gold 수송할 금 · @property rice 병량 · @property garrison 도시병사. 셋 다 0 이상이며 합이 0이면 거절.
 */
@Serializable
@SerialName("v2CityTransport")
data class CityTransport(
    val requestId: String? = null,
    val generalId: Int,
    val fromCityId: Int,
    val toCityId: Int,
    val gold: Long = 0,
    val rice: Long = 0,
    val garrison: Int = 0,
    val routeRevision: Long? = null,
    val expiresAt: String? = null,
    val topologyRevision: String? = null,
    val routePathHash: String? = null,
) : TurnDaemonCommand() {
    override val type: String get() = "v2CityTransport"
}
