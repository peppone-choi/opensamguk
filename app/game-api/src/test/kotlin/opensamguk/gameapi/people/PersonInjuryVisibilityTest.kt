package opensamguk.gameapi.people

import opensamguk.gameapi.people.injury.PersonInjuryVisibility
import opensamguk.gameapi.read.GeneralReadEntity
import opensamguk.gameapi.read.GeneralRetainerReadEntity
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PersonInjuryVisibilityTest {
    private val actor = GeneralReadEntity(id = 1, worldId = 7, userId = "41", injury = 20)
    private val child = GeneralReadEntity(id = 2, worldId = 7, injury = 30)
    private val grandchild = GeneralReadEntity(id = 3, worldId = 7, injury = 40)
    private val people = listOf(actor, child, grandchild)
    private val direct = GeneralRetainerReadEntity(id = 1, worldId = 7, masterGeneralId = 1, generalId = 2)
    private val nested = GeneralRetainerReadEntity(id = 2, worldId = 7, masterGeneralId = 2, generalId = 3)

    @Test fun `only owned self and a single direct card open the actual rate`() {
        val ids = PersonInjuryVisibility.audience(actor, 41, 7, people, listOf(direct, nested))
        assertEquals(setOf(1, 2), ids)
        assertEquals(20, PersonInjuryVisibility.rate(actor, ids))
        assertEquals(30, PersonInjuryVisibility.rate(child, ids))
        assertNull(PersonInjuryVisibility.rate(grandchild, ids))
    }

    @Test fun `missing source never guesses a direct card or owned body`() {
        assertEquals(setOf(1), PersonInjuryVisibility.audience(actor, 41, 7, people, null))
        assertEquals(emptySet(), PersonInjuryVisibility.audience(null, 41, 7, people, listOf(direct)))
        assertEquals(emptySet(), PersonInjuryVisibility.audience(actor, 99, 7, people, listOf(direct)))
        assertEquals(emptySet(), PersonInjuryVisibility.audience(actor, 41, null, people, listOf(direct)))
    }

    @Test fun `ambiguous direct cards remain private`() {
        val duplicate = GeneralRetainerReadEntity(id = 3, worldId = 7, masterGeneralId = 1, generalId = 2)
        assertEquals(setOf(1), PersonInjuryVisibility.audience(actor, 41, 7, people, listOf(direct, duplicate)))
    }

    @Test fun `cross world actor target or relationship does not disclose private injury`() {
        assertEquals(emptySet(), PersonInjuryVisibility.audience(actor, 41, 8, people, listOf(direct)))
        assertEquals(emptySet(), PersonInjuryVisibility.audience(actor, 41, 7,
            people + GeneralReadEntity(id = 4, worldId = 8), listOf(direct)))
        assertEquals(emptySet(), PersonInjuryVisibility.audience(actor, 41, 7, people,
            listOf(GeneralRetainerReadEntity(id = 3, worldId = 8, masterGeneralId = 1, generalId = 2))))
    }

    @Test fun `a true healthy rate is zero while hidden and invalid rates are null`() {
        actor.injury = 0
        assertEquals(0, PersonInjuryVisibility.rate(actor, setOf(1)))
        assertNull(PersonInjuryVisibility.rate(actor, emptySet()))
        for (invalid in listOf(-1, 101)) {
            actor.injury = invalid
            assertNull(PersonInjuryVisibility.rate(actor, setOf(1)))
        }
    }
}
