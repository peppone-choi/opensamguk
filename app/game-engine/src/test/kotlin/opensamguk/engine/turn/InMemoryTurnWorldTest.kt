package opensamguk.engine.turn

import opensamguk.common.world.WorldId
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class InMemoryTurnWorldTest {
    private val t0 = Instant.parse("0200-01-01T00:00:00Z")

    private fun general(id: Int, nationId: Int = 1, gold: Int = 100) = TurnGeneral(
        id = id,
        name = "g$id",
        nationId = nationId,
        cityId = 5,
        troopId = 0,
        stats = GeneralStats(80, 70, 60),
        experience = 0,
        dedication = 0,
        officerLevel = 0,
        gold = gold,
        turnTime = t0,
    )

    private fun nation(id: Int) = Nation(id = id, name = "n$id", color = "#000")

    private fun diplomacy(from: Int, to: Int) = TurnDiplomacy(from, to, state = 7, term = 0)

    private fun baseState(meta: Map<String, Any?> = emptyMap()) = TurnWorldState(
        id = 1, currentYear = 200, currentMonth = 1, tickSeconds = 3600, lastTurnTime = t0,
        meta = meta,
    )

    @Test
    fun `snapshot rejects a different configured world identity`() {
        assertFailsWith<IllegalArgumentException> {
            WorldSnapshot(state = baseState(), worldId = WorldId(2))
        }
    }

    @Test
    fun `update marks dirty and consume drains then second consume is empty`() {
        val world = InMemoryTurnWorld(WorldSnapshot(state = baseState(), generals = listOf(general(1)), worldId = opensamguk.common.world.WorldId((baseState()).id)))
        world.updateGeneral(general(1, gold = 250))

        val first = world.consumeDirtyState()
        assertEquals(1, first.generals.size)
        assertEquals(250, first.generals.single().gold)

        val second = world.consumeDirtyState()
        assertTrue(second.generals.isEmpty())
        assertTrue(second.cities.isEmpty())
        assertTrue(second.logs.isEmpty())
    }

    @Test
    fun `create-then-delete in same tick emits neither created nor deleted row`() {
        val world = InMemoryTurnWorld(WorldSnapshot(state = baseState(), worldId = opensamguk.common.world.WorldId((baseState()).id)))
        world.createGeneral(general(99))
        world.removeGeneral(99)

        val drained = world.consumeDirtyState()
        assertTrue(drained.createdGenerals.none { it.id == 99 }, "no created row")
        assertFalse(drained.deletedGenerals.contains(99), "no deleted row")
        assertTrue(drained.generals.none { it.id == 99 }, "no dirty row")
    }

    @Test
    fun `delete existing general appears in deletedGenerals`() {
        val world = InMemoryTurnWorld(WorldSnapshot(state = baseState(), generals = listOf(general(1)), worldId = opensamguk.common.world.WorldId((baseState()).id)))
        assertTrue(world.removeGeneral(1))

        val drained = world.consumeDirtyState()
        assertEquals(listOf(1), drained.deletedGenerals)
        assertTrue(drained.createdGenerals.isEmpty())
    }

    @Test
    fun `getter returns defensive copy`() {
        val world = InMemoryTurnWorld(WorldSnapshot(state = baseState(), generals = listOf(general(1, gold = 100)), worldId = opensamguk.common.world.WorldId((baseState()).id)))
        val external = world.listGenerals().single().copy(gold = 9999)
        assertEquals(9999, external.gold)
        assertEquals(100, world.getGeneralById(1)!!.gold, "internal state unaffected by external mutation")
    }

    @Test
    fun `removeNation prunes its diplomacy from dirty sets and records deletedNations`() {
        val snapshot = WorldSnapshot(
            state = baseState(),
            nations = listOf(nation(1), nation(2)),
            diplomacy = listOf(diplomacy(1, 2), diplomacy(2, 1)),
            worldId = opensamguk.common.world.WorldId((baseState()).id),
        )
        val world = InMemoryTurnWorld(snapshot)
        // dirty both diplomacy entries via update so they would otherwise be drained
        world.updateNation(nation(1).copy(gold = 5))

        assertTrue(world.removeNation(1))

        val drained = world.consumeDirtyState()
        assertEquals(listOf(1), drained.deletedNations)
        assertTrue(drained.diplomacy.none { it.fromNationId == 1 || it.toNationId == 1 }, "diplomacy pruned from dirty")
        assertTrue(drained.nations.none { it.id == 1 }, "removed nation not in dirty nations")
        assertTrue(world.listDiplomacy().none { it.fromNationId == 1 || it.toNationId == 1 }, "diplomacy gone from world")
    }

    @Test
    fun `allocateNationId skips a nation deleted in the same tick`() {
        val world = InMemoryTurnWorld(WorldSnapshot(state = baseState(), nations = listOf(nation(1), nation(2), nation(3)), worldId = opensamguk.common.world.WorldId((baseState()).id)))
        world.removeNation(3)
        assertEquals(4, world.allocateNationId(), "deleted id must not be reused before flush")
    }

    @Test
    fun `allocateNationId increments sequentially across a same-tick delete and create`() {
        val world = InMemoryTurnWorld(WorldSnapshot(state = baseState(), nations = listOf(nation(1), nation(2), nation(3)), worldId = opensamguk.common.world.WorldId((baseState()).id)))
        world.removeNation(3)
        assertEquals(4, world.allocateNationId())
        world.createNation(nation(4))
        assertEquals(5, world.allocateNationId())
    }

    @Test
    fun `allocateNationId uses persisted maxNationId from meta after restart`() {
        val world = InMemoryTurnWorld(
            WorldSnapshot(
                state = baseState(meta = mapOf("maxNationId" to 7)),
                nations = listOf(nation(1), nation(2), nation(3)),
                worldId = opensamguk.common.world.WorldId((baseState(meta = mapOf("maxNationId" to 7))).id),
            ),
        )
        assertEquals(8, world.allocateNationId(), "persisted high-water mark must dominate live keys")
    }

    @Test
    fun `allocateNationId uses archived nation ids from snapshot after restart`() {
        val world = InMemoryTurnWorld(
            WorldSnapshot(
                state = baseState(),
                nations = listOf(nation(1), nation(2), nation(3)),
                archivedNationIds = listOf(9),
                worldId = opensamguk.common.world.WorldId((baseState()).id),
            ),
        )

        assertEquals(10, world.allocateNationId(), "archived ng_old_nations ids must not be reused")
    }

    @Test
    fun `allocateGeneralId uses persisted maxGeneralId from meta after restart`() {
        val world = InMemoryTurnWorld(
            WorldSnapshot(
                state = baseState(meta = mapOf("maxGeneralId" to 9)),
                generals = listOf(general(1), general(2)),
                worldId = opensamguk.common.world.WorldId((baseState(meta = mapOf("maxGeneralId" to 9))).id),
            ),
        )
        assertEquals(10, world.allocateGeneralId(), "persisted high-water mark must dominate live keys")
    }

    @Test
    fun `restart after deletion does not reuse id below persisted high-water mark`() {
        val first = InMemoryTurnWorld(WorldSnapshot(state = baseState(), nations = listOf(nation(1)), worldId = opensamguk.common.world.WorldId((baseState()).id)))
        assertEquals(2, first.allocateNationId())
        first.createNation(nation(2))
        first.removeNation(1)
        val meta = first.getState().meta

        val restarted = InMemoryTurnWorld(
            WorldSnapshot(
                state = baseState(meta = meta),
                nations = listOf(nation(2)),
                worldId = opensamguk.common.world.WorldId((baseState(meta = meta)).id),
            ),
        )
        assertEquals(3, restarted.allocateNationId(), "deleted id below high-water mark must not be reused")
    }

    @Test
    fun `createNation bumps maxNationId in state meta`() {
        val world = InMemoryTurnWorld(WorldSnapshot(state = baseState(), nations = listOf(nation(1)), worldId = opensamguk.common.world.WorldId((baseState()).id)))
        world.createNation(nation(5))
        assertEquals(5, world.getState().meta["maxNationId"])
    }

    @Test
    fun `createGeneral bumps maxGeneralId in state meta`() {
        val world = InMemoryTurnWorld(WorldSnapshot(state = baseState(), generals = listOf(general(1)), worldId = opensamguk.common.world.WorldId((baseState()).id)))
        world.createGeneral(general(12))
        assertEquals(12, world.getState().meta["maxGeneralId"])
    }
}
