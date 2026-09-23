package opensamguk.logic.world

import java.util.Collections

/**
 * One 郡國 of the pinned han-tiles. [no] is the index into `parentRegions`/`juns` — the same number the
 * provinces PNG carries in its commandery channel, so the web fog layer and this index agree by construction.
 */
data class HanCommandery(val no: Int, val id: String, val name: String, val nameCh: String) {
    init {
        require(no >= 0) { "Commandery number must not be negative" }
        require(id.isNotBlank() && name.isNotBlank()) { "Commandery identity must not be blank" }
    }
}

/**
 * Commandery-level geography used by HWIHA vision: province → commandery and the shared-border graph.
 * Built from the selected world's pinned tiles only (see `HanCommanderyIndexJson`); never from a repository copy.
 */
class HanCommanderyIndex(
    val tilesContentHash: String,
    commanderies: List<HanCommandery>,
    commanderyByProvinceId: Map<String, Int>,
    adjacentPairs: Set<Pair<Int, Int>>,
) {
    val commanderies: List<HanCommandery> = Collections.unmodifiableList(ArrayList(commanderies))
    private val byProvince: Map<String, Int> = Collections.unmodifiableMap(LinkedHashMap(commanderyByProvinceId))
    private val byId: Map<String, HanCommandery> = this.commanderies.associateBy { it.id }
    private val neighbours: List<List<Int>>

    init {
        require(tilesContentHash.matches(Regex("[0-9a-f]{64}"))) { "Commandery index requires the tiles content hash" }
        require(this.commanderies.isNotEmpty()) { "Commandery index must not be empty" }
        require(this.commanderies.withIndex().all { (index, it) -> it.no == index }) { "Commandery numbers must be dense indices" }
        require(byId.size == this.commanderies.size) { "Duplicate commandery identity" }
        require(byProvince.values.all { it in this.commanderies.indices }) { "Province maps outside the commandery table" }
        require(byProvince.keys.none(String::isBlank)) { "Blank province identity" }
        val lists = List(this.commanderies.size) { sortedSetOf<Int>() }
        adjacentPairs.forEach { (a, b) ->
            require(a != b && a in this.commanderies.indices && b in this.commanderies.indices) { "Invalid commandery adjacency" }
            lists[a].add(b); lists[b].add(a)
        }
        neighbours = lists.map { Collections.unmodifiableList(it.toList()) }
    }

    val provinceIds: Set<String> get() = byProvince.keys

    fun commanderyOf(provinceId: String): Int? = byProvince[provinceId]

    fun commanderyOf(node: StrategicNodeRef?): Int? = (node as? StrategicNodeRef.LandProvince)?.let { byProvince[it.id] }

    fun byId(id: String): HanCommandery? = byId[id]

    /** Neighbours in ascending number order. */
    fun neighbours(no: Int): List<Int> {
        require(no in commanderies.indices) { "Unknown commandery $no" }
        return neighbours[no]
    }

    fun adjacent(a: Int, b: Int): Boolean = a in commanderies.indices && b in neighbours(a)

    /** Every commandery within [radius] shared-border steps of [no], including [no] itself. */
    fun within(no: Int, radius: Int): Set<Int> {
        require(radius >= 0) { "Radius must not be negative" }
        require(no in commanderies.indices) { "Unknown commandery $no" }
        val seen = sortedSetOf(no)
        var frontier = listOf(no)
        repeat(radius) {
            frontier = frontier.flatMap { neighbours[it] }.filter { seen.add(it) }
            if (frontier.isEmpty()) return seen
        }
        return seen
    }
}
