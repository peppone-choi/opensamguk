package opensamguk.logic.input

/**
 * 휘하 기록(`log_entry.event_kind`)의 종류 — 「지난 순」 화면이 순마다 묶고 종류로 가르는 열쇠다.
 *
 * 쓰는 쪽(엔진)과 읽는 쪽(game-api `/api/hwiha/last-turns`)이 같은 문자열을 쓰도록 여기 한 곳에 둔다.
 * 구체 식별자는 `meta.refs` 에 싣는다([REFS_META_KEY]).
 *
 * ### 누가 무엇을 보는가(#343)
 *
 * - **개인 기록**: `scope=GENERAL` 이고 `general_id` 가 본인인 줄만. 쓰는 쪽은 그 장수가 알아도 되는 것만
 *   그 장수 앞으로 쓴다 — 조우 상대의 병력·계획, 남의 발령은 쓰지 않는다.
 * - **세력 요약**: [NATION_SUMMARY_KINDS](본인 세력 앞 `scope=NATION`)와 [WORLD_SUMMARY_KINDS]
 *   (`scope=SYSTEM`, 모두 공개)만. 세력 내부 정보(월세입 등)는 기록은 하되 요약에 싣지 않는다.
 */
object HwihaRecordKind {
    const val REFS_META_KEY = "refs"

    // 장수 개인
    const val MARCH_ASSIGNMENT = "march.assignment"
    const val MARCH_CORPS = "march.corps"
    const val MARCH_DIRECT = "march.direct"
    const val DEPLOY_STARTED = "deploy.started"
    const val ENCOUNTER_PENDING = "encounter.pending"
    const val ENCOUNTER_DISBANDED = "encounter.disbanded"
    const val DISPATCH_ISSUED = "court.dispatchIssued"
    const val DISPATCH_RECEIVED = "court.dispatchReceived"
    const val DISPATCH_ACCEPTED = "court.dispatchAccepted"
    const val DISPATCH_REFUSED = "court.dispatchRefused"
    const val DISPATCH_CANCELLED = "court.dispatchCancelled"
    const val ENLISTED = "enlist.joined"
    const val RETAINER_JOINED = "enlist.retainerJoined"
    const val INPUT_REJECTED = "input.rejected"
    const val FIELD_APPLIED = "field.applied"
    const val RENOWN_EVENT = "renown.event"
    const val YUEDAN_ASSESSED = "yuedan.assessed"
    const val DEPARTURE_JUDGED = "retinue.departureJudged"
    /** 코스트 상한 초과로 이탈 판정을 받은 인물 본인 앞. 이탈은 배신이 아니다 — 명망 사건이 아니다(2026-09-23). */
    const val RETINUE_DEPARTED = "retinue.departed"

    // 세력
    const val INCOME_MONTHLY = "income.monthly"
    const val COUNTY_CAPTURED = "county.captured"
    const val COUNTY_LOST = "county.lost"

    // 세계
    const val YUEDAN_ANNOUNCED = "yuedan.announced"

    /** 세력 요약에 싣는 세력 앞 기록 — 縣 점령·상실은 지도에 드러나는 공개 사건이다. */
    val NATION_SUMMARY_KINDS: Set<String> = setOf(COUNTY_CAPTURED, COUNTY_LOST)

    /** 세력 요약에 싣는 세계 공개 기록 — 월단평 발표(§2.8). */
    val WORLD_SUMMARY_KINDS: Set<String> = setOf(YUEDAN_ANNOUNCED)

    /** 순 표기(1 상순 · 2 중순 · 3 하순). */
    fun phaseLabel(phase: Int): String = when (phase) {
        1 -> "상순"
        2 -> "중순"
        3 -> "하순"
        else -> throw IllegalArgumentException("phase must be 1..3, was $phase")
    }
}
