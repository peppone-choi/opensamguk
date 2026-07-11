package opensamguk.engine.turn

import opensamguk.logic.domain.metaDouble
import opensamguk.logic.domain.City as LogicCity
import opensamguk.logic.domain.Diplomacy as LogicDiplomacy
import opensamguk.logic.domain.General as LogicGeneral
import opensamguk.logic.domain.Nation as LogicNation

/**
 * Copy-on-write view over [InMemoryTurnWorld] for ONE general's turn (P1 Task F1).
 *
 * Reads fall through to the world; writes stage into the overlay until applied. This is the
 * structural analogue of the TS `WorldStateView.overrides` (the per-general copy-on-write
 * override that takes precedence over the shared world view) in
 * `app/game-engine/src/turn/ai/generalAi/worldStateView.ts`.
 *
 * The overlay stages ENGINE-model rows ([TurnGeneral]/[City]) so the apply path mutates the
 * world in its own model (the resolver/ChangeRecorder operate on the LOGIC model — F2/F3 — and
 * the conversion lives here). The overlay never mutates the world: a staged write is visible via
 * [getGeneral]/[getCity] but the world's rows are untouched until [applyTo] runs.
 *
 * Engine `TurnGeneral`/`City` carry more columns than the logic `General`/`City`. Conversion maps
 * the slice-relevant subset and carries `meta` verbatim (insertion order preserved):
 *  - `General.intel`        <- `TurnGeneral.stats.intelligence`
 *  - `General.experience`   <- `TurnGeneral.experience` (Int -> raw Double accumulator)
 *  - `General.dedication`   <- `TurnGeneral.dedication` (Int -> raw Double accumulator)
 *  - `City.trust`           <- `City.meta["trust"]` (engine City has no trust column; the logic
 *                              che math reads `trust/100.0` & `trust/80.0` as a Double)
 */
class PerTurnOverlay(private val world: InMemoryTurnWorld) {

    private val stagedGenerals = LinkedHashMap<Int, TurnGeneral>()
    private val stagedCities = LinkedHashMap<Int, City>()

    // --- engine-model reads (overlay-first, fall through to world) ---

    fun getGeneral(id: Int): TurnGeneral? = stagedGenerals[id] ?: world.getGeneralById(id)
    fun getCity(id: Int): City? = stagedCities[id] ?: world.getCityById(id)
    fun getNation(id: Int): Nation? = world.getNationById(id)

    // --- staged writes (visible in the overlay; world untouched until applyTo) ---

    fun stageGeneral(next: TurnGeneral) { stagedGenerals[next.id] = next }
    fun stageCity(next: City) { stagedCities[next.id] = next }

    fun isGeneralStaged(id: Int): Boolean = stagedGenerals.containsKey(id)
    fun isCityStaged(id: Int): Boolean = stagedCities.containsKey(id)

    /** Snapshot of the currently staged engine rows (read-only). */
    fun stagedGeneralIds(): Set<Int> = stagedGenerals.keys.toSet()
    fun stagedCityIds(): Set<Int> = stagedCities.keys.toSet()

    // --- logic-model reads (overlay-first; conversion engine -> logic) ---

    fun getLogicGeneral(id: Int): LogicGeneral? = getGeneral(id)?.let { toLogicGeneral(it) }
    fun getLogicCity(id: Int): LogicCity? = getCity(id)?.let { toLogicCity(it) }
    fun getLogicNation(id: Int): LogicNation? = getNation(id)?.let { toLogicNation(it) }

    // --- CD1: full-collection + directional-diplomacy logic reads (dest-* constraint family) ---
    // Staged-general writes take precedence over the world snapshot so NationList/GeneralList stay
    // consistent with the per-id reads above (CheckNationNameDuplicate scans NationList).

    fun listLogicNations(): List<LogicNation> = world.listNations().map { toLogicNation(it) }

    fun listLogicGenerals(): List<LogicGeneral> =
        world.listGenerals().map { toLogicGeneral(stagedGenerals[it.id] ?: it) }

    /** Directional (me,you) diplomacy row converted engine -> logic, or null when absent. */
    fun getLogicDiplomacy(me: Int, you: Int): LogicDiplomacy? =
        world.listDiplomacy().firstOrNull { it.fromNationId == me && it.toNationId == you }
            ?.let { toLogicDiplomacy(it) }

    companion object {
        /** Engine [TurnGeneral] -> logic [General] (slice subset + meta verbatim + P2 mil/equip). */
        fun toLogicGeneral(g: TurnGeneral): LogicGeneral = LogicGeneral(
            id = g.id,
            nationId = g.nationId,
            cityId = g.cityId,
            leadership = g.stats.leadership,
            strength = g.stats.strength,
            intel = g.stats.intelligence,
            injury = g.injury,
            experience = g.experience.toDouble(),
            dedication = g.dedication.toDouble(),
            officerLevel = g.officerLevel,
            gold = g.gold,
            rice = g.rice,
            // P2 military / equip surface (carried so the widened step-7 general UPDATE round-trips).
            crew = g.crew,
            train = g.train.toDouble(),
            atmos = g.atmos.toDouble(),
            crewTypeId = g.crewTypeId,
            troop = g.troopId,
            horse = g.role.items.horse ?: "None",
            weapon = g.role.items.weapon ?: "None",
            book = g.role.items.book ?: "None",
            item = g.role.items.item ?: "None",
            npcType = g.npcState,
            officerCity = (g.meta["officer_city"] as? Number)?.toInt() ?: 0,
            userId = g.userId,
            penalty = (g.meta["penalty"] as? Map<String, Any?>) ?: linkedMapOf(),
            meta = g.meta,
            politics = g.stats.politics,
            charm = g.stats.charm,
            age = g.age,
            turnTime = g.turnTime,
        )

        /** Engine [City] -> logic [City] (slice subset + P2 develop/defense; `trust` from `meta["trust"]`). */
        fun toLogicCity(c: City): LogicCity = LogicCity(
            id = c.id,
            nationId = c.nationId,
            level = c.level,
            commerce = c.commerce,
            commerceMax = c.commerceMax,
            agriculture = c.agriculture,
            agricultureMax = c.agricultureMax,
            supplyState = c.supplyState,
            frontState = c.frontState,
            // W0-8: 재해/호황 state(V14 영속 컬럼) — 엔진 인메모리 값을 그대로 실어 flush가 round-trip한다.
            state = c.state,
            trust = metaDouble(c.meta, "trust"),
            // P2 develop/defense surface (carried so the widened step-7 city UPDATE round-trips;
            // engine `defence` spelling maps to logic `defense`).
            security = c.security,
            securityMax = c.securityMax,
            defense = c.defence,
            defenseMax = c.defenceMax,
            wall = c.wall,
            wallMax = c.wallMax,
            population = c.population,
            populationMax = c.populationMax,
            dead = c.dead,
            trade = c.trade,
            region = c.region,
            term = c.term,
            officerSet = c.officerSet,
            conflict = c.conflict,
            meta = c.meta,
        )

        /** Engine [TurnDiplomacy] -> logic [Diplomacy] (directional me/you + state + term). */
        fun toLogicDiplomacy(d: TurnDiplomacy): LogicDiplomacy = LogicDiplomacy(
            me = d.fromNationId,
            you = d.toNationId,
            state = d.state,
            term = d.term,
        )

        /** Engine [Nation] -> logic [Nation] (slice subset + gold/rice/tech/power/type/name/color). */
        fun toLogicNation(n: Nation): LogicNation = LogicNation(
            id = n.id,
            level = n.level,
            capitalCityId = n.capitalCityId,
            name = n.name,
            color = n.color,
            typeCode = n.typeCode,
            gold = n.gold,
            rice = n.rice,
            power = n.power,
            // tech를 그대로 전달해야 flush가 시드값을 보존한다(미전달 시 LogicNation.tech 기본 0.0 → DB 0 덮어쓰기).
            tech = n.tech,
            gennum = (n.meta["gennum"] as? Number)?.toInt() ?: 0,
            capset = (n.meta["capset"] as? Number)?.toInt() ?: 0,
            meta = n.meta,
        )

        /**
         * Logic [Nation] -> engine [Nation] (the inverse of [toLogicNation]) for the founding created-set:
         * the 거병 resolver produces a logic Nation that the handler writes into the world via
         * [InMemoryTurnWorld.createNation]. Engine [Nation]에는 `gennum`/`capset` 전용 컬럼이 없어 `meta`로
         * round-trip 하지만(resolver가 `meta["gennum"]`를 찍음), `tech`는 power와 마찬가지로 전용 필드로
         * 보존한다. A logic `capitalCityId == 0` (거병 wandering) maps to the engine `Int?` field as 0.
         */
        fun toEngineNation(n: LogicNation): Nation = Nation(
            id = n.id,
            name = n.name,
            color = n.color,
            capitalCityId = n.capitalCityId,
            gold = n.gold,
            rice = n.rice,
            power = n.power,
            tech = n.tech,
            level = n.level,
            typeCode = n.typeCode,
            meta = n.meta,
        )

        /** Logic [Diplomacy] -> engine [TurnDiplomacy] (the inverse of [toLogicDiplomacy]; `dead` defaults 0). */
        fun toEngineDiplomacy(d: LogicDiplomacy): TurnDiplomacy = TurnDiplomacy(
            fromNationId = d.me,
            toNationId = d.you,
            state = d.state,
            term = d.term,
        )
    }
}
