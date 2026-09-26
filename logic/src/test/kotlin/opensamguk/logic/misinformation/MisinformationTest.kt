package opensamguk.logic.misinformation

import opensamguk.common.world.WorldId
import opensamguk.logic.input.Phase
import opensamguk.logic.input.DeploymentProjection
import opensamguk.logic.input.RuleProfile
import opensamguk.logic.vision.CorpsVisibility
import opensamguk.logic.vision.ScoutReport
import opensamguk.logic.vision.ScoutReports
import opensamguk.logic.vision.ScoutedCorps
import opensamguk.logic.vision.Vision
import opensamguk.logic.vision.VisionEntry
import opensamguk.logic.vision.VisionRules
import opensamguk.logic.vision.VisionTier
import opensamguk.logic.vision.VisionView
import opensamguk.logic.vision.VisionViewer
import opensamguk.logic.world.HanCommandery
import opensamguk.logic.world.HanCommanderyIndex
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MisinformationTest {
    private val payload = File("../data/curated/han/misinformation-values.json").readText()
    private val rules = MisinformationCatalog.parse(payload)
    private val created = Phase(189, 3, 1)
    private val fake = ScoutedCorps("feedfacefeedface", 31, 32, 2, "province-1", "B2")
    private val planted = FalseSighting("false-1", 7, 9, "commandery-1", fake, created,
        created.plus(rules.durationTurns))

    @Test
    fun `confirmed decision ledger fails closed on changed status`() {
        assertEquals(6, rules.durationTurns)
        assertEquals(100, rules.detectionPermille)
        assertFailsWith<IllegalArgumentException> {
            MisinformationCatalog.parse(payload.replaceFirst("\"CONFIRMED\"", "\"DRAFT\""))
        }
    }

    @Test
    fun `false sighting lives in recipient snapshot and server provenance is never projected`() {
        val report = ScoutReport("commandery-1", created, emptyList(), emptyList())
        val victim = Misinformation.victimReport(report, 9, created, listOf(planted))
        assertEquals(listOf(fake), victim.corps)
        assertEquals(emptyList(), Misinformation.victimReport(report, 8, created, listOf(planted)).corps)
        val victimBytes = victim.toMetaValue().toString()
        assertFalse(victimBytes.contains("casterGeneralId"))
        assertFalse(victimBytes.contains("false-1"))
        assertFalse(victimBytes.contains("falseSighting"))
        assertEquals(emptyList(), Misinformation.rescout(listOf(planted), 9, "commandery-1"))
        assertEquals(listOf(planted), Misinformation.rescout(listOf(planted), 8, "commandery-1"))
    }

    @Test
    fun `phantom is a view-only corps in FULL and INTEL and is absent from FOG`() {
        fun view(tier: VisionTier): VisionView = VisionView(created,
            listOf(VisionEntry(0, tier, if (tier == VisionTier.INTEL) created else null,
                if (tier == VisionTier.INTEL) 0 else null)), emptyList(), null)
        val map = mapOf("commandery-1" to 0)
        val provinceMap = mapOf("province-1" to 0)
        fun project(victim: Int, tier: VisionTier, records: List<FalseSighting> = listOf(planted)) =
            Misinformation.victimPhantoms(victim, view(tier), map, provinceMap, emptySet(), records, VisionRules.CANON)
        val full = project(9, VisionTier.FULL)
        assertEquals(1, full.size)
        assertEquals(VisionTier.FULL, full.single().visibility)
        assertEquals(null, full.single().orderId)
        assertEquals(null, full.single().troops)
        assertEquals(VisionTier.INTEL, project(9, VisionTier.INTEL).single().visibility)
        assertEquals(emptyList(), project(9, VisionTier.FOG))
        assertEquals(emptyList(), project(8, VisionTier.FULL))
        assertFailsWith<IllegalArgumentException> {
            Misinformation.victimPhantoms(9, view(VisionTier.FULL), map, provinceMap, setOf(fake.corpsKey),
                listOf(planted), VisionRules.CANON)
        }
        assertFailsWith<IllegalArgumentException> {
            project(9, VisionTier.FULL, listOf(planted, planted.copy(id = "false-2")))
        }
        // Only CorpsSighting is produced. A phantom has no deployment or order identity for encounter/supply.
        assertTrue(full.single().toString().contains("feedfacefeedface"))
        assertFalse(full.single().toString().contains("false-1"))
        assertEquals(emptyList(), project(9, VisionTier.INTEL,
            listOf(planted.copy(falseCorps = fake.copy(troopsBand = "INVALID")))))
    }

    @Test
    fun `intel phantom and snapshot sighting share the commandery scouting stamp`() {
        val now = created.plus(2)
        val laterPlanted = planted.copy(createdAt = created.plus(1), expiresAt = created.plus(1 + rules.durationTurns))
        val real = ScoutedCorps("aaaaaaaaaaaaaaaa", 41, 42, 3, "province-1", "B1")
        val report = Misinformation.victimReport(
            ScoutReport("commandery-1", created, emptyList(), listOf(real)), 9, now, listOf(laterPlanted))
        val index = HanCommanderyIndex("a".repeat(64),
            listOf(HanCommandery(0, "commandery-1", "군1", "郡1")), mapOf("province-1" to 0), emptySet())
        val viewer = VisionViewer(9, 1, null, emptyMap(), emptyMap(), emptySet(), emptyList(), emptyList(),
            ScoutReports(index.tilesContentHash, listOf(report)))
        val view = Vision.project(viewer, index, VisionRules.CANON, now)
        val snapshotSightings = CorpsVisibility.project(viewer, view, index,
            DeploymentProjection(RuleProfile.HWIHA, emptyList(), emptyList(), emptyList(), emptyList()), VisionRules.CANON)
        assertEquals(2, snapshotSightings.size)
        assertEquals(setOf(created), snapshotSightings.map { it.seenAt }.toSet())
        assertEquals(setOf(2), snapshotSightings.map { it.ageTurns }.toSet())
        val direct = Misinformation.victimPhantoms(9, view, mapOf("commandery-1" to 0),
            mapOf("province-1" to 0), setOf(real.corpsKey), listOf(laterPlanted), VisionRules.CANON).single()
        assertEquals(snapshotSightings.single { it.corpsKey == fake.corpsKey }, direct)
    }

    @Test
    fun `detection is deterministic and expiry removes the false sighting`() {
        val impossible = rules.copy(detectionPermille = 0)
        val repeated = Misinformation.advance("hidden", WorldId(1), created, impossible, listOf(planted))
        assertEquals(repeated, Misinformation.advance("hidden", WorldId(1), created, impossible, listOf(planted)))
        assertEquals(listOf(planted), repeated.active)
        val pending = Misinformation.advance("hidden", WorldId(1), created, impossible,
            listOf(planted.copy(createdAt = created.plus(1), expiresAt = created.plus(1 + rules.durationTurns))))
        assertEquals(1, pending.active.size)
        assertEquals(emptyList(), pending.detectedIds)
        val certain = Misinformation.advance("hidden", WorldId(1), created, rules.copy(detectionPermille = 1000), listOf(planted))
        assertEquals(emptyList(), certain.active)
        assertEquals(listOf(planted.id), certain.detectedIds)
        assertEquals(emptyList(), Misinformation.advance("hidden", WorldId(1), planted.expiresAt,
            impossible, listOf(planted)).active)
        assertFailsWith<IllegalArgumentException> {
            Misinformation.advance("hidden", WorldId(1), created, rules,
                listOf(planted.copy(expiresAt = created.plus(1))))
        }
    }
}
