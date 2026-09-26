package opensamguk.engine.campaign

import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.PerTurnOverlay
import opensamguk.logic.economy.CountyIncome
import opensamguk.logic.economy.CountyWarehouse
import opensamguk.logic.economy.Resources
import opensamguk.infra.seed.CountyProductionJson
import opensamguk.logic.input.RecordKind
import opensamguk.logic.input.RuleProfile
import opensamguk.logic.record.AudienceTarget
import opensamguk.logic.record.EventFact
import opensamguk.logic.record.EventKey
import opensamguk.logic.record.EventKind
import opensamguk.logic.record.EventRef
import opensamguk.logic.record.FactRole
import opensamguk.logic.record.RefRole
import org.slf4j.LoggerFactory

/**
 * 월 경계에서 縣 창고에 월세입을 넣는다. 캠페인 전용이고, 기존 국가·개인 재정은 같은 프로파일에서
 * 꺼진다(`WorldActionContext.skipsLegacyFinance`) — 두 재정을 함께 켜지 않는다.
 *
 * **한 달에 한 번**을 보장하는 것은 [STAMP_KEY] 다. 도장과 창고는 같은 flush 에 실린다 —
 * 도장이 저장되지 않았다면 창고 적립도 저장되지 않았으므로, 재실행이 그 달을 다시 넣는 것이 옳다.
 *
 * 창고가 없는 縣 은 건너뛴다. 명시 재고 입력이 없는 시나리오·기존 월드를 조용히 충전하지 않는다.
 *
 * 철·목재·말은 이 클래스가 만들지 않는다 — [production] 표가 준다. 기본값은 생성된 런타임 산출물이고,
 * 철·말의 위치는 사료 산지 원장, 목재는 지도 면적 축이다(tools/map/build_county_resource_production.py).
 */
class MonthlyCountyIncome(
    private val world: InMemoryTurnWorld,
    private val recorder: ChangeRecorder,
    private val production: Map<Int, Resources> = CountyProductionJson.table(),
) {
    data class Outcome(
        val stamp: String,
        val alreadyStamped: Boolean,
        val creditedCounties: Int = 0,
        val skippedNoWarehouse: Int = 0,
        val skippedInvalidState: Int = 0,
        val skippedOverflow: Int = 0,
        val total: Resources = Resources(),
    )

    fun credit(year: Int, month: Int): Outcome? {
        if (world.ruleProfile != RuleProfile.HWIHA) return null
        val stamp = stampOf(year, month)
        if (world.getState().meta[STAMP_KEY] == stamp) return Outcome(stamp, alreadyStamped = true)

        var credited = 0
        var noWarehouse = 0
        var invalid = 0
        var overflow = 0
        var total = Resources()
        val byNation = sortedMapOf<Int, Pair<Int, Resources>>()
        for (countyId in world.administrativeCountyIds.sorted()) {
            val before = world.getCityById(countyId) ?: continue
            val warehouse = try { CountyWarehouse.read(before.meta, countyId) }
                catch (_: IllegalArgumentException) { invalid++; continue }
            if (warehouse == null) { noWarehouse++; continue }
            val produced = CountyIncome.monthly(
                CountyIncome.CountyState(
                    ownerNationId = before.nationId,
                    population = before.population,
                    commerce = before.commerce,
                    commerceMax = before.commerceMax,
                    agriculture = before.agriculture,
                    agricultureMax = before.agricultureMax,
                    supplied = before.supplyState != 0,
                ),
                sites = production[countyId] ?: Resources(),
            )
            if (produced == Resources()) continue
            // 넘침은 그 縣 만 건너뛴다. 월 경계에서 던지면 턴 루프가 영구히 멈춘다.
            val next = try { warehouse.replace(warehouse.stock.credit(produced)) }
                catch (_: ArithmeticException) { overflow++; continue }
            val after = before.copy(meta = before.meta + (CountyWarehouse.META_KEY to next.toMetaValue()))
            if (world.applyCityDirtyFree(after) == null) {
                log.warn("campaign_county_income_skipped county={} reason=APPLY_REJECTED", countyId)
                invalid++
                continue
            }
            recorder.diffCity(PerTurnOverlay.toLogicCity(before), PerTurnOverlay.toLogicCity(after))
            credited++
            total = try { total.credit(produced) } catch (_: ArithmeticException) { total }
            if (before.nationId != 0) {
                val (count, sum) = byNation[before.nationId] ?: (0 to Resources())
                byNation[before.nationId] = (count + 1) to (try { sum.credit(produced) } catch (_: ArithmeticException) { sum })
            }
        }
        // Nation-internal (warehouses are the nation's own ledger) — recorded, never put in the nation summary.
        for ((nationId, entry) in byNation) {
            val (count, sum) = entry
            Records.nation(world, nationId, RecordKind.INCOME_MONTHLY,
                "縣 창고 ${count}곳에 월세입이 들어왔습니다.",
                linkedMapOf("stamp" to stamp, "counties" to count, "money" to sum.money, "grain" to sum.grain,
                    "iron" to sum.iron, "timber" to sum.timber, "horses" to sum.horses))
            world.recordEvent(
                kind = EventKind.INCOME_MONTHLY,
                audience = AudienceTarget.Nation(nationId),
                eventKey = EventKey.derive("income.monthly", world.worldId.value.toString(),
                    year.toString(), month.toString(), nationId.toString()),
                refs = mapOf(RefRole.NATION to EventRef.Nation(nationId)),
                facts = mapOf(
                    FactRole.COUNTIES to EventFact.Amount(count.toLong()),
                    FactRole.MONEY to EventFact.Amount(sum.money),
                    FactRole.GRAIN to EventFact.Amount(sum.grain),
                    FactRole.IRON to EventFact.Amount(sum.iron),
                    FactRole.TIMBER to EventFact.Amount(sum.timber),
                    FactRole.HORSES to EventFact.Amount(sum.horses),
                ),
            )
        }

        world.setGameEnvValue(STAMP_KEY, stamp)
        recorder.recordKv("game_env", "game_env", STAMP_KEY, stamp)
        return Outcome(stamp, alreadyStamped = false, credited, noWarehouse, invalid, overflow, total)
    }

    companion object {
        private val log = LoggerFactory.getLogger(MonthlyCountyIncome::class.java)
        const val STAMP_KEY = "countyIncomeMonth"
        fun stampOf(year: Int, month: Int): String = "%04d-%02d".format(year, month)
    }
}
