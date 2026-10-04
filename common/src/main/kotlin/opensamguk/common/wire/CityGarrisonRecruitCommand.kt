package opensamguk.common.wire

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 도시병사 보충 커맨드. 샌드박스 도시 원장을 쓰는 즉시 인테이크 명령이다.
 * sealed 직렬화기의 파일 밖 서브클래스 등록은 왕복 wire 테스트로 확인한다.
 *
 * @property cityId 보충 대상 도시.
 * @property amount 보충하려는 도시병사 수. 엔진 핸들러가 잔액·상한과 거절 사유를 판정한다.
 */
@Serializable
@SerialName("cityGarrisonRecruit")
data class CityGarrisonRecruit(
    val requestId: String? = null,
    val generalId: Int,
    val cityId: Int,
    val amount: Int,
    val expiresAt: String? = null,
) : TurnDaemonCommand() {
    override val type: String get() = "cityGarrisonRecruit"
}
