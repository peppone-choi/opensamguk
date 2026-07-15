package opensamguk.engine.turn

import opensamguk.engine.flush.DatabaseHooks
import opensamguk.logic.domain.General as LogicGeneral
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * F3 Task FF1 — the delete/tombstone emitter on the SINGLE dirty source.
 *
 * Faithful to `General.php:515-600` (kill: storeOldGeneral → DELETE general/general_turn/rank_data
 * + gennum-1) and the nation cascade. The [ChangeRecorder] gains `markGeneralDeleted` /
 * `markNationDeleted` so the recorder — NOT a bare `world.removeX` — is the lone emitter: a
 * tombstoned row leaves the update-set (kill() clears updatedVar at `General.php:595`, so a trailing
 * applyDB must NOT re-INSERT) and lands ONCE in `DirtyState.deletedGenerals` / `deletedNations`.
 */
class TombstoneEmitterTest {
    private val t0 = Instant.parse("0200-01-01T00:00:00Z")

    private fun engineGeneral(id: Int, nationId: Int = 1, gold: Int = 100) = TurnGeneral(
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

    private fun engineNation(id: Int) = Nation(id = id, name = "n$id", color = "#000")

    private fun engineCity(id: Int, nationId: Int) = City(
        id = id, name = "c$id", nationId = nationId, level = 5, frontState = 1,
        meta = mapOf("conflict" to mapOf("2" to 1)),
    )

    private fun logicGeneral(id: Int, nationId: Int = 1, gold: Int = 100) = LogicGeneral(
        id = id, nationId = nationId, cityId = 5,
        leadership = 80, strength = 70, intel = 60, injury = 0,
        experience = 0.0, dedication = 0.0, officerLevel = 0, gold = gold, rice = 0,
    )

    private fun baseState() = TurnWorldState(
        id = 1, currentYear = 200, currentMonth = 1, tickSeconds = 3600, lastTurnTime = t0,
    )

    @Test
    fun `markGeneralDeleted feeds deletedGenerals and excludes it from the update-set`() {
        val world = InMemoryTurnWorld(WorldSnapshot(state = baseState(), generals = listOf(engineGeneral(1))))
        val recorder = ChangeRecorder()

        recorder.markGeneralDeleted(world, 1)

        // the recorder no longer treats it as an UPDATE (no double-apply)
        assertFalse(recorder.dirtyGeneralIds().contains(1), "tombstoned general not in update-set")
        // the world dropped it and emits the delete exactly once
        val drained = world.consumeDirtyState()
        assertEquals(listOf(1), drained.deletedGenerals)
        assertTrue(drained.generals.none { it.id == 1 }, "tombstoned general not in dirty update rows")
    }

    @Test
    fun `a marked-then-mutated general emits ONLY the delete`() {
        val world = InMemoryTurnWorld(WorldSnapshot(state = baseState(), generals = listOf(engineGeneral(1, gold = 100))))
        val recorder = ChangeRecorder()

        // a mutation recorded BEFORE the kill must be discarded once the row is tombstoned.
        recorder.diffGeneral(logicGeneral(1, gold = 100), logicGeneral(1, gold = 250))
        assertTrue(recorder.dirtyGeneralIds().contains(1), "mutation recorded pre-kill")

        recorder.markGeneralDeleted(world, 1)

        assertFalse(recorder.dirtyGeneralIds().contains(1), "kill clears the pending update (General.php:595)")
        // a mutation recorded AFTER the kill is ignored too (tombstoned row never re-enters the update-set).
        recorder.diffGeneral(logicGeneral(1, gold = 250), logicGeneral(1, gold = 999))
        assertFalse(recorder.dirtyGeneralIds().contains(1), "post-kill mutation discarded")

        assertEquals(listOf(1), world.consumeDirtyState().deletedGenerals)
    }

    @Test
    fun `the snapshot list captures the old-general before delete`() {
        val general = engineGeneral(7, gold = 4242).copy(
            userId = "77",
            recentWarTime = t0.plusSeconds(1),
            injury = 4,
            experience = 123,
            dedication = 456,
            age = 31,
            role = GeneralRole(
                personality = "che_유지",
                specialDomestic = "che_상재",
                specialWar = "che_화공",
                items = GeneralItems(horse = "백마", weapon = "청룡언월도", book = "손자병법", item = "옥새"),
            ),
            meta = linkedMapOf(
                "affinity" to 88,
                "bornyear" to 169,
                "deadyear" to 240,
                "owner_name" to "owner",
                "leadership_exp" to 3,
                "officer_city" to 5,
                "dex1" to 11,
                "killturn" to 1234,
                "startage" to 20,
                "last_turn" to mapOf("0" to "휴식"),
                "aux" to mapOf("test" to 1),
                "penalty" to mapOf("warn" to 2),
            ),
        )
        val state = baseState().copy(meta = mapOf("generalHistory" to mapOf(7 to listOf("기존 연혁"))))
        val world = InMemoryTurnWorld(WorldSnapshot(state = state, generals = listOf(general)))
        val recorder = ChangeRecorder()

        world.pushLog(LogEntryDraft("general", "history", "이번 틱 첫 연혁", generalId = 7))
        world.pushLog(LogEntryDraft("general", "history", "이번 틱 최근 연혁", generalId = 7))
        recorder.markGeneralDeleted(world, 7)

        val snapshots = recorder.oldGeneralSnapshots()
        assertEquals(1, snapshots.size)
        assertEquals(7, snapshots.single().id)
        assertEquals(4242, snapshots.single().gold, "snapshot captured the pre-delete row")

        val payload = DatabaseHooks.toFlushPayload(world, recorder, world.consumeDirtyState())
        val archive = payload.oldGeneralSnapshots.single()
        assertEquals(7, archive.generalNo)
        assertEquals("77", archive.owner)
        assertEquals(20001, archive.lastYearMonth)
        assertEquals(4242, archive.data["gold"])
        assertEquals(4, archive.data["injury"])
        assertEquals(88, archive.data["affinity"])
        assertEquals("청룡언월도", archive.data["weapon"])
        assertEquals("che_상재", archive.data["special"])
        assertEquals("che_화공", archive.data["special2"])
        assertEquals("{\"test\":1}", archive.data["aux"])
        assertEquals("0200-01-01 08:27:52.000000", archive.data["turntime"])
        assertEquals("0200-01-01 08:27:53.000000", archive.data["recent_war"])
        assertEquals(
            listOf("이번 틱 최근 연혁", "이번 틱 첫 연혁", "기존 연혁"),
            archive.data["history"],
        )
        assertTrue(
            setOf("no", "owner", "npcmsg", "npc_org", "leadership_exp", "dex5", "permission", "recent_war", "history")
                .all(archive.data::containsKey),
        )
    }

    @Test
    fun `markNationDeleted marks the nation, reverts captured cities, and snapshots its generals`() {
        val nation = engineNation(2).copy(
            capitalCityId = 5,
            chiefGeneralId = 12,
            gold = 123,
            rice = 456,
            tech = 78.0,
            power = 90,
            level = 3,
            typeCode = "che_한",
            meta = linkedMapOf(
                "capset" to 1,
                "gennum" to 2,
                "bill" to 4,
                "rate" to 15,
                "rate_tmp" to 16,
                "secretlimit" to 5,
                "chief_set" to 1,
                "scout" to 2,
                "war" to 1,
                "strategic_cmd_limit" to 20,
                "surlimit" to 30,
                "spy" to linkedMapOf("5" to 3),
                "aux" to linkedMapOf("legacy" to 7),
                "nation_env" to linkedMapOf(
                    "max_power" to linkedMapOf("legacy" to 999, "gold" to 999),
                    "nationNotice" to linkedMapOf("msg" to "국가 공지"),
                    "scout_msg" to "정찰 공지",
                ),
            ),
        )
        val world = InMemoryTurnWorld(
            WorldSnapshot(
                state = baseState().copy(meta = mapOf("nationHistory" to mapOf(2 to listOf("기존 국사")))),
                generals = listOf(engineGeneral(12, nationId = 2), engineGeneral(11, nationId = 2)),
                cities = listOf(
                    engineCity(5, nationId = 2).copy(conflict = """{"2":10.0,"3":5.0}"""),
                    engineCity(6, nationId = 2),
                    engineCity(7, nationId = 3).copy(conflict = """{"3":8.0,"2":4.0}"""),
                ),
                nations = listOf(nation),
            ),
        )
        val recorder = ChangeRecorder()

        world.pushLog(LogEntryDraft("nation", "history", "이번 틱 첫 국사", nationId = 2))
        world.pushLog(LogEntryDraft("nation", "history", "이번 틱 최근 국사", nationId = 2))
        recorder.markNationDeleted(world, 2)

        // nation cascade: the nation lands in deletedNations exactly once.
        val drained = world.consumeDirtyState()
        assertEquals(listOf(2), drained.deletedNations)
        assertTrue(drained.nations.none { it.id == 2 }, "deleted nation not in dirty update rows")

        // captured cities reverted to neutral (nation=0, front_state=0, conflict removed) as city patches.
        val cityPatches = recorder.cityPatches().associateBy { it.id }
        assertEquals(0, cityPatches.getValue(5).columns["nationId"])
        assertEquals(0, cityPatches.getValue(5).columns["frontState"])
        assertEquals("{}", cityPatches.getValue(5).columns["conflict"])
        assertEquals(0, cityPatches.getValue(6).columns["nationId"])
        // PHP process_war.php:508-522 decodes and re-encodes without JSON_PRESERVE_ZERO_FRACTION.
        assertEquals("""{"3":8}""", cityPatches.getValue(7).columns["conflict"])
        assertEquals("""{"3":8}""", world.getCityById(7)!!.conflict)

        // the nation snapshot captures the nation + its general ids (the ng_old_nations archive write).
        val snap = recorder.nationSnapshots().single()
        assertEquals(2, snap.nation.id)
        assertEquals(listOf(11, 12), snap.generalIds)

        val payload = DatabaseHooks.toFlushPayload(world, recorder, drained)
        val data = payload.deletedNationSnapshots.single()["data"] as Map<*, *>
        assertEquals(2, data["nation"])
        assertEquals(listOf(11, 12), data["generals"])
        assertEquals(1, data["capset"])
        assertEquals(4, data["bill"])
        assertEquals(20, data["strategic_cmd_limit"])
        assertEquals("{\"5\":3}", data["spy"])
        assertEquals(mapOf("legacy" to 7, "gold" to 999), data["aux"], "PHP array union preserves aux collisions")
        assertTrue(data.keys.toList().indexOf("aux") < data.keys.toList().indexOf("generals"))
        assertEquals("국가 공지", data["msg"])
        assertEquals("정찰 공지", data["scout_msg"])
        assertEquals(listOf("이번 틱 최근 국사", "이번 틱 첫 국사", "기존 국사"), data["history"])
        assertTrue(
            setOf(
                "nation", "name", "color", "capital", "capset", "gennum", "gold", "rice", "bill",
                "rate", "rate_tmp", "secretlimit", "chief_set", "scout", "war", "strategic_cmd_limit",
                "surlimit", "tech", "power", "spy", "level", "type", "aux", "generals", "msg",
                "scout_msg", "history",
            ).all(data::containsKey),
        )
    }
}
