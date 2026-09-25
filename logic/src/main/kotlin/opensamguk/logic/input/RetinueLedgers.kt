package opensamguk.logic.input

/** Only person and named-unit cards occupy renown. A generic bugok has no entry here. */
data class RetinueOwner(val id: Int, val nationId: Int, val isLord: Boolean, val isHuman: Boolean,
                        val renownCapacity: Int, val personCardCost: Int)
data class RetinuePersonLink(val cardId: Int, val holderId: Int, val personId: Int)
data class RetinueNamedUnit(val cardId: String, val holderId: Int, val renownCost: Int)

data class RetinueLedger(
    val freeRenownByOwner: Map<Int, Int>,
    /** Complete subtree in deterministic preorder, including the requested owner. */
    val followersByOwner: Map<Int, List<Int>>,
    val topOwnerByPerson: Map<Int, Int>,
)

/** Validates the whole graph before computing any budget or allegiance transition. */
object RetinueLedgers {
    fun assess(people: List<RetinueOwner>, links: List<RetinuePersonLink>, units: List<RetinueNamedUnit> = emptyList()): RetinueLedger {
        require(people.map { it.id }.distinct().size == people.size)
        require(people.all { it.id > 0 && it.nationId >= 0 && it.renownCapacity >= 0 && it.personCardCost > 0 })
        require(links.map { it.cardId }.distinct().size == links.size)
        require(links.map { it.personId }.distinct().size == links.size) { "one person card may have only one holder" }
        val byId = people.associateBy { it.id }
        require(units.map { it.cardId }.distinct().size == units.size &&
            units.all { it.cardId.isNotBlank() && it.renownCost > 0 && it.holderId in byId })
        val parent = links.associate { link ->
            val holder = requireNotNull(byId[link.holderId]) { "dangling holder ${link.holderId}" }
            val person = requireNotNull(byId[link.personId]) { "dangling person ${link.personId}" }
            require(link.cardId > 0 && holder.id != person.id)
            require(holder.nationId == person.nationId) { "retinue cannot span nations" }
            require(holder.isLord || !person.isHuman) { "a non-lord cannot hold a human person" }
            person.id to holder.id
        }
        val children = links.groupBy({ it.holderId }, { it.personId })
        val followers = people.sortedBy { it.id }.associate { owner ->
            val visited = mutableSetOf<Int>()
            val order = mutableListOf<Int>()
            fun visit(id: Int) {
                require(visited.add(id)) { "retinue cycle" }
                order += id
                children[id].orEmpty().sorted().forEach(::visit)
            }
            visit(owner.id)
            owner.id to order
        }
        val top = people.associate { person ->
            val visited = mutableSetOf<Int>()
            var cursor = person.id
            while (cursor in parent) {
                require(visited.add(cursor)) { "retinue cycle" }
                cursor = parent.getValue(cursor)
            }
            person.id to cursor
        }
        val free = people.sortedBy { it.id }.associate { holder ->
            val personCost = links.filter { it.holderId == holder.id }.sumOf { byId.getValue(it.personId).personCardCost.toLong() }
            val unitCost = units.filter { it.holderId == holder.id }.sumOf { it.renownCost.toLong() }
            val occupied = personCost + unitCost
            require(occupied <= holder.renownCapacity) { "renown capacity exceeded for ${holder.id}" }
            holder.id to (holder.renownCapacity - occupied.toInt())
        }
        return RetinueLedger(free, followers, top)
    }
}
