package opensamguk.engine.hwiha

import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.logic.input.HwihaMarchReactions
import opensamguk.logic.world.LandMarchEntry
import opensamguk.logic.world.StrategicNodeRef

/**
 * 행군 진입 판정에서 반응 기록(설치 계책·요격·회피, 재설계 spec §5.1 4단계)이 주는 위험. 적 군단이 그 省에 있어서
 * 생기는 조우는 [HwihaMilitaryPresenceProvider] 가 따로 본다 — 여기는 그 밖의 반응만 판정한다.
 *
 * 운영 배선은 [HwihaMarchReactionInterpreter]가 맡는다. 권위가 없거나 틀이 깨졌으면 판정 불가(UNAVAILABLE),
 * 비어 있으면 위험 없음(CLEAR)이다. [NON_BLOCKING]은 이전 호출자를 위한 명시적 호환 정책이다.
 */
fun interface HwihaMarchReactionPolicy {
    fun entryHazard(world: InMemoryTurnWorld, actorId: Int, node: StrategicNodeRef.LandProvince): LandMarchEntry

    /** Hostile corps that have a validated retreat and yield this province on actual entry. */
    fun evadingOrderIds(world: InMemoryTurnWorld, actorId: Int, node: StrategicNodeRef.LandProvince): Set<String> = emptySet()

    /** Called only for provinces the mover actually reached, never for an edge partly paid this turn. */
    fun onEntered(world: InMemoryTurnWorld, recorder: ChangeRecorder, actorId: Int, node: StrategicNodeRef.LandProvince) = Unit

    /** Scheme contact stops a march but has no fabricated army to seal into grid combat. */
    fun schemeContact(world: InMemoryTurnWorld, actorId: Int, node: StrategicNodeRef.LandProvince): Boolean = false

    /** A ranged interceptor can become the defender once the mover reaches this province. */
    fun interceptsAt(world: InMemoryTurnWorld, actorId: Int, node: StrategicNodeRef.LandProvince): Boolean = false

    /** Direct travel also exposes a lone general to interception; other marches retain their existing policy. */
    fun directEntryHazard(world: InMemoryTurnWorld, actorId: Int, node: StrategicNodeRef.LandProvince): LandMarchEntry =
        entryHazard(world, actorId, node)
    fun directInterceptsAt(world: InMemoryTurnWorld, actorId: Int, node: StrategicNodeRef.LandProvince): Boolean =
        interceptsAt(world, actorId, node)
    fun onDirectEntered(world: InMemoryTurnWorld, recorder: ChangeRecorder, actorId: Int,
        node: StrategicNodeRef.LandProvince) = onEntered(world, recorder, actorId, node)

    companion object {
        val NON_BLOCKING = HwihaMarchReactionPolicy { world, _, _ ->
            when (HwihaMarchReactions.presence(world.getState().meta)) {
                HwihaMarchReactions.Presence.MISSING, HwihaMarchReactions.Presence.MALFORMED -> LandMarchEntry.UNAVAILABLE
                HwihaMarchReactions.Presence.EMPTY, HwihaMarchReactions.Presence.PENDING -> LandMarchEntry.CLEAR
            }
        }
    }
}
