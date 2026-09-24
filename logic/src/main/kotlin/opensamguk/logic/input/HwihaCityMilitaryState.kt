package opensamguk.logic.input

/** Training and spirit of city-owned troops. The headcount is the city's defence column. */
data class HwihaCityMilitaryState(val training: Int, val morale: Int) {
    init { require(training in 0..100 && morale in 0..100) }

    fun toMetaValue(): Map<String, Any> = linkedMapOf("version" to 1, "training" to training, "morale" to morale)

    companion object {
        const val META_KEY = "hwihaCityMilitary"
        val INITIAL = HwihaCityMilitaryState(50, 50)

        fun read(meta: Map<String, Any?>): HwihaCityMilitaryState {
            if (META_KEY !in meta) return INITIAL
            val value = meta[META_KEY] as? Map<*, *> ?: invalid()
            require(value.keys == setOf("version", "training", "morale") && value["version"] == 1)
            return HwihaCityMilitaryState(value["training"] as? Int ?: invalid(),
                value["morale"] as? Int ?: invalid())
        }

        private fun invalid(): Nothing = throw IllegalArgumentException("Invalid HWIHA city military state")
    }
}
