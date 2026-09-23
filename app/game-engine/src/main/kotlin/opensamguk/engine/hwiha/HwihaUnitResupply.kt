package opensamguk.engine.hwiha

import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.logic.input.HwihaDeploymentState
import opensamguk.logic.input.RuleProfile
import opensamguk.logic.war.hwiha.HwihaS3Provisional

/**
 * 월 경계 부곡 군량 보충(재설계 spec §5.2 1단계의 「군량」과 §9.2 「부대 유지비는 카드가 있는 곳의 망에서」의 임시 구현).
 * 부곡 휴대 군량을 병력 × [HwihaS3Provisional.UNIT_RESUPPLY_TARGET_MONTHS] 개월까지 창고망 곡으로 채운다.
 * 부곡 위치는 출전 중이면 지휘 장수, 아니면 주인 장수의 **실제 위치**(위치 정본 `positionOf`)다. 기준 城 id 는 城 없는 省에
 * 들어가도 이전 값으로 남으므로 쓰지 않는다. 위치가 城 없는 省이거나 주인 세력 縣이 아니면(적지 포위군) 채우지 않는다.
 * 도장([STAMP_KEY])으로 한 달에 한 번만 돈다.
 */
class HwihaUnitResupply(private val world: InMemoryTurnWorld, private val recorder: ChangeRecorder) {
    fun resupply(year: Int, month: Int): Int? {
        if (world.ruleProfile != RuleProfile.HWIHA) return null
        val stamp = "%04d-%02d".format(year, month)
        if (world.getState().meta[STAMP_KEY] == stamp) return 0
        val network = HwihaWarehouseNetwork(world, recorder)
        val commanderOf = world.listGenerals().flatMap { owner ->
            (try { HwihaDeploymentState.read(owner.meta) } catch (_: IllegalArgumentException) { null })?.corps.orEmpty()
        }.flatMap { corps -> corps.bugokIds.map { it to corps.commanderGeneralId } }.toMap()
        var filled = 0
        for (unit in world.listBugoks().sortedBy { it.id }) {
            val owner = world.getGeneralById(unit.masterGeneralId) ?: continue
            val target = unit.troops.toLong() * HwihaS3Provisional.UNIT_RESUPPLY_TARGET_MONTHS
            if (unit.provisions >= target) continue
            val holder = world.getGeneralById(commanderOf[unit.id] ?: owner.id) ?: continue
            val here = HwihaCorpsRations.cityAt(world, holder.id) ?: continue
            val counties = network.countiesFor(owner.nationId, here)
            if (counties.isEmpty()) continue
            val available = network.grainIn(counties) / HwihaS3Provisional.GRAIN_PER_PROVISION
            val add = minOf(target - unit.provisions, available)
            if (add <= 0) continue
            check(network.payGrain(owner.nationId, counties, add * HwihaS3Provisional.GRAIN_PER_PROVISION))
            world.updateBugok(unit.copy(provisions = Math.toIntExact(unit.provisions + add)))
            filled++
        }
        world.setGameEnvValue(STAMP_KEY, stamp)
        recorder.recordKv("game_env", "game_env", STAMP_KEY, stamp)
        return filled
    }

    companion object {
        const val STAMP_KEY = "hwihaUnitResupplyMonth"
    }
}
