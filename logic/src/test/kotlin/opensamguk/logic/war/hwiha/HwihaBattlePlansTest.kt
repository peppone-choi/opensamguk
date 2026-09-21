package opensamguk.logic.war.hwiha

import kotlin.test.*
import opensamguk.logic.input.*
import opensamguk.logic.world.StrategicNodeRef.LandProvince

class HwihaBattlePlansTest {
    private val encounter=HwihaCorpsEncounter(HwihaEncounterParticipant("a",1,1,1,listOf(10)),
        listOf(HwihaEncounterParticipant("b",2,2,2,listOf(20))),LandProvince("B"),LandProvince("A"),
        HwihaPhase(200,1,1),"qa","a".repeat(64))
    private fun defaults()=HwihaBattlePlans.defaultFor(encounter)
    private fun read(raw:Any?)=HwihaBattlePlans.read(mapOf(HwihaBattlePlans.META_KEY to raw),encounter)
    @Test fun `default plans seal explicit attacker defender actions and three retreat thresholds`() {
        val p=defaults()
        assertEquals(listOf(BattlePlanAction.ADVANCE,BattlePlanAction.HOLD),p.plans.map { it.initialAction })
        assertEquals(listOf(50,20,24),p.plans.first().commands.map { it.threshold })
        assertTrue(p.plans.flatMap { it.commands }.all { it.action==BattlePlanAction.RETREAT })
        assertEquals(p.toMetaValue(),assertNotNull(read(p.toMetaValue())).toMetaValue())
        assertNull(HwihaBattlePlans.read(emptyMap(),encounter))
        assertFailsWith<IllegalArgumentException> { read(null) }
    }
    @Test fun `canonical immutable plans ignore construction order and copy caller collections`() {
        val commands=defaults().plans.first().commands.reversed().toMutableList()
        val p=CommanderBattlePlan(1,BattlePlanAction.ADVANCE,commands);commands.clear()
        val plans=mutableListOf(defaults().plans[1],p)
        val snapshot=HwihaBattlePlans(encounter.encounterId,plans);plans.clear()
        assertEquals(defaults().snapshotId,snapshot.snapshotId)
        assertFailsWith<UnsupportedOperationException> { (snapshot.plans as MutableList<*>).clear() }
        assertFailsWith<UnsupportedOperationException> { (p.commands as MutableList<*>).clear() }
        assertNotEquals(defaults().snapshotId,HwihaBattlePlans(encounter.encounterId,listOf(
            CommanderBattlePlan(1,BattlePlanAction.HOLD,p.commands),defaults().plans[1])).snapshotId)
    }
    @Test fun `ranges unique slots initial action and exact participant set are enforced`() {
        val c=defaults().plans.first().commands.first()
        for(bad in listOf({c.copy(slot=-1)},{c.copy(slot=3)},{c.copy(threshold=0)},{c.copy(threshold=101)},
            {c.copy(condition=BattlePlanCondition.ROUND_AT_LEAST,threshold=25)})) assertFailsWith<IllegalArgumentException> { bad() }
        assertFailsWith<IllegalArgumentException> { CommanderBattlePlan(1,BattlePlanAction.RETREAT,emptyList()) }
        assertFailsWith<IllegalArgumentException> { CommanderBattlePlan(0,BattlePlanAction.HOLD,emptyList()) }
        assertFailsWith<IllegalArgumentException> { CommanderBattlePlan(1,BattlePlanAction.HOLD,listOf(c,c)) }
        assertFailsWith<IllegalArgumentException> { HwihaBattlePlans(encounter.encounterId,listOf(defaults().plans[0],defaults().plans[0])) }
        val incomplete=HwihaBattlePlans(encounter.encounterId,listOf(defaults().plans[0]))
        assertFailsWith<IllegalArgumentException> { read(incomplete.toMetaValue()) }
        assertFailsWith<IllegalArgumentException> { defaults().requireBinding(encounter.copy(phase=HwihaPhase(200,1,2))) }
    }
    @Test fun `strict metadata rejects extra keys wrong numeric types changed hash and noncanonical order`() {
        val raw=defaults().toMetaValue()
        for(bad in listOf(raw+("extra" to 1),raw+("version" to 1L),raw+("snapshotId" to "bad"),
            raw+("plans" to defaults().plans.reversed().map { it.toMetaValue() })))
            assertFailsWith<IllegalArgumentException> { read(bad) }
        val first=defaults().plans.first().toMetaValue()
        fun altered(plan:Map<String,Any>)=raw+("plans" to listOf(plan,defaults().plans[1].toMetaValue()))
        for(bad in listOf(first+("initialAction" to "UNKNOWN"),first+("commanderGeneralId" to 1.0),
            first+("commands" to defaults().plans[0].commands.reversed().map { it.toMetaValue() }),first+("extra" to 0)))
            assertFailsWith<IllegalArgumentException> { read(altered(bad)) }
        for(value in listOf<Any>(50L,"50",50.0,true)) {
            val commands=defaults().plans[0].commands.map { it.toMetaValue() }.toMutableList()
            commands[0]=commands[0]+("threshold" to value)
            assertFailsWith<IllegalArgumentException> { read(altered(first+("commands" to commands))) }
        }
    }
}
