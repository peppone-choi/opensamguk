package opensamguk.engine.turn

/** Linked insertion order with a one-unit before-image journal. */
internal class UnitJournalMap<K, V>(private val copyValue: (V) -> V) : LinkedHashMap<K, V>() {
    private var active: Snapshot? = null

    private sealed interface Before<out V> {
        data class Present<V>(val value: V) : Before<V>
        data object Missing : Before<Nothing>
    }

    inner class Snapshot internal constructor(private val order: List<K>) {
        private val before = LinkedHashMap<K, Before<V>>()

        internal fun capture(key: K) {
            if (before.containsKey(key)) return
            before[key] = if (rawContainsKey(key)) {
                @Suppress("UNCHECKED_CAST")
                Before.Present(copyValue(rawGet(key) as V))
            } else Before.Missing
        }

        internal fun captureAll() {
            for (key in order) capture(key)
        }

        fun commit() {
            check(active === this) { "map checkpoint is not active" }
            active = null
        }

        fun restore() {
            check(active === this) { "map checkpoint is not active" }
            active = null
            val restored = ArrayList<Pair<K, V>>(order.size)
            for (key in order) {
                val value = when (val previous = before[key]) {
                    is Before.Present -> copyValue(previous.value)
                    Before.Missing -> error("original map key disappeared from before-image")
                    null -> {
                        check(rawContainsKey(key)) { "untracked map removal: $key" }
                        @Suppress("UNCHECKED_CAST")
                        rawGet(key) as V
                    }
                }
                restored += key to value
            }
            rawClear()
            for ((key, value) in restored) rawPut(key, value)
        }
    }

    fun checkpoint(): Snapshot {
        check(active == null) { "nested world map checkpoint" }
        return Snapshot(super.keys.toList()).also { active = it }
    }

    /** Discard a partially assembled world checkpoint before any unit code can run. */
    fun abandonCheckpoint() {
        active = null
    }

    private fun rawContainsKey(key: K): Boolean = super.containsKey(key)
    private fun rawGet(key: K): V? = super.get(key)
    private fun rawClear() = super.clear()
    private fun rawPut(key: K, value: V): V? = super.put(key, value)

    override fun get(key: K): V? {
        active?.capture(key)
        return super.get(key)
    }

    override fun put(key: K, value: V): V? {
        active?.capture(key)
        return super.put(key, value)
    }

    override fun remove(key: K): V? {
        active?.capture(key)
        return super.remove(key)
    }

    override fun clear() {
        active?.captureAll()
        super.clear()
    }

    override fun putAll(from: Map<out K, V>) {
        from.forEach { (key, value) -> put(key, value) }
    }

    override val keys: MutableSet<K>
        get() = java.util.Collections.unmodifiableSet(super.keys)

    override val values: MutableCollection<V>
        get() {
            active?.captureAll()
            return super.values
        }

    override val entries: MutableSet<MutableMap.MutableEntry<K, V>>
        get() {
            active?.captureAll()
            return super.entries
        }
}
