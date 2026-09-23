package opensamguk.engine.hwiha

import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.logic.input.HwihaMarchReactions
import opensamguk.logic.world.LandMarchEntry
import opensamguk.logic.world.StrategicNodeRef

/**
 * 행군 진입 판정에서 반응 기록(설치 계책·요격·회피, 재설계 spec §5.1 4단계)이 주는 위험. 적 군단이 그 省에 있어서
 * 생기는 조우는 [HwihaMilitaryPresenceProvider] 가 따로 본다 — 여기는 그 밖의 반응만 판정한다.
 *
 * 반응 기록의 해석(요격 범위·시야·회피 철수)은 내정 입력 스트림(`hwihaCorpsPolicies`)과 시야 스트림(`HwihaVision…`)의
 * 몫이고 병합 때 이 경계에 연결한다. 이 브랜치의 기본값 [NON_BLOCKING] 은 임시 규칙이다
 * (`hwiha-s3-provisional-v1.json` reactions):
 *
 * - 반응 권위가 없거나 틀이 깨졌으면 판정 불가(UNAVAILABLE) — 없는 권위를 통행 가능으로 추정하지 않는다(기존 계약).
 * - 비어 있으면 위험 없음(CLEAR).
 * - 기록이 쌓였어도(PENDING) 해석기가 연결되기 전에는 **행군을 막지 않는다**(CLEAR). 그러지 않으면 누군가 요격·회피를
 *   걸자마자 세계의 모든 행군이 멈춘다. 요격·회피 효과는 이 기본값에서 적용되지 않는다.
 */
fun interface HwihaMarchReactionPolicy {
    fun entryHazard(world: InMemoryTurnWorld, actorId: Int, node: StrategicNodeRef.LandProvince): LandMarchEntry

    companion object {
        val NON_BLOCKING = HwihaMarchReactionPolicy { world, _, _ ->
            when (HwihaMarchReactions.presence(world.getState().meta)) {
                HwihaMarchReactions.Presence.MISSING, HwihaMarchReactions.Presence.MALFORMED -> LandMarchEntry.UNAVAILABLE
                HwihaMarchReactions.Presence.EMPTY, HwihaMarchReactions.Presence.PENDING -> LandMarchEntry.CLEAR
            }
        }
    }
}
