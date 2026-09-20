package opensamguk.logic.input

/** Game-design policy; callers must provide five source-validated stats, never importer defaults. */
object HwihaRenownRules {
    /** New characters only; monthly assessment must preserve and update existing renown. */
    const val INITIAL_CAPACITY = 30

    fun personCost(leadership: Int, strength: Int, intelligence: Int, politics: Int, charm: Int): Int {
        val stats = listOf(leadership, strength, intelligence, politics, charm)
        require(stats.all { it >= 0 }) { "five nonnegative source-validated stats required" }
        val total = stats.sumOf { it.toLong() }
        return maxOf(1L, (total + 49L) / 50L).toInt()
    }
}
