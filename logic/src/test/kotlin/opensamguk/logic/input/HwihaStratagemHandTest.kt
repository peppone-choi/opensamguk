package opensamguk.logic.input

import kotlin.test.*

class HwihaStratagemHandTest {
    private val phase=HwihaPhase(200,1,1)
    private fun initial()=HwihaStratagemHand.initial(1,phase)
    private fun read(raw:Any?,owner:Int=1)=HwihaStratagemHand.read(mapOf(HwihaStratagemHand.META_KEY to raw),owner)
    @Test fun `opening hand fixed types and one draw per actual later phase survive cold read`() {
        val h=initial()
        assertEquals(listOf(1,2),h.hand);assertEquals(listOf(3,4),h.drawPile);assertTrue(h.discard.isEmpty())
        assertEquals(listOf(HwihaStratagemCardType.FORTIFY,HwihaStratagemCardType.INSIGHT,HwihaStratagemCardType.FORTIFY,HwihaStratagemCardType.INSIGHT),(1..4).map(h::cardType))
        assertSame(h,h.advance(phase))
        val next=h.advance(phase.plus(10))
        assertEquals(listOf(1,2,3),next.hand);assertEquals(listOf(4),next.drawPile)
        val cold=assertNotNull(read(next.toMetaValue()))
        assertEquals(next.toMetaValue(),cold.toMetaValue());assertSame(cold,cold.advance(phase.plus(10)))
        assertFailsWith<IllegalArgumentException> { cold.advance(phase) }
    }
    @Test fun `full hand discards new card and empty draw pile recycles sorted discard`() {
        val full=initial().advance(phase.plus(1)).advance(phase.plus(2))
        assertEquals(listOf(1,2,3),full.hand);assertEquals(listOf(4),full.discard);assertTrue(full.drawPile.isEmpty())
        val consumed=full.consume(3).consume(1)
        assertEquals(listOf(4,3,1),consumed.discard)
        val recycled=consumed.advance(phase.plus(3))
        assertEquals(listOf(2,1),recycled.hand);assertEquals(listOf(3,4),recycled.drawPile);assertTrue(recycled.discard.isEmpty())
        assertEquals(full.hand,full.advance(phase.plus(3)).hand)
        assertFailsWith<IllegalArgumentException> { full.consume(4) }
        assertFailsWith<IllegalArgumentException> { consumed.consume(1) }
    }
    @Test fun `partition and immutable ownership never initialize missing or malformed state`() {
        val hand=mutableListOf(1,2);val pile=mutableListOf(3,4);val discard=mutableListOf<Int>()
        val h=HwihaStratagemHand(1,hand,pile,discard,phase);hand.clear();pile.clear();discard.add(1)
        assertEquals(initial().toMetaValue(),h.toMetaValue())
        for(list in listOf(h.hand,h.drawPile,h.discard))assertFailsWith<UnsupportedOperationException> { (list as MutableList<*>).clear() }
        assertNull(HwihaStratagemHand.read(emptyMap(),1));assertFailsWith<IllegalArgumentException> { read(null) }
        assertFailsWith<IllegalArgumentException> { read(h.toMetaValue(),2) }
        assertFailsWith<IllegalArgumentException> { HwihaStratagemHand(1,listOf(1,2,3,4),emptyList(),emptyList(),phase) }
        assertFailsWith<IllegalArgumentException> { HwihaStratagemHand(1,listOf(1,1),listOf(3,4),emptyList(),phase) }
        assertFailsWith<IllegalArgumentException> { HwihaStratagemHand(1,listOf(1,2),listOf(3,5),emptyList(),phase) }
        assertFailsWith<IllegalArgumentException> { h.cardType(0) }
    }
    @Test fun `strict metadata rejects numeric coercion missing extra fields and invalid phase`() {
        val raw=initial().toMetaValue()
        for(bad in listOf(raw+("version" to 1L),raw+("extra" to 1),raw-"discard",raw+("ownerGeneralId" to "1"),
            raw+("lastDrawPhase" to mapOf("year" to 200,"month" to 1,"phase" to 4))))
            assertFailsWith<IllegalArgumentException> { read(bad) }
        for(v in listOf<Any>(1L,1.0,"1",true)) assertFailsWith<IllegalArgumentException> { read(raw+("hand" to listOf(v,2))) }
    }
}
