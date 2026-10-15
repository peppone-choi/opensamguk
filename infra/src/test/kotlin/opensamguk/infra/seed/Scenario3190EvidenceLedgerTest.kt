package opensamguk.infra.seed

import opensamguk.infra.persistence.MetaJson
import opensamguk.infra.seed.ScenarioPersonPolicies.HierarchySource
import opensamguk.infra.seed.ScenarioRetainerEvidence.Classification
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class Scenario3190EvidenceLedgerTest {
    private val repo = Path.of("..")
    private val root = MetaJson.decode(Files.readString(repo.resolve(
        "infra/src/main/resources/scenario/scenario_3190.json")))
    private val baseline = ScenarioJson.loadScenario(MetaJson.encode(root))
    private val ledger = MetaJson.decode(Files.readString(repo.resolve(
        "data/scenario-hierarchy/scenario_3190.evidence.json")))

    private fun decode(input: Map<String, Any?> = ledger) =
        ScenarioRetainerEvidence.decode(input, baseline.retainers, baseline.startYear)

    @Test fun `ledger covers all 228 declared links exactly once without orphans`() {
        assertEquals(setOf("retainerEvidence"), ledger.keys)
        assertEquals(190, baseline.startYear)
        val evidence = decode()
        assertEquals(228, baseline.retainers.size)
        assertEquals(228, evidence.size)
        val links = evidence.map { ScenarioRetainer(it.general, it.master) }
        assertEquals(links.size, links.toSet().size)
        assertEquals(baseline.retainers.toSet(), links.toSet())
        for (row in evidence) {
            assertTrue(row.asOfYear <= baseline.startYear)
            if (row.classification == Classification.HISTORICAL_EXPLICIT) {
                assertTrue(row.sources.isNotEmpty())
                assertTrue(row.sources.all { it.book.isNotBlank() && it.chapter.isNotBlank() && it.quote.isNotBlank() })
            }
        }
    }

    @Test fun `unresearched ledger stays game only and cannot promote inventory or change runtime seed`() {
        val evidence = decode()
        assertTrue(evidence.all { it.classification == Classification.GAME_ONLY && it.sources.isEmpty() })
        val enriched = root + ledger
        val parsed = ScenarioJson.loadScenario(MetaJson.encode(enriched))
        assertEquals(evidence, parsed.retainerEvidence)
        assertEquals(baseline, parsed.copy(retainerEvidence = baseline.retainerEvidence))
        ScenarioImporter(parsed, emptyList()).validateSeedContract()
        val originalInventory = ScenarioPersonPolicies.hierarchyInventory(3190, root, baseline)
        val inventory = ScenarioPersonPolicies.requireHierarchyClaim(3190, enriched, parsed, HierarchySource.GAME_DECLARATION)
        assertEquals(originalInventory, inventory)
        assertFalse(ScenarioRetainerEvidence.isCompleteHistorical(baseline.retainers, evidence))
        assertFailsWith<IllegalArgumentException> {
            ScenarioPersonPolicies.requireHierarchyClaim(3190, enriched, parsed, HierarchySource.HISTORICAL_SOURCE)
        }
    }

    @Test fun `ledger parser rejects duplicate orphan missing quote and post start evidence`() {
        val rows = (ledger.getValue("retainerEvidence") as List<*>).map { raw ->
            val row = raw as Map<*, *>
            row.entries.associate { (key, value) -> key as String to value }
        }
        val first = rows.first()
        // Invalid schema probes only: these are not quotations or historical claims.
        val invalid = listOf(
            rows + first,
            rows.drop(1) + (first + ("general" to "__absent_ledger_probe__")),
            rows.drop(1) + (first + ("class" to "HISTORICAL_EXPLICIT")),
            rows.drop(1) + (first + mapOf("class" to "HISTORICAL_EXPLICIT", "sources" to
                listOf(mapOf("book" to "schema probe", "chapter" to "schema probe", "quote" to " ")))),
            rows.drop(1) + (first + ("asOfYear" to baseline.startYear + 1)),
        )
        for (variant in invalid) assertFailsWith<IllegalArgumentException> {
            decode(mapOf("retainerEvidence" to variant))
        }
        // The H1 decoder permits partial attribution; the ledger coverage gate must catch omissions.
        val partial = decode(mapOf("retainerEvidence" to rows.dropLast(1)))
        assertNotEquals(baseline.retainers.toSet(), partial.map { ScenarioRetainer(it.general, it.master) }.toSet())
    }
}
