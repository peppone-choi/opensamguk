package opensamguk.logic.world

/** Persisted world shape, independent of map bundle revisions and game rules. */
enum class WorldFormat {
    GENERAL_RETAINER_CAMPAIGN;

    companion object {
        const val CONFIG_KEY = "worldFormat"

        /** Existing worlds are never silently promoted to the current storage shape. */
        fun require(config: Map<String, Any?>, meta: Map<String, Any?> = emptyMap()): WorldFormat {
            rejectRetiredKeys(config, "config")
            rejectRetiredKeys(meta, "meta")
            val raw = config[CONFIG_KEY]
                ?: throw IllegalArgumentException("worldFormat is missing from world config")
            require(raw is String && raw == GENERAL_RETAINER_CAMPAIGN.name) {
                "unsupported worldFormat in world config: '$raw'"
            }
            return GENERAL_RETAINER_CAMPAIGN
        }

        private fun rejectRetiredKeys(value: Any?, path: String) {
            when (value) {
                is Map<*, *> -> value.forEach { (key, child) ->
                    val name = key as? String ?: return@forEach
                    require(name != "ruleProfile" && !name.startsWith("hwiha", ignoreCase = true)) {
                        "retired world key at $path.$name"
                    }
                    rejectRetiredKeys(child, "$path.$name")
                }
                is Iterable<*> -> value.forEachIndexed { index, child -> rejectRetiredKeys(child, "$path[$index]") }
            }
        }
    }
}
