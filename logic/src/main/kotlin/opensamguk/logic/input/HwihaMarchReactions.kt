package opensamguk.logic.input

/** Version one records a newly created world's empty reaction inventory, not reaction resolution. */
sealed interface HwihaMarchReactions {
    data object Empty : HwihaMarchReactions {
        fun toMetaValue(): Map<String, Any> = linkedMapOf(
            "version" to 1, "installedSchemes" to emptyList<Any>(),
            "interceptions" to emptyList<Any>(), "avoidanceOrders" to emptyList<Any>(),
        )
    }

    companion object {
        const val META_KEY = "hwihaMarchReactions"
        private val fields = setOf("version", "installedSchemes", "interceptions", "avoidanceOrders")

        fun read(meta: Map<String, Any?>): HwihaMarchReactions? {
            if (META_KEY !in meta) return null
            val raw = meta[META_KEY] as? Map<*, *> ?: invalid()
            require(raw.keys == fields && raw["version"] == 1) { "Unsupported march reaction schema" }
            for (field in fields - "version") {
                val entries = raw[field] as? List<*> ?: invalid()
                // Nonempty inventories require their own validated execution contract; never discard entries.
                require(entries.isEmpty()) { "March reactions cannot be resolved by the initial-state reader" }
            }
            return Empty
        }

        private fun invalid(): Nothing = throw IllegalArgumentException("Invalid HWIHA march reaction state")
    }
}
