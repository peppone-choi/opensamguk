package opensamguk.common.wire

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * OPENSAM-45 (1-a) — "네 명령 결과가 준비됐다"는 깨움 신호.
 *
 * **결과 본문도, 성공/실패 여부도 싣지 않는다.** 정본은 `GET /api/command/result/{requestId}`이며
 * 이 신호는 "이제 읽어도 된다"만 전한다. 이유는 두 가지다.
 *
 * 1. **정보 노출.** `/sse/turn`은 접속한 **모든** 브라우저에 같은 payload를 뿌리는 월드 전역
 *    채널이다(수신자별 필터가 없다). 여기에 deny 사유("금이 부족합니다.")나 결과 종류를 실으면
 *    남의 명령 결과가 전부 새어 나간다. `requestId`는 제출자만 아는 불투명 값이라 남이 받아도
 *    쓸 수 없다.
 * 2. **판정 이원화 금지.** 두 경로가 각자 판정하면 조용히 어긋난다(OPENSAM-13/135의 "202=성공"
 *    위조와 같은 종류의 사고).
 *
 * 별도 파일인 이유: `RealtimeEvent.kt`는 게이트 ② 동결 대상이라 sealed 계층을 파일 밖에서 넓힌다.
 */
@Serializable
@SerialName("commandSettled")
data class CommandSettledEvent(
    val at: String,
    val requestId: String,
) : RealtimeEvent() {
    override val type: String get() = "commandSettled"
}
