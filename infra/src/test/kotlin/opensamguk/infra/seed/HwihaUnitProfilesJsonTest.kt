package opensamguk.infra.seed

import kotlin.test.*
import java.security.MessageDigest
import opensamguk.common.constants.GameUnitConst
import opensamguk.logic.war.hwiha.*

class HwihaUnitProfilesJsonTest {
    private val raw = """{"version":1,"profiles":[{"crewTypeId":1100,"movementSteps":1,"attackRange":1,"attackPower":100,"defencePower":120,"initiative":20}],"unsupportedCrewTypeIds":[1000]}"""
    @Test fun `packaged explicit identities cover current catalog with delegated values only`() {
        val profiles = HwihaUnitProfilesJson.loadDefault()
        val catalog = GameUnitConst.all()
        assertEquals(catalog.keys,profiles.profiles.map { it.crewTypeId }.toSet()+profiles.unsupportedCrewTypeIds)
        for ((id, unit) in catalog) {
            val expected = when(unit.armType) {
                GameUnitConst.T_FOOTMAN -> HwihaUnitProfile(id,1,1,100,120,20)
                GameUnitConst.T_ARCHER -> HwihaUnitProfile(id,1,3,80,80,10)
                GameUnitConst.T_CAVALRY -> HwihaUnitProfile(id,2,1,110,100,30)
                else -> null
            }
            assertEquals(expected,profiles.find(id))
            assertEquals(expected == null,id in profiles.unsupportedCrewTypeIds)
        }
        assertNull(profiles.find(1)); assertNull(profiles.find(999999))
        assertFalse(999999 in profiles.unsupportedCrewTypeIds)
        val bytes = javaClass.classLoader.getResourceAsStream("battle/hwiha-unit-profiles-v1.json")!!.use { it.readBytes() }
        assertEquals(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },profiles.contentHash)
    }
    @Test fun `strict schema rejects duplicate keys trailing values coercion overflow and identity overlap`() {
        val bad = listOf(raw+" {}",raw.replace("\"version\":1","\"version\":1,\"version\":1"),
            raw.replace("\"version\":1","\"version\":2"),raw.replace("\"version\":1","\"version\":1.0"),
            raw.replace("\"version\":1","\"version\":\"1\""),raw.replace("\"version\":1","\"extra\":0,\"version\":1"),
            raw.replace("\"initiative\":20","\"initiative\":true"),raw.replace("\"initiative\":20","\"initiative\":2147483648"),
            raw.replace("\"initiative\":20","\"initiative\":null"),raw.replace("\"movementSteps\":1","\"movementSteps\":0"),
            raw.replace("[1000]","[1100]"),raw.replace("[1000]","[1000,1000]"),raw.replace("[1000]","[-1]"),
            raw.replace("\"initiative\":20","\"initiative\":20,\"unknown\":1"),
            raw.replace("\"initiative\":20","\"initiative\":20,\"initiative\":30"))
        bad.forEachIndexed { i,s -> assertFailsWith<IllegalArgumentException>("case $i") { HwihaUnitProfilesJson.load(s.toByteArray()) } }
        assertNotEquals(HwihaUnitProfilesJson.load(raw.toByteArray()).contentHash,HwihaUnitProfilesJson.load((raw+" ").toByteArray()).contentHash)
    }
    @Test fun `model owns immutable sorted collections and rejects invalid construction`() {
        val row = HwihaUnitProfile(1100,1,1,100,120,20)
        val source = mutableListOf(row.copy(crewTypeId=1200),row)
        val unsupported = mutableSetOf(1000)
        val p = HwihaUnitProfiles(1,"a".repeat(64),source,unsupported)
        source.clear();unsupported.clear()
        assertEquals(listOf(1100,1200),p.profiles.map { it.crewTypeId })
        assertEquals(setOf(1000),p.unsupportedCrewTypeIds)
        assertFailsWith<UnsupportedOperationException> { (p.profiles as MutableList<*>).clear() }
        assertFailsWith<UnsupportedOperationException> { (p.unsupportedCrewTypeIds as MutableSet<*>).clear() }
        assertFailsWith<IllegalArgumentException> { HwihaUnitProfiles(1,"a".repeat(64),listOf(row,row),emptySet()) }
        assertFailsWith<IllegalArgumentException> { HwihaUnitProfiles(1,"bad",listOf(row),emptySet()) }
        assertFailsWith<IllegalArgumentException> { row.copy(attackRange=0) }
        assertFailsWith<IllegalArgumentException> { row.copy(attackPower=0) }
        assertFailsWith<IllegalArgumentException> { row.copy(defencePower=-1) }
        assertFailsWith<IllegalArgumentException> { row.copy(initiative=-1) }
    }
}
