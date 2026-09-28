package opensamguk.logic.battle.realtime

import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

enum class BattleKind { ENCOUNTER, SIEGE }

data class TerrainProfile(val counts: Map<Char, Int>) {
    init {
        require(counts.keys == TERRAINS)
        require(counts.values.all { it >= 0 } && counts.values.sum() > 0)
    }

    val total: Int get() = counts.values.sum()

    companion object {
        private val TERRAINS = setOf('P', 'F', 'M', 'R', 'W')

        fun fromTiles(tiles: Iterable<Char>): TerrainProfile {
            val counts = TERRAINS.associateWith { 0 }.toMutableMap()
            tiles.forEach { tile -> counts[tile] = requireNotNull(counts[tile]) { "unknown terrain $tile" } + 1 }
            return TerrainProfile(counts)
        }
    }
}

data class TacticalBoard(
    val battlefield: Battlefield,
    val tileset: Int,
    val image: String,
    val landEligible: Boolean,
    val terrain: TerrainProfile,
)

data class BoardSelection(val board: TacticalBoard, val catalogSha256: String, val score: Long)

/** Reads the owner-accepted, source-free 214-board export. Callers pin catalogSha256 in the ticket. */
class TacticalBoardCatalog private constructor(val boards: List<TacticalBoard>, val catalogSha256: String) {
    fun select(battleId: String, kind: BattleKind, province: TerrainProfile): BoardSelection {
        require(battleId.isNotBlank())
        val boardKind = if (kind == BattleKind.SIEGE) "FORTRESS" else "FIELD"
        val candidates = boards.asSequence().filter { it.landEligible && it.battlefield.kind == boardKind }
            .map { it to distance(it.terrain, province) }
            .sortedWith(compareBy<Pair<TacticalBoard, Long>> { it.second }.thenBy { it.first.battlefield.id })
            .take(TacticalRules.CANON.topMapCandidates).toList()
        require(candidates.isNotEmpty()) { "no land board for $kind" }
        val digest = MessageDigest.getInstance("SHA-256").digest(battleId.toByteArray(Charsets.UTF_8))
        val choice = (0 until 8).fold(0L) { value, index -> (value shl 8) or (digest[index].toLong() and 255) }
        val (board, score) = candidates[java.lang.Long.remainderUnsigned(choice, candidates.size.toLong()).toInt()]
        return BoardSelection(board, catalogSha256, score)
    }

    companion object {
        fun parse(payload: String): TacticalBoardCatalog {
            val root = Json.parseToJsonElement(payload).jsonObject
            require(root.int("schemaVersion") == 1 && root.int("boardSize") == 64)
            require(root.string("status") == "owner-accepted derived catalog")
            require(!root.section("source").bool("originalBinaryCommitted"))
            val rawBoards = root.getValue("boards").jsonArray
            require(rawBoards.size == 214)
            val boards = rawBoards.map { element -> parseBoard(element.jsonObject) }
            require(boards.map { it.battlefield.id }.sorted() == (0..213).toList())
            val sha = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            return TacticalBoardCatalog(boards.sortedBy { it.battlefield.id }, sha)
        }

        private fun parseBoard(node: JsonObject): TacticalBoard {
            val id = node.int("id")
            val rows = node.getValue("terrainRows").jsonArray.map { it.jsonPrimitive.content }
            val field = Battlefield(id, node.string("kind"), rows)
            val counts = node.section("terrainCounts")
            val parsed = TerrainProfile(mapOf('P' to counts.int("P"), 'F' to counts.int("F"),
                'M' to counts.int("M"), 'R' to counts.int("R"), 'W' to counts.int("W")))
            require(parsed == TerrainProfile.fromTiles(rows.asSequence().flatMap { it.asSequence() }.asIterable()))
            val image = node.string("image")
            require(Regex("maps/battle_[0-9]{3}_ts[0-2]\\.png").matches(image))
            require(image.contains("battle_${id.toString().padStart(3, '0')}_ts${node.int("tileset")}"))
            return TacticalBoard(field, node.int("tileset"), image, node.bool("landEligible"), parsed)
        }

        private fun distance(board: TerrainProfile, province: TerrainProfile): Long =
            board.counts.keys.sumOf { terrain ->
                kotlin.math.abs(board.counts.getValue(terrain).toLong() * province.total -
                    province.counts.getValue(terrain).toLong() * board.total)
            }

        private fun JsonObject.section(key: String): JsonObject = getValue(key).jsonObject
        private fun JsonObject.int(key: String): Int = getValue(key).jsonPrimitive.int
        private fun JsonObject.bool(key: String): Boolean = getValue(key).jsonPrimitive.boolean
        private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content
    }
}
