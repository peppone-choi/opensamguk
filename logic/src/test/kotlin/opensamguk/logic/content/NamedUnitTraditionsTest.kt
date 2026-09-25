package opensamguk.logic.content

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class NamedUnitTraditionsTest {
    private val payload = Files.readString(Path.of("..", "data", "curated", "han", "named-unit-traditions.json"))
    private val units = NamedUnitTraditions.parse(payload)

    @Test
    fun `source ledger creates ten card headers without the retired unit loader`() {
        assertEquals(10, units.size)
        val baima = units.single { it.header.id == "unit.baima-yicong" }
        assertEquals(CardKind.UNIT, baima.header.kind)
        assertEquals(CardAvailability.UNIQUE, baima.header.availability)
        assertEquals("公孫瓚", baima.requiredGeneralName)
        assertEquals(7, baima.header.renownCost)
        assertEquals("後漢書 卷073", baima.header.provenanceBadges.single())
    }

    @Test
    fun `missing required general rejects a named formation`() {
        val baima = units.single { it.header.id == "unit.baima-yicong" }
        val missing = UnitFormationContext(false, true, emptySet(), 10)
        assertEquals(UnitFormationFailure.REQUIRED_GENERAL_MISSING, NamedUnitFormation.assess(baima, missing))
        assertNull(NamedUnitFormation.assess(baima, missing.copy(requiredGeneralPresent = true)))
    }

    @Test
    fun `unique card cannot be issued twice in a server`() {
        val baima = units.single { it.header.id == "unit.baima-yicong" }
        val issued = UnitFormationContext(true, true, setOf(baima.header.id), 10)
        assertEquals(UnitFormationFailure.UNIQUE_ALREADY_ISSUED, NamedUnitFormation.assess(baima, issued))
        val danyang = units.single { it.header.id == "unit.danyang-soldiers" }
        assertNull(NamedUnitFormation.assess(danyang, issued.copy(issuedCardIds = setOf(danyang.header.id))))
    }

    @Test
    fun `renown and regional requirement are checked before formation`() {
        val danyang = units.single { it.header.id == "unit.danyang-soldiers" }
        val context = UnitFormationContext(true, false, emptySet(), 2)
        assertEquals(UnitFormationFailure.REQUIRED_REGION_MISSING, NamedUnitFormation.assess(danyang, context))
        assertEquals(UnitFormationFailure.RENOWN_SHORTFALL,
            NamedUnitFormation.assess(danyang, context.copy(requiredRegionPresent = true)))
    }

    @Test
    fun `Fuling regional units retain MAP4 commandery pin`() {
        val fuling = units.filter { it.header.id in setOf("unit.chijia-regional", "unit.liannu-regional") }
        assertEquals(2, fuling.size)
        fuling.forEach {
            assertEquals("涪陵郡", it.requiredRegionName)
            assertEquals("PARENT-0150", it.requiredMapParentRegionId)
        }
    }

    @Test
    fun `historical name missing from quotation fails closed`() {
        val broken = payload.replaceFirst("\"historicalName\": \"白馬義從\"", "\"historicalName\": \"虛構兵名\"")
        assertFailsWith<IllegalArgumentException> { NamedUnitTraditions.parse(broken) }
    }
}
