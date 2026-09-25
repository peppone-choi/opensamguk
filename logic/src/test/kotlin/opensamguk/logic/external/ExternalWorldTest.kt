package opensamguk.logic.external

import opensamguk.common.world.WorldId
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ExternalWorldTest {
    private val actorsPayload = File("../data/curated/han/external-actors.json").readText()
    private val valuesPayload = File("../data/curated/han/world-event-values.json").readText()

    @Test
    fun `historical candidates carry citations but cannot activate without a dated period`() {
        val actors = ExternalCatalog.parseActors(actorsPayload)
        assertEquals(8, actors.size)
        assertTrue(actors.all { it.activation == "CANDIDATE" && it.subjectPeriod == null })
        assertFailsWith<IllegalArgumentException> {
            ExternalCatalog.parseActors(actorsPayload.replaceFirst("\"activation\": \"CANDIDATE\"", "\"activation\": \"ACTIVE\""))
        }
        assertFailsWith<IllegalArgumentException> {
            ExternalCatalog.parseActors(actorsPayload.replaceFirst("\"quote\": \"南單于新附\"", "\"quote\": \"\""))
        }
    }

    @Test
    fun `external actors never project as nations and orphan contacts fail`() {
        val id = ExternalActorId("external:wuhuan")
        assertFailsWith<IllegalArgumentException> { ExternalWorld.assertOutsideNationTable(listOf(id.value)) }
        assertFailsWith<IllegalArgumentException> {
            ExternalWorld.decide("seed", WorldId(1), 189, 3, 1, emptyList(),
                listOf(ExternalContact(1, id, ExternalRelation.HOSTILE, 5)), ExternalCatalog.parseEventRules(valuesPayload))
        assertFailsWith<IllegalArgumentException> {
            ExternalWorld.decide("seed", WorldId(1), 189, 3, 1, ExternalCatalog.parseActors(actorsPayload),
                listOf(ExternalContact(1, id, ExternalRelation.HOSTILE, 5)), ExternalCatalog.parseEventRules(valuesPayload))
        }
        }
    }

    @Test
    fun `world events replay identically independent of contact iteration order`() {
        val activeActors = ExternalCatalog.parseActors(actorsPayload).map {
            it.copy(activation = "ACTIVE", subjectPeriod = 189..189)
        }
        val contacts = listOf(
            ExternalContact(2, ExternalActorId("external:wuhuan"), ExternalRelation.HOSTILE, 5),
            ExternalContact(1, ExternalActorId("external:buyeo"), ExternalRelation.TRADE, 6),
        )
        val rules = ExternalCatalog.parseEventRules(valuesPayload)
        val first = ExternalWorld.decide("seed", WorldId(1), 189, 3, 1, activeActors, contacts, rules)
        val second = ExternalWorld.decide("seed", WorldId(1), 189, 3, 1, activeActors.reversed(), contacts.reversed(), rules.reversed())
        assertEquals(first, second)
        val certain = rules.map { it.copy(chancePermille = 1000) }
        assertEquals(2, ExternalWorld.decide("seed", WorldId(1), 189, 3, 1, activeActors, contacts, certain).size)
        assertEquals(0, ExternalWorld.decide("seed", WorldId(1), 189, 3, 1, activeActors, contacts,
            rules.map { it.copy(chancePermille = 0) }).size)
    }
}
