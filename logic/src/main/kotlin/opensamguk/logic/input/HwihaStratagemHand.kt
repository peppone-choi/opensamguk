package opensamguk.logic.input

import java.util.Collections

enum class HwihaStratagemCardType { FORTIFY, INSIGHT }

/** Fixed-order v1 instances. This state supplies cards, not their combat effects. */
class HwihaStratagemHand(val ownerGeneralId: Int, hand: List<Int>, drawPile: List<Int>, discard: List<Int>,
    val lastDrawPhase: HwihaPhase) {
    val hand: List<Int> = Collections.unmodifiableList(ArrayList(hand))
    val drawPile: List<Int> = Collections.unmodifiableList(ArrayList(drawPile))
    val discard: List<Int> = Collections.unmodifiableList(ArrayList(discard))
    init {
        require(ownerGeneralId > 0 && this.hand.size <= HAND_LIMIT)
        val all=this.hand+this.drawPile+this.discard
        require(all.size == 4 && all.toSet() == setOf(1,2,3,4)) { "Cards must partition the four owned instances" }
    }
    fun cardType(id: Int): HwihaStratagemCardType = when(id) {
        1,3 -> HwihaStratagemCardType.FORTIFY
        2,4 -> HwihaStratagemCardType.INSIGHT
        else -> throw IllegalArgumentException("Unknown stratagem instance")
    }
    fun advance(phase: HwihaPhase): HwihaStratagemHand {
        require(phase >= lastDrawPhase) { "Cannot draw in an older phase" }
        if(phase == lastDrawPhase)return this
        val pile=if(drawPile.isEmpty())discard.sorted() else drawPile
        val remainingDiscard=if(drawPile.isEmpty())emptyList() else discard
        // Exact partition plus hand limit guarantees at least one non-hand instance.
        val drawn=pile.first()
        return HwihaStratagemHand(ownerGeneralId,if(hand.size < HAND_LIMIT)hand+drawn else hand,
            pile.drop(1),if(hand.size < HAND_LIMIT)remainingDiscard else remainingDiscard+drawn,phase)
    }
    fun consume(instanceId: Int): HwihaStratagemHand {
        require(instanceId in hand) { "Only an owned hand instance can be consumed" }
        return HwihaStratagemHand(ownerGeneralId,hand.filter { it != instanceId },drawPile,discard+instanceId,lastDrawPhase)
    }
    fun toMetaValue(): Map<String,Any> = linkedMapOf("version" to 1,"ownerGeneralId" to ownerGeneralId,
        "hand" to hand,"drawPile" to drawPile,"discard" to discard,"lastDrawPhase" to lastDrawPhase.toMetaValue())
    companion object {
        const val META_KEY="hwihaStratagemHand"
        const val HAND_LIMIT=3
        fun initial(owner: Int, phase: HwihaPhase)=HwihaStratagemHand(owner,listOf(1,2),listOf(3,4),emptyList(),phase)
        fun read(meta: Map<String,Any?>, ownerGeneralId: Int): HwihaStratagemHand? {
            require(ownerGeneralId > 0)
            if(META_KEY !in meta)return null
            val row=meta[META_KEY] as? Map<*,*> ?: invalid()
            require(row.keys == setOf("version","ownerGeneralId","hand","drawPile","discard","lastDrawPhase") && row["version"] == 1)
            val owner=row["ownerGeneralId"] as? Int ?: invalid()
            require(owner == ownerGeneralId) { "Stratagem hand owner mismatch" }
            fun ids(key:String)=(row[key] as? List<*>)?.map { it as? Int ?: invalid() } ?: invalid()
            return HwihaStratagemHand(owner,ids("hand"),ids("drawPile"),ids("discard"),HwihaPhase.read(row["lastDrawPhase"]))
        }
        private fun invalid():Nothing=throw IllegalArgumentException("Invalid stratagem hand")
    }
}
