package opensamguk.logic.input

/** City-owned headcount is separate from the fortification score in City.defence. */
data class CityMilitaryState(val training: Int, val morale: Int, val troops: Int = 0) {
    init { require(training in 0..100 && morale in 0..100 && troops >= 0) }

    fun toMetaValue(): Map<String, Any> = linkedMapOf("version" to 2, "training" to training,
        "morale" to morale, "troops" to troops)

    companion object {
        const val META_KEY = "cityMilitary"
        val INITIAL = CityMilitaryState(50, 50)

        /** Import legacy garrison once when the city has no independent military state yet. */
        fun read(meta: Map<String, Any?>, legacyGarrison: Int = 0): CityMilitaryState {
            require(legacyGarrison >= 0)
            if (META_KEY !in meta) return INITIAL.copy(troops = legacyGarrison)
            val value = meta[META_KEY] as? Map<*, *> ?: invalid()
            val version = value["version"] as? Int ?: invalid()
            require(version == 2 && value.keys == setOf("version", "training", "morale", "troops"))
            return CityMilitaryState(value["training"] as? Int ?: invalid(),
                value["morale"] as? Int ?: invalid(),
                value["troops"] as? Int ?: invalid())
        }

        private fun invalid(): Nothing = throw IllegalArgumentException("Invalid HWIHA city military state")
    }
}
