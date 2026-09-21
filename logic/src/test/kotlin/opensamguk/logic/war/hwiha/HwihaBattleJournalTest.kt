package opensamguk.logic.war.hwiha

import kotlin.test.*
import opensamguk.logic.world.HwihaBattlefieldGeometry.Position

class HwihaBattleJournalTest {
    private fun input(round:Int=1)=HwihaRoundInput(round,listOf(HwihaGridExchange.MovementPlan(2,listOf(Position(-1,Int.MAX_VALUE))),
        HwihaGridExchange.MovementPlan(1,listOf(Position(0,0)))),listOf(HwihaGridExchange.AttackIntent(2,1),HwihaGridExchange.AttackIntent(1,2)))
    private fun journal()=HwihaBattleJournal("a".repeat(64),"b".repeat(64),emptyList())
    private fun read(raw:Any?)=HwihaBattleJournal.read(mapOf(HwihaBattleJournal.META_KEY to raw))
    @Test fun `canonical replay is idempotent and input values including negative positions survive`() {
        val j=journal().append(input())
        assertSame(j,j.append(input()))
        assertEquals(j.toMetaValue(),read(j.toMetaValue())!!.toMetaValue())
        assertEquals(listOf(1,2),j.rounds[0].movements.map { it.bugokId })
        assertEquals(Position(-1,Int.MAX_VALUE),j.rounds[0].movements[1].path.single())
        assertNull(HwihaBattleJournal.read(emptyMap()))
        assertFailsWith<IllegalArgumentException> { read(null) }
    }
    @Test fun `changed duplicate gaps unsorted rounds and rounds beyond limit reject`() {
        val j=journal().append(input())
        assertFailsWith<IllegalArgumentException> { j.append(HwihaRoundInput(1,emptyList(),emptyList())) }
        assertFailsWith<IllegalArgumentException> { j.append(input(3)) }
        assertFailsWith<IllegalArgumentException> { HwihaBattleJournal(j.encounterId,j.contextHash,listOf(input(2),input(1))) }
        assertFailsWith<IllegalArgumentException> { input(25) }
        assertFailsWith<IllegalArgumentException> { input(0) }
        var full=journal();for(n in 1..24)full=full.append(input(n))
        assertEquals(24,read(full.toMetaValue())!!.rounds.size)
        assertSame(full,full.append(input(24)))
    }
    @Test fun `immutable copies and identity constraints prevent caller mutation`() {
        val paths=mutableListOf(Position(0,0));val moves=mutableListOf(HwihaGridExchange.MovementPlan(1,paths))
        val attacks=mutableListOf(HwihaGridExchange.AttackIntent(1,2))
        val i=HwihaRoundInput(1,moves,attacks);paths.clear();moves.clear();attacks.clear()
        val rounds=mutableListOf(i);val j=HwihaBattleJournal("a".repeat(64),"b".repeat(64),rounds);rounds.clear()
        assertEquals(Position(0,0),j.rounds[0].movements[0].path.single())
        assertFailsWith<UnsupportedOperationException> { (j.rounds as MutableList<*>).clear() }
        assertFailsWith<UnsupportedOperationException> { (i.attacks as MutableList<*>).clear() }
        assertFailsWith<UnsupportedOperationException> { (i.movements as MutableList<*>).clear() }
        assertFailsWith<IllegalArgumentException> { HwihaRoundInput(1,listOf(i.movements[0],i.movements[0]),emptyList()) }
        assertFailsWith<IllegalArgumentException> { HwihaRoundInput(1,emptyList(),listOf(i.attacks[0],i.attacks[0])) }
        assertFailsWith<IllegalArgumentException> { HwihaRoundInput(1,emptyList(),listOf(HwihaGridExchange.AttackIntent(1,1))) }
        assertFailsWith<IllegalArgumentException> { HwihaRoundInput(1,listOf(HwihaGridExchange.MovementPlan(0,emptyList())),emptyList()) }
    }
    @Test fun `strict codec rejects tamper numeric coercion extra fields and noncanonical input order`() {
        val j=journal().append(input());val raw=j.toMetaValue();val r=input().toMetaValue()
        for(bad in listOf(raw+("version" to 1L),raw+("extra" to 1),raw+("snapshotId" to "bad"),raw+("contextHash" to "bad")))
            assertFailsWith<IllegalArgumentException> { read(bad) }
        for(v in listOf<Any>(1L,1.0,"1",true)) assertFailsWith<IllegalArgumentException> { read(raw+("rounds" to listOf(r+("round" to v)))) }
        assertFailsWith<IllegalArgumentException> { read(raw+("rounds" to listOf(r+("movements" to (r["movements"] as List<*>).reversed())))) }
        assertFailsWith<IllegalArgumentException> { read(raw+("rounds" to listOf(r+("attacks" to (r["attacks"] as List<*>).reversed())))) }
        val altered=HwihaBattleJournal(j.encounterId,j.contextHash,listOf(HwihaRoundInput(1,emptyList(),emptyList())))
        assertNotEquals(j.snapshotId,altered.snapshotId)
    }
}
