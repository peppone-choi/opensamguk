package opensamguk.engine.turn

import opensamguk.engine.flush.DatabaseHooks
import opensamguk.infra.persistence.ReservedTurnRepository.ReservedTurn
import opensamguk.logic.actions.CommandRegistry
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

    private fun wanderingLord(id: Int = 42, name: String = "진표") = actor(id, name).copy(
        nationId = 7,
        officerLevel = 12,
    )

    private fun homeCity() = City(
        id = 5,
        name = "성도",
        nationId = 0,
        level = 5,
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

    private fun wanderingNation(id: Int = 7, name: String = "진표") =
        Nation(id = id, name = name, color = "#330000", level = 0, capitalCityId = 0, meta = mapOf("gennum" to 1))

    private fun baseState() = TurnWorldState(
        id = 1, currentYear = YEAR, currentMonth = MONTH, tickSeconds = 3600, lastTurnTime = t0,
    )

    /** A world with two existing nations {1,2} + a neutral actor at 성도 → allocateNationId() == 3. */
    private fun worldWith() = InMemoryTurnWorld(
        WorldSnapshot(
            baseState(),
            generals = listOf(actor()),
            cities = listOf(homeCity()),
            nations = listOf(existingNation(1), existingNation(2)),
        ),
    )

    private fun handlerFor(world: InMemoryTurnWorld, scenario: Int) =
        ReservedTurnHandler(world, registry, FIXTURE_HIDDEN_SEED, START_YEAR, scenario = scenario)

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

        // the broadcast headline survives the general-pass handler (che_거병.php:161; was dropped pre-fix).
        // 성도 + 유비 (josa 가) → "<Y>유비</>가 <G><b>성도</b></>에 거병하였습니다." under the MONTH-prefix render.
        val broadcast = dirty.logs.filter { it.scope == "global" }.map { it.text }
        assertEquals(
            listOf("<C>●</>${MONTH}월:<Y>유비</>가 <G><b>성도</b></>에 거병하였습니다."),
            broadcast,
            "거병 broadcast action log is drained to the global scope",
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
    fun `che_해산 through the handler tombstones the wandering nation`() {
        val world = InMemoryTurnWorld(
            WorldSnapshot(
                baseState(),
                generals = listOf(wanderingLord()),
                cities = listOf(homeCity()),
                nations = listOf(wanderingNation()),
            ),
        )
        val handler = handlerFor(world, scenario = 1010)

        val outcome = handler.handle(42, ReservedTurn("che_해산", ""), YEAR, MONTH, "08:30")

        assertFalse(outcome.fellBack, "해산 passed FULL constraints and resolved")
        assertNull(world.getNationById(7), "the disbanded wandering nation leaves the world")
        val payload = DatabaseHooks.toFlushPayload(world, handler.recorder, world.consumeDirtyState())
        assertEquals(listOf(7), payload.deletedNations, "deleted nation reaches the flush payload")
        assertEquals(7, payload.deletedNationSnapshots.single()["nation"])
        assertEquals(listOf(42), payload.deletedNationSnapshots.single()["general_ids"])
        assertEquals(0, payload.updatedGenerals.single { it.id == 42 }.nationId)
    }
}
