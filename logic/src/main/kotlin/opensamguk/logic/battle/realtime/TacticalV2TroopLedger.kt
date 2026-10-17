package opensamguk.logic.battle.realtime

import java.util.Collections

/** Integer accounting for one real campaign source; combat damage rules live elsewhere. */
data class TacticalV2TroopBalance(
    val initial: Int,
    val active: Int,
    val escaped: Int,
    val casualties: Int,
    val captured: Int = 0,
) {
    init {
        require(initial > 0 && active >= 0 && escaped >= 0 && casualties >= 0 && captured >= 0)
        require(active.toLong() + escaped + casualties + captured == initial.toLong()) {
            "source troop conservation failed"
        }
    }
}

/** No source may disappear when a v2 battle damages, retreats, or applies its result. */
class TacticalV2TroopLedger(
    sources: List<TacticalV2UnitSource>,
    balances: Map<TacticalV2SourceKey, TacticalV2TroopBalance>,
) {
    val sources: List<TacticalV2UnitSource> = Collections.unmodifiableList(ArrayList(sources))
    val balances: Map<TacticalV2SourceKey, TacticalV2TroopBalance> =
        Collections.unmodifiableMap(LinkedHashMap(balances))

    init {
        require(this.sources == this.sources.sortedBy { it.key } &&
            this.sources.map { it.key }.distinct().size == this.sources.size)
        require(this.balances.keys == this.sources.map { it.key }.toSet())
        require(this.sources.all { this.balances.getValue(it.key).initial == it.initialTroops })
    }

    fun copy(
        sources: List<TacticalV2UnitSource> = this.sources,
        balances: Map<TacticalV2SourceKey, TacticalV2TroopBalance> = this.balances,
    ) = TacticalV2TroopLedger(sources, balances)

    override fun equals(other: Any?): Boolean = other is TacticalV2TroopLedger &&
        sources == other.sources && balances == other.balances

    override fun hashCode(): Int = listOf(sources, balances).hashCode()

    override fun toString(): String = "TacticalV2TroopLedger(sources=$sources, balances=$balances)"

    fun takeCasualties(key: TacticalV2SourceKey, amount: Int): TacticalV2TroopLedger {
        require(amount >= 0)
        val before = balances.getValue(key)
        require(amount <= before.active)
        return copy(balances = balances + (key to before.copy(
            active = before.active - amount, casualties = before.casualties + amount)))
    }

    fun escape(key: TacticalV2SourceKey): TacticalV2TroopLedger {
        require(key.kind == TacticalV2SourceKind.RETINUE) { "city garrison cannot retreat" }
        val before = balances.getValue(key)
        return copy(balances = balances + (key to before.copy(
            active = 0, escaped = before.escaped + before.active)))
    }

    fun escapeOne(key: TacticalV2SourceKey): TacticalV2TroopLedger {
        require(key.kind != TacticalV2SourceKind.CITY_GARRISON_BUGOK) { "city garrison cannot retreat" }
        val before = balances.getValue(key)
        require(before.active >= 1)
        return copy(balances = balances + (key to before.copy(
            active = before.active - 1, escaped = before.escaped + 1)))
    }

    fun captureOne(key: TacticalV2SourceKey): TacticalV2TroopLedger {
        val before = balances.getValue(key)
        require(before.active >= 1)
        return copy(balances = balances + (key to before.copy(
            active = before.active - 1, captured = before.captured + 1)))
    }

    /** New source identity is unique; replay handling belongs to the source registry. */
    fun addSource(source: TacticalV2UnitSource): TacticalV2TroopLedger {
        require(source.key !in balances) { "duplicate battle source" }
        return copy(sources = (sources + source).sortedBy { it.key },
            balances = balances + (source.key to TacticalV2TroopBalance(
                source.initialTroops, source.initialTroops, 0, 0)))
    }

    /** All actual city sources are summed without percentages or a four-token conversion. */
    fun cityRemaining(cityId: Int): Int {
        require(cityId > 0)
        val total = sources.asSequence().filter { it.key.kind == TacticalV2SourceKind.CITY_GARRISON_BUGOK &&
            it.key.cityId == cityId }.sumOf { source ->
            val balance = balances.getValue(source.key)
            balance.active.toLong() + balance.escaped
        }
        require(total <= Int.MAX_VALUE)
        return total.toInt()
    }

    companion object {
        fun fromSources(sources: List<TacticalV2UnitSource>): TacticalV2TroopLedger {
            val ordered = sources.sortedBy { it.key }
            require(ordered.map { it.key }.distinct().size == ordered.size)
            return TacticalV2TroopLedger(ordered, ordered.associate { source ->
                source.key to TacticalV2TroopBalance(source.initialTroops, source.initialTroops, 0, 0)
            })
        }
    }
}
