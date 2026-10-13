package opensamguk.infra.seed

import kotlin.test.*

class ScenarioRetainerEvidenceTest {
    private val links = listOf(ScenarioRetainer("child", "parent"), ScenarioRetainer("parent", "ruler"))
    // Schema fixtures only, not historical assertions about any real person.
    private fun row(link: ScenarioRetainer = links.first()): Map<String, Any?> = mapOf(
        "general" to link.general, "master" to link.master, "class" to "HISTORICAL_EXPLICIT",
        "sources" to listOf(mapOf("book" to "fixture book", "chapter" to "fixture chapter", "quote" to "fixture quotation")),
        "asOfYear" to 190,
    )
    private fun decode(rows: Any?) = ScenarioRetainerEvidence.decode(mapOf("retainerEvidence" to rows), links, 190)

    @Test fun `omission remains compatible but explicit null is rejected`() {
        assertEquals(emptyList(), ScenarioRetainerEvidence.decode(emptyMap(), links, 190))
        assertFailsWith<IllegalArgumentException> { decode(null) }
        assertFalse(ScenarioRetainerEvidence.isCompleteHistorical(links, decode(emptyList<Any>())))
    }

    @Test fun `historical coverage requires every link and no mixed classification`() {
        val complete = decode(links.map(::row))
        assertTrue(ScenarioRetainerEvidence.isCompleteHistorical(links, complete))
        assertFalse(ScenarioRetainerEvidence.isCompleteHistorical(links, decode(listOf(row()))))
        for (classification in listOf("PROVISIONAL", "GAME_ONLY")) {
            val mixed = decode(listOf(row(), row(links.last()) + ("class" to classification)))
            assertFalse(ScenarioRetainerEvidence.isCompleteHistorical(links, mixed))
        }
        assertFalse(ScenarioRetainerEvidence.isCompleteHistorical(emptyList(), emptyList()))
    }

    @Test fun `malformed duplicate orphan future and blank original sources fail closed`() {
        val badRows = listOf(
            listOf(row(), row()),
            listOf(row(ScenarioRetainer("absent", "parent"))),
            listOf(row() + ("asOfYear" to 191)),
            listOf(row() + ("asOfYear" to 190.5)),
            listOf(row() + ("asOfYear" to "190")),
            listOf(row() - "asOfYear"),
            listOf(row() + ("extra" to true)),
            listOf(row() + ("class" to "HISTORICAL_SOURCE")),
            listOf(row() + ("sources" to emptyList<Any>())),
            listOf(row() + ("sources" to null)),
        ) + listOf("book", "chapter", "quote").map { key ->
            listOf(row() + ("sources" to listOf(mapOf("book" to "b", "chapter" to "c", "quote" to "q") + (key to " "))))
        }
        for (rows in badRows) assertFailsWith<IllegalArgumentException> { decode(rows) }
    }

    @Test fun `game only attribution permits no original source without inventing one`() {
        val evidence = decode(listOf(row() + mapOf("class" to "GAME_ONLY", "sources" to emptyList<Any>())))
        assertEquals(ScenarioRetainerEvidence.Classification.GAME_ONLY, evidence.single().classification)
        assertTrue(evidence.single().sources.isEmpty())
    }
}
