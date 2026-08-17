package opensamguk.common.wire

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * OPENSAM-45 (1-a) — "명령 결과가 커밋됐다, 기다리는 게 있으면 지금 읽어라"는 깨움 신호.
 *
 * **결과 본문도, 성공/실패도, 어느 명령인지도 싣지 않는다.** 필드는 [at] 하나뿐이다. 정본은 언제나
 * `GET /api/command/result/{requestId}`이고 이 신호는 "이제 읽어도 된다"만 전한다. 이유는 세 가지다.
 *
 * 1. **정보 노출.** `/sse/turn`은 접속한 **모든** 브라우저에 같은 payload를 뿌리는 월드 전역
 *    채널이다(수신자별 필터가 없다). deny 사유("금이 부족합니다.")나 결과 종류를 실으면 남의 명령
 *    결과가 전부 샌다.
 * 2. **`requestId`도 못 싣는다.** 불투명 값이라 안전해 보이지만, 정본 엔드포인트
 *    `CommandController.commandResult(requestId)`에 **요청자 소유권 검사가 없다** — 값만 알면
 *    누구나 남의 결과를 읽는다. 지금까지 그 값은 제출자만 알았고, 전역 채널에 실으면 그 전제가
 *    깨진다. (엔드포인트에 소유권 검사를 더하는 일은 별개 티켓 OPENSAM-197.)
 * 3. **판정 이원화 금지.** 두 경로가 각자 판정하면 조용히 어긋난다(OPENSAM-13/135의 "202=성공"
 *    위조와 같은 종류의 사고).
 *
 * 식별자가 없으므로 신호는 "누군가의 결과가 커밋됐다"는 뜻이고, 기다리던 브라우저는 **자기**
 * `requestId`를 한 번 되읽어 확인한다. 남의 명령이 만든 신호에 깨어나 한 번 헛읽는 비용은
 * 폴링 19번보다 싸다.
 *
 * 별도 파일인 이유: `RealtimeEvent.kt`는 게이트 ② 동결 대상이라 sealed 계층을 파일 밖에서 넓힌다.
 */
@Serializable
@SerialName("commandSettled")
data class CommandSettledEvent(
    val at: String,
) : RealtimeEvent() {
    override val type: String get() = "commandSettled"
}
