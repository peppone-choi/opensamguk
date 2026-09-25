package opensamguk.engine.campaign

import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.logic.input.DeploymentState
import opensamguk.logic.input.RuleProfile
import opensamguk.logic.war.CampaignBalance

/**
 * 월 경계 부곡 군량 보충(재설계 spec §5.2 1단계의 「군량」과 §9.2 「부대 유지비는 카드가 있는 곳의 망에서」의 확정 수치).
 * 부곡 휴대 군량을 병력 × [CampaignBalance.UNIT_RESUPPLY_TARGET_MONTHS] 개월까지 창고망 곡으로 채운다.
 * 부곡 위치는 출전 중이면 지휘 장수, 아니면 주인 장수의 **실제 위치**(위치 정본 `positionOf`)다. 기준 城 id 는 城 없는 省에
 * 들어가도 이전 값으로 남으므로 쓰지 않는다. 위치가 城 없는 省이거나 주인 세력 縣이 아니면(적지 포위군) 채우지 않는다.
 * 도장([STAMP_KEY])으로 한 달에 한 번만 돈다.
 */
class UnitResupply(private val world: InMemoryTurnWorld, private val recorder: ChangeRecorder) {
    fun resupply(year: Int, month: Int): Int? {
        if (world.ruleProfile != RuleProfile.HWIHA) return null
        val stamp = "%04d-%02d".format(year, month)
        if (world.getState().meta[STAMP_KEY] == stamp) return 0
        val network = WarehouseNetwork(world, recorder)
        val commanderOf = world.listGenerals().flatMap { owner ->
            (try { DeploymentState.read(owner.meta) } catch (_: IllegalArgumentException) { null })?.corps.orEmpty()
        }.flatMap { corps -> corps.bugokIds.map { it to corps.commanderGeneralId } }.toMap()
        var filled = 0
        for (unit in world.listBugoks().sortedBy { it.id }) {
            val owner = world.getGeneralById(unit.masterGeneralId) ?: continue
            val target = (unit.troops.toLong() * CampaignBalance.UNIT_RESUPPLY_TARGET_MONTHS).coerceAtMost(Int.MAX_VALUE.toLong())
            if (unit.provisions >= target) continue
            val holder = world.getGeneralById(commanderOf[unit.id] ?: owner.id) ?: continue
            val here = CorpsRations.cityAt(world, holder.id) ?: continue
            val counties = network.countiesFor(owner.nationId, here)
            if (counties.isEmpty()) continue
            val available = network.grainIn(counties) / CampaignBalance.GRAIN_PER_PROVISION
            val add = minOf(target - unit.provisions, available)
            if (add <= 0) continue
            if (!network.payGrain(owner.nationId, counties, add * CampaignBalance.GRAIN_PER_PROVISION)) {
                Records.general(world, owner.id, opensamguk.logic.input.RecordKind.INPUT_REJECTED,
                    "부곡 ${unit.name}의 군량 보충을 건너뛰었습니다(창고 정산 실패).", mapOf("bugokId" to unit.id))
                continue
            }
            world.updateBugok(unit.copy(provisions = (unit.provisions.toLong() + add).toInt()))
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
