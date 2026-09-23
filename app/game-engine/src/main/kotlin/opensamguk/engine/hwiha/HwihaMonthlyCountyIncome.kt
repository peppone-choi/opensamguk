package opensamguk.engine.hwiha

import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.PerTurnOverlay
import opensamguk.logic.economy.HwihaCountyIncome
import opensamguk.logic.economy.HwihaCountyWarehouse
import opensamguk.logic.economy.HwihaResources
import opensamguk.logic.input.HwihaRecordKind
import opensamguk.logic.input.RuleProfile

/**
 * 월 경계에서 縣 창고에 월세입을 넣는다. HWIHA 전용이고, 기존 국가·개인 재정은 같은 프로파일에서
 * 꺼진다(`WorldActionContext.skipsLegacyFinance`) — 두 재정을 함께 켜지 않는다.
 *
 * **한 달에 한 번**을 보장하는 것은 [STAMP_KEY] 다. 도장과 창고는 같은 flush 에 실린다 —
 * 도장이 저장되지 않았다면 창고 적립도 저장되지 않았으므로, 재실행이 그 달을 다시 넣는 것이 옳다.
 *
 * 창고가 없는 縣 은 건너뛴다. 명시 재고 입력이 없는 시나리오·기존 월드를 조용히 충전하지 않는다.
 */
class HwihaMonthlyCountyIncome(
    private val world: InMemoryTurnWorld,
    private val recorder: ChangeRecorder,
) {
    data class Outcome(
        val stamp: String,
        val alreadyStamped: Boolean,
        val creditedCounties: Int = 0,
        val skippedNoWarehouse: Int = 0,
        val skippedInvalidState: Int = 0,
        val skippedOverflow: Int = 0,
        val total: HwihaResources = HwihaResources(),
    )

    fun credit(year: Int, month: Int): Outcome? {
        if (world.ruleProfile != RuleProfile.HWIHA) return null
        val stamp = stampOf(year, month)
        if (world.getState().meta[STAMP_KEY] == stamp) return Outcome(stamp, alreadyStamped = true)

        var credited = 0
        var noWarehouse = 0
        var invalid = 0
        var overflow = 0
        var total = HwihaResources()
        val byNation = sortedMapOf<Int, Pair<Int, HwihaResources>>()
        for (countyId in world.administrativeCountyIds.sorted()) {
            val before = world.getCityById(countyId) ?: continue
            val warehouse = try { HwihaCountyWarehouse.read(before.meta, countyId) }
                catch (_: IllegalArgumentException) { invalid++; continue }
            if (warehouse == null) { noWarehouse++; continue }
            val produced = HwihaCountyIncome.monthly(
                HwihaCountyIncome.CountyState(
                    ownerNationId = before.nationId,
                    population = before.population,
                    commerce = before.commerce,
                    commerceMax = before.commerceMax,
                    agriculture = before.agriculture,
                    agricultureMax = before.agricultureMax,
                    supplied = before.supplyState != 0,
                )
            )
            if (produced == HwihaResources()) continue
            // 넘침은 그 縣 만 건너뛴다. 월 경계에서 던지면 턴 루프가 영구히 멈춘다.
            val next = try { warehouse.replace(warehouse.stock.credit(produced)) }
                catch (_: ArithmeticException) { overflow++; continue }
            val after = before.copy(meta = before.meta + (HwihaCountyWarehouse.META_KEY to next.toMetaValue()))
            recorder.diffCity(PerTurnOverlay.toLogicCity(before), PerTurnOverlay.toLogicCity(after))
            checkNotNull(world.applyCityDirtyFree(after))
            credited++
            total = try { total.credit(produced) } catch (_: ArithmeticException) { total }
            if (before.nationId != 0) {
                val (count, sum) = byNation[before.nationId] ?: (0 to HwihaResources())
                byNation[before.nationId] = (count + 1) to (try { sum.credit(produced) } catch (_: ArithmeticException) { sum })
            }
        }
        // Nation-internal (warehouses are the nation's own ledger) — recorded, never put in the nation summary.
        for ((nationId, entry) in byNation) {
            val (count, sum) = entry
            HwihaRecords.nation(world, nationId, HwihaRecordKind.INCOME_MONTHLY,
                "縣 창고 ${count}곳에 월세입이 들어왔습니다.",
                linkedMapOf("stamp" to stamp, "counties" to count, "money" to sum.money, "grain" to sum.grain,
                    "iron" to sum.iron, "timber" to sum.timber, "horses" to sum.horses))
        }

        world.setGameEnvValue(STAMP_KEY, stamp)
        recorder.recordKv("game_env", "game_env", STAMP_KEY, stamp)
        return Outcome(stamp, alreadyStamped = false, credited, noWarehouse, invalid, overflow, total)
    }

    companion object {
        const val STAMP_KEY = "hwihaCountyIncomeMonth"
        fun stampOf(year: Int, month: Int): String = "%04d-%02d".format(year, month)
    }
}
