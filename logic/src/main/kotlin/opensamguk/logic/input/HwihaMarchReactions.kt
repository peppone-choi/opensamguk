package opensamguk.logic.input

/** Version one records a newly created world's empty reaction inventory, not reaction resolution. */
sealed interface HwihaMarchReactions {
    data object Empty : HwihaMarchReactions {
        fun toMetaValue(): Map<String, Any> = linkedMapOf(
            "version" to 1, "installedSchemes" to emptyList<Any>(),
            "interceptions" to emptyList<Any>(), "avoidanceOrders" to emptyList<Any>(),
        )
    }

    /** 반응 기록이 있는지·읽을 수 있는 틀인지만 가른다. 기록 해석(요격·회피)은 하지 않는다. */
    enum class Presence { MISSING, MALFORMED, EMPTY, PENDING }

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

        /**
         * 버전 1 틀(세 목록이 모두 목록)이면 비었는지(EMPTY) 기록이 있는지(PENDING)만 본다. 틀에 없는 필드가 더 있어도
         * 해석기가 붙기 전의 기록으로 보고 PENDING 이다. 키가 없으면 MISSING, 틀이 깨졌으면 MALFORMED.
         */
        fun presence(meta: Map<String, Any?>): Presence {
            if (META_KEY !in meta) return Presence.MISSING
            val raw = meta[META_KEY] as? Map<*, *> ?: return Presence.MALFORMED
            if (raw["version"] != 1 || !raw.keys.containsAll(fields)) return Presence.MALFORMED
            val lists = (fields - "version").map { raw[it] as? List<*> ?: return Presence.MALFORMED }
            return if (raw.keys == fields && lists.all { it.isEmpty() }) Presence.EMPTY else Presence.PENDING
        }

        private fun invalid(): Nothing = throw IllegalArgumentException("Invalid HWIHA march reaction state")
    }
}
