package opensamguk.engine.turn

import opensamguk.engine.flush.DatabaseHooks
import opensamguk.infra.persistence.ReservedTurnRepository.ReservedTurn
import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.event.EventTarget
import opensamguk.logic.stats.GeneralActionPipeline
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * WAVE 0 — the founding created-set live-daemon seam (the prod crash-loop fix).
 *
 * The golden `GeobyeongTest` (logic-level) proves `che_거병` resolves draw-for-draw, but it hand-builds the
 * preload args and reads `draft.createdNations` directly — it BYPASSES the engine→logic seam where the live
 * daemon crashed (`CheGeobyeong.kt:71` `error("거병 requires a preloaded newNationId …")`) and where the
 * founding INSERTs were never drained. This test closes that seam:
 *
 *  1. `che_거병` founds a nation THROUGH `ReservedTurnHandler.handle` with NO exception (the crash regression
 *     guard) and drains the created-set (nation + diplomacy + 24 nation_turn) into the world.
 *  2. the drained created-set survives into the [DatabaseHooks.toFlushPayload] (the FK-ordered flush input),
 *     and the actor lands in `updatedGenerals` (INSERTed nation, UPDATEd actor — §2 reconciliation).
 *  3. `secretlimit` honors the `scenario` ctor param (≥1000 ⇒ 1, the live-server branch; else 3).
 *
 * The phase gate stays the real PHP `che_거병` golden (`GeobyeongTest`); this engine test covers the seam the
 * golden cannot reach. (건국/cr_건국/무작위건국 Bug-B coverage is WAVE 0b — it needs the `sameMonthOrBefore`
 * preload faithfully ported first.)
 */
class FoundingHandlerSeamTest {

    private val t0 = Instant.parse("0190-07-01T08:30:00Z")
    private val pipeline = GeneralActionPipeline()
    private val registry = CommandRegistry(pipeline)

    private val FIXTURE_HIDDEN_SEED = "00000000000000000000000000000000"
    private val YEAR = 190
    private val MONTH = 7
    private val START_YEAR = 184

    /** A neutral actor at 성도 (city 5). makelimit/penalty absent ⇒ AllowJoinAction/NoPenalty pass. */
    private fun actor(id: Int = 42, name: String = "유비") = TurnGeneral(
        id = id,
        name = name,
        nationId = 0,
        cityId = 5,
        troopId = 0,
        stats = GeneralStats(leadership = 80, strength = 70, intelligence = 75),
        experience = 0,
        dedication = 0,
        officerLevel = 0,
        gold = 100,
        rice = 100,
        injury = 0,
        npcState = 0,
        turnTime = t0,
        meta = linkedMapOf("name" to name, "explevel" to 1, "dedlevel" to 1, "officer_city" to 5),
    )

    private fun wanderingLord(id: Int = 42, name: String = "진표", cityId: Int = 5) = actor(id, name).copy(
        nationId = 7,
        cityId = cityId,
        officerLevel = 12,
        meta = actor(id, name).meta + ("officer_city" to cityId),
    )

    private fun member(id: Int = 43, name: String = "관우", cityId: Int = 5) = actor(id, name).copy(
        nationId = 7,
        cityId = cityId,
        officerLevel = 1,
        meta = actor(id, name).meta + ("officer_city" to cityId),
    )

    private fun homeCity(id: Int = 5, name: String = "성도", level: Int = 5) = City(
        id = id,
        name = name,
        nationId = 0,
        level = level,
        commerce = 100,
        commerceMax = 100,
        agriculture = 100,
        agricultureMax = 100,
        supplyState = 1,
        frontState = 0,
        meta = linkedMapOf("trust" to 50),
    )

    private fun existingNation(id: Int) =
        Nation(id = id, name = "n$id", color = "#000", level = 2, capitalCityId = 90 + id)

    private fun wanderingNation(id: Int = 7, name: String = "진표", gennum: Int = 1) =
        Nation(id = id, name = name, color = "#330000", level = 0, capitalCityId = 0, meta = mapOf("gennum" to gennum))

    private fun baseState() = TurnWorldState(
        id = 1, currentYear = YEAR, currentMonth = MONTH, tickSeconds = 3600, lastTurnTime = t0,
        config = linkedMapOf("mapName" to "che"),
    )

    /** A world with two existing nations {1,2} + a neutral actor at 성도 → allocateNationId() == 3. */
    private fun worldWith() = InMemoryTurnWorld(
        WorldSnapshot(
            baseState(),
            generals = listOf(actor()),
            cities = listOf(homeCity()),
            nations = listOf(existingNation(1), existingNation(2)),
            worldId = opensamguk.common.world.WorldId((baseState()).id),
        ),
    )

    private fun handlerFor(
        world: InMemoryTurnWorld,
        scenario: Int,
        dynamicEventHandler: (EventTarget) -> Unit = { },
    ) = ReservedTurnHandler(
        world,
        registry,
        FIXTURE_HIDDEN_SEED,
        START_YEAR,
        scenario = scenario,
        dynamicEventHandler = dynamicEventHandler,
    )

    // ── (1) 거병 founds through the handler + drains the created-set ─────────────────────────────────

    @Test
    fun `che_거병 founds a nation through the handler and drains the created-set`() {
        val world = worldWith()
        val handler = handlerFor(world, scenario = 1010)

        // The crash regression guard: handle() must NOT throw (was CheGeobyeong.kt:71 every tick).
        val outcome = handler.handle(42, ReservedTurn("che_거병", ""), YEAR, MONTH, "08:30")
        assertFalse(outcome.fellBack, "거병 passed FULL constraints and resolved (not a 휴식 fallback)")

        val dirty = world.consumeDirtyState()

        // nation INSERT: id == allocateNationId() over {1,2} == 3; name == actor; secretlimit 1 (scenario≥1000).
        assertEquals(1, dirty.createdNations.size, "exactly one nation INSERTed")
        val n = dirty.createdNations.single()
        assertEquals(3, n.id, "placeholder id == maxNationId(2) + 1")
        assertEquals("유비", n.name, "the created nation takes the actor's name (generalName threaded)")
        assertEquals("che_중립", n.typeCode)
        assertEquals(2000, n.rice)
        assertEquals(1, (n.meta["secretlimit"] as Number).toInt(), "secretlimit 1 because scenario 1010 ≥ 1000")
        assertEquals(
            listOf(
                "capset",
                "gennum",
                "bill",
                "rate",
                "rate_tmp",
                "secretlimit",
                "chief_set",
                "scout",
                "war",
                "strategic_cmd_limit",
                "surlimit",
                "spy",
                "aux",
            ),
            n.meta.keys.toList(),
            "created nation folded meta follows the PHP nation schema order",
        )
        assertEquals(
            linkedMapOf<String, Any?>(
                "capset" to 0,
                "gennum" to 1,
                "bill" to 100,
                "rate" to 20,
                "rate_tmp" to 0,
                "secretlimit" to 1,
                "chief_set" to 0,
                "scout" to 0,
                "war" to 0,
                "strategic_cmd_limit" to 12,
                "surlimit" to 72,
                "spy" to "{}",
                "aux" to "{}",
            ),
            n.meta,
            "explicit INSERT values override schema defaults while omitted columns materialize their defaults",
        )
        val logicNation = PerTurnOverlay.toLogicNation(n)
        assertEquals(1, logicNation.gennum, "typed gennum round-trips through folded meta")
        assertEquals(0, logicNation.capset, "typed capset round-trips through folded meta")

        // 24 nation_turn rows (outer [12,11] × inner 0..11), all keyed to the new nation.
        assertEquals(24, dirty.nationTurnDirty.size, "24 reserved nation_turn rows drained")
        assertTrue(dirty.nationTurnDirty.all { it.nationId == 3 }, "all nation_turn rows key the new nation")
        assertEquals(
            buildList { for (lvl in listOf(12, 11)) for (idx in 0 until 12) add(lvl to idx) },
            dirty.nationTurnDirty.map { it.officerLevel to it.turnIdx },
            "nation_turn outer officer_level [12,11] × inner turn_idx 0..11",
        )

        // diplomacy: 2 rows per existing nation, ascending {dest,new} then {new,dest}, state 2 term 0.
        assertEquals(4, dirty.createdDiplomacy.size, "2 rows × 2 existing nations")
        assertEquals(
            listOf(1 to 3, 3 to 1, 2 to 3, 3 to 2),
            dirty.createdDiplomacy.map { it.fromNationId to it.toNationId },
            "ascending pair order {dest,new} then {new,dest}",
        )
        assertTrue(dirty.createdDiplomacy.all { it.state == 2 && it.term == 0 })

        // the actor JOINs: the world read-state reflects the new nation; the recorder marks it dirty.
        val joinedActor = world.getGeneralById(42)!!
        assertEquals(3, joinedActor.nationId, "actor's nation is the new nation")
        assertEquals(12, joinedActor.officerLevel, "actor becomes the lord (officer_level 12)")
        assertTrue(handler.recorder.dirtyGeneralIds().contains(42), "the actor UPDATE rides the recorder")

        val globalLogs = dirty.logs.filter { it.scope == "global" }.map { it.text }
        assertEquals(
            listOf(
                "<C>●</>${YEAR}년 ${MONTH}월:<Y><b>【거병】</b></><D><b>유비</b></>가 세력을 결성하였습니다.",
                "<C>●</>${YEAR}년 ${MONTH}월:<C><b>【아이템】</b></><D><b>재야</b></>의 <Y>유비</>가 <C>흉노마(+8)</>를 습득했습니다!",
                "<C>●</>${MONTH}월:<Y>유비</>가 <G><b>성도</b></>에 거병하였습니다.",
                "<C>●</>${MONTH}월:<Y>유비</>가 <C>흉노마(+8)</>를 습득했습니다!",
            ),
            globalLogs,
            "거병과 아이템 로그는 PHP ActionLogger의 global history/action 버킷 순서를 따른다",
        )
    }

    // ── (2) the created-set survives into the flush payload (FK-ordered) ─────────────────────────────

    @Test
    fun `the founding created-set survives to the flush payload`() {
        val world = worldWith()
        val handler = handlerFor(world, scenario = 1010)

        handler.handle(42, ReservedTurn("che_거병", ""), YEAR, MONTH, "08:30")
        val payload = DatabaseHooks.toFlushPayload(world, handler.recorder, world.consumeDirtyState())

        assertEquals(1, payload.createdNations.size, "createdNations carried to the flush")
        assertEquals(24, payload.createdNationTurns.size, "createdNationTurns carried to the flush")
        assertEquals(4, payload.createdDiplomacy.size, "createdDiplomacy carried to the flush")
        assertEquals(
            listOf(
                "HISTORY" to "<C>●</>${YEAR}년 ${MONTH}월:<G><b>성도</b></>에서 거병",
                "ACTION" to "<C>●</>${MONTH}월:거병에 성공하였습니다. <1>08:30</>",
            ),
            payload.logEntries
                .filter { it.scope == "GENERAL" && it.generalId == 42 }
                .filter { it.text.endsWith("에서 거병") || it.text.contains("거병에 성공하였습니다.") }
                .map { it.category to it.text },
            "Kotlin flush payload follows PHP ActionLogger general-history then general-action bucket order",
        )

        // the actor is an UPDATE (NOT a create) with the new nation id — the §2 reconciliation + FK ordering
        // (nation INSERT step-3 before the actor's nation_id UPDATE step-7).
        val updatedActor = payload.updatedGenerals.firstOrNull { it.id == 42 }
        assertNotNull(updatedActor, "the actor lands in updatedGenerals (not createdGenerals)")
        assertEquals(3, updatedActor!!.nationId, "the actor UPDATE carries the new nation id")
        assertEquals(12, updatedActor.officerLevel)
        assertTrue(payload.createdNations.none { it.id == 42 }, "the actor is NOT in createdNations")
    }

    // ── (3) secretlimit honors the scenario param ───────────────────────────────────────────────────

    @Test
    fun `secretlimit honors the scenario param`() {
        val wLive = worldWith()
        handlerFor(wLive, scenario = 1010).handle(42, ReservedTurn("che_거병", ""), YEAR, MONTH, "08:30")
        assertEquals(
            1,
            (wLive.consumeDirtyState().createdNations.single().meta["secretlimit"] as Number).toInt(),
            "scenario 1010 ≥ 1000 ⇒ secretlimit 1",
        )

        val wLegacy = worldWith()
        handlerFor(wLegacy, scenario = 999).handle(42, ReservedTurn("che_거병", ""), YEAR, MONTH, "08:30")
        assertEquals(
            3,
            (wLegacy.consumeDirtyState().createdNations.single().meta["secretlimit"] as Number).toInt(),
            "scenario 999 < 1000 ⇒ secretlimit 3",
        )
    }

    @Test
    fun `created nation explicit meta overrides defaults while typed counters remain canonical`() {
        val logic = opensamguk.logic.domain.Nation(
            id = 3,
            level = 0,
            capitalCityId = 0,
            gennum = 7,
            capset = 9,
            meta = linkedMapOf(
                "rate_tmp" to 17,
                "gennum" to 99,
                "capset" to 98,
                "custom" to "kept",
            ),
        )

        val engine = PerTurnOverlay.toEngineNation(logic)

        assertEquals(17, engine.meta["rate_tmp"], "explicit folded value overrides the PHP schema default")
        assertEquals("kept", engine.meta["custom"], "non-schema explicit meta survives the conversion")
        assertEquals(7, engine.meta["gennum"], "typed gennum is canonical over conflicting explicit meta")
        assertEquals(9, engine.meta["capset"], "typed capset is canonical over conflicting explicit meta")
        assertEquals(7, PerTurnOverlay.toLogicNation(engine).gennum)
        assertEquals(9, PerTurnOverlay.toLogicNation(engine).capset)
    }

    @Test
    fun `che_해산 through the handler tombstones the wandering nation`() {
        val world = InMemoryTurnWorld(
            WorldSnapshot(
                baseState(),
                generals = listOf(
                    wanderingLord().copy(gold = 5_000, rice = 5_000, troopId = 42, meta = wanderingLord().meta + ("belong" to 9)),
                    member(id = 43, name = "관우").copy(gold = 5_000, rice = 7_000, troopId = 42, meta = member(43, "관우").meta + ("belong" to 4)),
                ),
                cities = listOf(homeCity().copy(nationId = 7, frontState = 1), homeCity(id = 6, name = "허창", level = 5).copy(nationId = 7, frontState = 3)),
                nations = listOf(wanderingNation()),
                worldId = opensamguk.common.world.WorldId((baseState()).id),
            ),
        )
        val dispatched = mutableListOf<EventTarget>()
        val handler = handlerFor(world, scenario = 1010) { target ->
            assertNull(world.getNationById(7), "nation deletion must precede OCCUPY_CITY")
            assertTrue(world.listGenerals().all { it.nationId == 0 }, "general neutralization must precede OCCUPY_CITY")
            assertTrue(world.listCities().all { it.nationId == 0 && it.frontState == 0 }, "city release must precede OCCUPY_CITY")
            dispatched += target
        }

        val outcome = handler.handle(42, ReservedTurn("che_해산", ""), YEAR, MONTH, "08:30")

        assertFalse(outcome.fellBack, "해산 passed FULL constraints and resolved")
        assertNull(world.getNationById(7), "the disbanded wandering nation leaves the world")
        val lord = world.getGeneralById(42)!!
        val follower = world.getGeneralById(43)!!
        assertEquals(0, lord.nationId)
        assertEquals(0, follower.nationId)
        assertEquals(0, lord.officerLevel)
        assertEquals(0, follower.officerLevel)
        assertEquals(0, lord.troopId)
        assertEquals(0, follower.troopId)
        assertEquals(1_000, lord.gold, "lord gold clamps to default")
        assertEquals(1_000, lord.rice, "lord rice clamps to default")
        assertEquals(1_000, follower.gold, "member gold clamps through deleteNation pre-update")
        assertEquals(7_000, follower.rice, "PHP rice clamp bug leaves member rice unchanged after gold clamp")
        assertEquals(0, world.getCityById(5)!!.nationId)
        assertEquals(0, world.getCityById(5)!!.frontState)
        assertEquals(0, world.getCityById(6)!!.nationId)
        assertEquals(0, world.getCityById(6)!!.frontState)
        assertEquals(listOf(EventTarget.OCCUPY_CITY), dispatched, "successful disband dispatches OCCUPY_CITY once")
        val dirty = world.consumeDirtyState()
        assertTrue(
            dirty.logs.any {
                it.scope == "global" &&
                    it.category == "action" &&
                    it.text == "<C>●</>${MONTH}월:<Y>진표</>가 세력을 해산했습니다."
            },
            "해산 global action log must keep the actor name; empty '<Y></>가 …' is a prod regression",
        )
        assertTrue(dirty.logs.any { it.scope == "global" && it.category == "history" && it.text.contains("【멸망】") })
        assertTrue(dirty.logs.any { it.scope == "general" && it.generalId == 43 && it.category == "history" && it.text.contains("<R>멸망</>") })
        val payload = DatabaseHooks.toFlushPayload(world, handler.recorder, dirty)
        assertEquals(listOf(7), payload.deletedNations, "deleted nation reaches the flush payload")
        assertEquals(7, payload.deletedNationSnapshots.single()["nation"])
        assertEquals(listOf(42, 43), payload.updatedGenerals.map { it.id }.sorted())
        assertEquals(listOf(5, 6), payload.updatedCities.map { it.id }.sorted())
        assertEquals(0, payload.updatedGenerals.single { it.id == 42 }.nationId)
    }

    @Test
    fun `che_건국 through the handler raises the wandering nation and claims the city`() {
        val world = InMemoryTurnWorld(
            WorldSnapshot(
                baseState(),
                generals = listOf(wanderingLord().copy(userId = "55")),
                cities = listOf(homeCity()),
                nations = listOf(wanderingNation(gennum = 2), existingNation(1)),
                worldId = opensamguk.common.world.WorldId((baseState()).id),
            ),
        )
        val handler = handlerFor(world, scenario = 1010)

        val outcome = handler.handle(
            42,
            ReservedTurn("che_건국", """{"nationName":"촉","nationType":"che_명가","colorType":5}"""),
            YEAR,
            MONTH,
            "08:30",
        )

        assertFalse(
            outcome.fellBack,
            "건국 args must pass full constraints through the daemon seam: ${outcome.denyReason}",
        )
        val nation = world.getNationById(7)!!
        assertEquals(1, nation.level)
        assertEquals("촉", nation.name)
        assertEquals("che_명가", nation.typeCode)
        assertEquals(5, nation.capitalCityId)
        assertEquals(7, world.getCityById(5)!!.nationId)
        assertEquals(1000, world.getGeneralById(42)!!.experience)

        val dirty = world.consumeDirtyState()
        val payload = DatabaseHooks.toFlushPayload(world, handler.recorder, dirty)
        assertEquals(7, payload.updatedNations.single { it.id == 7 }.id)
        assertEquals(7, payload.updatedCities.single { it.id == 5 }.nationId)
        assertEquals(1000.0, payload.updatedGenerals.single { it.id == 42 }.experience)
        assertTrue(
            dirty.logs.any {
                it.scope == "global" &&
                    it.text == "<C>●</>${MONTH}월:<Y>진표</>가 <G><b>성도</b></>에 국가를 건설하였습니다."
            },
            "건국 global action log is drained to the global scope",
        )
        assertTrue(
            dirty.logs.any {
                it.scope == "global" &&
                    it.category == "history" &&
                    it.text == "<C>●</>${YEAR}년 ${MONTH}월:<Y><b>【건국】</b></>명가 <D><b>촉</b></>이 새로이 등장하였습니다."
            },
            "건국 global history log is drained",
        )
        assertEquals(
            listOf("active_action", "unifier"),
            handler.recorder.inheritanceKvWrites().map { it.key },
            "che_건국 grants active_action then unifier",
        )
    }

    @Test
    fun `che_건국 same-month guard executes 인재탐색 alternative with the same turn rng`() {
        val world = InMemoryTurnWorld(
            WorldSnapshot(
                baseState().copy(meta = linkedMapOf("init_year" to YEAR, "init_month" to MONTH)),
                generals = listOf(wanderingLord().copy(gold = 1_000)),
                cities = listOf(homeCity()),
                nations = listOf(
                    wanderingNation(gennum = 2),
                    existingNation(1),
                ),
                worldId = opensamguk.common.world.WorldId((baseState().copy(meta = linkedMapOf("init_year" to YEAR, "init_month" to MONTH))).id),
            ),
        )
        val handler = handlerFor(world, scenario = 1010)

        val outcome = handler.handle(
            42,
            ReservedTurn("che_건국", """{"nationName":"촉","nationType":"che_명가","colorType":5}"""),
            YEAR,
            MONTH,
            "08:30",
        )

        assertFalse(outcome.fellBack)
        assertEquals(0, world.getNationById(7)!!.level, "same-month guard must leave the wandering nation untouched")
        val logs = world.consumeDirtyState().logs.map { it.text }
        assertTrue(logs.first().contains("다음 턴부터 건국할 수 있습니다."))
        assertTrue(logs.drop(1).any { it.contains("인재") }, "alternative scout must run after the block log")
    }

    @Test
    fun `che_건국 same-month guard ignores stale nation init meta and reads game env`() {
        val world = InMemoryTurnWorld(
            WorldSnapshot(
                baseState().copy(meta = linkedMapOf("init_year" to YEAR - 1, "init_month" to MONTH)),
                generals = listOf(wanderingLord().copy(userId = "55")),
                cities = listOf(homeCity()),
                nations = listOf(
                    wanderingNation(gennum = 2).copy(meta = mapOf("gennum" to 2, "init_year" to YEAR, "init_month" to MONTH)),
                    existingNation(1),
                ),
                worldId = opensamguk.common.world.WorldId((baseState().copy(meta = linkedMapOf("init_year" to YEAR - 1, "init_month" to MONTH))).id),
            ),
        )
        val handler = handlerFor(world, scenario = 1010)

        val outcome = handler.handle(
            42,
            ReservedTurn("che_건국", """{"nationName":"촉","nationType":"che_명가","colorType":5}"""),
            YEAR,
            MONTH,
            "08:30",
        )

        assertFalse(outcome.fellBack)
        assertEquals(1, world.getNationById(7)!!.level, "stale nation init meta must not trigger the same-month block")
        assertEquals(7, world.getCityById(5)!!.nationId)
    }

    @Test
    fun `che_건국 same-month alternative respects 인재탐색 full constraints before mutation`() {
        val world = InMemoryTurnWorld(
            WorldSnapshot(
                baseState().copy(meta = linkedMapOf("init_year" to YEAR, "init_month" to MONTH, "develcost" to 52)),
                generals = listOf(wanderingLord().copy(gold = 0)),
                cities = listOf(homeCity(), homeCity(id = 6, name = "허창", level = 5)),
                nations = listOf(wanderingNation(gennum = 2), existingNation(1)),
                worldId = opensamguk.common.world.WorldId((baseState().copy(meta = linkedMapOf("init_year" to YEAR, "init_month" to MONTH, "develcost" to 52))).id),
            ),
        )
        val handler = handlerFor(world, scenario = 1010)

        val outcome = handler.handle(
            42,
            ReservedTurn("che_건국", """{"nationName":"촉","nationType":"che_명가","colorType":5}"""),
            YEAR,
            MONTH,
            "08:30",
        )

        assertFalse(outcome.fellBack)
        assertEquals(0, world.getNationById(7)!!.level)
        assertEquals(0, world.getGeneralById(42)!!.gold, "denied alternative scout must not spend gold")
        assertEquals(1, world.listGenerals().size, "denied alternative scout must not create an NPC")
        assertFalse(handler.recorder.isDirty, "same-month block plus denied alternative is a no-write turn")
        val logs = world.consumeDirtyState().logs.map { it.text }
        assertTrue(logs.any { it.contains("다음 턴부터 건국할 수 있습니다.") })
        assertTrue(logs.any { it.contains("자금이 모자랍니다. 인재탐색 실패.") })
    }

    @Test
    fun `che_선양 through the handler applies destGeneral and drains PHP logs`() {
        val world = InMemoryTurnWorld(
            WorldSnapshot(
                baseState(),
                generals = listOf(
                    wanderingLord(name = "유비").copy(userId = "55", experience = 1000, nationId = 7),
                    member(id = 43, name = "관우").copy(nationId = 7, officerLevel = 1),
                ),
                cities = listOf(homeCity()),
                nations = listOf(wanderingNation(id = 7, name = "촉", gennum = 2).copy(level = 1)),
                worldId = opensamguk.common.world.WorldId((baseState()).id),
            ),
        )
        val handler = handlerFor(world, scenario = 1010)

        val outcome = handler.handle(
            42,
            ReservedTurn("che_선양", """{"destGeneralID":43}"""),
            YEAR,
            MONTH,
            "08:30",
        )

        assertFalse(outcome.fellBack, "선양 must pass with a same-nation target: ${outcome.denyReason}")
        assertEquals(1, world.getGeneralById(42)!!.officerLevel)
        assertEquals(700, world.getGeneralById(42)!!.experience)
        assertEquals(12, world.getGeneralById(43)!!.officerLevel)
        val dirty = world.consumeDirtyState()
        assertEquals(listOf(42, 43), DatabaseHooks.toFlushPayload(world, handler.recorder, dirty).updatedGenerals.map { it.id }.sorted())
        assertTrue(
            dirty.logs.any {
                it.scope == "general" &&
                    it.generalId == 43 &&
                    it.text == "<C>●</>${MONTH}월:<Y>유비</>에게서 군주의 자리를 물려받습니다."
            },
        )
        assertTrue(dirty.logs.any { it.scope == "global" && it.category == "history" && it.text.contains("【선양】") })
        assertEquals(listOf("active_action"), handler.recorder.inheritanceKvWrites().map { it.key })
    }

    @Test
    fun `cr_건국 through the handler carries args into constraints and does not fallback`() {
        val world = InMemoryTurnWorld(
            WorldSnapshot(
                baseState(),
                generals = listOf(wanderingLord()),
                cities = listOf(homeCity()),
                nations = listOf(wanderingNation(gennum = 2), existingNation(1)),
                worldId = opensamguk.common.world.WorldId((baseState()).id),
            ),
        )
        val handler = handlerFor(world, scenario = 1010)

        val outcome = handler.handle(
            42,
            ReservedTurn("cr_건국", """{"nationName":"촉","nationType":"che_명가","colorType":5}"""),
            YEAR,
            MONTH,
            "08:30",
        )

        assertFalse(outcome.fellBack, "cr_건국 args must pass full constraints through the daemon seam")
        assertEquals(1, world.getNationById(7)!!.level)
        assertEquals(7, world.getCityById(5)!!.nationId)
    }

    @Test
    fun `active_action inheritance is recorded only for human owned generals`() {
        val npcWorld = InMemoryTurnWorld(
            WorldSnapshot(
                baseState(),
                generals = listOf(wanderingLord().copy(userId = "55", npcState = 2)),
                cities = listOf(homeCity()),
                nations = listOf(wanderingNation(gennum = 2), existingNation(1)),
                worldId = opensamguk.common.world.WorldId((baseState()).id),
            ),
        )
        val npcHandler = handlerFor(npcWorld, scenario = 1010)
        val npcOutcome = npcHandler.handle(
            42,
            ReservedTurn("cr_건국", """{"nationName":"촉","nationType":"che_명가","colorType":5}"""),
            YEAR,
            MONTH,
            "08:30",
        )
        assertFalse(npcOutcome.fellBack)
        assertTrue(npcHandler.recorder.inheritanceKvWrites().isEmpty(), "npc>=2 must not earn active_action")

        val unownedWorld = InMemoryTurnWorld(
            WorldSnapshot(
                baseState(),
                generals = listOf(wanderingLord().copy(userId = null, npcState = 0)),
                cities = listOf(homeCity()),
                nations = listOf(wanderingNation(gennum = 2), existingNation(1)),
                worldId = opensamguk.common.world.WorldId((baseState()).id),
            ),
        )
        val unownedHandler = handlerFor(unownedWorld, scenario = 1010)
        val unownedOutcome = unownedHandler.handle(
            42,
            ReservedTurn("cr_건국", """{"nationName":"촉","nationType":"che_명가","colorType":5}"""),
            YEAR,
            MONTH,
            "08:30",
        )
        assertFalse(unownedOutcome.fellBack)
        assertTrue(unownedHandler.recorder.inheritanceKvWrites().isEmpty(), "owner<=0 or absent must not earn active_action")
    }

    @Test
    fun `che_무작위건국 through the handler relocates all nation generals to the chosen city`() {
        val world = InMemoryTurnWorld(
            WorldSnapshot(
                baseState(),
                generals = listOf(wanderingLord(cityId = 99), member(cityId = 99)),
                cities = listOf(
                    homeCity(id = 99, name = "임시", level = 3),
                    homeCity(id = 5, name = "성도", level = 5).copy(conflict = "{\"1\":0.5}"),
                ),
                nations = listOf(wanderingNation(gennum = 2), existingNation(1)),
                worldId = opensamguk.common.world.WorldId((baseState()).id),
            ),
        )
        val handler = handlerFor(world, scenario = 1010)

        val outcome = handler.handle(
            42,
            ReservedTurn("che_무작위건국", """{"nationName":"촉","nationType":"che_명가","colorType":5}"""),
            YEAR,
            MONTH,
            "08:30",
        )

        assertFalse(outcome.fellBack, "무작위건국 args must pass full constraints through the daemon seam")
        assertEquals(5, world.getGeneralById(42)!!.cityId)
        assertEquals(5, world.getGeneralById(43)!!.cityId)
        assertEquals(7, world.getCityById(5)!!.nationId)
        assertEquals(100, world.getCityById(5)!!.commerce, "random founding keeps the chosen city's full row")
        assertEquals(100, world.getCityById(5)!!.agriculture, "random founding keeps the chosen city's full row")
        assertEquals("{}", world.getCityById(5)!!.conflict, "random founding clears only the city conflict")
        assertEquals(0, world.getCityById(99)!!.nationId)
        val nation = world.getNationById(7)!!
        assertEquals(1, nation.level)
        assertEquals(5, nation.capitalCityId)
        @Suppress("UNCHECKED_CAST")
        val aux = nation.meta["aux"] as Map<String, Any?>
        assertEquals(1, aux["can_국기변경"])
        assertEquals(1, aux["can_무작위수도이전"])

        val dirty = world.consumeDirtyState()
        val payload = DatabaseHooks.toFlushPayload(world, handler.recorder, dirty)
        assertEquals(listOf(42, 43), payload.updatedGenerals.map { it.id }.sorted())
        assertEquals(5, payload.updatedCities.single { it.id == 5 }.id)
        assertEquals(7, payload.updatedCities.single { it.id == 5 }.nationId)
    }

    @Test
    fun `che_무작위건국 without a candidate executes 해산 and skips the founding lottery`() {
        val lord = wanderingLord(cityId = 99).copy(
            meta = LinkedHashMap(wanderingLord(cityId = 99).meta).apply {
                put("aux", linkedMapOf("inheritRandomUnique" to "MARK"))
            },
        )
        val world = InMemoryTurnWorld(
            WorldSnapshot(
                baseState().copy(
                    meta = linkedMapOf(
                        "minMonthToAllowInheritItem" to 0,
                        "allItems" to linkedMapOf(
                            "horse" to linkedMapOf("che_명마_15_적토마" to 1),
                        ),
                    ),
                ),
                generals = listOf(lord, member(cityId = 99)),
                cities = listOf(homeCity(id = 99, name = "임시", level = 3).copy(nationId = 7)),
                nations = listOf(wanderingNation(gennum = 2), existingNation(1)),
                worldId = opensamguk.common.world.WorldId((baseState().copy(
                    meta = linkedMapOf(
                        "minMonthToAllowInheritItem" to 0,
                        "allItems" to linkedMapOf(
                            "horse" to linkedMapOf("che_명마_15_적토마" to 1),
                        ),
                    ),
                )).id),
            ),
        )
        val handler = handlerFor(world, scenario = 1010)

        val outcome = handler.handle(
            42,
            ReservedTurn("che_무작위건국", """{"nationName":"촉","nationType":"che_명가","colorType":5}"""),
            YEAR,
            MONTH,
            "08:30",
        )

        assertFalse(outcome.fellBack)
        assertNull(world.getNationById(7))
        assertEquals(0, world.getGeneralById(42)!!.nationId)
        assertEquals(0, world.getGeneralById(43)!!.nationId)
        assertEquals("None", world.getGeneralById(42)!!.role.items.horse)
        @Suppress("UNCHECKED_CAST")
        val aux = world.getGeneralById(42)!!.meta["aux"] as Map<String, Any?>
        assertEquals("MARK", aux["inheritRandomUnique"])
        val logs = world.consumeDirtyState().logs.map { it.text }
        assertTrue(logs.any { it.contains("건국할 수 있는 도시가 없습니다.") })
        assertTrue(logs.any { it.contains("세력을 해산했습니다.") })
    }
}
