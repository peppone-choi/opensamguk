package opensamguk.logic.tournament

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
