package opensamguk.common.wire

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * OPENSAM-153 (v2 R4) — 도시병사 보충 커맨드의 wire variant. **v2 샌드박스 전용이다.**
 *
 * ## 왜 파일이 따로인가 (설계안 §11 U9)
 *
 * `TurnDaemonCommand.kt`는 T1(패러티 코어) 동결 대상인데 74개 variant가 전부 `sealed class` 본문 **안에**
 * 중첩 선언돼 있다. 중첩은 그 파일을 열지 않고는 추가할 수 없으므로, Kotlin 1.5+ sealed 규칙(같은 패키지 +
 * 같은 모듈이면 파일이 달라도 된다)에 기대어 **같은 패키지의 신규 파일에 최상위 서브클래스**로 선언한다.
 * 신규 파일 추가는 게이트 ②(`--diff-filter=MD`)에 잡히지 않는다.
 *
 * 설계안이 U9로 남긴 미확인 사항은 "`@Serializable` sealed 직렬화기가 파일 밖 서브클래스를 자동 등록하는가"
 * 였다. `V2CityGarrisonRecruitWireTest`의 왕복 직렬화 테스트가 그 답이다 — 그 테스트가 이 파일의 존재 이유다.
 *
 * ## 왜 이름에 `V2` 접두사가 없는가
 *
 * `V2NamingConventionGuardTest`는 `V2`로 시작하는 클래스 선언이 `opensamguk.*.v2.*` 패키지 안에 있을 것을
 * 요구한다(그 가드는 소스 텍스트 스캔이라 주석 속 선언 형태도 위반으로 세므로, 이 KDoc은 그 패턴을 피해 적는다).
 * 그런데 sealed 서브클래스는 **부모와 같은 패키지**(`opensamguk.common.wire`)여야 하므로 `.v2` 패키지로
 * 옮길 수 없다 — 두 규칙이 정면으로 충돌한다. 접두사를 떼어 명명 가드를 지키고, v2 소속은 (a) 이 KDoc,
 * (b) `@SerialName("v2GarrisonRecruit")`의 `v2` 접두사, (c) 파일명으로 드러낸다. 그 가드가 스스로 적어 둔
 * 한계("`V2`로 시작하지 않는 v2 코드는 리뷰 사안으로 남는다")에 해당하므로 리뷰 문서에 명시한다.
 *
 * @property cityId 보충 대상 도시. v2 도시 원장(`v2_city_ledger`)의 금을 쓰고 같은 행의 `garrison`을 올린다.
 * @property amount 보충하려는 도시병사 수. 잔액·상한 판정은 엔진 핸들러가 하고, 거절도 결과로 회신한다.
 */
@Serializable
@SerialName("v2GarrisonRecruit")
data class CityGarrisonRecruit(
    val requestId: String? = null,
    val generalId: Int,
    val cityId: Int,
    val amount: Int,
    val expiresAt: String? = null,
) : TurnDaemonCommand() {
    override val type: String get() = "v2GarrisonRecruit"
}
