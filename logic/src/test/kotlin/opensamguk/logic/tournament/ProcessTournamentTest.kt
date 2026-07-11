package opensamguk.logic.tournament

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProcessTournamentTest {
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
}
