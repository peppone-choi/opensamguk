package opensamguk.logic.content

import opensamguk.logic.input.Aptitude
import kotlin.test.*

class PersonCardTest {
    private fun card(id: Int, contribution: Set<String> = emptySet()) = PersonCard(
        CardHeader("person:$id", "인물 $id", CardKind.PERSON, CardAvailability.UNIQUE,
            listOf(CardProvenance.GameTerm), 5, emptySet(), emptySet()),
        PersonCardIdentity(id, 170, 230, "신중", setOf("정찰"), "portraits/$id.png"),
        Aptitude.Stats(50, 50, 50, 50, 50),
        setOf(PersonBond(PersonBondKind.NATIVE_COUNTY, "county:1", setOf("citation:1"))),
        contribution,
        PersonCardSeason("province:1", "현령", 60, 10, 0, 0, 100, emptyList()),
    )

    @Test fun `person fields derive aptitude and source validated renown cost`() {
        val person = card(1)
        assertEquals(5, person.header.renownCost)
        assertEquals(50, person.aptitude.command)
        assertEquals(PersonBondKind.NATIVE_COUNTY, person.bonds.single().kind)
        assertEquals(5, PersonBondKind.entries.size)
        assertEquals(2, UnitBondKind.entries.size)
        assertFailsWith<IllegalArgumentException> { card(1).copy(header = card(1).header.copy(renownCost = 4)) }
        val bondState = PersonBondState(person.bonds)
        assertEquals(bondState, PersonBondState.read(mapOf(PersonBondState.META_KEY to bondState.toMetaValue())))
        assertFailsWith<IllegalArgumentException> { PersonBondState.read(mapOf(PersonBondState.META_KEY to
            mapOf("version" to 1, "bonds" to listOf(mapOf("kind" to "UNKNOWN", "targetId" to "county:1", "evidenceIds" to emptyList<String>()))))) }
    }

    @Test fun `only direct NPC cards contribute to their holder deck`() {
        val holdings = listOf(
            DirectPersonHolding(10, card(1, setOf("stratagem-insight")), true),
            DirectPersonHolding(1, card(2, setOf("stratagem-fortify")), true),
            DirectPersonHolding(10, card(3, setOf("stratagem-fortify")), false),
        )
        val catalogue = CommonStratagemCards.headers.values
        assertEquals(listOf("stratagem-insight"), PersonStratagemDeck.contributedIds(10, holdings, catalogue))
        assertEquals(listOf("stratagem-fortify"), PersonStratagemDeck.contributedIds(1, holdings, catalogue))
        assertEquals(listOf("stratagem-insight", "stratagem-insight"), PersonStratagemDeck.contributedIds(10,
            holdings + DirectPersonHolding(10, card(4, setOf("stratagem-insight")), true), catalogue))
        assertFailsWith<IllegalArgumentException> {
            PersonStratagemDeck.contributedIds(10, listOf(DirectPersonHolding(10, card(4, setOf("missing")), true)), catalogue)
        }
        val state = PersonContributionState(setOf("stratagem-insight"))
        assertEquals(state, PersonContributionState.read(mapOf(PersonContributionState.META_KEY to state.toMetaValue())))
    }
}
