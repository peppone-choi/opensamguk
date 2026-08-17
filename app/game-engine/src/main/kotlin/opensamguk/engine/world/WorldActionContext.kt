package opensamguk.engine.world

import opensamguk.common.constants.GameConst
import opensamguk.common.constants.GameUnitConst
import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.PhpMt19937
import opensamguk.common.rng.RandUtil
import opensamguk.common.rng.serializeSeed
import opensamguk.engine.config.StatisticInsertColumns
import opensamguk.engine.turn.City as EngineCity
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.GeneralTurnSeed
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.KvKey
import opensamguk.engine.turn.LogEntryDraft
import opensamguk.engine.turn.Nation as EngineNation
import opensamguk.engine.turn.PerTurnOverlay
import opensamguk.engine.v2.V2CityIncomeContext
import opensamguk.engine.v2.V2CityIncomeNation
import opensamguk.engine.v2.V2CityIncomeResult
import opensamguk.engine.v2.V2CityLedgerEntry
import opensamguk.engine.v2.V2CityLedgerStore
import opensamguk.engine.turn.RankColumn
import opensamguk.engine.turn.RankDelta
import opensamguk.engine.turn.TurnDiplomacy
import opensamguk.engine.turn.TurnGeneral
import opensamguk.engine.turn.Troop
import opensamguk.engine.turn.toTurnGeneral
import opensamguk.infra.persistence.MetaJson
import opensamguk.infra.read.ArchiveHistoryReader
import opensamguk.infra.read.AuctionBidRepository
import opensamguk.infra.read.AuctionRepository
import opensamguk.infra.read.BettingRepository
import opensamguk.infra.read.GameKvRepository
import opensamguk.infra.read.InheritanceRepository
import opensamguk.infra.read.StatisticSnapshotReader
import opensamguk.logic.auction.AuctionInfo
import opensamguk.logic.auction.AuctionType
import opensamguk.logic.auction.ResourceType
import opensamguk.logic.betting.BettingInfo
import opensamguk.logic.betting.BettingItem
import opensamguk.logic.betting.BettingWorldView
import opensamguk.logic.betting.GeneralForBetting
import opensamguk.logic.domain.City as LogicCity
import opensamguk.logic.domain.Diplomacy as LogicDiplomacy
import opensamguk.logic.domain.General as LogicGeneral
import opensamguk.logic.domain.Nation as LogicNation
import opensamguk.logic.domain.NationTurn
import opensamguk.logic.domain.metaDouble
import opensamguk.logic.domain.metaInt
import opensamguk.logic.domain.withMeta
import opensamguk.logic.event.DeleteEventContext
import opensamguk.logic.event.EventDispatcher
import opensamguk.logic.event.EventTarget
import opensamguk.logic.event.EventActionContext
import opensamguk.logic.event.EventStore
import opensamguk.logic.event.FinishNationBettingContext
import opensamguk.logic.event.LightActionWorld
import opensamguk.logic.event.NationBettingCandidate
import opensamguk.logic.event.OpenNationBettingContext
import opensamguk.logic.inheritance.GeneralProxy
import opensamguk.logic.inheritance.InheritancePointStore
import opensamguk.logic.inheritance.applyInheritanceUser
import opensamguk.logic.inheritance.mergeTotalInheritancePoint
import opensamguk.logic.message.MessageTarget
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.tick.CheckStatisticCalculator
import opensamguk.logic.tick.ServerClock
import opensamguk.logic.traits.NationTypeRegistry
import opensamguk.logic.util.phpRound
import opensamguk.logic.util.phpRoundDecimal
import opensamguk.logic.util.jsonDecode
import opensamguk.logic.util.jsonDecodeAny
import java.text.NumberFormat
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import opensamguk.logic.world.AssignSpecialityResult
import opensamguk.logic.world.BlockScoutWorld
import opensamguk.logic.world.BuiltGeneral
import opensamguk.logic.world.CityConstVariant
import opensamguk.logic.world.CityConstRegistry
import opensamguk.logic.world.CityLevel
import opensamguk.logic.world.CheckEmperiorContext
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
import opensamguk.logic.world.GeneralBuilder
import opensamguk.logic.world.ProcessIncomeContext
import opensamguk.logic.world.ProcessIncomeResult
import opensamguk.logic.world.ProcessSemiAnnualContext
import opensamguk.logic.world.ProcessSemiAnnualResult
import opensamguk.logic.world.ProcessWarIncomeContext
import opensamguk.logic.world.ProcessWarIncomeResult
import opensamguk.logic.world.ProvideNPCTroopLeaderContext
import opensamguk.logic.world.ProvideNPCTroopLeader
import opensamguk.logic.world.RandomizeCityTradeRateContext
import opensamguk.logic.world.RaiseDisasterResult
import opensamguk.logic.world.RaiseInvaderAction
import opensamguk.logic.world.RaiseInvaderContext
import opensamguk.logic.world.RaiseInvaderSpec
import opensamguk.logic.world.RaiseNPCNationAction
import opensamguk.logic.world.ScenarioStartEventContext
import opensamguk.logic.world.SpecialityHelper
import opensamguk.logic.world.SpecialityGeneral
import opensamguk.logic.world.SpecialityWorldView
import opensamguk.logic.world.SupplyCapital
import opensamguk.logic.world.UnblockScoutWorldView
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
    private val auctionRepository: AuctionRepository? = null,
    private val auctionBidRepository: AuctionBidRepository? = null,
    private val archiveHistoryReader: ArchiveHistoryReader? = null,
    private val statisticSnapshotReader: StatisticSnapshotReader? = null,
    private val gameKvRepository: GameKvRepository? = null,
    private val bettingRepository: BettingRepository? = null,
    private val inheritanceRepository: InheritanceRepository? = null,
    private val ambientPhpRandom: PhpMt19937 = PhpMt19937.ambient(),
    private val lockGame: () -> Boolean = { false },
    private val unlockGame: () -> Unit = {},
    // OPENSAM-151 — v2 도시 원장. v2 샌드박스 게이트가 꺼진 프로덕션에서는 null이고, 그 상태에서
    // V2ProcessCityIncome leaf가 돌면 fail-closed로 죽는다(무음 no-op이면 수입이 통째로 사라진다).
    private val v2CityLedger: V2CityLedgerStore? = null,
) : EventActionContext,
    ProcessIncomeContext,
    V2CityIncomeContext,
    ProcessWarIncomeContext,
    RandomizeCityTradeRateContext,
    ProcessSemiAnnualContext,
    MergeInheritWorld,
    UpdateCitySupplyContext,
    UpdateNationLevelContext,
    ProvideNPCTroopLeaderContext,
    DisasterWorldView,
    SpecialityWorldView,
    BlockScoutWorld,
    UnblockScoutWorldView,
    RaiseInvaderContext,
    InvaderEndingContext,
    OpenNationBettingContext,
    FinishNationBettingContext,
    CheckEmperiorContext,
    ScenarioStartEventContext,
    LightActionWorld {

    // ── helpers ────────────────────────────────────────────────────────────────────────────────

    companion object {
        const val ENV_EVENT_DISPATCHER = "eventDispatcher"
        private const val INTERNAL_FLUSH_BEFORE_ARCHIVE = "_flushBeforeArchive"
        private const val MAX_GENERALS_PER_MINUTE = 1000
        private val INVADER_TURNTERM_CANDIDATES = listOf(1, 2, 5, 10, 20, 30, 60, 120)
        private val SEOUL_ZONE: ZoneId = ZoneId.of("Asia/Seoul")
        private val PHP_DATETIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    }

    private var bufferedUnificationHistoryLogIndex: Int? = null

    private fun resolveHiddenSeed(): String = world.getState().meta["hiddenSeed"] as? String ?: ""
    private fun resolveYear(): Int = (env["year"] as? Number)?.toInt() ?: world.getState().currentYear
    private fun resolveMonth(): Int = (env["month"] as? Number)?.toInt() ?: world.getState().currentMonth
    private fun resolvePhase(): Int = (env["phase"] as? Number)?.toInt() ?: world.getState().currentPhase
    private fun resolveStartYear(): Int = (world.getState().meta["startYear"] as? Number)?.toInt() ?: 0
    private fun resolveKillturnEnv(): Int = (world.getState().meta["killturn"] as? Number)?.toInt() ?: 0
    private fun resolveTurnterm(): Int = (world.getState().meta["turnterm"] as? Number)?.toInt() ?: 1
    private fun activeCityConst(): CityConstVariant {
        val fromEnv = env["cityConst"] as? CityConstVariant
        if (fromEnv != null) return fromEnv
        val mapName = world.getState().meta["map"] as? String ?: "che"
        return CityConstRegistry.find(mapName) ?: CityConstRegistry.of("che")
    }

    private fun logDraft(
        scope: String,
        category: String,
        text: String,
        generalId: Int? = null,
        nationId: Int? = null,
        userId: Int? = null,
        subType: String? = null,
        flushBeforeArchive: Boolean = false,
    ): LogEntryDraft = LogEntryDraft(
        scope = scope,
        category = category,
        text = text,
        generalId = generalId,
        nationId = nationId,
        userId = userId,
        subType = subType,
        meta = if (flushBeforeArchive) mapOf(INTERNAL_FLUSH_BEFORE_ARCHIVE to true) else null,
        year = resolveYear(),
        month = resolveMonth(),
        phase = resolvePhase(),
    )

    private fun actionLogText(text: String, formatType: Int): String =
        when (formatType) {
            0 -> text
            1 -> "<C>●</>$text"
            2 -> "<C>●</>${resolveYear()}년 ${resolveMonth()}월:$text"
            3 -> "<C>●</>${resolveYear()}년:$text"
            4 -> "<C>●</>${resolveMonth()}월:$text"
            5 -> "<S>◆</>$text"
            6 -> "<S>◆</>${resolveYear()}년 ${resolveMonth()}월:$text"
            7 -> "<R>★</>$text"
            8 -> "<R>★</>${resolveYear()}년 ${resolveMonth()}월:$text"
            else -> text
        }

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
        activeCityConst()

    /** [UpdateNationLevelContext], [ProvideNPCTroopLeaderContext]. */
    override fun nations(): List<opensamguk.logic.domain.Nation> =
        world.listNations().sortedBy { it.id }.map { PerTurnOverlay.toLogicNation(it) }

    override fun generalNames(): List<String> = world.listGenerals().map { it.name }

    override fun shuffleNpcNationCandidates(cities: List<LogicCity>): List<LogicCity> {
        // PHP RaiseNPCNation uses ambient Util::shuffle_assoc outside the seeded action RandUtil.
        // Sanctioned deterministic divergence: preserve the action stream boundary and replayability;
        // do not claim byte-identical PHP ambient permutation parity.
        val seed = serializeSeed(resolveHiddenSeed(), RaiseNPCNationAction.NAME, resolveYear(), resolveMonth())
        return RandUtil(LiteHashDrbg(seed)).shuffle(cities)
    }

    override fun allocateNationId(): Int = world.allocateNationId()

    override fun stageGeneral(general: BuiltGeneral): Int {
        val id = world.allocateGeneralId()
        recorder.recordGeneralCreate(world, general.toTurnGeneral(id, world.getState()))
        return id
    }

    override fun stageNation(nation: LogicNation) {
        world.createNation(PerTurnOverlay.toEngineNation(nation))
    }

    override fun stageDiplomacy(diplomacy: LogicDiplomacy) {
        world.createDiplomacy(PerTurnOverlay.toEngineDiplomacy(diplomacy))
    }

    override fun stageNationTurn(turn: NationTurn) {
        world.createNationTurn(turn)
    }

    override fun stageCity(city: LogicCity) {
        val pre = world.getCityById(city.id) ?: return
        recorder.diffCity(PerTurnOverlay.toLogicCity(pre), city)
        val nextMeta = LinkedHashMap(pre.meta)
        nextMeta["trust"] = city.trust
        world.applyCityDirtyFree(
            pre.copy(
                nationId = city.nationId,
                level = city.level,
                state = city.state,
                population = city.population,
                populationMax = city.populationMax,
                dead = city.dead,
                agriculture = city.agriculture,
                agricultureMax = city.agricultureMax,
                commerce = city.commerce,
                commerceMax = city.commerceMax,
                security = city.security,
                securityMax = city.securityMax,
                supplyState = city.supplyState,
                frontState = city.frontState,
                defence = city.defense,
                defenceMax = city.defenseMax,
                wall = city.wall,
                wallMax = city.wallMax,
                trade = city.trade,
                region = city.region,
                term = city.term,
                officerSet = city.officerSet,
                conflict = city.conflict,
                meta = nextMeta,
            ),
        )
    }

    override fun stageNationEnv(nationId: Int, key: String, value: Any?) {
        recorder.recordNationEnvKv(nationId, key, value)
    }

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
                generals = world.listGenerals().filter { it.nationId == n.id && it.npcState != 5 }.sortedBy { it.id }
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

    // ── V2CityIncomeContext (OPENSAM-151) ──────────────────────────────────────────────────────

    private fun requireV2Ledger(): V2CityLedgerStore = v2CityLedger
        ?: error("v2 도시 원장 스토어가 없다 — v2 샌드박스 게이트 밖에서 V2ProcessCityIncome 이 디스패치됐다")

    override fun v2CityIncomeNations(resource: String): List<V2CityIncomeNation> {
        val ledger = requireV2Ledger().entries(world.worldId)
        // 국가/도시/장수 스냅샷은 v1 [incomeNations]를 **그대로** 재사용한다(세율 rate_tmp, npcState!=5 제외,
        // officerCntByCity 집계까지 동일해야 하므로 두 벌로 갈라 두지 않는다).
        return incomeNations().map { n ->
            V2CityIncomeNation(
                nation = n,
                generalCityIds = n.generals.mapNotNull { g -> world.getGeneralById(g.id)?.let { g.id to it.cityId } }.toMap(),
                ledger = n.cities.associate { c ->
                    val e = ledger[c.id] ?: V2CityLedgerEntry.EMPTY
                    c.id to (if (resource == "gold") e.gold else e.rice)
                },
            )
        }
    }

    override fun applyV2CityIncome(result: V2CityIncomeResult) {
        val resource = result.resource
        val store = requireV2Ledger()
        // v1과 달리 nation.gold/rice 는 건드리지 않는다 — v2에서 수입은 도시 원장에만 들어간다.
        for (d in result.ledgerDeltas) {
            if (resource == "gold") {
                store.adjust(world.worldId, recorder, d.cityId, goldDelta = d.delta)
            } else {
                store.adjust(world.worldId, recorder, d.cityId, riceDelta = d.delta)
            }
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

    override fun lastNpcTroopLeaderId(): Int {
        val key = "lastNPCTroopLeaderID"
        val pending = recorder.kvDirty()[KvKey("game_env", "game_env", key)] as? Number
        if (pending != null) return pending.toInt()
        val persisted = gameKvRepository?.findByTable("game_env")?.firstNotNullOfOrNull { row ->
            if (row.namespace == "game_env" && row.key == key) {
                runCatching { jsonDecodeAny(row.value) }.getOrNull() as? Number
            } else {
                null
            }
        }
        return persisted?.toInt() ?: 0
    }

    override fun setLastNpcTroopLeaderId(id: Int) {
        recorder.recordKv("game_env", "game_env", "lastNPCTroopLeaderID", id)
        env["lastNPCTroopLeaderID"] = id
    }

    override fun mintTroopLeaders(
        nationId: Int,
        leaders: List<ProvideNPCTroopLeader.NewLeader>,
        seed: String,
    ) {
        val rng = RandUtil(LiteHashDrbg(seed))
        val cityPool = world.listCities().sortedBy { it.id }
            .map { GeneralBuilder.CityChoice(it.id, it.nationId) }
        val initialTurns = List(GameConst.maxTurn) {
            GeneralTurnSeed(actionCode = "che_집합", argJson = "{}", brief = "집합")
        }
        for (leader in leaders) {
            val built = GeneralBuilder(rng, leader.name, nationId)
                .setAffinity(999)
                .setStat(10, 10, 10)
                .setSpecialSingle(null)
                .setEgo("che_은둔")
                .setSpecYear(999, 999)
                .setKillturn(70)
                .setMoney(0, 0)
                .setNPCType(5)
                .fillRemainSpecAsZero(resolveYear(), resolveStartYear())
                .build(
                    year = resolveYear(),
                    month = resolveMonth(),
                    turnterm = resolveTurnterm(),
                    cityPool = cityPool,
                )
                ?: continue
            val generalId = world.allocateGeneralId()
            val general = built.toTurnGeneral(generalId, world.getState()).copy(troopId = generalId)
            recorder.recordGeneralCreate(world, general, initialTurns)
            world.createTroop(Troop(id = generalId, nationId = nationId, name = general.name))
        }
    }

    override fun nationBettingCandidates(): List<NationBettingCandidate> =
        world.listNations().map { nation ->
            val generalCount = world.listGenerals().count { it.nationId == nation.id }
            val cityCount = world.listCities().count { it.nationId == nation.id }
            NationBettingCandidate(
                nationId = nation.id,
                name = nation.name,
                power = nation.power,
                generalCount = generalCount,
                cityCount = cityCount,
                aux = linkedMapOf(
                    "nation" to nation.id,
                    "name" to nation.name,
                    "color" to nation.color,
                    "level" to nation.level,
                    "type" to nation.typeCode,
                    "capital" to (nation.capitalCityId ?: 0),
                    "gennum" to generalCount,
                    "power" to nation.power,
                    "city_cnt" to cityCount,
                ),
            )
        }

    override fun nextBettingId(): Int {
        val pending = (recorder.kvDirty()[KvKey("game_env", "game_env", "last_betting_id")] as? Number)?.toInt()
        val persisted = gameKvRepository?.findByTable("game_env")?.firstNotNullOfOrNull { row ->
            if (row.namespace == "game_env" && row.key == "last_betting_id") {
                (runCatching { jsonDecodeAny(row.value) }.getOrNull() as? Number)?.toInt()
            } else {
                null
            }
        }
        val next = maxOf(pending ?: 0, persisted ?: 0) + 1
        recorder.recordKv("game_env", "game_env", "last_betting_id", next)
        return next
    }

    override fun saveBettingInfo(info: BettingInfo) {
        recorder.recordKv("betting", "betting", "id_${info.id}", info.toKvMap())
    }

    override fun scheduleNationBettingFinish(bettingId: Int, nationCnt: Int) {
        val store = env[DeleteEventContext.ENV_KEY] as? EventStore
            ?: error("OpenNationBetting requires the live EventStore")
        store.insertRaw(
            targetCode = "destroy_nation",
            priority = 1000,
            conditionJson = kotlinx.serialization.json.Json.parseToJsonElement(
                """["RemainNation","<=",$nationCnt]""",
            ),
            actionJson = kotlinx.serialization.json.Json.parseToJsonElement(
                """[["FinishNationBetting",$bettingId],["DeleteEvent"]]""",
            ),
        )
    }

    override fun placeNationBettingBonus(bettingId: Int, amount: Int) {
        recorder.recordBettingInsert(
            linkedMapOf(
                "betting_id" to bettingId,
                "general_id" to 0,
                "user_id" to null,
                "betting_type" to "[-1]",
                "amount" to amount,
            ),
        )
    }

    override fun notifyNationBettingOpened(name: String) {
        val text = "새로운 $name 내기가 열렸습니다. 천통국 베팅란을 확인해주세요."
        world.listGenerals()
            .filter { it.npcState <= 1 }
            .forEach { general ->
                markNewMessage(general)
                val nation = world.getNationById(general.nationId)
                val dest = MessageTarget(
                    generalId = general.id,
                    generalName = general.name,
                    nationId = general.nationId,
                    nationName = nation?.name ?: "재야",
                    color = nation?.color ?: "#000000",
                    icon = general.meta["picture"]?.toString() ?: "",
                )
                val body = MetaJson.encode(
                    linkedMapOf(
                        "src" to MessageTarget.buildSystemTarget().toArray(),
                        "dest" to dest.toArray(),
                        "text" to text,
                        "option" to linkedMapOf<String, Any?>(),
                    ),
                )
                recorder.recordMessageInsert(
                    mailbox = general.id,
                    type = "private",
                    srcId = 0,
                    destId = general.id,
                    time = world.getState().lastTurnTime.atZone(SEOUL_ZONE).format(PHP_DATETIME_FORMAT),
                    validUntil = "9999-12-31 00:00:00",
                    bodyJson = body,
                )
            }
    }

    override fun loadBettingInfo(bettingId: Int): BettingInfo? {
        val pending = recorder.kvDirty()[KvKey("betting", "betting", "id_$bettingId")] as? Map<*, *>
        if (pending != null) {
            @Suppress("UNCHECKED_CAST")
            return BettingInfo.fromKvMap(pending as Map<String, Any?>)
        }
        return gameKvRepository?.findByTable("betting")?.firstNotNullOfOrNull { row ->
            runCatching { jsonDecode(row.value) }.getOrNull()
                ?.let(BettingInfo::fromKvMap)
                ?.takeIf { it.id == bettingId }
        }
    }

    override fun aliveNationIds(): List<Int> =
        world.listNations().filter { it.level > 0 }.map { it.id }

    override fun loadBettingItems(bettingId: Int): List<BettingItem> =
        bettingRepository?.findByBettingId(bettingId).orEmpty().map { row ->
            BettingItem(
                rowId = row.id,
                bettingId = row.bettingId,
                generalId = row.generalId,
                userId = row.userId,
                bettingType = row.bettingType,
                amount = row.amount,
            )
        }

    override fun generalsById(ids: List<Int>): Map<Int, GeneralForBetting> =
        ids.mapNotNull { id ->
            world.getGeneralById(id)?.let { general ->
                id to GeneralForBetting(id, general.npcState, general.name)
            }
        }.toMap()

    override fun addGeneralGold(generalId: Int, amount: Int) {
        val before = world.getGeneralById(generalId) ?: return
        val after = before.copy(gold = before.gold + amount)
        recorder.diffGeneral(PerTurnOverlay.toLogicGeneral(before), PerTurnOverlay.toLogicGeneral(after))
        world.updateGeneral(after)
    }

    override fun increaseRankData(generalId: Int, type: String, amount: Double) {
        val column = RankColumn.byColumn(type) ?: return
        recorder.recordRankIncrease(generalId, column, phpRound(amount))
    }

    override fun getRankVar(generalId: Int, type: String, default: Int): Int =
        world.getGeneralById(generalId)?.let { effectiveRankValue(it, type) } ?: default

    override fun increaseInheritancePointRaw(userId: Int, amount: Double): Double {
        val pending = recorder.effectiveInheritancePoint(userId, "previous")?.first
        val persisted = inheritanceRepository
            ?.findByTableAndNamespaceAndKey("inheritance", "inheritance_$userId", "previous")
            ?.let { row ->
                ((runCatching { jsonDecodeAny(row.value) }.getOrNull() as? List<*>)?.getOrNull(0) as? Number)
                    ?.toDouble()
            }
        val next = (pending ?: persisted ?: 0.0) + amount
        recorder.recordInheritancePointSet(userId, "previous", next, null)
        return next
    }

    override fun pushUserLogs(userId: Int, lines: List<String>, type: String) {
        lines.forEach { recorder.recordInheritanceLog(userId, it, type) }
    }

    override fun pushGeneralActionLog(generalId: Int, msg: String) {
        val nationId = world.getGeneralById(generalId)?.nationId
        world.pushLog(logDraft("general", "action", msg, generalId = generalId, nationId = nationId))
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

    override fun setAllNationScout(value: Int) {
        for (nation in world.listNations().sortedBy { it.id }) {
            val nextMeta = LinkedHashMap(nation.meta)
            nextMeta["scout"] = value
            val post = nation.copy(meta = nextMeta)
            recorder.diffNation(PerTurnOverlay.toLogicNation(nation), PerTurnOverlay.toLogicNation(post))
            world.updateNation(post)
        }
    }

    override fun setBlockChangeScout(value: Boolean) {
        recorder.recordKv("game_env", "game_env", "block_change_scout", value)
    }

    override fun setGameEnvBlockChangeScout(value: Boolean) {
        setBlockChangeScout(value)
    }

    override fun raiseInvader(spec: RaiseInvaderSpec): Int {
        val invaderCities = activeCityConst().all().values.filter { it.level == 4 }
        if (invaderCities.isEmpty()) return 0

        setIsunited(1)
        val preInvaderGenerals = world.listGenerals()
        val ordinaryGenerals = preInvaderGenerals.filter { it.npcState < 4 }
        val npcEachCount = if (spec.npcEachCount < 0) {
            ordinaryGenerals.size.toDouble() / invaderCities.size * -spec.npcEachCount
        } else {
            spec.npcEachCount
        }.toInt().coerceAtLeast(10)
        val totalGenerals = npcEachCount * invaderCities.size + preInvaderGenerals.size
        val actionTurnterm = resolveTurnterm()
        if (totalGenerals > MAX_GENERALS_PER_MINUTE * actionTurnterm) {
            val nextTurnterm = INVADER_TURNTERM_CANDIDATES.firstOrNull {
                totalGenerals <= MAX_GENERALS_PER_MINUTE * it
            }
            if (nextTurnterm != null) {
                changeInvaderTurnterm(actionTurnterm, nextTurnterm)
            }
        }
        val specAverage = if (spec.specAvg < 0) {
            ordinaryGenerals
                .map { it.stats.leadership + it.stats.strength + it.stats.intelligence }
                .averageIntOrZero() * -spec.specAvg
        } else {
            spec.specAvg
        }.div(3.0).toInt()
        val activeNations = world.listNations().filter { it.level > 0 }
        val tech = (if (spec.tech < 0) {
            activeNations.map { it.tech }.averageDoubleOrZero() * -spec.tech
        } else {
            spec.tech
        }).toInt()
        val dex = if (spec.dex < 0) {
            ordinaryGenerals.map { general ->
                (1..5).sumOf { index -> (general.meta["dex$index"] as? Number)?.toDouble() ?: 0.0 } / 5.0
            }.averageDoubleOrZero() * -spec.dex
        } else {
            spec.dex
        }.toInt()
        val averageExperience = preInvaderGenerals
            .filter { it.npcState < 6 }
            .map { it.experience.toDouble() }
            .averageDoubleOrZero()
            .toInt()
        val rng = RandUtil(
            LiteHashDrbg(serializeSeed(resolveHiddenSeed(), RaiseInvaderAction.NAME, resolveYear(), resolveMonth())),
        )

        for (nation in world.listNations().sortedBy { it.id }) {
            val meta = LinkedHashMap(nation.meta).apply {
                this["war"] = 0
                this["scout"] = 0
            }
            updateNationForInvader(nation, nation.copy(meta = meta))
        }

        val invaderCityIds = invaderCities.map { it.id }.toSet()
        val disabledCities = LinkedHashSet<Int>()
        for (nation in world.listNations().sortedBy { it.id }) {
            val oldCapital = nation.capitalCityId ?: continue
            if (oldCapital !in invaderCityIds) continue
            val candidates = world.listCities()
                .filter { it.nationId == nation.id && it.id != oldCapital && it.id !in invaderCityIds }
                .sortedBy { it.id }
            if (candidates.isEmpty()) {
                disabledCities += oldCapital
                continue
            }
            val newCapital = rng.choice(candidates).id
            updateNationForInvader(nation, nation.copy(capitalCityId = newCapital))
            world.listGenerals()
                .filter { it.nationId == nation.id && it.cityId == oldCapital }
                .forEach { general -> updateGeneralForInvader(general, general.copy(cityId = newCapital)) }
        }
        world.listGenerals()
            .filter { ((it.meta["officer_city"] as? Number)?.toInt() ?: 0) in invaderCityIds }
            .forEach { general ->
                updateGeneralForInvader(
                    general,
                    general.copy(
                        officerLevel = 1,
                        meta = LinkedHashMap(general.meta).apply { this["officer_city"] = 0 },
                    ),
                )
            }
        for (cityId in invaderCityIds) {
            val city = world.getCityById(cityId) ?: continue
            updateCityForInvader(city, city.copy(nationId = 0, frontState = 0, supplyState = 1))
        }
        world.listGenerals().sortedBy { it.id }.forEach { general ->
            updateGeneralForInvader(general, general.copy(gold = 999_999, rice = 999_999))
        }

        val existingNationIds = world.listNations().map { it.id }.toList()
        val createdNationIds = ArrayList<Int>()
        val cityPool = world.listCities().map { GeneralBuilder.CityChoice(it.id, it.nationId) }
        for (cityConst in invaderCities) {
            if (cityConst.id in disabledCities) continue
            val nationId = world.allocateNationId()
            val nationName = "ⓞ${cityConst.name}족"
            val nationMeta = linkedMapOf<String, Any?>(
                "gennum" to npcEachCount + 1,
                "bill" to 100,
                "rate" to 15,
                "scout" to 0,
                "war" to 0,
                "strategic_cmd_limit" to 24,
                "surlimit" to 72,
                "scout_msg" to "중원의 부패를 물리쳐라! 이민족 침범!",
                "aux" to linkedMapOf("can_국기변경" to 1),
            )
            world.createNation(
                EngineNation(
                    id = nationId,
                    name = nationName,
                    color = "#800080",
                    capitalCityId = cityConst.id,
                    gold = 9_999_999,
                    rice = 9_999_999,
                    tech = tech.toDouble(),
                    level = 2,
                    typeCode = "che_병가",
                    meta = nationMeta,
                ),
            )
            updateCityForInvader(
                checkNotNull(world.getCityById(cityConst.id)),
                checkNotNull(world.getCityById(cityConst.id)).copy(nationId = nationId),
            )

            val rulerBuilt = GeneralBuilder(rng, "${cityConst.name}대왕", nationId)
                .setEgo("che_패권")
                .setSpecial("che_인덕", "che_척사")
                .setLifeSpan(resolveYear() - 20, resolveYear() + 20)
                .setCityID(cityConst.id)
                .setNPCType(9)
                .setStat((specAverage * 1.8).toInt(), (specAverage * 1.8).toInt(), (specAverage * 1.2).toInt())
                .setAffinity(999)
                .setExpDed((averageExperience * 1.2).toInt(), null)
                .setMoney(99_999, 99_999)
                .setSpecYear(0, 0)
                .build(resolveYear(), resolveMonth(), actionTurnterm, cityPool)
                ?: error("RaiseInvader ruler was not created")
            val rulerId = world.allocateGeneralId()
            recorder.recordGeneralCreate(
                world,
                rulerBuilt.toInvaderTurnGeneral(rulerId).copy(officerLevel = 12),
            )

            for (index in 1..npcEachCount) {
                val leadership = rng.nextRangeInt((specAverage * 1.2).toInt(), (specAverage * 1.4).toInt())
                val mainStat = rng.nextRangeInt((specAverage * 1.2).toInt(), (specAverage * 1.4).toInt())
                val subStat = specAverage * 3 - leadership - mainStat
                val builder = GeneralBuilder(rng, "${cityConst.name}장수$index", nationId)
                    .setEgo("che_패권")
                    .setSpecial("che_인덕", "che_척사")
                    .setLifeSpan(resolveYear() - 20, resolveYear() + 20)
                    .setCityID(cityConst.id)
                    .setNPCType(9)
                    .setAffinity(999)
                    .setExpDed(averageExperience, null)
                    .setMoney(99_999, 99_999)
                if (rng.nextBit()) {
                    val dexTable = phpShuffle(listOf(dex * 2, dex, dex))
                    builder.setStat(leadership, mainStat, subStat)
                        .setDex(dexTable[0], dexTable[1], dexTable[2], dex, 0)
                } else {
                    builder.setStat(leadership, subStat, mainStat)
                        .setDex(dex, dex, dex, dex * 2, 0)
                }
                val built = builder
                    .setSpecYear(0, 0)
                    .build(resolveYear(), resolveMonth(), actionTurnterm, cityPool)
                    ?: continue
                recorder.recordGeneralCreate(world, built.toInvaderTurnGeneral(world.allocateGeneralId()))
            }
            for (chiefLevel in 12 downTo GameConst.getNationChiefLevel(2)) {
                for (turnIndex in 0 until GameConst.maxChiefTurn) {
                    world.createNationTurn(
                        NationTurn(nationId, chiefLevel, turnIndex, "휴식", null, "휴식"),
                    )
                }
            }
            createdNationIds += nationId
            scheduleInvaderEvent("""[["AutoDeleteInvader",$nationId]]""")
        }
        scheduleInvaderEvent("""[["InvaderEnding"]]""")

        val allNationIds = existingNationIds + createdNationIds
        for (from in allNationIds) {
            for (to in allNationIds) {
                if (from == to) continue
                val desired = when {
                    from in createdNationIds && to in createdNationIds -> 7 to 480
                    (from in existingNationIds && to in createdNationIds) ||
                        (from in createdNationIds && to in existingNationIds) -> 1 to 24
                    else -> continue
                }
                upsertDiplomacyForInvader(from, to, desired.first, desired.second)
            }
        }

        val cityMaxPopulation = specAverage * npcEachCount * 100 * 4
        for (city in world.listCities().sortedBy { it.id }) {
            val invaderOwned = city.nationId in createdNationIds
            val after = city.copy(
                populationMax = if (invaderOwned) cityMaxPopulation else city.populationMax,
                defenceMax = if (invaderOwned) 100_000 else city.defenceMax,
                wallMax = if (invaderOwned) 10_000 else city.wallMax,
                population = if (invaderOwned) cityMaxPopulation else city.populationMax,
                agriculture = city.agricultureMax,
                commerce = city.commerceMax,
                security = city.securityMax,
            )
            updateCityForInvader(city, after)
        }
        pushGlobalHistoryLog("<L><b>【이벤트】</b></>각지의 이민족들이 <M>궐기</>합니다!")
        pushGlobalHistoryLog("<L><b>【이벤트】</b></>중원의 전 국가에 <M>선전포고</> 합니다!")
        pushGlobalHistoryLog("<L><b>【이벤트】</b></>이민족의 기세는 그 누구도 막을 수 없을듯 합니다!")
        recorder.recordKv("game_env", "game_env", "block_change_scout", false)
        world.setGameEnvValue("block_change_scout", false)
        unlockGame()
        return createdNationIds.size
    }

    // ── InvaderEndingContext ───────────────────────────────────────────────────────────────────
    /** `$gameStor->isunited`(php:22) — game_env isunited(0=평시,1=침략자 진행,2=천하통일,3=엔딩). */
    override fun isunited(): Int = (world.getState().meta["isunited"] as? Number)?.toInt() ?: 0

    /** `SELECT count(*) FROM nation`(InvaderEnding.php:25) — level 필터 없는 전 국가 수. */
    override fun nationCount(): Int = world.listNations().size

    /** `SELECT count(*) FROM city WHERE nation = 0`(php:36) — 공백지(소유국가 0) 도시 수. */
    override fun neutralCityCount(): Int = world.listCities().count { it.nationId == 0 }

    /** `count(CityConst::all())`(php:44 / checkEmperior php:716-723) — loaded-row count가 아니라
     *  active scenario map의 static city count를 쓴다. 부분 로딩/누락 시 조기 통일을 막는다. */
    override fun totalCityCount(): Int = activeCityConst().all().size

    /** `SELECT name FROM nation LIMIT 1`(php:39) — ORDER BY 없는 LIMIT 1 = 삽입(=PK) 순서 첫 행.
     *  in-memory 아날로그는 listNations()의 첫 원소(LinkedHashMap PK 삽입 순서). 빈 결과면 null. */
    override fun firstNationName(): String? = world.listNations().firstOrNull()?.name

    /** `ActionLogger::pushGlobalHistoryLog`(php:54-60) — InvaderEndingContext 의 무-type 시그니처.
     *  LightActionWorld.pushGlobalHistoryLog(msg, type)와 arity가 달라 별도 override. */
    override fun pushGlobalHistoryLog(msg: String) {
        world.pushLog(logDraft("global", "history", actionLogText(msg, LightActionWorld.YEAR_MONTH)))
    }

    override fun pushPreformattedGlobalHistoryLog(msg: String) {
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

    override fun activeNationIds(): List<Int> =
        world.listNations().filter { it.level > 0 }.map { it.id }

    override fun cityCountOf(nationId: Int): Int =
        world.listCities().count { it.nationId == nationId }

    override fun nationName(nationId: Int): String? = world.getNationById(nationId)?.name

    override fun pushNationalHistoryLog(nationId: Int, msg: String) {
        val draft = logDraft("nation", "history", actionLogText(msg, LightActionWorld.YEAR_MONTH), nationId = nationId)
        bufferedUnificationHistoryLogIndex = world.peekLogs().size
        world.pushLog(draft)
    }

    override fun checkStatistic() {
        val row = CheckStatisticCalculator.compute(
            year = resolveYear(),
            month = resolveMonth(),
            generals = world.listGenerals().map { statisticGeneral(it) },
            nations = world.listNations().map { PerTurnOverlay.toLogicNation(it) },
            cities = world.listCities().map { PerTurnOverlay.toLogicCity(it) },
            nationTypeNameOf = ::nationTypeDisplayName,
            personalityNameOf = { GameConst.personalityNameOf(it.toString()) },
            specialDomesticNameOf = { SpecialityHelper.domesticName(it) },
            specialWarNameOf = { SpecialityHelper.warName(it) },
            crewtypeShortNameOf = { GameUnitConst.byId(it)?.name ?: "$it" },
        )
        recorder.recordStatisticInsert(StatisticInsertColumns.from(row))
    }

    private fun statisticGeneral(g: TurnGeneral): LogicGeneral {
        val statisticMeta = g.meta +
            mapOf(
                "personal" to (g.role.personality ?: g.meta["personal"] ?: g.meta["personal_code"] ?: "None"),
                "special" to (g.meta["special"] ?: g.meta["special_code"] ?: "None"),
                "special2" to (g.role.specialWar ?: g.meta["special2"] ?: g.meta["special2_code"] ?: "None"),
            ) +
            if (g.recentWarTime != null) mapOf("recent_war" to g.recentWarTime.toString()) else emptyMap()
        return PerTurnOverlay.toLogicGeneral(g).copy(meta = statisticMeta)
    }

    override fun closeActiveUniqueAuctions() {
        val repo = auctionRepository ?: return
        val activeAuctions = repo.findByFinishedFalseAndTypeValue(AuctionType.UNIQUE_ITEM.value)
            .sortedBy { it.closeDate }
        if (activeAuctions.isEmpty()) return
        val bidRepo = requireNotNull(auctionBidRepository) {
            "AuctionBidRepository is required to rollback active unique auctions"
        }
        activeAuctions.forEach { entity ->
            val auctionId = entity.id ?: return@forEach
            val info = AuctionInfo(
                id = auctionId,
                type = entity.type,
                finished = true,
                target = entity.target,
                hostGeneralId = entity.hostGeneralId,
                reqResource = entity.reqResource,
                openDate = entity.openDate.toString(),
                closeDate = entity.closeDate.toString(),
                detail = AuctionInfo.fromArray(
                    linkedMapOf(
                        "type" to entity.type.value,
                        "req_resource" to entity.reqResource.value,
                        "open_date" to entity.openDate.toString(),
                        "close_date" to entity.closeDate.toString(),
                        "detail" to entity.detail,
                    ),
                ).detail,
            )
            bidRepo.findTopByAuctionIdOrderByAmountDesc(auctionId)?.let { bid ->
                refundCancelledAuctionBid(info, bid.generalId, bid.amount)
            }
            recorder.recordAuctionUpsert(id = auctionId, columns = info.toArray(withoutId = true))
        }
    }

    private fun refundCancelledAuctionBid(info: AuctionInfo, generalId: Int, amount: Int) {
        val bidder = world.getGeneralById(generalId) ?: return
        when (info.reqResource) {
            ResourceType.INHERITANCE_POINT -> {
                val ownerId = bidder.userId?.toIntOrNull()
                val isUnited = ((world.getState().meta["isunited"] as? Number)?.toInt() ?: 0) != 0
                if (ownerId != null && bidder.npcState < 2 && !isUnited) {
                    recorder.recordInheritancePointIncrease(ownerId, "previous", amount.toDouble(), null)
                }
                recorder.recordRankIncrease(generalId, RankColumn.INHERIT_SPENT_DYN, -amount)
            }
            ResourceType.GOLD -> updateRefundedResource(bidder, bidder.copy(gold = bidder.gold + amount))
            ResourceType.RICE -> updateRefundedResource(bidder, bidder.copy(rice = bidder.rice + amount))
        }

        markNewMessage(bidder)
        val nation = world.getNationById(bidder.nationId)
        val dest = MessageTarget(
            generalId = bidder.id,
            generalName = bidder.name,
            nationId = bidder.nationId,
            nationName = nation?.name ?: "재야",
            color = nation?.color ?: "#000000",
            icon = bidder.meta["picture"]?.toString() ?: "",
        )
        val body = MetaJson.encode(
            linkedMapOf(
                "src" to MessageTarget.buildSystemTarget().toArray(),
                "dest" to dest.toArray(),
                "text" to "${info.id}번 ${info.detail.title}가 취소되었습니다.",
                "option" to linkedMapOf<String, Any?>(),
            ),
        )
        recorder.recordMessageInsert(
            mailbox = bidder.id,
            type = "private",
            srcId = 0,
            destId = bidder.id,
            time = world.getState().lastTurnTime.toString(),
            validUntil = "9999-12-31T00:00:00Z",
            bodyJson = body,
        )
    }

    private fun updateRefundedResource(before: TurnGeneral, after: TurnGeneral) {
        recorder.diffGeneral(PerTurnOverlay.toLogicGeneral(before), PerTurnOverlay.toLogicGeneral(after))
        world.updateGeneral(after)
    }

    override fun grantUnifierInheritancePoint(nationId: Int, points: Int) {
        world.listGenerals()
            .filter { it.nationId == nationId && it.npcState < 2 && it.officerLevel > 4 }
            .forEach { g ->
                g.userId?.toIntOrNull()?.let { owner ->
                    recorder.recordInheritancePointIncrease(owner, "unifier", points.toDouble(), null)
                }
            }
    }

    override fun runUnitedEvent() {
        val dispatcher = env[ENV_EVENT_DISPATCHER] as? EventDispatcher ?: return
        val factory = env["worldEventContextFactory"] as? ((MutableMap<String, Any?>) -> EventActionContext)
            ?: { mutableEnv: MutableMap<String, Any?> ->
                WorldActionContext(
                    mutableEnv,
                    world,
                    recorder,
                    pipeline,
                    auctionRepository,
                    auctionBidRepository,
                    archiveHistoryReader,
                    statisticSnapshotReader,
                )
            }
        dispatcher.run(
            target = EventTarget.UNITED,
            contextFactory = factory,
            envSupplier = { env },
        )
    }

    override fun mergeAndApplyInheritance() {
        val store = inheritanceStoreSnapshot()
        val serverID = (world.getState().meta["ngGameId"] as? Number)?.toInt()
            ?: world.archiveServerId()?.toIntOrNull()
            ?: 0
        for (general in world.listGenerals().filter { it.userId != null && it.npcState < 2 }) {
            val ownerID = general.userId?.toIntOrNull() ?: continue
            mergeTotalInheritancePoint(
                general = inheritanceGeneralProxy(general, ownerID),
                isRebirth = false,
                isUnited = false,
                year = resolveYear(),
                startYear = resolveStartYear(),
                month = resolveMonth(),
                store = store,
                serverID = serverID,
                resultSink = recorder::recordInheritanceResultSnapshot,
            )
            val previous = applyInheritanceUser(
                ownerID = ownerID,
                isRebirth = false,
                store = store,
                userLogSink = { text, tag -> recorder.recordInheritanceLog(ownerID, text, tag) },
            )
            recorder.recordInheritancePointSet(ownerID, "previous", previous.toDouble(), null)
        }
    }

    private fun inheritanceStoreSnapshot(): InheritancePointStore {
        val store = InheritancePointStore()
        val loaded = world.getState().meta["inheritancePoints"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
        for ((ownerAny, entriesAny) in loaded) {
            val ownerID = when (ownerAny) {
                is Number -> ownerAny.toInt()
                is String -> ownerAny.toIntOrNull()
                else -> null
            } ?: continue
            val entries = entriesAny as? Map<*, *> ?: continue
            for ((keyAny, valueAny) in entries) {
                val key = keyAny?.toString() ?: continue
                val pair = valueAny as? List<*> ?: continue
                val value = (pair.getOrNull(0) as? Number)?.toDouble() ?: continue
                store.putRaw(ownerID, key, value, pair.getOrNull(1))
            }
        }
        for (write in recorder.inheritanceKvWrites()) {
            if (write.table != "inheritance") continue
            val ownerID = write.namespace.removePrefix("inheritance_").toIntOrNull() ?: continue
            val pair = write.value as? List<*> ?: continue
            val value = (pair.getOrNull(0) as? Number)?.toDouble() ?: continue
            store.putRaw(ownerID, write.key, value, pair.getOrNull(1))
        }
        return store
    }

    private fun inheritanceGeneralProxy(general: TurnGeneral, ownerID: Int): GeneralProxy =
        object : GeneralProxy {
            override val id: Int = general.id
            override val owner: Int = ownerID
            override val npc: Int = general.npcState
            override fun getVar(key: String): Int = when (key) {
                "belong" -> (general.meta["belong"] as? Number)?.toInt() ?: 0
                else -> (general.meta[key] as? Number)?.toInt() ?: 0
            }
            override fun getRankVar(key: String): Int {
                val base = (general.meta[key] as? Number)?.toInt() ?: 0
                val column = RankColumn.byColumn(key) ?: return base
                return when (val delta = recorder.rankDeltas(general.id)[column]) {
                    is RankDelta.Increment -> base + delta.value
                    is RankDelta.Set -> delta.value
                    null -> base
                }
            }
            override fun getAuxVar(key: String): Any? {
                val aux = general.meta["aux"] as? Map<*, *> ?: return null
                return aux[key]
            }
        }

    override fun checkHallForEligibleUserGenerals() {
        val serverId = world.archiveServerId() ?: return
        val season = (world.getState().meta["season"] as? Number)?.toInt() ?: 0
        val scenario = (world.getState().meta["scenario"] as? Number)?.toInt() ?: 0
        val serverCnt = (world.getState().meta["serverCount"] as? Number)?.toInt() ?: 0
        val serverName = world.getState().meta["serverName"]?.toString() ?: ""
        val scenarioName = world.getState().meta["scenario_text"]?.toString()
            ?: world.getState().meta["scenarioText"]?.toString()
            ?: ""
        val startTime = world.getState().meta["starttime"]?.toString()
            ?: world.getState().meta["startTime"]?.toString()
            ?: ""
        val unitedTime = Instant.now().atZone(SEOUL_ZONE).format(PHP_DATETIME_FORMAT)
        for (g in world.listGenerals().filter { it.npcState < 2 && it.age >= GameConst.minPushHallAge }) {
            val nation = hallNationOf(g)
            for ((type, value) in hallMetricRows(g)) {
                recorder.recordHallUpsert(
                    linkedMapOf(
                        "server_id" to serverId,
                        "season" to season,
                        "scenario" to scenario,
                        "general_no" to g.id,
                        "type" to type,
                        "value" to value,
                        "owner" to g.userId,
                        "aux" to MetaJson.encode(
                            linkedMapOf(
                                "name" to g.name,
                                "nationName" to nation.name,
                                "bgColor" to nation.color,
                                "fgColor" to phpNewColor(nation.color),
                                "picture" to (g.meta["picture"] ?: "default.jpg"),
                                "imgsvr" to ((g.meta["imgsvr"] as? Number)?.toInt()
                                    ?: (g.meta["image_server"] as? Number)?.toInt()
                                    ?: 0),
                                "startTime" to startTime,
                                "unitedTime" to unitedTime,
                                "ownerName" to ownerDisplayName(g),
                                "serverID" to serverId,
                                "serverIdx" to serverCnt,
                                "serverName" to serverName,
                                "scenarioName" to scenarioName,
                            ),
                        ),
                    ),
                )
            }
        }
    }

    private fun hallMetricRows(g: TurnGeneral): List<Pair<String, Any>> {
        fun rank(type: String): Int = effectiveRankValue(g, type)
        fun natural(type: String): Int = when (type) {
            "experience" -> g.experience
            "dedication" -> g.dedication
            "dex1", "dex2", "dex3", "dex4", "dex5" -> (g.meta[type] as? Number)?.toInt() ?: 0
            else -> 0
        }
        fun fit(value: Int): Int = value.coerceAtLeast(1)
        val ttw = rank("ttw")
        val ttd = rank("ttd")
        val ttl = rank("ttl")
        val tlw = rank("tlw")
        val tld = rank("tld")
        val tll = rank("tll")
        val tsw = rank("tsw")
        val tsd = rank("tsd")
        val tsl = rank("tsl")
        val tiw = rank("tiw")
        val tid = rank("tid")
        val til = rank("til")
        val betGold = fit(rank("betgold"))
        val war = fit(rank("warnum"))
        val deathCrew = fit(rank("deathcrew"))
        val deathPerson = fit(rank("deathcrew_person"))
        val tt = fit(ttw + ttd + ttl)
        val tl = fit(tlw + tld + tll)
        val ts = fit(tsw + tsd + tsl)
        val ti = fit(tiw + tid + til)
        val calc = mapOf(
            "ttrate" to ttw.toDouble() / tt,
            "tlrate" to tlw.toDouble() / tl,
            "tsrate" to tsw.toDouble() / ts,
            "tirate" to tiw.toDouble() / ti,
            "betrate" to rank("betwingold").toDouble() / betGold,
            "winrate" to rank("killnum").toDouble() / war,
            "killrate" to rank("killcrew").toDouble() / deathCrew,
            "killrate_person" to rank("killcrew_person").toDouble() / deathPerson,
        )
        val types = listOf(
            "experience" to "natural",
            "dedication" to "natural",
            "firenum" to "rank",
            "warnum" to "rank",
            "killnum" to "rank",
            "winrate" to "calc",
            "occupied" to "rank",
            "killcrew" to "rank",
            "killrate" to "calc",
            "killcrew_person" to "rank",
            "killrate_person" to "calc",
            "dex1" to "natural",
            "dex2" to "natural",
            "dex3" to "natural",
            "dex4" to "natural",
            "dex5" to "natural",
            "ttrate" to "calc",
            "tlrate" to "calc",
            "tsrate" to "calc",
            "tirate" to "calc",
            "betgold" to "rank",
            "betwin" to "rank",
            "betwingold" to "rank",
            "betrate" to "calc",
        )
        return types.mapNotNull { (type, valueType) ->
            if ((type == "winrate" || type == "killrate") && rank("warnum") < 10) return@mapNotNull null
            if (type == "ttrate" && tt < 50) return@mapNotNull null
            if (type == "tlrate" && tl < 50) return@mapNotNull null
            if (type == "tsrate" && ts < 50) return@mapNotNull null
            if (type == "tirate" && ti < 50) return@mapNotNull null
            if (type == "betrate" && rank("betgold") < 1000) return@mapNotNull null
            val value: Any = when (valueType) {
                "natural" -> natural(type)
                "rank" -> rank(type)
                else -> calc.getValue(type)
            }
            val numericValue = when (value) {
                is Number -> value.toDouble()
                else -> value.toString().toDoubleOrNull() ?: 0.0
            }
            if (numericValue <= 0.0) null else type to value
        }
    }

    private fun hallNationOf(general: TurnGeneral): EngineNation =
        world.getNationById(general.nationId)
            ?: EngineNation(
                id = 0,
                name = "재야",
                color = "#000000",
                typeCode = GameConst.neutralNationType,
            )

    private fun ownerDisplayName(general: TurnGeneral): String? {
        val ownerId = general.userId ?: return general.meta["owner_name"]?.toString()
        val owners = world.getState().meta["ownerNames"] as? Map<*, *>
            ?: world.getState().meta["memberNames"] as? Map<*, *>
            ?: world.getState().meta["userNames"] as? Map<*, *>
        return owners?.get(ownerId)?.toString()
            ?: owners?.get(ownerId.toIntOrNull())?.toString()
            ?: general.meta["owner_name"]?.toString()
    }

    private fun nationTypeDisplayName(typeCode: String): String =
        if (typeCode == GameConst.neutralNationType || typeCode == "None") "-" else typeCode.removePrefix("che_")

    private fun phpNewColor(color: String): String =
        when (color.uppercase()) {
            "",
            "#330000",
            "#FF0000",
            "#800000",
            "#A0522D",
            "#FF6347",
            "#808000",
            "#008000",
            "#2E8B57",
            "#008080",
            "#6495ED",
            "#0000FF",
            "#000080",
            "#483D8B",
            "#7B68EE",
            "#800080",
            "#A9A9A9",
            "#000000",
            -> "#FFFFFF"
            else -> "#000000"
        }

    override fun persistUnificationArchive(nationId: Int, josaYi: String) {
        val nation = world.getNationById(nationId) ?: return
        val serverId = world.archiveServerId() ?: return
        val generals = world.listGenerals()
        val nationGenerals = generals.filter { it.nationId == nationId }
        val rankedNationGenerals = nationGenerals.sortedByDescending { it.dedication }
        val archiveHistory = archiveVisibleNationHistory(nationId)
        rankedNationGenerals.forEach {
            world.pushLog(
                logDraft(
                    "general",
                    "action",
                    actionLogText("<D><b>${nation.name}</b></>${josaYi} 전토를 통일하였습니다.", LightActionWorld.YEAR_MONTH),
                    generalId = it.id,
                    nationId = nationId,
                    userId = it.userId?.toIntOrNull(),
                    flushBeforeArchive = true,
                ),
            )
        }
        generals.filter { it.nationId == 0 }.forEach { recorder.recordOldGeneralSnapshot(it) }
        nationGenerals.forEach { recorder.recordOldGeneralSnapshot(it) }

        recorder.recordNationArchiveSnapshot(nationArchive(serverId, nation, nationGenerals, archiveHistory))
        recorder.recordNationArchiveSnapshot(
            linkedMapOf(
                "server_id" to serverId,
                "nation" to 0,
                "data" to linkedMapOf("nation" to 0, "name" to "재야", "generals" to generals.filter { it.nationId == 0 }.map { it.id }),
            ),
        )
        recorder.recordGameWinnerUpdate(serverId, nationId)
        recorder.recordEmperiorInsert(emperiorColumns(serverId, nation, rankedNationGenerals, josaYi, archiveHistory))
    }

    private fun nationArchive(
        serverId: String,
        nation: EngineNation,
        nationGenerals: List<TurnGeneral>,
        history: List<String>,
    ): Map<String, Any?> {
        val nationEnv = (nation.meta["nation_env"] as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value } ?: emptyMap()
        val aux = LinkedHashMap<String, Any?>()
        (nation.meta["aux"] as? Map<*, *>)?.forEach { (k, v) -> aux[k.toString()] = v }
        (nationEnv["max_power"] as? Map<*, *>)?.forEach { (k, v) ->
            val key = k.toString()
            if (!aux.containsKey(key)) aux[key] = v
        }
        val data = linkedMapOf<String, Any?>(
            "nation" to nation.id,
            "name" to nation.name,
            "color" to nation.color,
            "capital" to nation.capitalCityId,
            "capset" to (nation.meta["capset"] ?: 0),
            "gennum" to (nation.meta["gennum"] ?: nationGenerals.size),
            "gold" to nation.gold,
            "rice" to nation.rice,
            "bill" to (nation.meta["bill"] ?: 0),
            "rate" to (nation.meta["rate"] ?: 0),
            "rate_tmp" to (nation.meta["rate_tmp"] ?: 0),
            "secretlimit" to (nation.meta["secretlimit"] ?: 3),
            "chief_set" to (nation.meta["chief_set"] ?: 0),
            "scout" to (nation.meta["scout"] ?: 0),
            "war" to (nation.meta["war"] ?: 0),
            "strategic_cmd_limit" to (nation.meta["strategic_cmd_limit"] ?: 36),
            "surlimit" to (nation.meta["surlimit"] ?: 72),
            "tech" to nation.tech,
            "power" to nation.power,
            "spy" to jsonColumn(nation.meta["spy"] ?: emptyMap<String, Any?>()),
            "level" to nation.level,
            "type" to nation.typeCode,
            "aux" to aux,
            "generals" to nationGenerals.map { it.id },
            "msg" to ((nationEnv["nationNotice"] as? Map<*, *>)?.get("msg") ?: ""),
            "scout_msg" to nationEnv["scout_msg"],
            "history" to history,
        )
        return linkedMapOf(
            "server_id" to serverId,
            "nation" to nation.id,
            "data" to data,
        )
    }

    private fun jsonColumn(value: Any?): String = when (value) {
        is String -> value
        else -> MetaJson.encode(value)
    }

    private fun emperiorColumns(
        serverId: String,
        nation: EngineNation,
        nationGenerals: List<TurnGeneral>,
        josaYi: String,
        history: List<String>,
    ): Map<String, Any?> {
        val cities = world.listCities()
        val totalPop = cities.sumOf { it.population }
        val totalMaxPop = cities.sumOf { it.populationMax }.coerceAtLeast(1)
        val chiefs = nationGenerals.filter { it.officerLevel >= 5 }.associateBy { it.officerLevel }
        val genNames = nationGenerals.sortedByDescending { it.dedication }.joinToString(", ") { it.name }
        val statisticRows = loadedStatisticRows() + recorder.statisticInserts().map { it.columns }
        val maxNationCount = statisticRows.maxOfOrNull { statisticInt(it["nation_count"]) } ?: 1
        val maxGeneralCount = statisticRows.mapNotNull { it["gen_count"]?.toString() }.maxOrNull()
            ?: world.listGenerals().size.toString()
        val nationStat = statisticRows.firstOrNull { statisticInt(it["nation_count"]) == maxNationCount }.orEmpty()
        val latestStat = statisticRows.lastOrNull().orEmpty()
        val serverName = world.getState().meta["serverName"]?.toString() ?: ""
        val serverCnt = (world.getState().meta["serverCount"] as? Number)?.toInt() ?: 0
        fun chiefName(level: Int) = chiefs[level]?.name
        fun chiefPic(level: Int) = chiefs[level]?.meta?.get("picture")?.toString()
        return linkedMapOf(
            "phase" to "$serverName${serverCnt}기",
            "server_id" to serverId,
            "nation_count" to "1 / $maxNationCount",
            "nation_name" to (nationStat["nation_name"] ?: ""),
            "nation_hist" to (nationStat["nation_hist"] ?: ""),
            "gen_count" to "${world.listGenerals().size} / $maxGeneralCount",
            "personal_hist" to (latestStat["personal_hist"] ?: ""),
            "special_hist" to (latestStat["special_hist"] ?: ""),
            "name" to nation.name,
            "type" to nation.typeCode,
            "color" to nation.color,
            "year" to resolveYear(),
            "month" to resolveMonth(),
            "power" to nation.power,
            "gennum" to ((nation.meta["gennum"] as? Number)?.toInt() ?: nationGenerals.size),
            "citynum" to cityCountOf(nation.id),
            "pop" to "$totalPop / $totalMaxPop",
            "poprate" to "${phpRoundString(totalPop.toDouble() / totalMaxPop * 100, 2)} %",
            "gold" to nation.gold,
            "rice" to nation.rice,
            "l12name" to chiefName(12),
            "l12pic" to chiefPic(12),
            "l11name" to chiefName(11),
            "l11pic" to chiefPic(11),
            "l10name" to chiefName(10),
            "l10pic" to chiefPic(10),
            "l9name" to chiefName(9),
            "l9pic" to chiefPic(9),
            "l8name" to chiefName(8),
            "l8pic" to chiefPic(8),
            "l7name" to chiefName(7),
            "l7pic" to chiefPic(7),
            "l6name" to chiefName(6),
            "l6pic" to chiefPic(6),
            "l5name" to chiefName(5),
            "l5pic" to chiefPic(5),
            "tiger" to topRankString("killnum", nation.id, 5),
            "eagle" to topRankString("firenum", nation.id, 7),
            "gen" to genNames,
            "history" to MetaJson.encode(history),
            "aux" to (latestStat["aux"] ?: "{}"),
        )
    }

    private fun loadedStatisticRows(): List<Map<String, Any?>> =
        statisticSnapshotReader?.snapshotRows(world.worldId)
            ?: (world.getState().meta["statisticRows"] as? List<*>)
                .orEmpty()
                .mapNotNull { row ->
                    (row as? Map<*, *>)?.entries?.associateTo(LinkedHashMap()) { (key, value) ->
                        key.toString() to value
                    }
                }

    private fun statisticInt(value: Any?): Int = when (value) {
        is Number -> value.toInt()
        else -> value?.toString()?.toIntOrNull() ?: 0
    }

    private fun archiveVisibleNationHistory(nationId: Int): List<String> {
        val persisted = archiveHistoryReader?.nationHistory(world.worldId, nationId)
            ?: run {
                val loaded = world.getState().meta["nationHistory"] as? Map<*, *>
                val rows = loaded?.get(nationId) ?: loaded?.get(nationId.toString())
                (rows as? List<*>)
                    .orEmpty()
                    .mapNotNull { it?.toString() }
            }
        val bufferedIndex = bufferedUnificationHistoryLogIndex
        val pending = world.peekLogs()
            .withIndex()
            .filter { (index, entry) ->
                index != bufferedIndex &&
                    entry.scope == "nation" &&
                    entry.category == "history" &&
                    entry.nationId == nationId
            }
            .map { it.value.text }
            .asReversed()
        return pending + persisted
    }

    private fun topRankString(type: String, nationId: Int, limit: Int): String =
        world.listGenerals()
            .asSequence()
            .filter { it.nationId == nationId }
            .map { it to effectiveRankValue(it, type) }
            .filter { (_, value) -> value > 0 }
            .sortedByDescending { (_, value) -> value }
            .take(limit)
            .joinToString(", ") { (general, value) ->
                "${general.name}【${NumberFormat.getIntegerInstance(Locale.US).format(value)}】"
            }

    private fun effectiveRankValue(general: TurnGeneral, type: String): Int {
        val base = (general.meta[type] as? Number)?.toInt() ?: 0
        val column = RankColumn.byColumn(type) ?: return base
        return when (val delta = recorder.rankDeltas(general.id)[column]) {
            is RankDelta.Increment -> base + delta.value
            is RankDelta.Set -> delta.value
            null -> base
        }
    }

    private fun phpRoundString(value: Double, scale: Int): String =
        phpRoundDecimal(value, scale).stripTrailingZeros().toPlainString()

    override fun logHistory() {
        val state = world.getState()
        val map = linkedMapOf<String, Any?>(
            "startYear" to ((state.meta["startYear"] as? Number)?.toInt() ?: resolveYear()),
            "year" to resolveYear(),
            "month" to resolveMonth(),
            "cityList" to world.listCities().map {
                listOf(it.id, it.level, it.state, it.nationId, it.region, it.supplyState)
            },
            "nationList" to world.listNations().filter { it.id != 0 }.map {
                listOf(it.id, it.name, it.color, it.capitalCityId ?: 0)
            },
            "spyList" to emptyMap<String, Any?>(),
            "shownByGeneralList" to emptyList<Int>(),
            "myCity" to null,
            "myNation" to null,
            "version" to 0,
            "result" to true,
        )
        val nations = world.listNations().filter { it.id != 0 }.map {
            linkedMapOf<String, Any?>(
                "nation" to it.id,
                "name" to it.name,
                "color" to it.color,
                "type" to it.typeCode,
                "level" to it.level,
                "capital" to (it.capitalCityId ?: 0),
                "gennum" to (it.meta["gennum"] ?: world.listGenerals().count { general -> general.nationId == it.id }),
                "power" to it.power,
            )
        }.toMutableList()
        nations += linkedMapOf(
            "nation" to 0,
            "name" to "재야",
            "color" to "#000000",
            "type" to GameConst.neutralNationType,
            "level" to 0,
            "capital" to 0,
            "gold" to 0,
            "rice" to 2000,
            "tech" to 0,
            "gennum" to 1,
            "power" to 1,
        )
        val nationsById = nations.associateByTo(LinkedHashMap()) { (it["nation"] as Number).toInt() }
        for (city in world.listCities()) {
            val nation = nationsById[city.nationId] ?: continue
            @Suppress("UNCHECKED_CAST")
            val cities = nation.getOrPut("cities") { mutableListOf<String>() } as MutableList<String>
            cities += city.name
        }
        val sortedNations = nations.sortedByDescending { (it["power"] as Number).toInt() }
        val globalHistory = currentGlobalLogs("history")
        val globalAction = currentGlobalLogs("action")
        val mapJson = MetaJson.encode(map)
        val nationsJson = MetaJson.encode(sortedNations)
        val globalHistoryJson = MetaJson.encode(globalHistory)
        val globalActionJson = MetaJson.encode(globalAction)
        recorder.recordYearbookInsert(
            linkedMapOf(
                "server_id" to (world.archiveServerId() ?: world.getState().serverId ?: "default"),
                "year" to resolveYear(),
                "month" to resolveMonth(),
                "map" to mapJson,
                "nations" to nationsJson,
                "global_history" to globalHistoryJson,
                "global_action" to globalActionJson,
            ),
        )
    }

    private fun currentGlobalLogs(category: String): List<String> {
        val persisted = archiveHistoryReader?.globalLogs(world.worldId, category, resolveYear(), resolveMonth())
            ?: (world.getState().meta["globalLogs"] as? List<*>)
                .orEmpty()
                .mapNotNull { it as? Map<*, *> }
                .filter {
                    it["category"]?.toString()?.equals(category, ignoreCase = true) == true &&
                        (it["year"] as? Number)?.toInt() == resolveYear() &&
                        (it["month"] as? Number)?.toInt() == resolveMonth()
                }
                .mapNotNull { it["text"]?.toString() }
        val pending = world.peekLogs()
            .filter {
                it.scope.lowercase() in setOf("global", "system") &&
                    it.category.equals(category, ignoreCase = true) &&
                    (it.year ?: resolveYear()) == resolveYear() &&
                    (it.month ?: resolveMonth()) == resolveMonth()
            }
            .map { it.text }
            .asReversed()
        val logs = pending + persisted
        if (logs.isNotEmpty()) return logs
        return when (category.lowercase()) {
            "history" -> listOf("<C>●</>${resolveYear()}년 ${resolveMonth()}월: 기록 없음")
            "action" -> listOf("<C>●</>${resolveMonth()}월: 기록 없음")
            else -> emptyList()
        }
    }

    override fun raiseInvaderMessages() {
        if (activeCityConst().all().none { it.value.level == 4 }) return
        val winnerNationId = activeNationIds().singleOrNull() ?: return
        val chiefs = world.listGenerals()
            .filter { it.nationId == winnerNationId && it.officerLevel >= 5 }
            .associateBy { it.officerLevel }
        (12 downTo 5)
            .mapNotNull(chiefs::get)
            .filter { it.npcState < 2 }
            .take(2)
            .forEach(::recordInvaderOfferMessages)
    }

    private fun recordInvaderOfferMessages(general: TurnGeneral) {
        markNewMessage(general)
        val nation = world.getNationById(general.nationId)
        val src = linkedMapOf<String, Any?>(
            "id" to 0,
            "name" to "",
            "nation_id" to 0,
            "nation" to "System",
            "color" to "#000000",
            "icon" to "",
        )
        val dest = linkedMapOf<String, Any?>(
            "id" to general.id,
            "name" to general.name,
            "nation_id" to general.nationId,
            "nation" to (nation?.name ?: "재야"),
            "color" to (nation?.color ?: "#000000"),
            "icon" to (general.meta["picture"]?.toString() ?: ""),
        )
        val now = world.getState().lastTurnTime.toString()
        val validUntil = "9999-12-31T00:00:00Z"
        val offers = listOf(
            listOf(-2.0, -1.2, 15000.0, -1.0) to "어려움",
            listOf(-2.0, -1.2, -1.0, -0.5) to "보통",
            listOf(-1.0, -1.0, -0.8, 0.0) to "쉬움",
        )
        for ((args, difficulty) in offers) {
            val option = linkedMapOf<String, Any?>(
                "action" to "raiseInvader",
                "args" to args,
                "used" to false,
            )
            val text = "이벤트 게임으로 이민족[$difficulty]을 소환"
            val receiverBody = MetaJson.encode(
                linkedMapOf("src" to src, "dest" to dest, "text" to text, "option" to option),
            )
            val receiverId = recorder.recordMessageInsert(
                general.id,
                "private",
                0,
                general.id,
                now,
                validUntil,
                receiverBody,
            )
            val senderOption = LinkedHashMap(option).apply { this["receiverMessageID"] = receiverId }
            val senderBody = MetaJson.encode(
                linkedMapOf("src" to src, "dest" to dest, "text" to text, "option" to senderOption),
            )
            recorder.recordMessageInsert(0, "private", 0, general.id, now, validUntil, senderBody)
        }
    }

    private fun markNewMessage(general: TurnGeneral) {
        if ((general.meta["newmsg"] as? Number)?.toInt() == 1) return
        val nextMeta = LinkedHashMap(general.meta)
        nextMeta["newmsg"] = 1
        val next = general.copy(meta = nextMeta)
        recorder.diffGeneral(PerTurnOverlay.toLogicGeneral(general), PerTurnOverlay.toLogicGeneral(next))
        world.updateGeneral(next)
    }

    /** `$gameStor->refreshLimit = $gameStor->refreshLimit * factor`(php:65). meta 즉시 반영 —
     *  game_env refreshLimit 컬럼 flush/boot-load 는 isunited 와 동일 클래스의 별도 갭(LEDGER 백로그:
     *  game_env KV write seam 부재). [InMemoryTurnWorld.multiplyRefreshLimit] 참조. */
    override fun multiplyRefreshLimit(factor: Int) {
        world.multiplyRefreshLimit(factor)
        recorder.recordKv("game_env", "game_env", "refreshLimit", world.getState().meta["refreshLimit"])
    }

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
        world.pushLog(logDraft("global", "action", actionLogText(msg, 4)))
    }

    override fun pushGlobalHistoryLog(msg: String, type: Int) {
        world.pushLog(logDraft("global", "history", actionLogText(msg, type), subType = type.toString()))
    }

    override fun pushGeneralHistoryLog(msg: String, type: Int) {
        world.pushLog(logDraft("general", "history", actionLogText(msg, type), subType = type.toString()))
    }

    private fun updateGeneralForInvader(before: TurnGeneral, after: TurnGeneral) {
        if (before == after) return
        recorder.diffGeneral(PerTurnOverlay.toLogicGeneral(before), PerTurnOverlay.toLogicGeneral(after))
        world.applyGeneralDirtyFree(after)
    }

    private fun BuiltGeneral.toInvaderTurnGeneral(id: Int): TurnGeneral {
        val general = toTurnGeneral(id, world.getState())
        return general.copy(
            meta = LinkedHashMap(general.meta).apply {
                remove("specage")
                remove("specage2")
            },
        )
    }

    private fun updateNationForInvader(before: EngineNation, after: EngineNation) {
        if (before == after) return
        recorder.diffNation(PerTurnOverlay.toLogicNation(before), PerTurnOverlay.toLogicNation(after))
        world.applyNationDirtyFree(after)
    }

    private fun updateCityForInvader(before: EngineCity, after: EngineCity) {
        if (before == after) return
        recorder.diffCity(PerTurnOverlay.toLogicCity(before), PerTurnOverlay.toLogicCity(after))
        world.applyCityDirtyFree(after)
    }

    private fun upsertDiplomacyForInvader(from: Int, to: Int, state: Int, term: Int) {
        val before = world.getDiplomacy(from, to)
        if (before == null) {
            world.createDiplomacy(TurnDiplomacy(from, to, state, term))
            return
        }
        val after = before.copy(state = state, term = term)
        recorder.diffDiplomacy(before, after)
        world.updateDiplomacy(from, to, state, term)
    }

    private fun scheduleInvaderEvent(actionsJson: String) {
        val store = env[DeleteEventContext.ENV_KEY] as? EventStore
            ?: error("RaiseInvader requires the live EventStore")
        store.insertRaw(
            targetCode = "month",
            priority = 1000,
            conditionJson = kotlinx.serialization.json.Json.parseToJsonElement("true"),
            actionJson = kotlinx.serialization.json.Json.parseToJsonElement(actionsJson),
        )
    }

    private fun changeInvaderTurnterm(oldTurnterm: Int, nextTurnterm: Int) {
        val locked = lockGame()
        if (oldTurnterm == nextTurnterm) {
            if (locked) unlockGame()
            return
        }
        val serverTurnTime = world.getState().lastTurnTime
        val unitRatio = nextTurnterm.toDouble() / oldTurnterm
        for (general in world.listGenerals().sortedBy { it.id }) {
            val distanceNanos = kotlin.math.abs(Duration.between(general.turnTime, serverTurnTime).toNanos())
            val nextTurnTime = serverTurnTime.plusNanos((distanceNanos * unitRatio).toLong())
            updateGeneralForInvader(general, general.copy(turnTime = nextTurnTime))
        }
        val elapsedMonths =
            (resolveYear() - resolveStartYear()).toLong() * 12L + resolveMonth() - 1L
        val rawStartTime = serverTurnTime.minusSeconds(elapsedMonths * nextTurnterm * 60L)
        val startTime = ServerClock.cutTurn(rawStartTime, nextTurnterm)
            .atZone(SEOUL_ZONE)
            .format(PHP_DATETIME_FORMAT)
        world.applyAdminWorldSettings(
            status = null,
            configPatch = mapOf(
                "turnterm" to nextTurnterm,
                "starttime" to startTime,
                "startTime" to startTime,
            ),
            tickSeconds = nextTurnterm * 60,
        )
        recorder.recordKv("game_env", "game_env", "turnterm", nextTurnterm)
        recorder.recordKv("game_env", "game_env", "starttime", startTime)
        pushPreformattedGlobalHistoryLog("<R>★</>턴시간이 <C>${nextTurnterm}분</>으로 변경됩니다.")
        if (locked) unlockGame()
    }

    private fun phpShuffle(values: List<Int>): List<Int> {
        val shuffled = values.toMutableList()
        synchronized(ambientPhpRandom) {
            for (index in shuffled.lastIndex downTo 1) {
                val other = ambientPhpRandom.range(0, index)
                val previous = shuffled[index]
                shuffled[index] = shuffled[other]
                shuffled[other] = previous
            }
        }
        return shuffled
    }
}

private fun List<Int>.averageIntOrZero(): Double = if (isEmpty()) 0.0 else average()

private fun List<Double>.averageDoubleOrZero(): Double = if (isEmpty()) 0.0 else average()

private fun BettingInfo.toKvMap(): Map<String, Any?> =
    linkedMapOf(
        "id" to id,
        "type" to type,
        "name" to name,
        "finished" to finished,
        "selectCnt" to selectCnt,
        "isExclusive" to isExclusive,
        "reqInheritancePoint" to reqInheritancePoint,
        "openYearMonth" to openYearMonth,
        "closeYearMonth" to closeYearMonth,
        "candidates" to candidates.mapKeys { it.key.toString() }.mapValues { (_, item) ->
            linkedMapOf(
                "title" to item.title,
                "info" to item.info,
                "isHtml" to item.isHtml,
                "aux" to item.aux,
            )
        },
        "winner" to winner,
    )
