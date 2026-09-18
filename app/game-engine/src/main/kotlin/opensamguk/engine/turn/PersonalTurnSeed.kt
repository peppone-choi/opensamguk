package opensamguk.engine.turn

import opensamguk.common.rng.serializeSeed
import opensamguk.logic.input.RuleProfile

/**
 * 개인 턴 시드 파생 — 개인 턴 결정론 계약(2026-09-18) §2.2·§4, 이슈 #787.
 *
 * - SAMMO: `(hiddenSeed, domain, year, month, generalId, …scope)`. PHP 동결 기준선(ADR-LITE-042)의
 *   골든 입력이다. 성분을 더하거나 빼거나 순서를 바꾸면 기준선이 깨진다 — **절대 고치지 마라.**
 * - HWIHA: `(hiddenSeed, domain, worldId, year, month, phase, generalId, …scope)`. 월 3순이 같은
 *   시드에서 출발하는 것과 모든 월드가 같은 hiddenSeed 를 쓰는 것을 시드가 직접 가른다.
 *   선례: V59 전장 진입 시드(`BattlefieldTurnHandler`).
 *
 * `year·month·phase` 는 호출부가 넘긴다. 현재는 세계 시계다(계약 미결 Q2).
 */
internal fun personalTurnSeed(
    profile: RuleProfile,
    hiddenSeed: String,
    domain: String,
    worldId: Int,
    year: Int,
    month: Int,
    phase: Int,
    generalId: Int,
    vararg scope: Any,
): String = when (profile) {
    RuleProfile.SAMMO -> serializeSeed(hiddenSeed, domain, year, month, generalId, *scope)
    RuleProfile.HWIHA -> serializeSeed(hiddenSeed, domain, worldId, year, month, phase, generalId, *scope)
}

/** 월드에서 profile·worldId·순을 읽어 [personalTurnSeed] 를 부른다. */
internal fun InMemoryTurnWorld.personalTurnSeed(
    hiddenSeed: String,
    domain: String,
    year: Int,
    month: Int,
    generalId: Int,
    vararg scope: Any,
): String = personalTurnSeed(
    ruleProfile, hiddenSeed, domain, worldId.value, year, month, getState().currentPhase, generalId, *scope,
)
