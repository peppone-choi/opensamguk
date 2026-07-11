package opensamguk.engine.run

import opensamguk.common.rng.RandUtil
import opensamguk.engine.turn.City
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.GeneralStats
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.LogEntryDraft
import opensamguk.engine.turn.Nation
import opensamguk.engine.turn.PerTurnOverlay
import opensamguk.engine.turn.TurnGeneral
import opensamguk.engine.tournament.TournamentAdminService
import opensamguk.infra.read.AuctionRepository
import opensamguk.logic.actions.GeneralActionDraft
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.actions.founding.CheHaesan
import opensamguk.logic.auction.AuctionType
import opensamguk.logic.auction.registerNeutralAuctions
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.util.phpRound
import opensamguk.logic.tick.PostUpdateMonthly
import opensamguk.logic.world.CheckEmperiorContext
import opensamguk.logic.world.CityConstRegistry
import opensamguk.logic.world.DiplomacyRow
import opensamguk.logic.world.FrontCity
import opensamguk.logic.world.PowerKv
import opensamguk.logic.world.checkEmperior
import opensamguk.logic.world.PostNationPowerInput
import opensamguk.logic.world.PostFrontResult
import opensamguk.logic.world.PowerCity
import opensamguk.logic.world.PowerGeneral
import opensamguk.logic.world.postUpdateMonthlyDiplomacy
import opensamguk.logic.world.postUpdateMonthlyPower
import opensamguk.logic.world.postUpdateMonthlyTail
import opensamguk.logic.world.setNationFront
import java.time.Instant
import opensamguk.logic.domain.City as LogicCity
import opensamguk.logic.domain.General as LogicGeneral
import opensamguk.logic.domain.Nation as LogicNation

/**
 * P6 Task 6 — the `postUpdateMonthly` hook wired into [MonthlyPipeline].
 *
 * Implements the Q1-Q17 ordered settlement set (POST1/POST2/POST3) on the single month-scoped RNG.
 * All mutations are recorded through [ChangeRecorder] (the single dirty source) and applied to
 * [InMemoryTurnWorld] read-state so downstream logic sees the post-state immediately.
 */
class MonthlyPostUpdateHook(
    private val world: InMemoryTurnWorld,
    private val recorder: ChangeRecorder,
    private val pipeline: GeneralActionPipeline,
    private val auctionRepository: AuctionRepository? = null,
    private val tournamentAdmin: TournamentAdminService = TournamentAdminService(),
) : PostUpdateMonthly<RandUtil> {

    override fun run(monthlyRng: RandUtil) {
        val nations = world.listNations()
        val generals = world.listGenerals()
        val cities = world.listCities()

        // ---- POST1 (Q1-Q4): power aggregate + jitter ----
        val powerInputs = nations.map { nation ->
            val nationGenerals = generals.filter { it.nationId == nation.id }
            val nationCities = cities.filter { it.nationId == nation.id }
            val logicNation = PerTurnOverlay.toLogicNation(nation)
            PostNationPowerInput(
                nationId = nation.id,
                gennum = logicNation.gennum,
                gold = nation.gold,
                rice = nation.rice,
                tech = logicNation.tech.toInt(),
                level = nation.level,
                generals = nationGenerals.map { g ->
                    val meta = g.meta
                    PowerGeneral(
                        id = g.id,
                        gold = g.gold,
                        rice = g.rice,
                        leadership = g.stats.leadership,
                        strength = g.stats.strength,
                        intel = g.stats.intelligence,
                        npc = g.npcState,
                        dexSum = DEX_KEYS.sumOf { (meta[it] as? Number)?.toInt() ?: 0 },
                        experience = g.experience,
                        dedication = g.dedication,
                        crew = g.crew,
                        killcrewPersonRankValue = 0, // rank_data not loaded in-memory; defaults to 0 (PHP LEFT JOIN parity)
                        deathcrewPersonRankValue = 0,
                    )
                },
                cities = nationCities.map { c ->
                    PowerCity(
                        pop = c.population,
                        sumStat = c.population + c.agriculture + c.commerce + c.security + c.wall + c.defence,
                        sumMax = c.populationMax + c.agricultureMax + c.commerceMax + c.securityMax + c.wallMax + c.defenceMax,
                        supply = c.supplyState != 0,
                    )
                },
                cityNames = nationCities.map { it.name },
            )
        }

        val existingMaxPower = nations.mapNotNull { nation ->
            powerKvFrom(nationEnv(nation)["max_power"])?.let { nation.id to it }
        }.toMap()
        val powerResult = postUpdateMonthlyPower(powerInputs, existingMaxPower, monthlyRng)

        for (result in powerResult.nations) {
            val pre = world.getNationById(result.nationId) ?: continue
            val logicPre = PerTurnOverlay.toLogicNation(pre)
            val logicPost = logicPre.copy(power = result.power)
            recorder.diffNation(logicPre, logicPost)
            world.applyNationDirtyFree(pre.copy(power = result.power))
            recorder.recordNationEnvKv(
                result.nationId,
                "max_power",
                linkedMapOf(
                    "maxPower" to result.maxPowerKv.maxPower,
                    "maxCrew" to result.maxPowerKv.maxCrew,
                    "maxCities" to result.maxPowerKv.maxCities,
                ),
            )
        }

        // ---- POST2 (Q5-Q10): diplomacy settlement ----
        val diplomacyRows = world.listDiplomacy().map {
            DiplomacyRow(me = it.fromNationId, you = it.toNationId, state = it.state, term = it.term, dead = it.dead)
        }
        val genNum = generals.groupBy { it.nationId }.mapValues { it.value.size }
        val nationNames = nations.associate { it.id to it.name }
        val nationIds = nations.map { it.id }

        val availableWarSettingCnt = nations.mapNotNull { nation ->
            val cnt = (nationEnv(nation)["available_war_setting_cnt"] as? Number)?.toInt()
            cnt?.let { nation.id to it }
        }.toMap()
        val diplomacyResult = postUpdateMonthlyDiplomacy(
            rows = diplomacyRows,
            genNum = genNum,
            nationNames = nationNames,
            maxPower = availableWarSettingCnt,
            nations = nationIds,
        )

        // Q5 — war-term update on state=0 rows
        for (u in diplomacyResult.q5Updates) {
            val pre = world.getDiplomacy(u.me, u.you) ?: continue
            val post = world.updateDiplomacy(u.me, u.you, pre.state, u.newTerm, u.newDead) ?: continue
            recorder.diffDiplomacy(pre, post)
        }

        // Q6 — 개전 logs
        for (log in diplomacyResult.warStartLogs) {
            world.pushLog(LogEntryDraft(scope = "global", category = "history", text = log))
        }

        // Q7 — 종전 state updates
        for (u in diplomacyResult.q7StateUpdates) {
            val pre = world.getDiplomacy(u.me, u.you) ?: continue
            val post = world.updateDiplomacy(u.me, u.you, u.newState, u.newTerm, pre.dead) ?: continue
            recorder.diffDiplomacy(pre, post)
        }

        // Q7 — 종전 logs
        for (log in diplomacyResult.warStopLogs) {
            world.pushLog(LogEntryDraft(scope = "global", category = "history", text = log))
        }

        // Q9 — bulk final state/term/dead
        for (u in diplomacyResult.q9Updates) {
            val pre = world.getDiplomacy(u.me, u.you) ?: continue
            val post = world.updateDiplomacy(u.me, u.you, u.newState, u.newTerm, u.newDead) ?: continue
            recorder.diffDiplomacy(pre, post)
        }

        // Q10 — available_war_setting_cnt KV
        for ((nationId, cnt) in diplomacyResult.availableWarSettingCnt) {
            recorder.recordNationEnvKv(nationId, "available_war_setting_cnt", cnt)
        }

        // ---- POST3 (Q11-Q17): tail ----
        val state = world.getState()
        val year = state.currentYear
        val startYear = (state.meta["startYear"] as? Number)?.toInt() ?: 0
        val isUnited = (state.meta["isunited"] as? Int ?: 0) != 0

        postUpdateMonthlyTail(
            year = year,
            startYear = startYear,
            rng = monthlyRng,
            checkWander = { rng -> checkWander(rng, year, state.currentMonth) },
            updateGeneralNumber = { updateGeneralNumber() },
            triggerTournament = { rng -> triggerTournament(rng) },
            registerAuction = { rng -> registerAuction(rng) },
            setNationFront = { setNationFronts() },
            checkEmperior = { checkEmperior(WorldCheckEmperiorContext(world)) }, // Q14 천하통일 탐지 (no rng)
            isUnited = isUnited,
        )
    }

    private fun checkWander(rng: RandUtil, year: Int, month: Int) {
        val env = opensamguk.logic.domain.WorldEnv(year = year, startYear = startYear(), develCost = (year - startYear() + 10) * 2)
        val wanderers = world.listGenerals()
            .filter { g -> g.officerLevel == 12 && world.getNationById(g.nationId)?.level == 0 }
            .sortedBy { it.id }
        for (wanderer in wanderers) {
            val preCity = world.getCityById(wanderer.cityId) ?: continue
            val preNation = world.getNationById(wanderer.nationId) ?: continue
            val draft = GeneralActionDraft(
                PerTurnOverlay.toLogicGeneral(wanderer),
                PerTurnOverlay.toLogicCity(preCity),
                PerTurnOverlay.toLogicNation(preNation),
            )
            draft.cascadeGenerals.addAll(
                world.listGenerals()
                    .filter { it.nationId == wanderer.nationId && it.id != wanderer.id }
                    .sortedBy { it.id }
                    .map { PerTurnOverlay.toLogicGeneral(it) },
            )
            draft.cascadeCities.addAll(
                world.listCities()
                    .filter { it.nationId == wanderer.nationId }
                    .sortedBy { it.id }
                    .map { PerTurnOverlay.toLogicCity(it) },
            )
            val ctx = GeneralActionResolveContext(
                draft = draft,
                rng = rng,
                env = env,
                month = month,
                date = turnTimeHm(wanderer.turnTime),
                generalName = wanderer.name,
            )
            ctx.addLog("초반 제한후 방랑군은 자동 해산됩니다.")
            val command = CheHaesan(pipeline)
            command.resolve(ctx)
            command.lastDeletedNationId?.let { recorder.markNationDeleted(world, it) }
            recorder.diffGeneral(PerTurnOverlay.toLogicGeneral(wanderer), draft.general)
            world.applyGeneralDirtyFree(applyGeneralPatch(wanderer, draft.general))
            for (general in draft.cascadeGenerals) {
                val pre = world.getGeneralById(general.id) ?: continue
                recorder.diffGeneral(PerTurnOverlay.toLogicGeneral(pre), general)
                world.applyGeneralDirtyFree(applyGeneralPatch(pre, general))
            }
            for (city in draft.cascadeCities) {
                val pre = world.getCityById(city.id) ?: continue
                recorder.diffCity(PerTurnOverlay.toLogicCity(pre), city)
                world.applyCityDirtyFree(applyCityPatch(pre, city))
            }
            for (line in ctx.logs()) world.pushLog(LogEntryDraft("general", "action", line, generalId = wanderer.id, nationId = wanderer.nationId))
            for (line in ctx.globalActionLogs()) world.pushLog(LogEntryDraft("global", "action", line, generalId = wanderer.id, nationId = wanderer.nationId))
        }
    }

    private fun updateGeneralNumber() {
        val counts = world.listGenerals()
            .filter { it.npcState != 5 }
            .groupingBy { it.nationId }
            .eachCount()
        for ((nationId, count) in counts) {
            if (nationId == 0) continue
            val pre = world.getNationById(nationId) ?: continue
            val logicPre = PerTurnOverlay.toLogicNation(pre)
            val nextMeta = LinkedHashMap(logicPre.meta)
            nextMeta["gennum"] = count
            val logicPost = logicPre.copy(gennum = count, meta = nextMeta)
            recorder.diffNation(logicPre, logicPost)
            world.applyNationDirtyFree(applyNationPatch(pre, logicPost))
        }
    }

    private fun triggerTournament(rng: RandUtil) {
        val state = world.getState()
        if (((state.meta["tournament"] as? Number)?.toInt() ?: 0) != 0) return
        if (!boolMeta("tnmt_trig")) return
        if (!rng.nextBool(0.4)) return

        val rawPattern = state.meta["tnmt_pattern"] as? List<*> ?: emptyList<Any?>()
        val pattern = rawPattern.mapNotNull { (it as? Number)?.toInt() }.toMutableList()
        if (pattern.isEmpty()) {
            pattern.addAll(listOf(0, 0, 1, 2, 3).shuffled())
        }
        val tournamentType = pattern.removeAt(pattern.lastIndex)
        recorder.recordKv("game_env", "game_env", "tnmt_pattern", pattern)
        tournamentAdmin.startTournament(world, recorder, tournamentType, Instant.now())
    }

    private fun registerAuction(rng: RandUtil) {
        val active = auctionRepository?.findByFinishedFalse().orEmpty()
        val neutralBuyRiceCount = active.count { it.hostGeneralId == 0 && it.type == AuctionType.BUY_RICE }
        val neutralSellRiceCount = active.count { it.hostGeneralId == 0 && it.type == AuctionType.SELL_RICE }
        val targetGenerals = world.listGenerals().filter { it.npcState < 2 }
        val avgGold = targetGenerals.map { it.gold }.average().takeUnless { it.isNaN() }
        val avgRice = targetGenerals.map { it.rice }.average().takeUnless { it.isNaN() }
        val result = registerNeutralAuctions(
            avgGold = avgGold,
            avgRice = avgRice,
            neutralBuyRiceCount = neutralBuyRiceCount,
            neutralSellRiceCount = neutralSellRiceCount,
            rng = rng,
            now = Instant.now(),
            turnTermMinutes = turnTerm(),
        )
        for (opened in result.opened) {
            recorder.recordAuctionUpsert(id = null, columns = opened.info.toArray())
        }
    }

    private fun setNationFronts(): List<PostFrontResult> {
        val state = world.getState()
        val cityConst = CityConstRegistry.find(state.meta["map"] as? String ?: "che") ?: CityConstRegistry.of("che")
        val cities = world.listCities()
        val frontCities = cities.map { FrontCity(it.id, it.nationId, it.frontState) }
        val diplomacy = world.listDiplomacy().map { PerTurnOverlay.toLogicDiplomacy(it) }
        val result = mutableListOf<PostFrontResult>()
        for (nation in world.listNations().filter { it.level > 0 }) {
            val fronts = setNationFront(nation.id, frontCities, diplomacy, cityConst)
            for ((cityId, front) in fronts.fronts) {
                val pre = world.getCityById(cityId) ?: continue
                if (pre.frontState == front) continue
                val logicPre = PerTurnOverlay.toLogicCity(pre)
                val logicPost = logicPre.copy(frontState = front)
                recorder.diffCity(logicPre, logicPost)
                world.applyCityDirtyFree(pre.copy(frontState = front))
            }
            result += PostFrontResult(nation.id)
        }
        return result
    }

    private fun boolMeta(key: String): Boolean = when (val raw = world.getState().meta[key]) {
        is Boolean -> raw
        is Number -> raw.toInt() != 0
        else -> false
    }

    private fun startYear(): Int =
        (world.getState().meta["startYear"] as? Number)?.toInt()
            ?: (world.getState().meta["startyear"] as? Number)?.toInt()
            ?: world.getState().currentYear

    private fun turnTerm(): Int =
        (world.getState().meta["turnterm"] as? Number)?.toInt() ?: (world.getState().tickSeconds / 60)

    private fun turnTimeHm(turnTime: Instant): String {
        val value = turnTime.toString()
        val separator = value.indexOf('T')
        return if (separator >= 0 && value.length >= separator + 6) value.substring(separator + 1, separator + 6) else ""
    }

    @Suppress("UNCHECKED_CAST")
    private fun nationEnv(nation: Nation): Map<String, Any?> =
        (nation.meta["nation_env"] as? Map<*, *>)?.entries?.associate { (k, v) -> k.toString() to v } ?: emptyMap()

    private fun powerKvFrom(raw: Any?): PowerKv? {
        val map = raw as? Map<*, *> ?: return null
        val maxCities = (map["maxCities"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
        return PowerKv(
            maxPower = (map["maxPower"] as? Number)?.toInt() ?: 0,
            maxCrew = (map["maxCrew"] as? Number)?.toInt() ?: 0,
            maxCities = maxCities,
        )
    }

    private fun applyGeneralPatch(engine: TurnGeneral, post: LogicGeneral): TurnGeneral =
        engine.copy(
            gold = post.gold,
            rice = post.rice,
            injury = post.injury,
            officerLevel = post.officerLevel,
            cityId = post.cityId,
            nationId = post.nationId,
            troopId = post.troop,
            stats = GeneralStats(
                leadership = post.leadership,
                strength = post.strength,
                intelligence = post.intel,
                politics = engine.stats.politics,
                charm = engine.stats.charm,
            ),
            experience = phpRound(post.experience),
            dedication = phpRound(post.dedication),
            crew = post.crew,
            train = phpRound(post.train),
            atmos = phpRound(post.atmos),
            crewTypeId = post.crewTypeId,
            role = engine.role.copy(items = engine.role.items.copy(horse = post.horse, weapon = post.weapon, book = post.book, item = post.item)),
            npcState = post.npcType,
            userId = post.userId?.toString(),
            meta = post.meta,
        )

    private fun applyNationPatch(engine: Nation, logic: LogicNation): Nation =
        engine.copy(
            name = logic.name,
            color = logic.color,
            capitalCityId = logic.capitalCityId,
            gold = logic.gold,
            rice = logic.rice,
            power = logic.power,
            tech = logic.tech,
            level = logic.level,
            typeCode = logic.typeCode,
            meta = logic.meta,
        )

    private fun applyCityPatch(engine: City, post: LogicCity): City {
        val nextMeta = if (post.trust != (engine.meta["trust"] as? Number)?.toDouble()) {
            LinkedHashMap(engine.meta).apply { this["trust"] = post.trust }
        } else {
            post.meta
        }
        return engine.copy(
            level = post.level,
            state = post.state,
            commerce = post.commerce,
            commerceMax = post.commerceMax,
            agriculture = post.agriculture,
            agricultureMax = post.agricultureMax,
            population = post.population,
            populationMax = post.populationMax,
            dead = post.dead,
            security = post.security,
            securityMax = post.securityMax,
            defence = post.defense,
            defenceMax = post.defenseMax,
            wall = post.wall,
            wallMax = post.wallMax,
            supplyState = post.supplyState,
            frontState = post.frontState,
            nationId = post.nationId,
            trade = post.trade,
            region = post.region,
            term = post.term,
            officerSet = post.officerSet,
            conflict = post.conflict,
            meta = nextMeta,
        )
    }

    companion object {
        private val DEX_KEYS = listOf("dex1", "dex2", "dex3", "dex4", "dex5")
    }
}

/**
 * Q14 `checkEmperior` 의 월드 시임을 [InMemoryTurnWorld] 위에 구현한다(`func_gamerule.php:696-769`).
 * level>0 국가/도시 소유 판정 read + isunited 전이 write + 전토통일 국가사 로그 push.
 * isunited 는 meta 에만 in-memory 반영(컬럼 flush/boot-load 는 별도 갭 — LEDGER 백로그).
 */
class WorldCheckEmperiorContext(
    private val world: InMemoryTurnWorld,
) : CheckEmperiorContext {
    override fun isunited(): Int = (world.getState().meta["isunited"] as? Number)?.toInt() ?: 0

    override fun activeNationIds(): List<Int> =
        world.listNations().filter { it.level > 0 }.map { it.id }

    override fun cityCountOf(nationId: Int): Int =
        world.listCities().count { it.nationId == nationId }

    // 전 도시가 시드되므로 in-memory city 수 = count(CityConst::all()) (장기-시뮬 게이트 전제).
    override fun totalCityCount(): Int = world.listCities().size

    override fun nationName(nationId: Int): String? = world.getNationById(nationId)?.name

    override fun pushNationalHistoryLog(nationId: Int, msg: String) {
        world.pushLog(LogEntryDraft(scope = "nation", category = "history", text = msg, nationId = nationId))
    }

    override fun setIsunited(value: Int) = world.setIsunited(value)
}
