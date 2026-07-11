package opensamguk.engine.turn

import opensamguk.infra.persistence.ReservedTurnRepository.ReservedTurn
import opensamguk.engine.flush.DatabaseHooks
import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.statview.WorldEnvBuilder
import opensamguk.logic.stats.GeneralActionPipeline
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P1 Task F3 — the [ReservedTurnHandler] end-to-end: full constraints (the SAME `:logic` library) →
 * per-action RNG → resolve → [ChangeRecorder] (single dirty source) → dirty-free apply + logs.
 *
 * Determinism is asserted structurally (same world + same seed → identical post-state). The exact
 * float golden is pinned by the AREA G PHP oracle (G1/G2) once the captured `hiddenSeed` lands —
 * here `FIXTURE_HIDDEN_SEED` is the placeholder the resolve tests use.
 */
class ReservedTurnHandlerTest {

    private val t0 = Instant.parse("0200-01-01T12:34:00Z")
    private val pipeline = GeneralActionPipeline()
    private val registry = CommandRegistry(pipeline)

    // FIXTURE INPUT — replaced by the G1-captured golden hiddenSeed (UniqueConst::$hiddenSeed) before lock.
    private val FIXTURE_HIDDEN_SEED = "00000000000000000000000000000000"
    private val YEAR = 200
    private val MONTH = 1
    private val START_YEAR = 184

    private fun general(
        id: Int = 42,
        nationId: Int = 1,
        cityId: Int = 7,
        gold: Int = 100_000,
        intel: Int = 80,
    ) = TurnGeneral(
        id = id,
        name = "g$id",
        nationId = nationId,
        cityId = cityId,
        troopId = 0,
        stats = GeneralStats(leadership = 70, strength = 70, intelligence = intel),
        experience = 0,
        dedication = 0,
        officerLevel = 0,
        gold = gold,
        rice = 1000,
        injury = 0,
        turnTime = t0,
        // killturn>0: 살아있는 장수는 양수 killturn을 가진다(PHP는 $gameStor->killturn에서 시드). strict-< 교정
        // 후 drain 꼬리(updateTurnTime, TurnExecutionHelper.php:185)의 killturn<=0 kill 게이트가 동작하므로,
        // 기본 env baselineKillturn=0에서 killturn 미설정(0) 장수는 tail에서 kill된다 → 양수로 생존시킨다.
        meta = linkedMapOf("explevel" to 10, "intel_exp" to 3, "max_domestic_critical" to 0.0, "killturn" to 80),
    )

    private fun city(
        id: Int = 7,
        nationId: Int = 1,
        agri: Int = 1000,
        agriMax: Int = 20000,
        supplyState: Int = 1,
        frontState: Int = 0,
    ) = City(
        id = id,
        name = "c$id",
        nationId = nationId,
        level = 5,
        agriculture = agri,
        agricultureMax = agriMax,
        commerce = 1000,
        commerceMax = 20000,
        supplyState = supplyState,
        frontState = frontState,
        meta = linkedMapOf("trust" to 50),   // INTEGER-valued trust (G1 invariant), typed Double in logic
    )

    private fun nation(id: Int = 1, level: Int = 2, capital: Int = 99) =
        Nation(id = id, name = "n$id", color = "#000", level = level, capitalCityId = capital)

    private fun baseState() = TurnWorldState(
        id = 1, currentYear = YEAR, currentMonth = MONTH, tickSeconds = 3600, lastTurnTime = t0,
    )

    private fun worldWith(
        generals: List<TurnGeneral> = listOf(general()),
        cities: List<City> = listOf(city()),
        nations: List<Nation> = listOf(nation()),
    ) = InMemoryTurnWorld(WorldSnapshot(baseState(), generals, cities, nations))

    private fun handlerFor(world: InMemoryTurnWorld, scenario: Int = 0) =
        ReservedTurnHandler(world, registry, FIXTURE_HIDDEN_SEED, START_YEAR, scenario = scenario)

    @Test
    fun `available general che_농지개간 increases agriculture decreases gold pushes log and records dirty`() {
        val world = worldWith()
        val handler = handlerFor(world)

        val outcome = handler.handle(42, "che_농지개간", YEAR, MONTH, "12:34")

        assertFalse(outcome.fellBack, "AVAILABLE general resolves the requested action, not the fallback")
        assertEquals("che_농지개간", outcome.definition.key)
        assertNull(outcome.denyReason)
        assertEquals(1, outcome.logs.size, "exactly one action log (no level cross in P1)")

        // post-state visible in the world (dirty-free apply wrote the engine rows)
        val postCity = world.getCityById(7)!!
        val postGeneral = world.getGeneralById(42)!!
        assertTrue(postCity.agriculture > 1000, "agriculture increased: ${postCity.agriculture}")
        assertTrue(postGeneral.gold < 100_000, "gold decreased by reqGold: ${postGeneral.gold}")
        assertEquals(4, (postGeneral.meta["intel_exp"] as Number).toInt(), "intel_exp incremented 3 -> 4")

        // ChangeRecorder is the SINGLE dirty source — the resolver never touched world.updateGeneral/updateCity
        assertTrue(handler.recorder.isDirty, "recorder marked the mutation dirty")
        assertEquals(setOf(42), handler.recorder.dirtyGeneralIds())
        assertEquals(setOf(7), handler.recorder.dirtyCityIds())

        // the world's OWN dirty set stays empty (dirty-free apply path — flush reads the recorder, not the world)
        val worldDirty = world.consumeDirtyState()
        assertTrue(worldDirty.generals.isEmpty(), "dirty-free apply never marks the world general dirty")
        assertTrue(worldDirty.cities.isEmpty(), "dirty-free apply never marks the world city dirty")
        assertEquals(1, worldDirty.logs.size, "the action log was pushed to the world")
    }

    @Test
    fun `recorded patch matches the applied world post-state`() {
        val world = worldWith()
        val handler = handlerFor(world)

        handler.handle(42, "che_농지개간", YEAR, MONTH, "12:34")

        val cityPatch = handler.recorder.cityPatches().single()
        assertEquals(7, cityPatch.id)
        assertEquals(world.getCityById(7)!!.agriculture, cityPatch.columns["agriculture"],
            "recorder's agriculture patch equals the world's applied post-state")
    }

    @Test
    fun `blocked general non-owned city falls back to rest with deny reason and no economic mutation`() {
        // general's city is owned by nation 2, not the general's nation 1 → OccupiedCity Deny.
        val world = worldWith(
            generals = listOf(general(cityId = 7)),
            cities = listOf(city(id = 7, nationId = 2)),
            nations = listOf(nation(id = 1), nation(id = 2)),
        )
        val handler = handlerFor(world)

        val outcome = handler.handle(42, "che_농지개간", YEAR, MONTH, "12:34")

        assertTrue(outcome.fellBack, "denied turn falls back to 휴식")
        assertEquals("휴식", outcome.definition.key)
        assertEquals("아국이 아닙니다.", outcome.denyReason, "OccupiedCity deny reason (PHP getFailString)")

        // no economic mutation
        assertEquals(1000, world.getCityById(7)!!.agriculture, "agriculture untouched on a denied turn")
        assertEquals(100_000, world.getGeneralById(42)!!.gold, "gold untouched on a denied turn")
        assertFalse(handler.recorder.isDirty, "nothing recorded dirty on a denied turn")

        // the deny-reason log was pushed (휴식-fallback log)
        val worldDirty = world.consumeDirtyState()
        assertEquals(1, worldDirty.logs.size, "the deny-reason log was pushed")
        assertTrue(worldDirty.logs.single().text.contains("아국이 아닙니다."), "deny log carries the reason")
    }

    @Test
    fun `insufficient gold falls back with the funds deny reason`() {
        val world = worldWith(generals = listOf(general(gold = 0)))
        val handler = handlerFor(world)

        val outcome = handler.handle(42, "che_농지개간", YEAR, MONTH, "12:34")

        assertTrue(outcome.fellBack)
        assertEquals("자금이 모자랍니다.", outcome.denyReason, "ReqGeneralGold deny reason")
        assertEquals(0, world.getGeneralById(42)!!.gold, "gold untouched")
        assertFalse(handler.recorder.isDirty)
    }

    @Test
    fun `determinism same world and seed yield identical post-state across two runs`() {
        val worldA = worldWith()
        handlerFor(worldA).handle(42, "che_농지개간", YEAR, MONTH, "12:34")

        val worldB = worldWith()
        handlerFor(worldB).handle(42, "che_농지개간", YEAR, MONTH, "12:34")

        val a = worldA.getGeneralById(42)!!
        val b = worldB.getGeneralById(42)!!
        assertEquals(a.gold, b.gold)
        assertEquals(a.experience, b.experience)
        assertEquals(a.dedication, b.dedication)
        assertEquals(a.meta["intel_exp"], b.meta["intel_exp"])
        assertEquals(a.meta["max_domestic_critical"], b.meta["max_domestic_critical"])
        assertEquals(worldA.getCityById(7)!!.agriculture, worldB.getCityById(7)!!.agriculture)
    }

    @Test
    fun `full-mode env equals the precheck env for the same fixture (single shared env-builder)`() {
        val world = worldWith()
        val handler = handlerFor(world)

        val outcome = handler.handle(42, "che_농지개간", YEAR, MONTH, "12:34")

        // The precheck call site (E2 PrecheckStateViewFactory) builds its env through the SAME helper.
        val precheckEnv = WorldEnvBuilder.commandEnvMap(YEAR, START_YEAR, MONTH, 1)

        // key-for-key equality proves the one shared helper — neither call site can drift (P1 #7).
        assertEquals(precheckEnv, outcome.env, "full-mode env == precheck env (same WorldEnvBuilder)")
        assertEquals(precheckEnv.keys.toList(), outcome.env.keys.toList(), "env key order identical")
        assertEquals(YEAR, outcome.env["year"])
        assertEquals(START_YEAR, outcome.env["startYear"])
        assertEquals((YEAR - START_YEAR + 10) * 2, outcome.env["develCost"], "develCost = (year-startYear+10)*2")
    }

    @Test
    fun `che_인재탐색 creates discovered NPC and records active action inheritance through flush payload`() {
        val actor = general(id = 42, gold = 100_000).copy(
            userId = "777",
            name = "유비",
            stats = GeneralStats(leadership = 95, strength = 90, intelligence = 85, politics = 77, charm = 66),
            meta = linkedMapOf(
                "name" to "유비",
                "leadership_exp" to 0.0,
                "strength_exp" to 0.0,
                "intel_exp" to 0.0,
                "explevel" to 10,
                "killturn" to 80,
                "dex1" to 4,
                "dex2" to 3,
                "dex3" to 2,
                "dex4" to 1,
                "dex5" to 5,
            ),
        )
        val world = InMemoryTurnWorld(
            WorldSnapshot(
                baseState().copy(meta = linkedMapOf("maxgeneral" to 500, "develcost" to 52, "turnterm" to 60)),
                generals = listOf(actor),
                cities = listOf(city(id = 7, nationId = 1), city(id = 8, nationId = 0)),
                nations = listOf(nation(id = 1)),
            ),
        )
        val handler = handlerFor(world)

        val outcome = handler.handle(42, "che_인재탐색", YEAR, MONTH, "12:34")

        assertFalse(outcome.fellBack)
        assertEquals("che_인재탐색", outcome.definition.key)
        assertEquals(2, world.listGenerals().size, "the discovered NPC is visible in the live world")
        val created = world.listGenerals().single { it.id != 42 }
        assertEquals(3, created.npcState)
        assertEquals(50, created.stats.politics, "scout NPC keeps the 5-stat raw politics contract")
        assertEquals(50, created.stats.charm, "scout NPC keeps the 5-stat raw charm contract")
        assertTrue((created.meta["dex5"] as Number).toInt() >= 0, "dex5 is carried in meta for JSON raw round-trip")

        val payload = DatabaseHooks.toFlushPayload(world, handler.recorder, world.consumeDirtyState())
        assertEquals(listOf(created.id), payload.createdGenerals.map { it.columns["id"] })
        assertEquals(listOf("inheritance_777"), payload.inheritanceKvWrites.map { it.namespace })
        assertEquals(listOf("active_action"), payload.inheritanceKvWrites.map { it.key })
        assertTrue(
            (payload.inheritanceKvWrites.single().value as List<*>).first() as Double >= 1.0,
            "PHP valueFit(sqrt(1 / foundProp), 1) lower-bound is persisted",
        )
    }

    @Test
    fun `che_임관 preloads the destination nation and increments gennum`() {
        val actor = general(nationId = 0, cityId = 7).copy(troopId = 42)
        val lord = general(id = 50, nationId = 2, cityId = 8).copy(officerLevel = 12)
        val destNation = nation(id = 2).copy(meta = linkedMapOf("gennum" to 1, "scout" to 0))
        val world = worldWith(
            generals = listOf(actor, lord),
            cities = listOf(city(id = 7, nationId = 0), city(id = 8, nationId = 2)),
            nations = listOf(destNation),
        )
        val handler = handlerFor(world)

        val outcome = handler.handle(42, ReservedTurn("che_임관", """{"destNationID":2}"""), YEAR, MONTH, "12:34")

        assertFalse(outcome.fellBack, "임관 should pass full constraints: ${outcome.denyReason}")
        val joined = world.getGeneralById(42)!!
        assertEquals(2, joined.nationId)
        assertEquals(1, joined.officerLevel)
        assertEquals(8, joined.cityId, "지정 국가 임관은 목적 국가 군주의 도시로 이동한다")
        assertEquals(0, joined.troopId, "che_임관은 PHP처럼 troop을 0으로 리셋한다")
        assertEquals(2, (world.getNationById(2)!!.meta["gennum"] as Number).toInt())
        assertEquals(setOf(2), handler.recorder.dirtyNationIds())
    }

    @Test
    fun `che_랜덤임관 loads live candidate nations and increments gennum`() {
        val actor = general(nationId = 0, cityId = 7).copy(
            npcState = 2,
            meta = general().meta + mapOf("affinity" to 40, "name" to "g42"),
        )
        val lord = general(id = 50, nationId = 2, cityId = 8).copy(officerLevel = 12, npcState = 2)
        val destNation = nation(id = 2).copy(meta = linkedMapOf("gennum" to 1, "scout" to 0, "affinity" to 45))
        val world = worldWith(
            generals = listOf(actor, lord),
            cities = listOf(city(id = 7, nationId = 0), city(id = 8, nationId = 2)),
            nations = listOf(destNation),
        )
        val handler = handlerFor(world, scenario = 1010)

        val outcome = handler.handle(42, ReservedTurn("che_랜덤임관", ""), YEAR, MONTH, "12:34")

        assertFalse(outcome.fellBack, "랜덤임관 should pass full constraints: ${outcome.denyReason}")
        val joined = world.getGeneralById(42)!!
        assertEquals(2, joined.nationId)
        assertEquals(1, joined.officerLevel)
        assertEquals(8, joined.cityId)
        assertEquals(2, (world.getNationById(2)!!.meta["gennum"] as Number).toInt())
        assertTrue(outcome.logs.none { it.contains("임관 가능한 국가가 없습니다.") })
        assertEquals(setOf(2), handler.recorder.dirtyNationIds())
    }

    @Test
    fun `che_장수대상임관 follows the target general city and keeps troop`() {
        val actor = general(nationId = 0, cityId = 7).copy(troopId = 42)
        val target = general(id = 50, nationId = 2, cityId = 8).copy(officerLevel = 1)
        val lord = general(id = 51, nationId = 2, cityId = 9).copy(officerLevel = 12)
        val destNation = nation(id = 2).copy(meta = linkedMapOf("gennum" to 1, "scout" to 0))
        val world = worldWith(
            generals = listOf(actor, target, lord),
            cities = listOf(city(id = 7, nationId = 0), city(id = 8, nationId = 2), city(id = 9, nationId = 2)),
            nations = listOf(destNation),
        )
        val handler = handlerFor(world)

        val outcome = handler.handle(42, ReservedTurn("che_장수대상임관", """{"destGeneralID":50}"""), YEAR, MONTH, "12:34")

        assertFalse(outcome.fellBack, "장수대상임관 should pass full constraints: ${outcome.denyReason}")
        val joined = world.getGeneralById(42)!!
        assertEquals(2, joined.nationId)
        assertEquals(1, joined.officerLevel)
        assertEquals(8, joined.cityId, "장수대상임관은 목적 장수의 도시를 따른다")
        assertEquals(42, joined.troopId, "che_장수대상임관은 PHP처럼 troop을 유지한다")
        assertEquals(2, (world.getNationById(2)!!.meta["gennum"] as Number).toInt())
        assertEquals(setOf(2), handler.recorder.dirtyNationIds())
    }

    @Test
    fun `lifecycle drains all due generals in one pass`() {
        val world = worldWith(
            generals = listOf(general(id = 42), general(id = 43)),
            cities = listOf(city(id = 7)),
        )
        val handler = handlerFor(world)
        val lifecycle = TurnDaemonLifecycle(world, handler) {
            opensamguk.infra.persistence.ReservedTurnRepository.ReservedTurn("che_농지개간", "")
        }

        // PHP 선택 게이트(TurnExecutionHelper.php:237) `turntime < %s`(STRICT <): turnTime(t0)과 같은
        // 시각은 due가 아니다. t0보다 미래 시각을 넘겨 두 장수를 due로 만든다(과거 inclusive `<=` 버그 제거).
        val runTime = t0.plusSeconds(1)  // both generals' turnTime(t0) < runTime → due
        val handled = lifecycle.runTick(runTime)

        assertEquals(2, handled.size, "both due generals processed in one pass")
        assertEquals(listOf(42, 43), handled.map { it.generalId }, "deterministic order: ascending id")
        assertNotNull(handled.first { it.generalId == 42 })
        // both generals share the one city; the recorder accumulates across the pass (one flush boundary)
        assertEquals(setOf(42, 43), handler.recorder.dirtyGeneralIds())
        assertEquals(setOf(7), handler.recorder.dirtyCityIds())
    }
}
