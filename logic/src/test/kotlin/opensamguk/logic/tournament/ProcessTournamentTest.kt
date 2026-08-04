package opensamguk.logic.tournament

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import opensamguk.common.rng.PhpMt19937
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProcessTournamentTest {
    private val golden: JsonObject by lazy {
        val text = checkNotNull(javaClass.classLoader.getResourceAsStream("golden/tournament/fight-fixtures.json"))
            .bufferedReader()
            .use { it.readText() }
        Json.parseToJsonElement(text).jsonObject
    }

    @Test
    fun `six real PHP fight fixtures replay byte for byte`() {
        for (fixtureElement in golden.getValue("fightCases").jsonArray) {
            val fixture = fixtureElement.jsonObject
            val args = fixture.getValue("fightArgs").jsonObject
            val beforeRank = fixture.getValue("rankDataBefore").jsonObject
            val rankValues = linkedMapOf<Pair<Int, String>, Int>()
            val left = fixture.getValue("gen1").jsonObject.toEntry()
            val right = fixture.getValue("gen2").jsonObject.toEntry()
            beforeRank.getValue("g1").jsonObject.forEach { (type, value) ->
                rankValues[left.id to type] = value.jsonPrimitive.int
            }
            beforeRank.getValue("g2").jsonObject.forEach { (type, value) ->
                rankValues[right.id to type] = value.jsonPrimitive.int
            }

            val result = TournamentFightEngine(
                random = PhpMt19937(fixture.getValue("seed").jsonPrimitive.int),
                rankValue = { generalId, type -> rankValues[generalId to type] ?: 0 },
            ).fight(
                tournamentType = args.getValue("tnmtType").jsonPrimitive.int,
                tournament = args.getValue("tnmt").jsonPrimitive.int,
                phase = args.getValue("phs").jsonPrimitive.int,
                group = args.getValue("group").jsonPrimitive.int,
                left = left,
                right = right,
                decisive = args.getValue("type").jsonPrimitive.int == 1,
            )

            assertEquals(
                fixture.getValue("logLines").jsonArray.map { it.jsonPrimitive.content },
                result.logs,
                fixture.getValue("caseId").jsonPrimitive.content,
            )
            assertEquals(fixture.getValue("sel").jsonPrimitive.int, result.selection)
            assertEntryDelta(fixture.getValue("tournamentAfter").jsonObject.getValue("g1").jsonObject, result.left)
            assertEntryDelta(fixture.getValue("tournamentAfter").jsonObject.getValue("g2").jsonObject, result.right)

            val expectedRank = fixture.getValue("rankDataAfter").jsonObject
            val actualRank = LinkedHashMap(rankValues)
            result.rankDeltas.forEach { delta ->
                actualRank[delta.generalId to delta.type] =
                    (actualRank[delta.generalId to delta.type] ?: 0) + delta.amount
            }
            expectedRank.getValue("g1").jsonObject.forEach { (type, value) ->
                assertEquals(value.jsonPrimitive.int, actualRank[left.id to type], "${fixture["caseId"]}: left $type")
            }
            expectedRank.getValue("g2").jsonObject.forEach { (type, value) ->
                assertEquals(value.jsonPrimitive.int, actualRank[right.id to type], "${fixture["caseId"]}: right $type")
            }
        }
    }

    @Test
    fun `qualify phase 55 fights every group then promotes by point goal and stable seq`() {
        val qualify = golden.getValue("qualifyPromote").jsonObject
        val entries = linkedMapOf<Int, TournamentEntry>()
        qualify.getValue("before").jsonArray.forEachIndexed { group, rows ->
            rows.jsonArray.forEach { rowElement ->
                val row = rowElement.jsonObject
                val id = row.getValue("seq").jsonPrimitive.int
                entries[id] = TournamentEntry(
                    id = id,
                    npc = 0,
                    name = "G${group}_${row.getValue("grpNo").jsonPrimitive.int}",
                    leadership = 50 + row.getValue("grpNo").jsonPrimitive.int,
                    strength = 50,
                    intel = 50,
                    level = 10,
                    group = group,
                    groupNo = row.getValue("grpNo").jsonPrimitive.int,
                    win = row.getValue("win").jsonPrimitive.int,
                    draw = row.getValue("draw").jsonPrimitive.int,
                    lose = row.getValue("lose").jsonPrimitive.int,
                    goal = row.getValue("gl").jsonPrimitive.int,
                    seq = id,
                )
            }
        }
        val store = InMemoryTournamentStore(entries)

        val result = TournamentProcessor(
            store = store,
            betting = NoopTournamentBetting,
            random = PhpMt19937(qualify.getValue("seed").jsonPrimitive.int),
        ).process(
            TournamentState(
                tournament = 2,
                phase = 55,
                type = qualify.getValue("tnmtType").jsonPrimitive.int,
                auto = true,
                time = Instant.EPOCH,
                turnTermMinutes = 5,
            ),
            Instant.EPOCH,
        )

        assertEquals(3, result.state.tournament)
        assertEquals(0, result.state.phase)
        assertEquals(8, result.fightLogs.size)
        qualify.getValue("after").jsonArray.forEachIndexed { group, rows ->
            rows.jsonArray.forEach { rowElement ->
                val expected = rowElement.jsonObject
                val actual = store.entries().single {
                    it.group == group && it.groupNo == expected.getValue("grpNo").jsonPrimitive.int
                }
                assertEquals(expected.getValue("win").jsonPrimitive.int, actual.win)
                assertEquals(expected.getValue("draw").jsonPrimitive.int, actual.draw)
                assertEquals(expected.getValue("lose").jsonPrimitive.int, actual.lose)
                assertEquals(expected.getValue("gl").jsonPrimitive.int, actual.goal)
                assertEquals(expected.getValue("prmt").jsonPrimitive.int, actual.promote)
            }
        }
    }

    @Test
    fun `state five assigns finals opens betting and writes betting deadline like PHP`() {
        val entries = linkedMapOf<Int, TournamentEntry>()
        repeat(16) { idx ->
            entries[idx + 1] = TournamentEntry(
                id = idx + 1,
                npc = 0,
                name = "g${idx + 1}",
                leadership = 80 - idx,
                strength = 70,
                intel = 60,
                level = 0,
                group = idx / 2 + 10,
                groupNo = idx % 2,
                promote = idx % 2 + 1,
                seq = idx + 1,
            )
        }
        val store = InMemoryTournamentStore(entries = entries)
        val opened = mutableListOf<List<TournamentEntry>>()
        val result = TournamentProcessor(
            store = store,
            betting = object : TournamentBettingPort {
                override fun open(type: Int, unitSeconds: Int, finalists: List<TournamentEntry>): Int {
                    opened.add(finalists)
                    return 77
                }

                override fun close(bettingId: Int) = Unit
                override fun refund(bettingId: Int) = Unit
                override fun payout(bettingId: Int, winnerId: Int) = Unit
            },
        ).process(
            state = TournamentState(
                tournament = 5,
                phase = 0,
                type = 0,
                auto = true,
                time = Instant.parse("2026-07-10T00:00:00Z"),
                turnTermMinutes = 30,
            ),
            now = Instant.parse("2026-07-10T00:00:00Z"),
        )

        assertEquals(6, result.state.tournament)
        assertEquals(0, result.state.phase)
        assertEquals(77, result.state.lastBettingId)
        assertEquals(Instant.parse("2026-07-10T00:30:00Z"), result.state.time)
        assertEquals(1, opened.size)
        assertEquals(16, opened.single().size)
        assertTrue(store.entries().all { it.group in 20..27 })
    }

    private fun JsonObject.toEntry(): TournamentEntry =
        TournamentEntry(
            id = getValue("no").jsonPrimitive.int,
            npc = getValue("npc").jsonPrimitive.int,
            name = getValue("name").jsonPrimitive.content,
            leadership = getValue("leadership").jsonPrimitive.int,
            strength = getValue("strength").jsonPrimitive.int,
            intel = getValue("intel").jsonPrimitive.int,
            level = getValue("lvl").jsonPrimitive.int,
            group = getValue("grp").jsonPrimitive.int,
            groupNo = getValue("grp_no").jsonPrimitive.int,
            win = getValue("win").jsonPrimitive.int,
            draw = getValue("draw").jsonPrimitive.int,
            lose = getValue("lose").jsonPrimitive.int,
            goal = getValue("gl").jsonPrimitive.int,
            promote = getValue("prmt").jsonPrimitive.int,
            seq = getValue("seq").jsonPrimitive.int,
            horse = getValue("h").jsonPrimitive.content,
            weapon = getValue("w").jsonPrimitive.content,
            book = getValue("b").jsonPrimitive.content,
        )

    private fun assertEntryDelta(expected: JsonObject, actual: TournamentEntry) {
        assertContentEquals(
            intArrayOf(
                expected.getValue("win").jsonPrimitive.int,
                expected.getValue("draw").jsonPrimitive.int,
                expected.getValue("lose").jsonPrimitive.int,
                expected.getValue("gl").jsonPrimitive.int,
            ),
            intArrayOf(actual.win, actual.draw, actual.lose, actual.goal),
        )
    }

    private object NoopTournamentBetting : TournamentBettingPort {
        override fun open(type: Int, unitSeconds: Int, finalists: List<TournamentEntry>): Int = 0
        override fun close(bettingId: Int) = Unit
        override fun refund(bettingId: Int) = Unit
        override fun payout(bettingId: Int, winnerId: Int) = Unit
    }
}
