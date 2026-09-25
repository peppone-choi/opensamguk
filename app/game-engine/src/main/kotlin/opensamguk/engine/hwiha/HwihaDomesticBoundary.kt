package opensamguk.engine.hwiha

import opensamguk.logic.domestic.SeatStats
import opensamguk.logic.domestic.WorkStep
import opensamguk.logic.domestic.DomesticEffects

import opensamguk.logic.domestic.DomesticProjection
import opensamguk.logic.domestic.DomesticRules

import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.LogEntryDraft
import opensamguk.logic.economy.HwihaCountyWarehouse
import opensamguk.logic.input.*
import org.slf4j.LoggerFactory

/**
 * 순 경계의 내정 진행(§5.2 3단계). 월세입(4단계) 앞에서 한 순에 한 번 돈다 — 도장 [STAMP_KEY] 과 변경이 같은 flush 에 실린다.
 *
 * 1. 郡 방침 대기를 현행으로 올린다(郡에는 카드가 하나가 아니라 순 경계를 효력 시점으로 쓴다).
 * 2. 공사: 다음 순 경계부터 진척한다(§4). 縣 id 순으로 그 縣 창고에서 진척만큼 나눠 낸다(창고 정산 경계 [HwihaWarehouseSettlement]).
 *    모자라면 진척 없이 멈춤 사유를 남기고, 縣 주인이 바뀌면 공사를 거둔다.
 * 3. 縣 방침: 縣 id 순으로 유효 방침을 한 번 적용한다(앉은 縣令 능력치, 빈자리는 기본 방침).
 * 4. 반응 목록을 다시 쓴다(출전이 끝난 군단의 요격·회피를 뺀다).
 * 5. 월 경계(상순)면 縣令으로 배치된 카드가 앉은 縣의 지표를 지난달과 비교해 오른 것이 있으면 치적 사건을 낸다.
 */
class HwihaDomesticBoundary(
    private val world: InMemoryTurnWorld,
    private val recorder: ChangeRecorder,
    private val context: HwihaDomesticContext,
) {
    data class Outcome(val stamp: String, val alreadyStamped: Boolean, val worksAdvanced: Int = 0, val worksCompleted: Int = 0,
        val worksStopped: Int = 0, val meritEvents: Int = 0)

    fun run(meritClosedBeforeMonthlyEvents: Boolean = false): Outcome? {
        if (world.ruleProfile != RuleProfile.HWIHA) return null
        val now = world.hwihaNow()
        val stamp = stampOf(now)
        if (world.getState().meta[STAMP_KEY] == stamp) return Outcome(stamp, alreadyStamped = true)
        activateCommanderyPolicies(now)
        var advanced = 0; var completed = 0; var stopped = 0
        // Seats do not depend on works, so one projection serves every county's work speed.
        val seats = context.projection(world)
        for (countyId in world.administrativeCountyIds.sorted()) {
            when (progressWork(countyId, now, seats)) {
                WorkResult.ADVANCED -> advanced++
                WorkResult.COMPLETED -> completed++
                WorkResult.STOPPED -> stopped++
                WorkResult.NONE -> Unit
            }
        }
        val state = context.projection(world)
        val effects = HwihaDomesticCountyEffects(world, recorder, context)
        for (county in state.counties) effects.apply(county.id, state)
        HwihaReactionInventory(world, recorder).rebuild()
        val events = if (now.phase == 1 && !meritClosedBeforeMonthlyEvents) monthlyMerit(now, compare = true, open = false) else 0
        if (now.phase == 1) monthlyMerit(now, compare = false, open = true)
        world.setGameEnvValue(STAMP_KEY, stamp)
        recorder.recordKv("game_env", "game_env", STAMP_KEY, stamp)
        return Outcome(stamp, false, advanced, completed, stopped, events)
    }

    private fun activateCommanderyPolicies(now: HwihaPhase) {
        for (nation in world.listNations().sortedBy { it.id }) {
            val policies = try { HwihaCommanderyPolicies.read(nation.meta) } catch (_: IllegalArgumentException) { continue } ?: continue
            var next = policies
            for (entry in policies.entries) if (entry.slot.pending != null) next = next.with(entry.commanderyId, entry.slot.activate(now))
            if (next != policies) world.updateNationMeta(recorder, nation.id,
                nation.meta.withKey(HwihaCommanderyPolicies.META_KEY, next.takeIf { it.entries.isNotEmpty() }?.toMetaValue()))
        }
    }

    private enum class WorkResult { NONE, ADVANCED, COMPLETED, STOPPED }

    private fun progressWork(countyId: Int, now: HwihaPhase, state: DomesticProjection): WorkResult {
        val city = world.getCityById(countyId) ?: return WorkResult.NONE
        val works = try { HwihaCountyWorks.read(city.meta) } catch (_: IllegalArgumentException) { return WorkResult.NONE }
            ?: return WorkResult.NONE
        val active = works.active ?: return WorkResult.NONE
        // Starts at the next phase boundary after intake, never in the phase it was ordered (§4).
        if (active.requestedAt >= now || active.lastProgressAt == now) return WorkResult.NONE
        val actorNation = world.getGeneralById(active.actorId)?.nationId
        if (city.nationId <= 0 || actorNation != city.nationId) {
            world.updateCityMeta(recorder, countyId, city.meta.withKey(HwihaCountyWorks.META_KEY,
                HwihaCountyWorks(null, works.completed).takeIf { it.completed.isNotEmpty() }?.toMetaValue()))
            log(active.actorId, "${city.name}의 ${active.work.label} 공사를 거두었습니다(현의 주인이 바뀌었습니다).")
            return WorkResult.STOPPED
        }
        val warehouse = try { HwihaCountyWarehouse.read(city.meta, countyId) } catch (_: IllegalArgumentException) { null }
        if (warehouse == null) return stop(city.id, works, active, now, "WAREHOUSE_NOT_READY")
        val county = state.county(countyId) ?: return WorkResult.NONE
        val seat = try { DomesticRules.seatedMagistrate(county, state) } catch (_: IllegalArgumentException) { null }?.let { seat ->
            val person = state.person(seat.personId)
                ?: return stop(countyId, works, active, now, "SEAT_PERSON_MISSING")
            SeatStats(person.leadership, person.strength, person.intelligence, person.politics, person.charm,
                state.homeCountyByGeneral[person.id] == countyId)
        }
        val levels = HwihaDomesticCountyEffects.levelsOf(city)
        return when (val step = DomesticEffects.progressWork(context.design, active, now, warehouse.stock, levels, seat)) {
            is WorkStep.Stopped -> stop(countyId, works, step.work, now, step.reason)
            is WorkStep.Advanced -> {
                if (!settle(countyId, city.nationId, warehouse.revision, step)) return stop(countyId, works, active, now, "STALE_WAREHOUSE")
                val after = world.getCityById(countyId) ?: return missingCounty(active.actorId, countyId)
                world.updateCityMeta(recorder, countyId, after.meta.withKey(HwihaCountyWorks.META_KEY,
                    HwihaCountyWorks(step.work, works.completed).toMetaValue()))
                WorkResult.ADVANCED
            }
            is WorkStep.Completed -> {
                if (!settle(countyId, city.nationId, warehouse.revision, step)) return stop(countyId, works, active, now, "STALE_WAREHOUSE")
                val after = world.getCityById(countyId) ?: return missingCounty(active.actorId, countyId)
                val done = HwihaCountyWorks(null, (works.completed + step.completed).sortedWith(
                    compareBy({ it.completedAt }, { it.work.ordinal })))
                val trust = opensamguk.engine.turn.ReservedTurnHandler.materializeMariaDbFloat(step.levels.trust)
                var meta = after.meta.withKey(HwihaCountyWorks.META_KEY, done.toMetaValue())
                if (trust != HwihaDomesticCountyEffects.trustOf(after)) meta = meta.withKey("trust", trust)
                val next = after.copy(population = step.levels.population, agriculture = step.levels.agriculture,
                    commerce = step.levels.commerce, security = step.levels.security, defence = step.levels.defence,
                    wall = step.levels.wall, meta = meta)
                if (world.applyCityDirtyFree(next) == null) return missingCounty(active.actorId, countyId)
                recorder.diffCity(opensamguk.engine.turn.PerTurnOverlay.toLogicCity(after), opensamguk.engine.turn.PerTurnOverlay.toLogicCity(next))
                log(active.actorId, "${city.name}의 ${active.work.label} 공사를 마쳤습니다.")
                WorkResult.COMPLETED
            }
        }
    }

    /** 창고 차감은 정산 경계로만 한다(소유 세력·revision 재검사). */
    private fun settle(countyId: Int, nationId: Int, revision: Long, step: WorkStep): Boolean {
        val debit = when (step) {
            is WorkStep.Advanced -> step.debit
            is WorkStep.Completed -> step.debit
            is WorkStep.Stopped -> return false
        }
        if (debit == opensamguk.logic.economy.HwihaResources()) return true
        return HwihaWarehouseSettlement(world, recorder).settle(countyId, nationId, revision, debit) ==
            HwihaWarehouseSettlement.Result.APPLIED
    }

    private fun stop(countyId: Int, works: HwihaCountyWorks, work: HwihaActiveWork, now: HwihaPhase, reason: String): WorkResult {
        val city = world.getCityById(countyId) ?: return missingCounty(work.actorId, countyId)
        val stopped = work.copy(stopReason = reason)
        if (work.stopReason != reason) log(work.actorId, "${city.name}의 ${work.work.label} 공사가 멈췄습니다: ${reasonText(reason)}")
        world.updateCityMeta(recorder, countyId, city.meta.withKey(HwihaCountyWorks.META_KEY,
            HwihaCountyWorks(stopped, works.completed).toMetaValue()))
        return WorkResult.STOPPED
    }

    /** 縣令으로 배치된 카드가 앉은 縣만 비교한다. 지난달 기록이 없으면(처음 앉은 달) 사건 없이 기록만 남긴다. */
    /** Close the prior month before global growth events, so natural growth cannot become a magistrate's merit. */
    fun closeMonthlyMerit(year: Int, month: Int): Int = monthlyMerit(HwihaPhase(year, month, 1), compare = true, open = false)

    private fun monthlyMerit(now: HwihaPhase, compare: Boolean, open: Boolean): Int {
        val month = "%04d-%02d".format(now.year, now.month)
        val state = context.projection(world)
        var events = 0
        for (county in state.counties) {
            val seat = try { DomesticRules.seatedMagistrate(county, state) } catch (_: IllegalArgumentException) { null }
            val city = world.getCityById(county.id) ?: continue
            val previous = try { HwihaCountyMonthly.read(city.meta) } catch (_: IllegalArgumentException) { null }
            if (seat == null || !seat.placed) {
                if (HwihaCountyMonthly.META_KEY in city.meta) world.updateCityMeta(recorder, county.id, city.meta - HwihaCountyMonthly.META_KEY)
                continue
            }
            if (previous?.stamp == month) continue
            val current = HwihaDomesticCountyEffects.levelsOf(city).indicators()
            val risen = previous?.indicators?.let { current.risenSince(it) }.orEmpty()
            if (compare && previous != null && risen.isNotEmpty()) {
                val retainerId = seat.retainerId
                if (retainerId == null) log(seat.controllerId, "${city.name}의 치적을 기록하지 못했습니다(현령 카드가 없습니다).")
                else {
                    context.merit.onCountyIndicatorsRose(HwihaGovernanceMeritEvent(seat.controllerId, seat.personId,
                        retainerId, county.id, month, previous.indicators, current, risen))
                    events++
                }
            }
            if (open) world.updateCityMeta(recorder, county.id, city.meta.withKey(HwihaCountyMonthly.META_KEY,
                HwihaCountyMonthly(month, current).toMetaValue()))
        }
        return events
    }

    private fun reasonText(reason: String) = when (reason) {
        DomesticEffects.INSUFFICIENT_STOCK -> "창고의 자재가 모자랍니다."
        "WAREHOUSE_NOT_READY" -> "현의 창고를 확인할 수 없습니다."
        "STALE_WAREHOUSE" -> "창고 정산이 어긋났습니다."
        "SEAT_PERSON_MISSING" -> "현령 인물 정보를 확인할 수 없습니다."
        else -> reason
    }

    private fun missingCounty(actorId: Int, countyId: Int): WorkResult {
        logger.warn("hwiha_domestic_work_skipped county={} reason=MISSING_COUNTY", countyId)
        log(actorId, "현 $countyId 정보를 확인할 수 없어 공사 진행을 건너뛰었습니다.")
        return WorkResult.STOPPED
    }

    private fun log(generalId: Int, text: String) {
        val general = world.getGeneralById(generalId) ?: return
        world.pushLog(LogEntryDraft(scope = "general", category = "action", text = text, generalId = generalId, nationId = general.nationId))
    }

    companion object {
        private val logger = LoggerFactory.getLogger(HwihaDomesticBoundary::class.java)
        const val STAMP_KEY = "hwihaDomesticPhase"
        fun stampOf(phase: HwihaPhase): String = "%04d-%02d-%d".format(phase.year, phase.month, phase.phase)
    }
}
