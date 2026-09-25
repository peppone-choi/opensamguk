package opensamguk.engine.hwiha

import opensamguk.logic.domestic.CorpsPolicyAssignments

import opensamguk.logic.domestic.CorpsPolicy

import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.logic.input.*

/**
 * 요격·회피 군단 방침(현행)을 월드 `hwihaMarchReactions` 의 두 목록으로 다시 쓴다. 출전 기록이 사라진 군단은 빠진다.
 *
 * 저장: 월드 상태 meta 는 flush 가 정해진 열만 쓰므로 이 키는 game_env KV(`game_kv` table=game_env)로 영속하고
 * 메모리에서는 [InMemoryTurnWorld.setGameEnvValue] 로 반영한다 — 부팅 로더가 world_state.meta 위에 game_env 를 덮어 읽는다.
 * 키가 없거나(기존 월드) 오염된 목록은 빈 목록으로 보충·덮어쓰지 않는다(초기 반응 상태 결정 2026-09-21).
 */
class HwihaReactionInventory(private val world: InMemoryTurnWorld, private val recorder: ChangeRecorder) {
    enum class Result { UNCHANGED, WRITTEN, MISSING, INVALID }

    fun rebuild(): Result {
        if (world.ruleProfile != RuleProfile.HWIHA) return Result.MISSING
        val current = try { MarchReactions.read(world.getState().meta) } catch (_: IllegalArgumentException) {
            return Result.INVALID
        } ?: return Result.MISSING
        val people = world.listGenerals().sortedBy { it.id }
        val deployed = try {
            people.flatMap { person -> DeploymentState.read(person.meta)?.corps.orEmpty() }
        } catch (_: IllegalArgumentException) { return Result.INVALID }
        val intercept = mutableListOf<ReactionOrder>()
        val evade = mutableListOf<ReactionOrder>()
        for (owner in people) {
            val policies = try { CorpsPolicyAssignments.read(owner.meta) } catch (_: IllegalArgumentException) { return Result.INVALID } ?: continue
            for (entry in policies.entries) {
                val active = entry.slot.active ?: continue
                val corps = deployed.singleOrNull { it.orderId == entry.orderId && it.ownerGeneralId == owner.id &&
                    it.commanderGeneralId == entry.commanderGeneralId } ?: continue
                val order = ReactionOrder(corps.orderId, corps.ownerGeneralId, corps.commanderGeneralId, corps.nationId, active.since)
                when (CorpsPolicy.valueOf(active.policy)) {
                    CorpsPolicy.INTERCEPT -> intercept += order
                    CorpsPolicy.EVADE -> evade += order
                    else -> Unit
                }
            }
        }
        val next = MarchReactions.of(intercept, evade, current.installedSchemes)
        if (next == current) return Result.UNCHANGED
        val value = next.toMetaValue()
        world.setGameEnvValue(MarchReactions.META_KEY, value)
        recorder.recordKv("game_env", "game_env", MarchReactions.META_KEY, value)
        return Result.WRITTEN
    }
}
