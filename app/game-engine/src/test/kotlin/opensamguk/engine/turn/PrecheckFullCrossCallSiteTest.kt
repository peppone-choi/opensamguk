package opensamguk.engine.turn

import opensamguk.gameapi.precheck.CommandPrecheckService
import opensamguk.gameapi.precheck.PrecheckResult
import opensamguk.gameapi.precheck.PrecheckStateViewFactory
import opensamguk.gameapi.read.CityReadEntity
import opensamguk.gameapi.read.CityReadRepository
import opensamguk.gameapi.read.DiplomacyReadRepository
import opensamguk.gameapi.read.DiplomacyReadEntity
import opensamguk.gameapi.read.GeneralReadEntity
import opensamguk.gameapi.read.GeneralReadRepository
import opensamguk.gameapi.read.NationReadEntity
import opensamguk.gameapi.read.NationReadRepository
import opensamguk.gameapi.read.WorldStateReadEntity
import opensamguk.gameapi.read.WorldStateReadRepository
import opensamguk.infra.persistence.ReservedTurnRepository.ReservedTurn
import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.world.CityConstRegistry
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.time.Instant
import java.util.Optional
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * P1 Task G3 (cross-call-site, P1 review-edit #10) — the structural proof that the TWO REAL call
 * sites of the shared `:logic` constraint library AGREE, not just two invocations inside `:logic`.
 *
 * It drives:
 *  - the REAL `:app:game-api` [CommandPrecheckService] (JPA read repos stubbed — the E2 unit-test
 *    pattern, so NO database is needed) in PRECHECK mode, and
 *  - the REAL `:app:game-engine` [ReservedTurnHandler] full-mode `evaluateConstraints` path (the
 *    daemon turn entry point) in FULL mode,
 *
 * against the SAME seeded world (the SAME general/city/nation, projected into each call site's row
 * model) and the SAME action code. For an AVAILABLE fixture BOTH must say "go"; for a denying
 * fixture BOTH must DENY with the IDENTICAL outcome class AND the IDENTICAL PHP reason string. A
 * second, drifted constraint implementation on either side could not pass this silently.
 *
 * The canonical fixture is the E2/G3-logic fixture: nation 1 (level 7, capital 5) / city 5 (owned,
 * supplied, agri 4000 < 8000) / general 10 in city 5, gold 4000. world env year 200 / startYear 190
 * -> develCost 40, so the 농지개간 cost is 40 and gold 4000 clears it.
 */
class PrecheckFullCrossCallSiteTest {

    private val pipeline = GeneralActionPipeline()
    private val registry = CommandRegistry(pipeline)

    private val ACTION = "che_농지개간"
    private val RECRUIT_ACTION = "che_징병"
    private val GENERAL_ID = 10
    private val CITY_ID = 5
    private val NATION_ID = 1
    private val YEAR = 200
    private val MONTH = 3
    private val START_YEAR = 190
    private val HIDDEN_SEED = "00000000000000000000000000000000"
    private val t0 = Instant.parse("0200-03-01T12:34:00Z")

    /** One canonical logical fixture, tunable on the few fields the constraints read. */
    private inner class Fixture(
        val gold: Int = 4000,
        val rice: Int = 3000,                // < generalMinimumRice(500) is never used; 헌납 funds clear
        val cityNationId: Int = NATION_ID,   // != actor nation -> OccupiedCity deny
        val cityId: Int = CITY_ID,
        val destCityId: Int? = null,
        val destCityNationId: Int? = null,
        val routeCityId: Int? = null,
        val routeCityNationId: Int = 0,
        val cityLevel: Int = 5,
        val cityPopulation: Int = 100_000,
        val supplyState: Int = 1,            // 0 -> SuppliedCity deny
        val agri: Int = 4000,
        val agriMax: Int = 8000,             // agri == agriMax -> RemainCityCapacity deny
        val wall: Int = 2000,
        val wallMax: Int = 8000,             // wall == wallMax -> RemainCityCapacity deny (성벽 보수)
        val officerLevel: Int = 5,           // 12 -> NotLord deny (하야); != 12 -> BeChief deny (포상)
        val crew: Int = 0,
        val atWarNationId: Int? = null,
        val nationTech: Int = 0,
        val nationGold: Int = 100_000,
        val nationRice: Int = 100_000,
        val mapName: String? = "che",
        val unitSet: String? = null,
    )

    private fun Fixture.worldConfig(): Map<String, Any?> = linkedMapOf<String, Any?>(
        "startYear" to START_YEAR,
    ).apply {
        mapName?.let { this["mapName"] = it }
        unitSet?.let { this["unitSet"] = it }
    }

    // --- game-api side: project the fixture into JPA read entities + stub the repos ---

    private fun precheckService(f: Fixture): CommandPrecheckService {
        val general = GeneralReadEntity(
            id = GENERAL_ID, nationId = NATION_ID, cityId = f.cityId,
            leadership = 70, strength = 30, intel = 95, injury = 0,
            experience = 1200, dedication = 900, officerLevel = f.officerLevel,
            gold = f.gold, rice = f.rice,
            crew = f.crew,
            meta = linkedMapOf("explevel" to 4, "intel_exp" to 12, "max_domestic_critical" to 3.5),
        )
        val city = CityReadEntity(
            id = f.cityId, nationId = f.cityNationId, level = f.cityLevel,
            commerce = 3000, commerceMax = 8000, agriculture = f.agri, agricultureMax = f.agriMax,
            supplyState = f.supplyState, frontState = 0, trust = 82.0,
            wall = f.wall, wallMax = f.wallMax,
            population = f.cityPopulation,
            meta = linkedMapOf(),
        )
        val destCity = f.destCityId?.let { id ->
            CityReadEntity(
                id = id, nationId = f.destCityNationId ?: f.cityNationId, level = f.cityLevel,
                commerce = 3000, commerceMax = 8000, agriculture = f.agri, agricultureMax = f.agriMax,
                supplyState = f.supplyState, frontState = 0, trust = 82.0,
                wall = f.wall, wallMax = f.wallMax,
                population = f.cityPopulation,
                meta = linkedMapOf(),
            )
        }
        val routeCity = f.routeCityId?.let { id ->
            CityReadEntity(
                id = id, nationId = f.routeCityNationId, level = f.cityLevel,
                commerce = 3000, commerceMax = 8000, agriculture = f.agri, agricultureMax = f.agriMax,
                supplyState = f.supplyState, frontState = 0, trust = 82.0,
                wall = f.wall, wallMax = f.wallMax, population = f.cityPopulation, meta = linkedMapOf(),
            )
        }
        val nation = NationReadEntity(
            id = NATION_ID, level = 7, capitalCityId = f.cityId, tech = f.nationTech.toDouble(),
            gold = f.nationGold, rice = f.nationRice,
        )
        val worldState = WorldStateReadEntity(
            id = 1, scenarioCode = "scenario_2", currentYear = YEAR, currentMonth = MONTH,
            tickSeconds = 3600, config = f.worldConfig(), meta = linkedMapOf(),
        )

        val generals = mock(GeneralReadRepository::class.java)
        val cities = mock(CityReadRepository::class.java)
        val nations = mock(NationReadRepository::class.java)
        val diplomacies = mock(DiplomacyReadRepository::class.java)
        val worldStates = mock(WorldStateReadRepository::class.java)
        `when`(generals.findById(GENERAL_ID)).thenReturn(Optional.of(general))
        `when`(cities.findById(f.cityId)).thenReturn(Optional.of(city))
        destCity?.let { `when`(cities.findById(it.id)).thenReturn(Optional.of(it)) }
        `when`(cities.findByNationIdOrderByIdAsc(NATION_ID)).thenReturn(listOf(city))
        `when`(nations.findById(NATION_ID)).thenReturn(Optional.of(nation))
        val diplomacyRows = f.atWarNationId?.let { enemy ->
            listOf(DiplomacyReadEntity(id = 1, srcNationId = NATION_ID, destNationId = enemy, stateCode = 0, term = 0))
        }.orEmpty()
        `when`(diplomacies.findBySrcNationId(NATION_ID)).thenReturn(diplomacyRows)
        `when`(cities.findAll()).thenReturn(listOfNotNull(city, destCity, routeCity))
        `when`(worldStates.findAll()).thenReturn(listOf(worldState))
        val factory = PrecheckStateViewFactory(generals, cities, nations, diplomacies, worldStates)
        return CommandPrecheckService(factory, registry)
    }

    // --- game-engine side: project the SAME fixture into the in-memory turn world ---

    private fun engineHandler(f: Fixture): Pair<ReservedTurnHandler, InMemoryTurnWorld> {
        val general = TurnGeneral(
            id = GENERAL_ID, name = "g$GENERAL_ID", nationId = NATION_ID, cityId = f.cityId, troopId = 0,
            stats = GeneralStats(leadership = 70, strength = 30, intelligence = 95),
            experience = 1200, dedication = 900, officerLevel = f.officerLevel,
            gold = f.gold, rice = f.rice, injury = 0, turnTime = t0,
            crew = f.crew,
            meta = linkedMapOf("explevel" to 4, "intel_exp" to 12, "max_domestic_critical" to 3.5),
        )
        val city = City(
            id = f.cityId, name = "c${f.cityId}", nationId = f.cityNationId, level = f.cityLevel,
            commerce = 3000, commerceMax = 8000, agriculture = f.agri, agricultureMax = f.agriMax,
            supplyState = f.supplyState, frontState = 0,
            wall = f.wall, wallMax = f.wallMax,
            population = f.cityPopulation,
            meta = linkedMapOf("trust" to 82),   // engine City has no trust column; lives in meta
        )
        val destCity = f.destCityId?.let { id ->
            city.copy(id = id, name = "c$id", nationId = f.destCityNationId ?: f.cityNationId)
        }
        val routeCity = f.routeCityId?.let { id ->
            city.copy(id = id, name = "c$id", nationId = f.routeCityNationId)
        }
        // The actor's own nation; a second nation 2 exists so the OccupiedCity-deny fixture (city
        // owned by nation 2) resolves a real nation row, matching the game-api stub world.
        val nations = listOf(
            Nation(
                id = NATION_ID,
                name = "n$NATION_ID",
                color = "#000",
                level = 7,
                capitalCityId = f.cityId,
                gold = f.nationGold,
                rice = f.nationRice,
                tech = f.nationTech.toDouble(),
            ),
            Nation(id = 2, name = "n2", color = "#111", level = 7, capitalCityId = null),
        )
        val state = TurnWorldState(
            id = 1, currentYear = YEAR, currentMonth = MONTH, tickSeconds = 3600, lastTurnTime = t0,
            config = f.worldConfig(),
        )
        val world = InMemoryTurnWorld(
            WorldSnapshot(
                state,
                listOf(general),
                listOfNotNull(city, destCity, routeCity),
                nations,
                diplomacy = f.atWarNationId?.let { enemy ->
                    listOf(TurnDiplomacy(NATION_ID, enemy, state = 0, term = 0))
                }.orEmpty(),
                worldId = opensamguk.common.world.WorldId(state.id),
            ),
        )
        return ReservedTurnHandler(world, registry, HIDDEN_SEED, START_YEAR) to world
    }

    @Test
    fun `AVAILABLE fixture both real call sites agree go`() {
        val f = Fixture()  // owned, supplied, funded, agri < max

        // game-api PRECHECK
        val precheck = precheckService(f).precheck(GENERAL_ID, ACTION)
        assertEquals(PrecheckResult.Available, precheck, "game-api precheck: AVAILABLE")

        // game-engine FULL (the daemon turn entry point)
        val (handler, _) = engineHandler(f)
        val outcome = handler.handle(GENERAL_ID, ACTION, YEAR, MONTH, "12:34")

        // SAME outcome CLASS: Allow -> AVAILABLE / not-fell-back
        assertFalse(outcome.fellBack, "game-engine full: Allow (resolved, did NOT fall back to 휴식)")
        assertEquals(ACTION, outcome.definition.key, "the requested action resolved, not the fallback")
        assertEquals(null, outcome.denyReason, "no deny reason on an allowed turn")
    }

    @Test
    fun `missing map fails identically at precheck and daemon call sites`() {
        assertMapFailureAgreement(Fixture(mapName = null), "world state requires an explicit mapName in config/meta")
    }

    @Test
    fun `unknown map fails identically at precheck and daemon call sites`() {
        assertMapFailureAgreement(Fixture(mapName = "unknown"), "world state has unknown mapName: unknown")
    }

    @Test
    fun `Han-only move adjacency agrees at precheck and daemon call sites`() {
        val fixture = Fixture(cityId = 3, destCityId = 421, mapName = "han")
        assertAvailableAgreement(
            action = "che_이동",
            fixture = fixture,
            argJson = """{"destCityID":421}""",
            args = linkedMapOf("destCityID" to 421),
        )
    }

    @Test
    fun `Han-only population movement edge agrees at precheck and daemon call sites`() {
        val fixture = Fixture(cityId = 3, destCityId = 421, destCityNationId = NATION_ID, officerLevel = 12, mapName = "han")
        assertAvailableAgreement(
            action = "cr_인구이동",
            fixture = fixture,
            argJson = """{"destCityID":421,"amount":100}""",
            args = linkedMapOf("destCityID" to 421, "amount" to 100),
        )
    }

    @Test
    fun `destination command families reject Han-only cities on Che identically`() {
        val commands = linkedMapOf(
            "che_수몰" to """{"destCityID":421}""",
            "che_백성동원" to """{"destCityID":421}""",
            "che_선동" to """{"destCityID":421}""",
            "che_탈취" to """{"destCityID":421}""",
            "che_첩보" to """{"destCityID":421}""",
            "che_화계" to """{"destCityID":421}""",
            "che_파괴" to """{"destCityID":421}""",
            "che_초토화" to """{"destCityID":421}""",
            "cr_인구이동" to """{"destCityID":421,"amount":100}""",
            "che_허보" to """{"destCityID":421}""",
        )
        val fixture = Fixture(cityId = 3, destCityId = 421, destCityNationId = 2, officerLevel = 12, mapName = "che")
        for ((action, argJson) in commands) {
            val args = linkedMapOf<String, Any?>("destCityID" to 421).apply {
                if (action == "cr_인구이동") put("amount", 100)
            }
            assertDenyAgreement(
                fixture = fixture,
                reason = "Invalid destination city.",
                constraintName = "ActiveMapDestCity",
                action = action,
                argJson = argJson,
                args = args,
            )
        }
    }

    @Test
    fun `Han multi-hop sortie agrees at precheck and daemon call sites`() {
        // id 419 는 남양군/형주 「석」(析) 이다 — han.json 에 이름이 같은 「석」이 하나 더 있다
        // (id 595, 익주, 锡). 재번호매김이 419 를 다른 城으로 옮기면 이 단언이 먼저 빨개진다.
        assertEquals("석", CityConstRegistry.of("han").byId(419)!!.name)
        assertEquals(CityConstRegistry.of("han").regionIdByName("형주"), CityConstRegistry.of("han").byId(419)!!.region)

        val fixture = Fixture(
            cityId = 3,
            cityNationId = NATION_ID,
            destCityId = 29,
            destCityNationId = 2,
            routeCityId = 419,
            routeCityNationId = 0,
            crew = 1_000,
            atWarNationId = 2,
            mapName = "han",
        )
        assertAvailableAgreement(
            action = "che_출병",
            fixture = fixture,
            argJson = """{"destCityID":29}""",
            args = linkedMapOf("destCityID" to 29),
        )
    }

    @Test
    fun `OccupiedCity deny both real call sites agree with the IDENTICAL reason string`() {
        // city owned by nation 2, actor in nation 1 -> OccupiedCity denies in BOTH modes.
        assertDenyAgreement(Fixture(cityNationId = 2), "아국이 아닙니다.", "OccupiedCity")
    }

    @Test
    fun `SuppliedCity deny both real call sites agree with the IDENTICAL reason string`() {
        assertDenyAgreement(Fixture(supplyState = 0), "고립된 도시입니다.", "SuppliedCity")
    }

    @Test
    fun `ReqGeneralGold deny both real call sites agree with the IDENTICAL reason string`() {
        // gold 10 < develCost 40 -> ReqGeneralGold denies in BOTH modes.
        assertDenyAgreement(Fixture(gold = 10), "자금이 모자랍니다.", "ReqGeneralGold")
    }

    @Test
    fun `RemainCityCapacity deny both real call sites agree with the IDENTICAL reason string`() {
        assertDenyAgreement(Fixture(agri = 8000, agriMax = 8000), "농지 개간은 충분합니다.", "RemainCityCapacity")
    }

    @Test
    fun `recruit numeric string amount is normalized identically before PRECHECK and FULL capacity checks`() {
        assertRecruitDenyAgreement(
            fixture = Fixture(),
            args = linkedMapOf("crewType" to 1100, "amount" to "999999"),
            argJson = """{"crewType":1100,"amount":"999999"}""",
            reason = "주민이 부족합니다.",
            constraintName = "ReqCityCapacity",
        )
    }

    @Test
    fun `recruit malformed crewType and amount are rejected identically before execution`() {
        assertRecruitDenyAgreement(
            fixture = Fixture(),
            args = linkedMapOf("crewType" to "1100", "amount" to 100),
            argJson = """{"crewType":"1100","amount":100}""",
            reason = "인자가 올바르지 않습니다.",
            constraintName = null,
        )
        assertRecruitDenyAgreement(
            fixture = Fixture(),
            args = linkedMapOf("crewType" to 1100, "amount" to -1),
            argJson = """{"crewType":1100,"amount":-1}""",
            reason = "인자가 올바르지 않습니다.",
            constraintName = null,
        )
    }

    @Test
    fun `recruit blank unitSet fails closed at both real call sites`() {
        assertRecruitDenyAgreement(
            fixture = Fixture(unitSet = "  "),
            args = linkedMapOf("crewType" to 1100, "amount" to 100),
            argJson = """{"crewType":1100,"amount":100}""",
            reason = "현재 선택할 수 없는 병종입니다.",
            constraintName = "AvailableRecruitCrewType",
        )
    }

    @Test
    fun `recruit map-specific city and region requirements use miniche_b at both call sites`() {
        assertRecruitAvailableAgreement(
            fixture = Fixture(mapName = "miniche_b", nationTech = 3000, cityId = 1),
            args = linkedMapOf("crewType" to 1104, "amount" to 100),
            argJson = """{"crewType":1104,"amount":100}""",
        )
        assertRecruitAvailableAgreement(
            fixture = Fixture(mapName = "miniche_b", nationTech = 3000, cityId = 3),
            args = linkedMapOf("crewType" to 1204, "amount" to 100),
            argJson = """{"crewType":1204,"amount":100}""",
        )
        assertRecruitAvailableAgreement(
            fixture = Fixture(mapName = "miniche_b", nationTech = 1000, cityId = 1),
            args = linkedMapOf("crewType" to 1101, "amount" to 100),
            argJson = """{"crewType":1101,"amount":100}""",
        )
    }

    // =====================================================================================
    // P2 SAMPLE — the SAME cross-call-site invariant for a representative command per family
    // (develop / trade / personnel / nation). Each drives the REAL game-api precheck AND the
    // REAL game-engine full-mode entry point against the SAME seeded world and asserts the
    // IDENTICAL Allow/Deny + PHP reason. They share the SINGLE :logic constraint library; a
    // drifted second implementation on either side could not pass these silently.
    // =====================================================================================

    // --- CMD-DEVELOP: che_성벽보수 (wall column, strength-stat develop — a DIFFERENT develop
    // column + stat path than the canonical 농지개간 above). ---

    @Test
    fun `P2 develop che_성벽보수 AVAILABLE both real call sites agree go`() {
        // owned, supplied, funded, wall(2000) < wallMax(8000) -> both ALLOW + resolve.
        assertAvailableAgreement("che_성벽보수", Fixture())
    }

    @Test
    fun `P2 develop che_성벽보수 RemainCityCapacity deny both real call sites agree`() {
        // wall == wallMax -> RemainCityCapacity denies; josa of "성벽 보수" + "은" == "는".
        assertDenyAgreement(
            Fixture(wall = 8000, wallMax = 8000), "성벽 보수는 충분합니다.", "RemainCityCapacity",
            action = "che_성벽보수",
        )
    }

    // --- CMD-TRADE: che_헌납 (notBeNeutral/occupiedCity/suppliedCity + reqGeneralRice). The daemon
    // receives the valid required payload that PHP argTest demands; PRECHECK has no request-arg seam,
    // so its empty args select the same isGold=false rice constraint branch. ---

    @Test
    fun `P2 trade che_헌납 AVAILABLE both real call sites agree go`() {
        assertAvailableAgreement("che_헌납", Fixture(), tributeRiceArgs, tributeRiceArgsMap)
    }

    @Test
    fun `P2 trade che_헌납 SuppliedCity deny both real call sites agree`() {
        assertDenyAgreement(
            Fixture(supplyState = 0), "고립된 도시입니다.", "SuppliedCity",
            action = "che_헌납", argJson = tributeRiceArgs, args = tributeRiceArgsMap,
        )
    }

    // --- CMD-PERSONNEL: che_하야 (notBeNeutral/notLord). DENY-only via NotLord (a lord cannot
    // 하야); the resolve path is a heavy cross-entity write covered by the personnel goldens. ---

    @Test
    fun `P2 personnel che_하야 NotLord deny both real call sites agree`() {
        // officerLevel 12 (the lord) -> NotLord denies in BOTH modes.
        assertDenyAgreement(Fixture(officerLevel = 12), "군주입니다.", "NotLord", action = "che_하야")
    }

    // --- CMD-NATION: che_포상 (NationCommand: notBeNeutral/occupiedCity/beChief/suppliedCity +
    // existsDestGeneral/friendlyDestGeneral/reqNation). DENY via BeChief (a non-수뇌 cannot reward).
    // (A funded-chief ALLOW is NOT a valid cross-site case: 포상's FULL set genuinely requires a dest
    // general, which the no-args precheck cannot supply — precheck would return Unknown there, the
    // designed precheck/full divergence, not a parity failure. So 포상 is exercised DENY-only here.) ---

    @Test
    fun `P2 nation che_포상 BeChief deny both real call sites agree`() {
        // officerLevel 4 (an ordinary general, not a 수뇌 > 4) -> BeChief denies in BOTH modes,
        // ahead of the dest-general requirement.
        assertDenyAgreement(
            Fixture(officerLevel = 4),
            "수뇌가 아닙니다.",
            "BeChief",
            action = "che_포상",
            argJson = rewardRiceArgs,
            args = rewardRiceArgsMap,
        )
    }

    /**
     * Drive BOTH real call sites with [action] over the SAME AVAILABLE [fixture] and assert they
     * agree on "go": game-api precheck == [PrecheckResult.Available] AND game-engine full resolves
     * the requested action (did NOT fall back to 휴식, carries no deny reason).
     */
    private fun assertAvailableAgreement(
        action: String,
        fixture: Fixture,
        argJson: String = "",
        args: Map<String, Any?> = emptyMap(),
    ) {
        val precheck = precheckService(fixture).precheck(GENERAL_ID, action, args)
        assertEquals(PrecheckResult.Available, precheck, "game-api precheck: AVAILABLE ($action)")

        val (handler, _) = engineHandler(fixture)
        val outcome = handler.handle(GENERAL_ID, ReservedTurn(action, argJson), YEAR, MONTH, "12:34")
        assertFalse(outcome.fellBack, "game-engine full: Allow — resolved, did NOT fall back ($action)")
        assertEquals(action, outcome.definition.key, "the requested action resolved, not the fallback")
        assertEquals(null, outcome.denyReason, "no deny reason on an allowed turn ($action)")
    }

    /**
     * Drive BOTH real call sites with the SAME denying [fixture] and assert they produce the IDENTICAL
     * outcome class (Blocked / fell-back) AND the IDENTICAL PHP [reason] string (+ constraintName).
     */
    private fun assertDenyAgreement(
        fixture: Fixture,
        reason: String,
        constraintName: String,
        action: String = ACTION,
        argJson: String = "",
        args: Map<String, Any?> = emptyMap(),
    ) {
        // game-api PRECHECK -> Blocked(reason, constraintName)
        val precheck = precheckService(fixture).precheck(GENERAL_ID, action, args)
        val blocked = assertIs<PrecheckResult.Blocked>(precheck, "game-api precheck denies ($action)")
        assertEquals(reason, blocked.reason, "game-api deny reason ($action)")
        assertEquals(constraintName, blocked.constraintName, "game-api constraintName ($action)")

        // game-engine FULL -> fell back to 휴식 carrying the SAME deny reason
        val (handler, _) = engineHandler(fixture)
        val outcome = handler.handle(GENERAL_ID, ReservedTurn(action, argJson), YEAR, MONTH, "12:34")
        assertTrue(outcome.fellBack, "game-engine full denies — falls back to 휴식 ($action)")
        assertEquals("휴식", outcome.definition.key, "denied turn resolves to the fallback ($action)")
        assertEquals(reason, outcome.denyReason, "game-engine deny reason ($action)")

        // THE invariant: both REAL call sites returned the SAME class + the SAME byte-identical reason.
        assertEquals(blocked.reason, outcome.denyReason,
            "precheck reason == full reason (one shared constraint library, two real call sites)")
    }

    private fun assertRecruitAvailableAgreement(
        fixture: Fixture,
        args: Map<String, Any?>,
        argJson: String,
    ) {
        val precheck = precheckService(fixture).precheck(GENERAL_ID, RECRUIT_ACTION, args)
        assertEquals(PrecheckResult.Available, precheck, "game-api precheck: recruit AVAILABLE")

        val (handler, _) = engineHandler(fixture)
        val outcome = handler.handle(GENERAL_ID, ReservedTurn(RECRUIT_ACTION, argJson), YEAR, MONTH, "12:34")
        assertFalse(outcome.fellBack, "game-engine full: recruit Allow")
        assertEquals(RECRUIT_ACTION, outcome.definition.key)
    }

    private fun assertRecruitDenyAgreement(
        fixture: Fixture,
        args: Map<String, Any?>,
        argJson: String,
        reason: String,
        constraintName: String?,
    ) {
        val precheck = precheckService(fixture).precheck(GENERAL_ID, RECRUIT_ACTION, args)
        val blocked = assertIs<PrecheckResult.Blocked>(precheck, "game-api precheck denies recruit")
        assertEquals(reason, blocked.reason)
        assertEquals(constraintName, blocked.constraintName)

        val (handler, _) = engineHandler(fixture)
        val outcome = handler.handle(GENERAL_ID, ReservedTurn(RECRUIT_ACTION, argJson), YEAR, MONTH, "12:34")
        assertTrue(outcome.fellBack, "game-engine full denies recruit")
        assertEquals(reason, outcome.denyReason)
        assertEquals(blocked.reason, outcome.denyReason)
    }

    private fun assertMapFailureAgreement(fixture: Fixture, message: String) {
        val precheckError = assertFailsWith<RuntimeException> {
            precheckService(fixture).precheck(GENERAL_ID, ACTION)
        }
        val (handler, _) = engineHandler(fixture)
        val daemonError = assertFailsWith<RuntimeException> {
            handler.handle(GENERAL_ID, ReservedTurn(ACTION, ""), YEAR, MONTH, "12:34")
        }

        assertEquals(precheckError::class, daemonError::class)
        assertEquals(message, precheckError.message)
        assertEquals(precheckError.message, daemonError.message)
    }

    private companion object {
        const val tributeRiceArgs = """{"isGold":false,"amount":1000}"""
        const val rewardRiceArgs = """{"isGold":false,"amount":1000,"destGeneralID":99}"""
        val tributeRiceArgsMap = linkedMapOf<String, Any?>("isGold" to false, "amount" to 1000)
        val rewardRiceArgsMap = linkedMapOf<String, Any?>("isGold" to false, "amount" to 1000, "destGeneralID" to 99)
    }
}
