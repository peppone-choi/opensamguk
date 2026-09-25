package opensamguk.logic.vision

import opensamguk.logic.input.*


import opensamguk.logic.world.HanCommandery
import opensamguk.logic.world.HanCommanderyIndex
import opensamguk.logic.world.StrategicNodeRef
import kotlin.test.*

/** Vision projection (#785) and the corps leak boundary (#343/#465) on a synthetic five-commandery line. */
class VisionTest {
    private val hash = "a".repeat(64)
    // 0 — 1 — 2 — 3 — 4 (line); province pN belongs to commandery N, p0b also to 0.
    private val index = HanCommanderyIndex(hash,
        (0..4).map { HanCommandery(it, "PARENT-$it", "군$it", "郡$it") },
        (0..4).associate { "p$it" to it } + ("p0b" to 0),
        setOf(0 to 1, 1 to 2, 2 to 3, 3 to 4))
    private val rules = VisionRules.CANON
    private val now = Phase(190, 3, 2)

    private fun land(id: String) = StrategicNodeRef.LandProvince(id)
    private fun viewer(
        actorNode: StrategicNodeRef? = land("p0"),
        nationId: Int = 1,
        territory: Set<String> = emptySet(),
        corps: Map<Int, StrategicNodeRef?> = emptyMap(),
        retinue: Map<Int, StrategicNodeRef?> = emptyMap(),
        posts: List<ScoutPost> = emptyList(),
        towers: List<Pair<Int, String>> = emptyList(),
        reports: ScoutReports? = null,
    ) = VisionViewer(1, nationId, actorNode, corps, retinue, territory, posts, towers, reports)

    private fun report(commandery: Int, seenAt: Phase, vararg corps: ScoutedCorps) =
        ScoutReport("PARENT-$commandery", seenAt, listOf(ScoutedCity(100 + commandery, 2, true)),
            corps.sortedBy { it.corpsKey })

    private fun tiers(view: VisionView) = view.entries.map { it.tier }

    @Test fun `self sees only its own commandery by default and everything else is fog`() {
        val view = Vision.project(viewer(), index, rules, now)
        assertEquals(listOf(VisionTier.FULL, VisionTier.FOG, VisionTier.FOG, VisionTier.FOG, VisionTier.FOG), tiers(view))
        assertEquals(listOf(VisionSource(VisionSourceKind.SELF, 0, 0, "p0", 1)), view.sources)
    }

    @Test fun `scout posts and watchtowers reach one neighbour step and corps and retinue see where they stand`() {
        val view = Vision.project(viewer(actorNode = null, corps = mapOf(7 to land("p4")),
            posts = listOf(ScoutPost(3, "p2")), towers = emptyList()), index, rules, now)
        assertEquals(listOf(VisionTier.FOG, VisionTier.FULL, VisionTier.FULL, VisionTier.FULL, VisionTier.FULL), tiers(view))
        val tower = Vision.project(viewer(actorNode = null, towers = listOf(55 to "p0b"),
            retinue = mapOf(9 to land("p3"))), index, rules, now)
        assertEquals(listOf(VisionTier.FULL, VisionTier.FULL, VisionTier.FOG, VisionTier.FULL, VisionTier.FOG), tiers(tower))
    }

    @Test fun `territory is one row per commandery and an unaffiliated viewer cannot claim territory`() {
        val view = Vision.project(viewer(actorNode = null, territory = setOf("p0", "p0b", "p3")), index, rules, now)
        assertEquals(listOf(VisionSourceKind.TERRITORY, VisionSourceKind.TERRITORY), view.sources.map { it.kind })
        assertEquals(listOf(0, 3), view.sources.map { it.commanderyNo })
        assertTrue(view.sources.all { it.provinceId == null })
        assertFailsWith<IllegalArgumentException> { viewer(nationId = 0, territory = setOf("p1")) }
    }

    @Test fun `scouted commandery is intel with its age and a live source overrides it`() {
        val reports = ScoutReports(hash, listOf(report(1, Phase(190, 2, 3)), report(3, now)))
        val view = Vision.project(viewer(reports = reports), index, rules, now)
        assertEquals(VisionEntry(1, VisionTier.INTEL, Phase(190, 2, 3), 2), view.entry(1))
        assertEquals(VisionEntry(3, VisionTier.INTEL, now, 0), view.entry(3))
        val covered = Vision.project(viewer(reports = reports, posts = listOf(ScoutPost(3, "p0"))), index, rules, now)
        assertEquals(VisionTier.FULL, covered.tierOf(1))
        assertEquals(VisionTier.INTEL, covered.tierOf(3))
        // Across a year boundary the age still counts 旬 (36 per year).
        assertEquals(37, Vision.ageTurns(Phase(189, 3, 1), Phase(190, 3, 2)))
    }

    @Test fun `notebooks from other tiles or from the future never become intel`() {
        val other = ScoutReports("b".repeat(64), listOf(report(2, now)))
        assertEquals(VisionTier.FOG, Vision.project(viewer(reports = other), index, rules, now).tierOf(2))
        val future = ScoutReports(hash, listOf(report(2, Phase(190, 3, 3))))
        assertEquals(VisionTier.FOG, Vision.project(viewer(reports = future), index, rules, now).tierOf(2))
    }

    // ── corps projection ───────────────────────────────────────────────────

    private fun person(id: Int, nation: Int, node: StrategicNodeRef?) = DeploymentPerson(id, nation, false, node, false)
    private fun corps(order: String, owner: Int, nation: Int, vararg units: Int) =
        DeployedCorps(order, owner, owner, null, nation, units.toList().sorted(), Phase(190, 1, 1))

    /** Viewer 1 (nation 1) owns order-own at p0; 2 (nation 2) at p1; 3 (nation 3) at p2; 4 (nation 3) at p4. */
    private fun projection(): DeploymentProjection = DeploymentProjection(RuleProfile.HWIHA,
        listOf(person(1, 1, land("p0")), person(2, 2, land("p1")), person(3, 3, land("p2")), person(4, 3, land("p4"))),
        listOf(DeploymentUnit(11, 1, 1234, null), DeploymentUnit(21, 2, 6000, null), DeploymentUnit(22, 2, 300, null),
            DeploymentUnit(31, 3, 45000, null), DeploymentUnit(41, 4, 800, null)),
        emptyList(),
        listOf(corps("order-own", 1, 1, 11), corps("order-neighbour", 2, 2, 21, 22),
            corps("order-secret-fog", 3, 3, 31), corps("order-far", 4, 3, 41)))

    @Test fun `opaque corps key uses the neutral stored domain`() {
        assertEquals("b9e894c4ea86e3f7", ScoutCapture.corpsKey("order-42"))
    }

    @Test fun `fog corps never appear, own corps are exact and others are banded`() {
        val v = viewer(posts = listOf(ScoutPost(5, "p0")))   // FULL: 0, 1
        val view = Vision.project(v, index, rules, now)
        val seen = CorpsVisibility.project(v, view, index, projection(), rules)
        assertEquals(listOf("order-own", ScoutCapture.corpsKey("order-neighbour")), seen.map { it.orderId ?: it.corpsKey })
        val own = seen.first(); val other = seen.last()
        assertEquals(1234, own.troops); assertNull(own.troopsBand); assertEquals("order-own", own.orderId)
        assertNull(other.troops); assertNull(other.orderId); assertEquals("B3", other.troopsBand)
        assertTrue(seen.none { it.ownerGeneralId == 3 || it.ownerGeneralId == 4 }, "FOG corps leaked: $seen")
    }

    @Test fun `intel shows only the scouting snapshot, not the live corps standing there now`() {
        val seenThen = ScoutedCorps(ScoutCapture.corpsKey("order-old"), 9, 9, 3, "p2", "B1")
        val reports = ScoutReports(hash, listOf(report(2, Phase(190, 1, 1), seenThen)))
        val v = viewer(reports = reports)
        val view = Vision.project(v, index, rules, now)
        assertEquals(VisionTier.INTEL, view.tierOf(2))
        val seen = CorpsVisibility.project(v, view, index, projection(), rules)
        val intel = seen.single { it.visibility == VisionTier.INTEL }
        assertEquals(seenThen.corpsKey, intel.corpsKey)
        assertEquals(Phase(190, 1, 1), intel.seenAt); assertEquals(7, intel.ageTurns); assertEquals("B1", intel.troopsBand)
        // The live 45000-strong corps in the same INTEL commandery stays hidden: a snapshot is not live sight.
        assertTrue(seen.none { it.corpsKey == ScoutCapture.corpsKey("order-secret-fog") })
    }

    @Test fun `an old snapshot of a commandery that is now in full sight yields only the live corps`() {
        val stale = ScoutedCorps(ScoutCapture.corpsKey("order-gone"), 8, 8, 2, "p1", "B5")
        val reports = ScoutReports(hash, listOf(report(1, Phase(189, 1, 1), stale)))
        val v = viewer(reports = reports, posts = listOf(ScoutPost(5, "p0")))   // FULL: 0, 1
        val view = Vision.project(v, index, rules, now)
        assertEquals(VisionTier.FULL, view.tierOf(1))
        val seen = CorpsVisibility.project(v, view, index, projection(), rules)
        assertTrue(seen.none { it.visibility == VisionTier.INTEL || it.corpsKey == stale.corpsKey }, "$seen")
        assertEquals(listOf(true, false), seen.map { it.own })
    }

    @Test fun `a broken relationship is not reported as a standing army even in full sight`() {
        val broken = projection().let { it.copy(units = it.units.filterNot { unit -> unit.id == 21 }) }
        val v = viewer(posts = listOf(ScoutPost(5, "p0")))
        val seen = CorpsVisibility.project(v, Vision.project(v, index, rules, now), index, broken, rules)
        assertEquals(listOf(true), seen.map { it.own })
    }
}
