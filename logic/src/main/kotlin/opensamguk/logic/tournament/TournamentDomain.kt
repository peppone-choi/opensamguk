package opensamguk.logic.tournament

import java.time.Instant

data class TournamentEntry(
    val id: Int,
    val npc: Int,
    val name: String,
    val leadership: Int,
    val strength: Int,
    val intel: Int,
    val level: Int,
    val group: Int,
    val groupNo: Int,
    val win: Int = 0,
    val draw: Int = 0,
    val lose: Int = 0,
    val goal: Int = 0,
    val promote: Int = 0,
    val seq: Int = 0,
    val horse: String = "None",
    val weapon: String = "None",
    val book: String = "None",
) {
    val total: Int get() = leadership + strength + intel
    val games: Int get() = win + draw + lose
    val point: Int get() = win * 3 + draw
}

data class TournamentState(
    val tournament: Int,
    val phase: Int,
    val type: Int,
    val auto: Boolean,
    val time: Instant?,
    val turnTermMinutes: Int,
    val lastBettingId: Int = 0,
)

data class TournamentProcessResult(
    val state: TournamentState,
    val changed: Boolean,
)

interface TournamentStore {
    fun entries(): List<TournamentEntry>
    fun replaceAll(entries: List<TournamentEntry>)
    fun upsert(entry: TournamentEntry)
    fun clear()
}

class InMemoryTournamentStore(
    entries: Map<Int, TournamentEntry> = emptyMap(),
) : TournamentStore {
    private val rows = LinkedHashMap<Int, TournamentEntry>()

    init {
        entries.values.sortedWith(compareBy<TournamentEntry> { it.group }.thenBy { it.groupNo }.thenBy { it.seq })
            .forEach { rows[it.id] = it }
    }

    override fun entries(): List<TournamentEntry> =
        rows.values.sortedWith(compareBy<TournamentEntry> { it.group }.thenBy { it.groupNo }.thenBy { it.seq })

    override fun replaceAll(entries: List<TournamentEntry>) {
        rows.clear()
        entries.forEach { rows[it.id] = it }
    }

    override fun upsert(entry: TournamentEntry) {
        rows[entry.id] = entry
    }

    override fun clear() {
        rows.clear()
    }
}

interface TournamentBettingPort {
    fun open(type: Int, unitSeconds: Int, finalists: List<TournamentEntry>): Int
    fun close(bettingId: Int)
    fun refund(bettingId: Int)
    fun payout(bettingId: Int, winnerId: Int)
}

fun tournamentTypeText(type: Int): String =
    listOf("전력전", "통솔전", "일기토", "설전").getOrElse(type) { "TOURNAMENT_TYPE_ERR_$type" }

fun tournamentStateHtml(state: Int): String =
    listOf(
        "<span style='color:magenta;'>경기 없음</span>",
        "<span style='color:orange;'>참가 모집중</span>",
        "<span style='color:orange;'>예선 진행중</span>",
        "<span style='color:orange;'>본선 추첨중</span>",
        "<span style='color:orange;'>본선 진행중</span>",
        "<span style='color:orange;'>16강 배정중</span>",
        "<span style='color:orange;'>베팅 진행중</span>",
        "<span style='color:orange;'>16강 진행중</span>",
        "<span style='color:orange;'>8강 진행중</span>",
        "<span style='color:orange;'>4강 진행중</span>",
        "<span style='color:orange;'>결승 진행중</span>",
    ).getOrElse(state) { "TOURNAMENT_TYPE_ERR_$state" }
