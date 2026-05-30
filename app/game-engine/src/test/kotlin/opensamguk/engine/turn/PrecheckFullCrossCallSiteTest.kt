package opensamguk.engine.turn

import opensamguk.gameapi.precheck.CommandPrecheckService
import opensamguk.gameapi.precheck.PrecheckResult
import opensamguk.gameapi.precheck.PrecheckStateViewFactory
import opensamguk.gameapi.read.CityReadEntity
import opensamguk.gameapi.read.CityReadRepository
import opensamguk.gameapi.read.DiplomacyReadRepository
import opensamguk.gameapi.read.GeneralReadEntity
import opensamguk.gameapi.read.GeneralReadRepository
import opensamguk.gameapi.read.NationReadEntity
import opensamguk.gameapi.read.NationReadRepository
import opensamguk.gameapi.read.WorldStateReadEntity
import opensamguk.gameapi.read.WorldStateReadRepository
import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.stats.GeneralActionPipeline
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.time.Instant
import java.util.Optional
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
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
        val supplyState: Int = 1,            // 0 -> SuppliedCity deny
        val agri: Int = 4000,
        val agriMax: Int = 8000,             // agri == agriMax -> RemainCityCapacity deny
        val wall: Int = 2000,
        val wallMax: Int = 8000,             // wall == wallMax -> RemainCityCapacity deny (성벽 보수)
        val officerLevel: Int = 5,           // 12 -> NotLord deny (하야); != 12 -> BeChief deny (포상)
    )

    // --- game-api side: project the fixture into JPA read entities + stub the repos ---

    private fun precheckService(f: Fixture): CommandPrecheckService {
        val general = GeneralReadEntity(
            id = GENERAL_ID, nationId = NATION_ID, cityId = CITY_ID,
            leadership = 70, strength = 30, intel = 95, injury = 0,
            experience = 1200, dedication = 900, officerLevel = f.officerLevel,
            gold = f.gold, rice = f.rice,
            meta = linkedMapOf("explevel" to 4, "intel_exp" to 12, "max_domestic_critical" to 3.5),
        )
        val city = CityReadEntity(
            id = CITY_ID, nationId = f.cityNationId, level = 5,
            commerce = 3000, commerceMax = 8000, agriculture = f.agri, agricultureMax = f.agriMax,
            supplyState = f.supplyState, frontState = 0, trust = 82,
            wall = f.wall, wallMax = f.wallMax,
            meta = linkedMapOf(),
        )
        val nation = NationReadEntity(id = NATION_ID, level = 7, capitalCityId = CITY_ID)
        val worldState = WorldStateReadEntity(
            id = 1, scenarioCode = "scenario_2", currentYear = YEAR, currentMonth = MONTH,
            tickSeconds = 3600, config = linkedMapOf("startYear" to START_YEAR), meta = linkedMapOf(),
        )

        val generals = mock(GeneralReadRepository::class.java)
        val cities = mock(CityReadRepository::class.java)
        val nations = mock(NationReadRepository::class.java)
        val diplomacies = mock(DiplomacyReadRepository::class.java)
        val worldStates = mock(WorldStateReadRepository::class.java)
        `when`(generals.findById(GENERAL_ID)).thenReturn(Optional.of(general))
        `when`(cities.findById(CITY_ID)).thenReturn(Optional.of(city))
        `when`(nations.findById(NATION_ID)).thenReturn(Optional.of(nation))
        `when`(diplomacies.findBySrcNationId(NATION_ID)).thenReturn(emptyList())
        `when`(worldStates.findAll()).thenReturn(listOf(worldState))
        val factory = PrecheckStateViewFactory(generals, cities, nations, diplomacies, worldStates)
        return CommandPrecheckService(factory, registry)
    }

    // --- game-engine side: project the SAME fixture into the in-memory turn world ---

    private fun engineHandler(f: Fixture): Pair<ReservedTurnHandler, InMemoryTurnWorld> {
        val general = TurnGeneral(
            id = GENERAL_ID, name = "g$GENERAL_ID", nationId = NATION_ID, cityId = CITY_ID, troopId = 0,
            stats = GeneralStats(leadership = 70, strength = 30, intelligence = 95),
            experience = 1200, dedication = 900, officerLevel = f.officerLevel,
            gold = f.gold, rice = f.rice, injury = 0, turnTime = t0,
            meta = linkedMapOf("explevel" to 4, "intel_exp" to 12, "max_domestic_critical" to 3.5),
        )
        val city = City(
            id = CITY_ID, name = "c$CITY_ID", nationId = f.cityNationId, level = 5,
            commerce = 3000, commerceMax = 8000, agriculture = f.agri, agricultureMax = f.agriMax,
            supplyState = f.supplyState, frontState = 0,
            wall = f.wall, wallMax = f.wallMax,
            meta = linkedMapOf("trust" to 82),   // engine City has no trust column; lives in meta
        )
        // The actor's own nation; a second nation 2 exists so the OccupiedCity-deny fixture (city
        // owned by nation 2) resolves a real nation row, matching the game-api stub world.
        val nations = listOf(
            Nation(id = NATION_ID, name = "n$NATION_ID", color = "#000", level = 7, capitalCityId = CITY_ID),
            Nation(id = 2, name = "n2", color = "#111", level = 7, capitalCityId = null),
        )
        val state = TurnWorldState(
            id = 1, currentYear = YEAR, currentMonth = MONTH, tickSeconds = 3600, lastTurnTime = t0,
        )
        val world = InMemoryTurnWorld(WorldSnapshot(state, listOf(general), listOf(city), nations))
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

    // --- CMD-TRADE: che_헌납 (notBeNeutral/occupiedCity/suppliedCity + reqGeneralRice; empty args
    // -> isGold=false -> the rice branch, identical on both sites). ---

    @Test
    fun `P2 trade che_헌납 AVAILABLE both real call sites agree go`() {
        // owned, supplied, rice(3000) >= generalMinimumRice(500) -> both ALLOW + resolve (amount 0).
        assertAvailableAgreement("che_헌납", Fixture())
    }

    @Test
    fun `P2 trade che_헌납 SuppliedCity deny both real call sites agree`() {
        assertDenyAgreement(Fixture(supplyState = 0), "고립된 도시입니다.", "SuppliedCity", action = "che_헌납")
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
    // BeChief is the 3rd constraint, ahead of the dest-general requirements — so it denies on BOTH
    // sides with empty args (no dest general loaded), keeping this a clean pure-state cross-site deny.
    // (A funded-chief ALLOW is NOT a valid cross-site case: 포상's FULL set genuinely requires a dest
    // general, which the no-args precheck cannot supply — precheck would return Unknown there, the
    // designed precheck/full divergence, not a parity failure. So 포상 is exercised DENY-only here.) ---

    @Test
    fun `P2 nation che_포상 BeChief deny both real call sites agree`() {
        // officerLevel 4 (an ordinary general, not a 수뇌 > 4) -> BeChief denies in BOTH modes,
        // ahead of the dest-general requirement.
        assertDenyAgreement(Fixture(officerLevel = 4), "수뇌가 아닙니다.", "BeChief", action = "che_포상")
    }

    /**
     * Drive BOTH real call sites with [action] over the SAME AVAILABLE [fixture] and assert they
     * agree on "go": game-api precheck == [PrecheckResult.Available] AND game-engine full resolves
     * the requested action (did NOT fall back to 휴식, carries no deny reason).
     */
    private fun assertAvailableAgreement(action: String, fixture: Fixture) {
        val precheck = precheckService(fixture).precheck(GENERAL_ID, action)
        assertEquals(PrecheckResult.Available, precheck, "game-api precheck: AVAILABLE ($action)")

        val (handler, _) = engineHandler(fixture)
        val outcome = handler.handle(GENERAL_ID, action, YEAR, MONTH, "12:34")
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
    ) {
        // game-api PRECHECK -> Blocked(reason, constraintName)
        val precheck = precheckService(fixture).precheck(GENERAL_ID, action)
        val blocked = assertIs<PrecheckResult.Blocked>(precheck, "game-api precheck denies ($action)")
        assertEquals(reason, blocked.reason, "game-api deny reason ($action)")
        assertEquals(constraintName, blocked.constraintName, "game-api constraintName ($action)")

        // game-engine FULL -> fell back to 휴식 carrying the SAME deny reason
        val (handler, _) = engineHandler(fixture)
        val outcome = handler.handle(GENERAL_ID, action, YEAR, MONTH, "12:34")
        assertTrue(outcome.fellBack, "game-engine full denies — falls back to 휴식 ($action)")
        assertEquals("휴식", outcome.definition.key, "denied turn resolves to the fallback ($action)")
        assertEquals(reason, outcome.denyReason, "game-engine deny reason ($action)")

        // THE invariant: both REAL call sites returned the SAME class + the SAME byte-identical reason.
        assertEquals(blocked.reason, outcome.denyReason,
            "precheck reason == full reason (one shared constraint library, two real call sites)")
    }
}
