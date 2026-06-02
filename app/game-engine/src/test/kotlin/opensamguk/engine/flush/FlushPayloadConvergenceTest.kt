package opensamguk.engine.flush

import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.City
import opensamguk.engine.turn.GeneralStats
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.KvKey
import opensamguk.engine.turn.Nation
import opensamguk.engine.turn.RankColumn
import opensamguk.engine.turn.TurnGeneral
import opensamguk.engine.turn.TurnWorldState
import opensamguk.engine.turn.WorldSnapshot
import opensamguk.logic.domain.Nation as LogicNation
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T0.3 — the #1 round-trip fix. The daemon flush builder used to flush ONLY general+city+logs and
 * silently DROP every nation / rank / kv / diplomacy delta. This test asserts the converged superset
 * builder [DatabaseHooks.toFlushPayload] (the one `TurnRunService` now routes through) actually
 * carries a nation gold delta, a rank bump, and a KV write (int-ns nation_env + string-ns game_env)
 * into the [opensamguk.infra.persistence.FlushPayload] the JdbcFlushExecutor consumes.
 */
class FlushPayloadConvergenceTest {

    private val t0 = Instant.parse("0200-01-01T00:00:00Z")

    private fun baseState() = TurnWorldState(
        id = 1, currentYear = 200, currentMonth = 1, tickSeconds = 3600, lastTurnTime = t0,
    )

    private fun engineGeneral(id: Int, nationId: Int = 1) = TurnGeneral(
        id = id, name = "g$id", nationId = nationId, cityId = 5, troopId = 0,
        stats = GeneralStats(80, 70, 60), experience = 0, dedication = 0,
        officerLevel = 0, gold = 100, turnTime = t0,
    )

    private fun engineNation(id: Int, gold: Int) = Nation(id = id, name = "n$id", color = "#000", gold = gold)

    private fun world(): InMemoryTurnWorld = InMemoryTurnWorld(
        WorldSnapshot(
            state = baseState(),
            generals = listOf(engineGeneral(10)),
            nations = listOf(engineNation(1, gold = 1000)),
            cities = listOf(City(id = 5, name = "c5", nationId = 1, level = 5)),
        ),
    )

    @Test
    fun `nation gold delta survives the converged flush builder (no longer dropped)`() {
        val world = world()
        val recorder = ChangeRecorder()

        // apply a nation gold change to the world's post-state, recorded as dirty via diffNation.
        val pre = LogicNation(id = 1, level = 0, capitalCityId = null, name = "n1", color = "#000",
            typeCode = "che_중립", gold = 1000, rice = 0, tech = 0.0, gennum = 1, capset = 0)
        val post = pre.copy(gold = 1500)
        recorder.diffNation(pre, post)
        world.applyNationDirtyFree(world.getNationById(1)!!.copy(gold = 1500))

        val payload = DatabaseHooks.toFlushPayload(world, recorder, world.consumeDirtyState())

        assertEquals(1, payload.updatedNations.size, "the nation UPDATE must reach the payload (was dropped)")
        assertEquals(1500, payload.updatedNations.single().gold)
    }

    @Test
    fun `rank delta + KV write (both namespaces) reach the payload`() {
        val world = world()
        val recorder = ChangeRecorder()

        recorder.recordRankIncrease(generalId = 10, column = RankColumn.WARNUM, value = 3)
        recorder.recordNationEnvKv(nationId = 1, key = "nationNotice", value = mapOf("text" to "공지"))
        recorder.recordKv(table = "game_env", namespace = "global", key = "last_betting_id", value = 7)

        val payload = DatabaseHooks.toFlushPayload(world, recorder, world.consumeDirtyState())

        // rank delta present.
        assertEquals(1, payload.rankWrites.size)
        assertEquals("warnum", payload.rankWrites.single().type)

        // both KV writes present, insertion-ordered; int-ns routes via nation_env, string-ns via game_env.
        assertEquals(2, payload.kvWrites.size)
        val nationEnv = payload.kvWrites[0]
        assertEquals("nation_env", nationEnv.table)
        assertEquals("1", nationEnv.namespace)
        assertEquals("nationNotice", nationEnv.key)
        val gameEnv = payload.kvWrites[1]
        assertEquals("game_env", gameEnv.table)
        assertEquals("global", gameEnv.namespace)
        assertEquals(7, gameEnv.value)
    }

    @Test
    fun `bidirectional diplomacy update reaches the payload (both directions)`() {
        val world = InMemoryTurnWorld(
            WorldSnapshot(
                state = baseState(),
                nations = listOf(engineNation(1, gold = 1000), engineNation(2, gold = 1000)),
                diplomacy = listOf(
                    opensamguk.engine.turn.TurnDiplomacy(1, 2, state = 2, term = 0),
                    opensamguk.engine.turn.TurnDiplomacy(2, 1, state = 2, term = 0),
                ),
            ),
        )
        val recorder = ChangeRecorder()

        // 선전포고: state 2 -> 1, term -> 24 on BOTH rows (the resolver applies + the recorder diffs).
        val a0 = world.getDiplomacy(1, 2)!!
        world.updateDiplomacy(1, 2, state = 1, term = 24)
        recorder.diffDiplomacy(a0, world.getDiplomacy(1, 2)!!)
        val b0 = world.getDiplomacy(2, 1)!!
        world.updateDiplomacy(2, 1, state = 1, term = 24)
        recorder.diffDiplomacy(b0, world.getDiplomacy(2, 1)!!)

        val payload = DatabaseHooks.toFlushPayload(world, recorder, world.consumeDirtyState())

        assertEquals(2, payload.updatedDiplomacy.size, "both diplomacy directions reach the payload")
        assertEquals(listOf(1 to 2, 2 to 1), payload.updatedDiplomacy.map { it.fromNationId to it.toNationId })
        assertTrue(payload.updatedDiplomacy.all { it.state == 1 && it.term == 24 })
    }

    @Test
    fun `recordKv last-write-wins per key and delete-on-null are preserved`() {
        val recorder = ChangeRecorder()
        recorder.recordKv("game_env", "global", "k", 1)
        recorder.recordKv("game_env", "global", "k", 2)   // overwrite
        recorder.recordKv("betting", "id_3", "k2", null)   // delete-on-null
        val kv = recorder.kvDirty()
        assertEquals(2, kv.size)
        assertEquals(2, kv[KvKey("game_env", "global", "k")])
        assertTrue(kv.containsKey(KvKey("betting", "id_3", "k2")))
        assertEquals(null, kv[KvKey("betting", "id_3", "k2")])
    }
}
