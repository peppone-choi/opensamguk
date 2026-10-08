package opensamguk.logic.retainer

data class RetinueHierarchyPerson(val id: Int, val nationId: Int)
data class RetinueHierarchyLink(val masterId: Int, val generalId: Int)

/** A personal relationship forest. Office, title, human status and command authority are separate facts. */
class RetinueHierarchy private constructor(
    private val personIds: Set<Int>,
    val parentByPerson: Map<Int, Int>,
    val childrenByOwner: Map<Int, List<Int>>,
) {
    /** Includes the owner. Preorder, with each owner's children ordered by person ID. */
    fun subtree(ownerId: Int): List<Int> {
        require(ownerId in personIds) { "retinue owner not found" }
        val pending = ArrayDeque<Int>().apply { add(ownerId) }
        val result = mutableListOf<Int>()
        while (pending.isNotEmpty()) {
            val id = pending.removeLast()
            result.add(id)
            childrenByOwner[id].orEmpty().asReversed().forEach(pending::addLast)
        }
        return result
    }

    /** Direct superior first, root last; excludes the person. */
    fun ancestors(personId: Int): List<Int> {
        require(personId in personIds) { "retinue person not found" }
        return generateSequence(parentByPerson[personId]) { parentByPerson[it] }.toList()
    }

    companion object {
        fun build(people: List<RetinueHierarchyPerson>, links: List<RetinueHierarchyLink>): RetinueHierarchy {
            val byId = people.associateBy { it.id }
            require(byId.size == people.size && people.all { it.id > 0 && it.nationId >= 0 }) {
                "retinue requires unique positive people and nonnegative nations"
            }
            val parent = links.associate { it.generalId to it.masterId }
            require(parent.size == links.size) { "one person may have only one direct superior" }
            for (link in links) {
                val master = requireNotNull(byId[link.masterId]) { "dangling retinue superior" }
                val person = requireNotNull(byId[link.generalId]) { "dangling retinue person" }
                require(master.id != person.id && master.nationId == person.nationId) {
                    "retinue requires distinct people in the same nation"
                }
            }
            for (id in byId.keys) {
                val visited = mutableSetOf<Int>()
                var cursor: Int? = id
                while (cursor != null) {
                    require(visited.add(cursor)) { "retinue cycle" }
                    cursor = parent[cursor]
                }
            }
            return RetinueHierarchy(byId.keys.toSet(), parent.toSortedMap(),
                links.groupBy({ it.masterId }, { it.generalId }).mapValues { (_, children) -> children.sorted() }.toSortedMap())
        }
    }
}
