package opensamguk.logic.actions.nation

/**
 * DIVERGENCE 상수 — 외교 수락 5-stat 게이트 (오픈삼국 1.0.0+ 독자기능, devsam/core에 없음).
 *
 * **NO PHP GOLDEN ORACLE.** devsam/core(grand truth)는 3-stat이라 정치(politics) 컬럼이 없으므로 이 게이트는
 * 캡처된 PHP 픽스처로 재생할 수 없다. [WorldEnv.fiveStatLogic][opensamguk.logic.domain.WorldEnv.fiveStatLogic]
 * 플래그가 ON일 때만 발동하며, OFF(기본)에서는 게이트가 inert → 패러티 경로는 byte-identical.
 *
 * 외교 수락 3종(종전수락 / 불가침수락 / 불가침파기수락)에서, 조약을 체결하는 수락측 장수(`draft.general`,
 * 외교를 성사시키는 외교관)의 정치력이 이 기준선 미만이면 수락이 **실패**한다 — 한글 실패 로그를 남기고
 * 외교 상태 변경(cascadeDiplomacy)을 건너뛴다.
 */
object DiplomacyDivergence {
    /** 외교 수락 성공에 요구되는 수락측 장수 정치력 기준선(divergence, RNG 무관 deterministic). */
    const val POLITICS_DIPLOMACY_BAR: Int = 30

    /** 정치력 부족 수락 실패 로그(divergence 텍스트 — 패러티 오라클 없음). */
    const val POLITICS_FAIL_LOG: String = "정치력이 부족하여 외교를 성사시키지 못했습니다."
}
