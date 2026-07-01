package opensamguk.engine.world

import opensamguk.common.constants.GameConst
import opensamguk.common.rng.RandUtil
import opensamguk.engine.turn.City as EngineCity
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.LogEntryDraft
import opensamguk.engine.turn.Nation as EngineNation
import opensamguk.engine.turn.PerTurnOverlay
import opensamguk.engine.turn.RankColumn
import opensamguk.engine.turn.TurnGeneral
import opensamguk.logic.domain.General as LogicGeneral
import opensamguk.logic.domain.NationTurn
import opensamguk.logic.domain.metaDouble
import opensamguk.logic.domain.metaInt
import opensamguk.logic.domain.withMeta
import opensamguk.logic.event.DeleteEventContext
import opensamguk.logic.event.EventActionContext
import opensamguk.logic.event.EventStore
import opensamguk.logic.event.LightActionWorld
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.traits.NationTypeRegistry
import opensamguk.logic.util.phpRound
import opensamguk.logic.world.AssignSpecialityResult
import opensamguk.logic.world.CityConstVariant
import opensamguk.logic.world.CityLevel
import opensamguk.logic.world.CitySupplyResult
import opensamguk.logic.world.DisasterCity
import opensamguk.logic.world.DisasterWorldView
import opensamguk.logic.world.IncomeGeneral
import opensamguk.logic.world.IncomeNation
import opensamguk.logic.world.IncomeNationUpdate
import opensamguk.logic.world.InvaderEndingContext
import opensamguk.logic.world.UpdateNationLevel.LevelUpEffects
import opensamguk.logic.world.UpdateNationLevel.LotteryResult
import opensamguk.logic.world.MergeGeneral
import opensamguk.logic.world.MergeInheritPointRank
import opensamguk.logic.world.MergeInheritResult
import opensamguk.logic.world.MergeInheritWorld
import opensamguk.logic.world.ProcessIncomeContext
import opensamguk.logic.world.ProcessIncomeResult
import opensamguk.logic.world.ProcessSemiAnnualContext
import opensamguk.logic.world.ProcessSemiAnnualResult
import opensamguk.logic.world.ProcessWarIncomeContext
import opensamguk.logic.world.ProcessWarIncomeResult
import opensamguk.logic.world.ProvideNPCTroopLeaderContext
import opensamguk.logic.world.RandomizeCityTradeRateContext
import opensamguk.logic.world.RaiseDisasterResult
import opensamguk.logic.world.SpecialityGeneral
import opensamguk.logic.world.SpecialityWorldView
import opensamguk.logic.world.SupplyCapital
import opensamguk.logic.world.UpdateCitySupplyContext
import opensamguk.logic.world.UpdateNationLevelContext
import opensamguk.logic.world.WarIncomeNation

/**
 * P6 / Task 4 — The engine-side unified adapter that implements ALL richer-context interfaces the
 * Action leaves require, by reading from [InMemoryTurnWorld] and writing through
 * [ChangeRecorder] + world mutators.
 *
 * Every Action leaf (ProcessIncome, ProcessWarIncome, RaiseDisaster, RandomizeCityTradeRate,
 * AssignGeneralSpeciality, ProcessSemiAnnual, MergeInheritPointRank, UpdateCitySupply,
 * UpdateNationLevel, ProvideNPCTroopLeader, plus the light actions NewYear etc.) defines its own
 * richer-context interface. The engine supplies ONE object that implements all of them.
 */
class WorldActionContext(
    override val env: MutableMap<String, Any?>,
    private val world: InMemoryTurnWorld,
    private val recorder: ChangeRecorder,
    override val pipeline: GeneralActionPipeline,
) : EventActionContext,
    ProcessIncomeContext,
    ProcessWarIncomeContext,
    RandomizeCityTradeRateContext,
    ProcessSemiAnnualContext,
    MergeInheritWorld,
    UpdateCitySupplyContext,
    UpdateNationLevelContext,
    ProvideNPCTroopLeaderContext,
    DisasterWorldView,
    SpecialityWorldView,
    InvaderEndingContext,
    LightActionWorld {

    // ── helpers ────────────────────────────────────────────────────────────────────────────────

    private fun resolveHiddenSeed(): String = world.getState().meta["hiddenSeed"] as? String ?: ""
    private fun resolveYear(): Int = (env["year"] as? Number)?.toInt() ?: world.getState().currentYear
    private fun resolveMonth(): Int = (env["month"] as? Number)?.toInt() ?: world.getState().currentMonth
    private fun resolvePhase(): Int = (env["phase"] as? Number)?.toInt() ?: world.getState().currentPhase
    private fun resolveStartYear(): Int = (world.getState().meta["startYear"] as? Number)?.toInt() ?: 0
    private fun resolveKillturnEnv(): Int = (world.getState().meta["killturn"] as? Number)?.toInt() ?: 0
    private fun resolveTurnterm(): Int = (world.getState().meta["turnterm"] as? Number)?.toInt() ?: 1

    private fun logDraft(
        scope: String,
        category: String,
        text: String,
        generalId: Int? = null,
        nationId: Int? = null,
        userId: Int? = null,
        subType: String? = null,
    ): LogEntryDraft = LogEntryDraft(
        scope = scope,
        category = category,
        text = text,
        generalId = generalId,
        nationId = nationId,
        userId = userId,
        subType = subType,
        year = resolveYear(),
        month = resolveMonth(),
        phase = resolvePhase(),
    )

    private fun officerCntByCity(nationId: Int): Map<Int, Int> {
        val out = LinkedHashMap<Int, Int>()
        for (g in world.listGenerals()) {
            if (g.nationId != nationId) continue
            if (g.officerLevel !in 2..4) continue
            val officerCity = (g.meta["officer_city"] as? Number)?.toInt() ?: continue
            if (officerCity != g.cityId) continue
            out[officerCity] = (out[officerCity] ?: 0) + 1
        }
        return out
    }

    // ── shared overrides (satisfy multiple interfaces) ─────────────────────────────────────────

    /** [RandomizeCityTradeRateContext] property; [UpdateNationLevelContext] / [ProvideNPCTroopLeaderContext] method. */
    override val hiddenSeed: String get() = resolveHiddenSeed()
    override fun hiddenSeed(): String = resolveHiddenSeed()
    override fun year(): Int = resolveYear()
    override fun month(): Int = resolveMonth()

    /** [ProcessWarIncomeContext], [ProcessSemiAnnualContext], [UpdateCitySupplyContext]. */
    override fun cities(): List<opensamguk.logic.domain.City> =
        world.listCities().sortedBy { it.id }.map { PerTurnOverlay.toLogicCity(it) }

    /** [UpdateCitySupplyContext], [UpdateNationLevelContext], [ProvideNPCTroopLeaderContext]. */
    override fun generals(): List<LogicGeneral> =
        world.listGenerals().sortedBy { it.id }.map { PerTurnOverlay.toLogicGeneral(it) }

    /** [UpdateCitySupplyContext], [UpdateNationLevelContext]. */
    override fun cityConst(): CityConstVariant =
        env["cityConst"] as? CityConstVariant
            ?: error("cityConst must be threaded in env before UpdateCitySupply/UpdateNationLevel runs")

    /** [UpdateNationLevelContext], [ProvideNPCTroopLeaderContext]. */
    override fun nations(): List<opensamguk.logic.domain.Nation> =
        world.listNations().sortedBy { it.id }.map { PerTurnOverlay.toLogicNation(it) }

    // ── ProcessIncomeContext ───────────────────────────────────────────────────────────────────

    override fun incomeNations(): List<IncomeNation> =
        world.listNations().sortedBy { it.id }.map { n ->
            IncomeNation(
                id = n.id,
                name = n.name,
                gold = n.gold,
                rice = n.rice,
                level = n.level,
                taxRate = metaDouble(n.meta, "rate_tmp"),
                bill = metaDouble(n.meta, "bill"),
                capitalId = n.capitalCityId ?: 0,
                nationType = NationTypeRegistry.resolve(n.typeCode),
                cities = world.listCities().filter { it.nationId == n.id }.sortedBy { it.id }
                    .map { PerTurnOverlay.toLogicCity(it) },
                generals = world.listGenerals().filter { it.nationId == n.id }.sortedBy { it.id }
                    .map { IncomeGeneral(it.id, it.dedication.toDouble(), it.officerLevel) },
                officerCntByCity = officerCntByCity(n.id),
            )
        }

    override fun applyIncome(result: ProcessIncomeResult) {
        val resource = result.resource
        for (nu in result.nationUpdates) {
            val pre = world.getNationById(nu.nationId) ?: continue
            val preLogic = PerTurnOverlay.toLogicNation(pre)
            val postLogic = if (resource == "gold") preLogic.copy(gold = nu.newResource) else preLogic.copy(rice = nu.newResource)
            val postEngine = if (resource == "gold") pre.copy(gold = nu.newResource) else pre.copy(rice = nu.newResource)
            recorder.diffNation(preLogic, postLogic)
            world.updateNation(postEngine)
        }
        for ((nationId, value) in result.prevIncome) {
            recorder.recordKv("nation_env", nationId.toString(), "prev_income_$resource", value)
        }
        for (pg in result.generalPayouts) {
            val pre = world.getGeneralById(pg.generalId) ?: continue
            val preLogic = PerTurnOverlay.toLogicGeneral(pre)
            val postLogic = if (resource == "gold") preLogic.copy(gold = preLogic.gold + pg.amount) else preLogic.copy(rice = preLogic.rice + pg.amount)
            val postEngine = if (resource == "gold") pre.copy(gold = pre.gold + pg.amount) else pre.copy(rice = pre.rice + pg.amount)
            recorder.diffGeneral(preLogic, postLogic)
            world.updateGeneral(postEngine)
            for (line in pg.logLines) {
                world.pushLog(logDraft("general", "history", line, generalId = pre.id, nationId = pre.nationId))
            }
        }
        world.pushLog(logDraft("global", "history", result.globalHistory))
    }

    // ── ProcessWarIncomeContext ────────────────────────────────────────────────────────────────

    override fun warIncomeNations(): List<WarIncomeNation> =
        world.listNations().sortedBy { it.id }.map { n ->
            WarIncomeNation(
                id = n.id,
                level = n.level,
                gold = n.gold,
                nationType = NationTypeRegistry.resolve(n.typeCode),
            )
        }

    override fun applyWarIncome(result: ProcessWarIncomeResult) {
        for (add in result.nationGoldAdds) {
            val pre = world.getNationById(add.nationId) ?: continue
            val preLogic = PerTurnOverlay.toLogicNation(pre)
            val postLogic = preLogic.copy(gold = add.newGold)
            recorder.diffNation(preLogic, postLogic)
            world.updateNation(pre.copy(gold = add.newGold))
        }
        for (cu in result.cityUpdates) {
            val pre = world.getCityById(cu.cityId) ?: continue
            val preLogic = PerTurnOverlay.toLogicCity(pre)
            val postLogic = preLogic.copy(population = cu.newPop, dead = cu.newDead)
            recorder.diffCity(preLogic, postLogic)
            // Engine City has no `dead` column (dead rides TurnDiplomacy); diffCity also skips dead.
            world.updateCity(pre.copy(population = cu.newPop))
        }
    }

    // ── RandomizeCityTradeRateContext ──────────────────────────────────────────────────────────

    override val cities: List<CityLevel>
        get() = world.listCities().sortedBy { it.id }.map { CityLevel(it.id, it.level) }

    override fun applyTradeRates(rates: Map<Int, Int?>) {
        for ((cityId, rate) in rates) {
            val pre = world.getCityById(cityId) ?: continue
            val preLogic = PerTurnOverlay.toLogicCity(pre)
            val postLogic = preLogic.copy(trade = rate)
            recorder.diffCity(preLogic, postLogic)
            world.updateCity(pre.copy(trade = rate))
        }
    }

    // ── ProcessSemiAnnualContext ───────────────────────────────────────────────────────────────

    override fun semiAnnualNations(): List<opensamguk.logic.world.SemiAnnualNation> =
        world.listNations().sortedBy { it.id }.map { n ->
            opensamguk.logic.world.SemiAnnualNation(
                id = n.id,
                taxRate = metaDouble(n.meta, "rate_tmp"),
                nationType = NationTypeRegistry.resolve(n.typeCode),
                gold = n.gold,
                rice = n.rice,
            )
        }

    override fun semiAnnualGenerals(): List<opensamguk.logic.world.SemiAnnualGeneral> =
        world.listGenerals().sortedBy { it.id }.map { g ->
            opensamguk.logic.world.SemiAnnualGeneral(g.id, g.gold, g.rice)
        }

    override fun applySemiAnnual(result: ProcessSemiAnnualResult) {
        val resource = result.resource
        for (cu in result.cityUpdates) {
            val pre = world.getCityById(cu.cityId) ?: continue
            val preLogic = PerTurnOverlay.toLogicCity(pre)
            val postLogic = preLogic.copy(
                agriculture = cu.newAgri,
                commerce = cu.newComm,
                security = cu.newSecu,
                defense = cu.newDef,
                wall = cu.newWall,
                population = cu.newPop,
                trust = cu.newTrust,
                dead = cu.newDead,
            )
            recorder.diffCity(preLogic, postLogic)

            // Engine City stores trust in meta; dead is not a city column.
            val nextMeta = LinkedHashMap(pre.meta)
            nextMeta["trust"] = cu.newTrust
            world.updateCity(
                pre.copy(
                    agriculture = cu.newAgri,
                    commerce = cu.newComm,
                    security = cu.newSecu,
                    defence = cu.newDef,
                    wall = cu.newWall,
                    population = cu.newPop,
                    meta = nextMeta,
                )
            )
        }
        for (gu in result.generalUpkeep) {
            val pre = world.getGeneralById(gu.generalId) ?: continue
            val preLogic = PerTurnOverlay.toLogicGeneral(pre)
            val postLogic = if (resource == "gold") preLogic.copy(gold = gu.newResource) else preLogic.copy(rice = gu.newResource)
            val postEngine = if (resource == "gold") pre.copy(gold = gu.newResource) else pre.copy(rice = gu.newResource)
            recorder.diffGeneral(preLogic, postLogic)
            world.updateGeneral(postEngine)
        }
        for (nu in result.nationUpkeep) {
            val pre = world.getNationById(nu.nationId) ?: continue
            val preLogic = PerTurnOverlay.toLogicNation(pre)
            val postLogic = if (resource == "gold") preLogic.copy(gold = nu.newResource) else preLogic.copy(rice = nu.newResource)
            val postEngine = if (resource == "gold") pre.copy(gold = nu.newResource) else pre.copy(rice = nu.newResource)
            recorder.diffNation(preLogic, postLogic)
            world.updateNation(postEngine)
        }
    }

    // ── MergeInheritWorld ──────────────────────────────────────────────────────────────────────

    override fun mergeGenerals(): List<MergeGeneral> =
        world.listGenerals().sortedBy { it.id }.map { MergeGeneral(it.id, it.nationId) }

    override fun inheritancePoints(key: String): Map<Int, Int> =
        MergeInheritPointRank.ZERO_POINT_SOURCE(key)

    override fun applyMerge(result: MergeInheritResult) {
        for (row in result.mergeRows) {
            val column = RankColumn.byColumn(row.type) ?: continue
            recorder.recordRankSet(row.generalId, column, row.value)
        }
        // P3 bound: all merge row values are 0; derived updates are deferred to a future flush
        // extension that models the correlated SQL UPDATEs (inherit_earned = SUM(...),
        // inherit_spent = inherit_spent_dyn). For P3 the zero-point source means no net change.
    }

    // ── UpdateCitySupplyContext ────────────────────────────────────────────────────────────────

    override fun capitals(): List<SupplyCapital> =
        world.listNations().filter { it.level > 0 }.sortedBy { it.id }
            .map { SupplyCapital(it.capitalCityId ?: 0, it.id) }

    override fun applyCitySupply(result: CitySupplyResult) {
        for (postLogic in result.cities) {
            val pre = world.getCityById(postLogic.id) ?: continue
            val preLogic = PerTurnOverlay.toLogicCity(pre)
            recorder.diffCity(preLogic, postLogic)

            val nextMeta = LinkedHashMap(pre.meta)
            nextMeta["trust"] = postLogic.trust
            if (postLogic.term != 0) nextMeta["term"] = postLogic.term else nextMeta.remove("term")
            if (postLogic.officerSet != 0) nextMeta["officer_set"] = postLogic.officerSet else nextMeta.remove("officer_set")
            if (postLogic.conflict != "{}") nextMeta["conflict"] = postLogic.conflict else nextMeta.remove("conflict")

            world.updateCity(
                pre.copy(
                    supplyState = postLogic.supplyState,
                    population = postLogic.population,
                    agriculture = postLogic.agriculture,
                    commerce = postLogic.commerce,
                    security = postLogic.security,
                    defence = postLogic.defense,
                    wall = postLogic.wall,
                    nationId = postLogic.nationId,
                    frontState = postLogic.frontState,
                    meta = nextMeta,
                )
            )
        }
        for (postLogic in result.generals) {
            val pre = world.getGeneralById(postLogic.id) ?: continue
            val preLogic = PerTurnOverlay.toLogicGeneral(pre)
            recorder.diffGeneral(preLogic, postLogic)
            world.updateGeneral(
                pre.copy(
                    crew = postLogic.crew,
                    train = postLogic.train.toInt(),
                    atmos = postLogic.atmos.toInt(),
                    officerLevel = postLogic.officerLevel,
                    meta = if (postLogic.officerLevel == 1) withMeta(pre.meta, "officer_city" to 0) else pre.meta,
                )
            )
        }
        for (log in result.isolatedLogs) {
            world.pushLog(logDraft("global", "history", log))
        }
        // Thread lost-city ids through env so the F3 tombstone seam can consume them later.
        env["lostCityIds"] = result.lostCityIds
    }

    // ── UpdateNationLevelContext ───────────────────────────────────────────────────────────────

    override fun cityOwnership(): List<Pair<Int, Int>> =
        world.listCities().sortedBy { it.id }.map { it.id to it.nationId }

    override fun startYear(): Int = resolveStartYear()
    override fun killturnEnv(): Int = resolveKillturnEnv()
    override fun turnterm(): Int = resolveTurnterm()

    override fun lordName(nationId: Int): String? =
        world.listGenerals().find { it.nationId == nationId && it.officerLevel == 12 }?.name

    override fun applyNationLevelUp(effects: LevelUpEffects) {
        val pre = world.getNationById(effects.nation.id) ?: return
        val preLogic = PerTurnOverlay.toLogicNation(pre)
        recorder.diffNation(preLogic, effects.nation)
        world.updateNation(
            pre.copy(
                level = effects.nation.level,
                gold = effects.nation.gold,
                rice = effects.nation.rice,
                meta = effects.nation.meta,
            )
        )
        if (effects.globalHistoryLog.isNotBlank()) {
            world.pushLog(logDraft("global", "history", effects.globalHistoryLog))
        }
        if (effects.nationalHistoryLog.isNotBlank()) {
            world.pushLog(logDraft("nation", "history", effects.nationalHistoryLog, nationId = effects.nation.id))
        }
        // Thread nation-turn seed through env until the world gains a native nation_turn dirty channel.
        val existing = (env["nationTurnSeed"] as? MutableList<NationTurn>) ?: mutableListOf()
        existing.addAll(effects.nationTurnSeed)
        env["nationTurnSeed"] = existing
    }

    override fun giveRandomUniqueItem(rng: RandUtil, winnerId: Int): Boolean {
        // P6 seam — item grant requires catalog + occupancy queries not yet wired.
        return false
    }

    override fun applyLotteryResult(nationId: Int, result: LotteryResult) {
        val chiefId = result.chiefId ?: return
        if (result.chiefInheritancePointDelta <= 0) return
        recorder.recordInheritancePointIncrease(
            ownerID = chiefId,
            key = "unifier",
            value = result.chiefInheritancePointDelta.toDouble(),
            aux = null,
        )
    }

    // ── ProvideNPCTroopLeaderContext ───────────────────────────────────────────────────────────

    override fun lastNpcTroopLeaderId(): Int =
        (env["last_npc_troop_leader_id"] as? Number)?.toInt() ?: 0

    override fun setLastNpcTroopLeaderId(id: Int) {
        recorder.recordKv("game_env", "", "last_npc_troop_leader_id", id)
        env["last_npc_troop_leader_id"] = id
    }

    override fun mintTroopLeader(nationId: Int, leader: opensamguk.logic.world.ProvideNPCTroopLeader.NewLeader, seed: String) {
        // P6 seam — GeneralBuilder mint + troop-row insert + 집합 reservation.
    }

    // ── DisasterWorldView ──────────────────────────────────────────────────────────────────────

    override fun disasterCities(): List<DisasterCity> =
        world.listCities().sortedBy { it.id }.map {
            DisasterCity(
                cityId = it.id,
                name = it.name,
                state = it.state,
                secu = it.security,
                secuMax = it.securityMax,
            )
        }

    override fun applyDisaster(result: RaiseDisasterResult) {
        for ((cityId, state) in result.stateResets) {
            val pre = world.getCityById(cityId) ?: continue
            // W0-8: city.state는 V14부터 영속 컬럼 — 무조건 리셋(state<=10→0)도 diffCity로 기록해
            // recorder-flush 경로에 싣는다(P0-36 재기동 유실 수정). updateCity는 tick-dirty도 함께 마킹.
            val preLogic = PerTurnOverlay.toLogicCity(pre)
            recorder.diffCity(preLogic, preLogic.copy(state = state))
            world.updateCity(pre.copy(state = state))
        }
        for (effect in result.effects) {
            val pre = world.getCityById(effect.cityId) ?: continue
            val preLogic = PerTurnOverlay.toLogicCity(pre)

            fun newStat(value: Int, max: Int) = if (effect.capped) {
                kotlin.math.min(max, phpRound(value * effect.affectRatio))
            } else {
                phpRound(value * effect.affectRatio)
            }

            // trust(FLOAT 컬럼)는 pop·agri·comm·secu·def·wall 과 SAME city-update 에서 같은 affectRatio 로 곱해진다.
            // RaiseDisaster.php:154 호황 → least(trust * ratio, 100)  (리터럴 100 캡, trust_max 아님)
            // RaiseDisaster.php:129 재난 → trust * ratio              (무캡)
            // PHP 는 trust 에 round() 를 걸지 않는 생 float 곱이다 → phpRound/newStat 경유 금지(생 Double 곱).
            val newTrust = if (effect.capped) {
                kotlin.math.min(100.0, preLogic.trust * effect.affectRatio)
            } else {
                preLogic.trust * effect.affectRatio
            }

            val postLogic = preLogic.copy(
                // W0-8: 선택 도시의 stateCode(1~9)도 diff에 포함 — V14 영속 컬럼 (P0-36).
                state = effect.stateCode,
                trust = newTrust,
                agriculture = newStat(preLogic.agriculture, preLogic.agricultureMax),
                commerce = newStat(preLogic.commerce, preLogic.commerceMax),
                security = newStat(preLogic.security, preLogic.securityMax),
                defense = newStat(preLogic.defense, preLogic.defenseMax),
                wall = newStat(preLogic.wall, preLogic.wallMax),
                population = newStat(preLogic.population, preLogic.populationMax),
            )
            recorder.diffCity(preLogic, postLogic)
            // 엔진 City 는 trust 전용 컬럼이 없고 meta["trust"](Double)에 보관한다(PerTurnOverlay.toLogicCity).
            // → in-memory 갱신도 meta 에 새 trust 를 써야 diffCity 와 일관되고 다음 틱이 곱셈 결과를 본다.
            val nextMeta = LinkedHashMap(pre.meta)
            nextMeta["trust"] = newTrust
            world.updateCity(
                pre.copy(
                    state = effect.stateCode,
                    agriculture = postLogic.agriculture,
                    commerce = postLogic.commerce,
                    security = postLogic.security,
                    defence = postLogic.defense,
                    wall = postLogic.wall,
                    population = postLogic.population,
                    meta = nextMeta,
                )
            )
        }
        result.logLine?.let {
            world.pushLog(logDraft("global", "history", it))
        }
    }

    // ── SpecialityWorldView ────────────────────────────────────────────────────────────────────

    override fun specialityGenerals(): List<SpecialityGeneral> =
        world.listGenerals().sortedBy { it.id }.map { g ->
            SpecialityGeneral(
                no = g.id,
                name = g.name,
                nation = g.nationId,
                age = g.age,
                specage = metaInt(g.meta, "specage"),
                specage2 = metaInt(g.meta, "specage2"),
                special = g.meta["special"] as? String ?: g.role.specialDomestic ?: GameConst.defaultSpecialDomestic,
                special2 = g.role.specialWar ?: g.meta["special2"] as? String ?: GameConst.defaultSpecialWar,
                leadership = g.stats.leadership,
                strength = g.stats.strength,
                intel = g.stats.intelligence,
                dex1 = metaInt(g.meta, "dex1"),
                dex2 = metaInt(g.meta, "dex2"),
                dex3 = metaInt(g.meta, "dex3"),
                dex4 = metaInt(g.meta, "dex4"),
                dex5 = metaInt(g.meta, "dex5"),
                npc = g.npcState,
                aux = g.meta["aux"] as? Map<String, Any?> ?: emptyMap(),
            )
        }

    override fun applySpeciality(result: AssignSpecialityResult) {
        for (a in result.domesticAssignments) {
            val pre = world.getGeneralById(a.generalId) ?: continue
            val post = pre.copy(role = pre.role.copy(specialDomestic = a.special))
            recorder.diffGeneral(PerTurnOverlay.toLogicGeneral(pre), PerTurnOverlay.toLogicGeneral(post))
            world.updateGeneral(post)
            world.pushLog(logDraft("general", "action", a.actionLog, generalId = a.generalId, nationId = a.nation))
            world.pushLog(logDraft("general", "history", a.historyLog, generalId = a.generalId, nationId = a.nation))
        }
        for (a in result.warAssignments) {
            val pre = world.getGeneralById(a.generalId) ?: continue
            val newMeta = if (a.updatedAux != null) {
                LinkedHashMap(pre.meta).apply { this["aux"] = a.updatedAux }
            } else pre.meta
            val post = pre.copy(role = pre.role.copy(specialWar = a.special), meta = newMeta)
            recorder.diffGeneral(PerTurnOverlay.toLogicGeneral(pre), PerTurnOverlay.toLogicGeneral(post))
            world.updateGeneral(post)
            world.pushLog(logDraft("general", "action", a.actionLog, generalId = a.generalId, nationId = a.nation))
            world.pushLog(logDraft("general", "history", a.historyLog, generalId = a.generalId, nationId = a.nation))
        }
    }

    // ── InvaderEndingContext ───────────────────────────────────────────────────────────────────
    // 침략자(이민족) 이벤트 종료 leaf(InvaderEnding.php)의 월드 read/write 시임. WorldCheckEmperiorContext
    // (sibling Q14)와 동일 패턴 — InMemoryTurnWorld read + meta 전이 write + global-history 로그 push.

    /** `$gameStor->isunited`(php:22) — game_env isunited(0=평시,1=침략자 진행,2=천하통일,3=엔딩). */
    override fun isunited(): Int = (world.getState().meta["isunited"] as? Number)?.toInt() ?: 0

    /** `SELECT count(*) FROM nation`(InvaderEnding.php:25) — level 필터 없는 전 국가 수. */
    override fun nationCount(): Int = world.listNations().size

    /** `SELECT count(*) FROM city WHERE nation = 0`(php:36) — 공백지(소유국가 0) 도시 수. */
    override fun neutralCityCount(): Int = world.listCities().count { it.nationId == 0 }

    /** `count(CityConst::all())`(php:44) — 전 도시가 시드되므로 in-memory city 수 = 전체 시나리오 도시 수
     *  (WorldCheckEmperiorContext.totalCityCount 와 동일 근거: 장기-시뮬 게이트 전제). */
    override fun totalCityCount(): Int = world.listCities().size

    /** `SELECT name FROM nation LIMIT 1`(php:39) — ORDER BY 없는 LIMIT 1 = 삽입(=PK) 순서 첫 행.
     *  in-memory 아날로그는 listNations()의 첫 원소(LinkedHashMap PK 삽입 순서). 빈 결과면 null. */
    override fun firstNationName(): String? = world.listNations().firstOrNull()?.name

    /** `ActionLogger::pushGlobalHistoryLog`(php:54-60) — InvaderEndingContext 의 무-type 시그니처.
     *  LightActionWorld.pushGlobalHistoryLog(msg, type)와 arity가 달라 별도 override. */
    override fun pushGlobalHistoryLog(msg: String) {
        world.pushLog(logDraft("global", "history", msg))
    }

    /** `logger->flush()`(php:64) — 엔진은 pushLog로 이미 로그를 큐에 적재하고 flush 단계에서 일괄 드레인하므로
     *  faithful no-op(PHP의 flush는 pending 로그 확정일 뿐 — 엔진은 이미 큐잉 완료). */
    override fun flushLogs() {
        // no-op: 엔진은 world.pushLog 시점에 로그를 큐잉하고 turn flush 에서 드레인한다(위 docstring 참조).
    }

    /** `$gameStor->setValue('isunited', 3)`(php:63). meta 즉시 반영(컬럼 flush 는 LEDGER 백로그). */
    // setIsunited(value): 아래 setIsunited(value: Int) — InvaderEndingContext + 공유 시그니처(WorldCheckEmperior 류).
    override fun setIsunited(value: Int) = world.setIsunited(value)

    /** `$gameStor->refreshLimit = $gameStor->refreshLimit * factor`(php:65). meta 즉시 반영 —
     *  game_env refreshLimit 컬럼 flush/boot-load 는 isunited 와 동일 클래스의 별도 갭(LEDGER 백로그:
     *  game_env KV write seam 부재). [InMemoryTurnWorld.multiplyRefreshLimit] 참조. */
    override fun multiplyRefreshLimit(factor: Int) = world.multiplyRefreshLimit(factor)

    /** `$db->delete('event', 'id = %i', currentEventID)`(php:67-68) — 1회용 event 자기 삭제.
     *  WorldEventContextFactory 가 env[DeleteEventContext.ENV_KEY] 로 심은 live EventStore 에 위임
     *  (DeleteEventAction 과 동일 시임). 미공급 시(테스트 등 env 없음) 무음 — store.delete 는 멱등(map remove). */
    override fun deleteOwnEvent(eventID: Int) {
        val store = env[DeleteEventContext.ENV_KEY] as? EventStore ?: return
        store.delete(eventID)
    }

    // ── LightActionWorld ───────────────────────────────────────────────────────────────────────

    override fun incrementAllGeneralAge() {
        for (g in world.listGenerals().sortedBy { it.id }) {
            world.applyGeneralDirtyFree(g.copy(age = g.age + 1))
        }
    }

    override fun incrementBelongWhereNationNonZero() {
        for (g in world.listGenerals().sortedBy { it.id }) {
            if (g.nationId == 0) continue
            val newBelong = metaInt(g.meta, "belong") + 1
            world.applyGeneralDirtyFree(g.copy(meta = withMeta(g.meta, "belong" to newBelong)))
        }
    }

    override fun clearAllNationChiefSet() {
        for (n in world.listNations().sortedBy { it.id }) {
            val nextMeta = LinkedHashMap(n.meta)
            nextMeta["chief_set"] = 0
            world.updateNation(n.copy(meta = nextMeta))
        }
    }

    override fun clearAllCityOfficerSet() {
        for (c in world.listCities().sortedBy { it.id }) {
            val nextMeta = LinkedHashMap(c.meta)
            nextMeta["officer_set"] = 0
            world.updateCity(c.copy(meta = nextMeta))
        }
    }

    override fun addGlobalBetray(cnt: Int, ifMax: Int) {
        for (g in world.listGenerals().sortedBy { it.id }) {
            val betray = metaInt(g.meta, "betray")
            if (betray > ifMax) continue
            world.applyGeneralDirtyFree(g.copy(meta = withMeta(g.meta, "betray" to betray + cnt)))
        }
    }

    override fun pushGlobalActionLog(msg: String) {
        world.pushLog(logDraft("global", "action", msg))
    }

    override fun pushGlobalHistoryLog(msg: String, type: Int) {
        world.pushLog(logDraft("global", "history", msg, subType = type.toString()))
    }

    override fun pushGeneralHistoryLog(msg: String, type: Int) {
        world.pushLog(logDraft("general", "history", msg, subType = type.toString()))
    }
}
