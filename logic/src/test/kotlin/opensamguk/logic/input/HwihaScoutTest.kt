package opensamguk.logic.input

import opensamguk.logic.economy.HwihaResources
import opensamguk.logic.world.HanCommandery
import opensamguk.logic.world.HanCommanderyIndex
import opensamguk.logic.world.StrategicNodeRef
import kotlin.test.*

class HwihaScoutTest {
    private val hash = "c".repeat(64)
    private val index = HanCommanderyIndex(hash,
        (0..3).map { HanCommandery(it, "PARENT-$it", "군$it", "郡$it") },
        mapOf("p0" to 0, "p1" to 1, "p2" to 2, "p3" to 3), setOf(0 to 1, 1 to 2))
    private val rules = HwihaVisionRules.CANON
    private fun land(id: String) = StrategicNodeRef.LandProvince(id)

    @Test fun `scout input is exactly one commandery id and canonicalizes`() {
        val input = assertNotNull(HwihaScoutInput.parse(5, """ {"commanderyId" : "PARENT-1"} """))
        assertEquals("""{"commanderyId":"PARENT-1"}""", HwihaScoutInput.canonicalJson(input))
        listOf(null, "", "{}", """{"commanderyId":1}""", """{"commanderyId":""}""",
            """{"commanderyId":"PARENT-1","extra":1}""", """{"commanderyId":"PARENT-1","commanderyId":"PARENT-2"}""",
            """{"commanderyId":"PARENT-1","commanderyId":"PARENT-2"}""", """["PARENT-1"]""",
        ).forEach { assertNull(HwihaScoutInput.parse(5, it), "accepted: $it") }
        assertNull(HwihaScoutInput.parse(0, """{"commanderyId":"PARENT-1"}"""))
    }

    @Test fun `only a commandery sharing a border with where the actor stands can be scouted`() {
        val eligible = assertIs<ScoutAssessment.Eligible>(HwihaScoutRules.assess(RuleProfile.HWIHA, land("p1"), "PARENT-2", index))
        assertEquals(1, eligible.origin.no); assertEquals(2, eligible.target.no)
        fun reason(node: StrategicNodeRef?, id: String, profile: RuleProfile = RuleProfile.HWIHA) =
            assertIs<ScoutAssessment.Rejected>(HwihaScoutRules.assess(profile, node, id, index)).reason
        assertEquals(ScoutFailure.NOT_ADJACENT, reason(land("p0"), "PARENT-2"))
        assertEquals(ScoutFailure.NOT_ADJACENT, reason(land("p1"), "PARENT-1"))   // own commandery is already seen
        assertEquals(ScoutFailure.NOT_ADJACENT, reason(land("p3"), "PARENT-2"))   // island: no neighbours
        assertEquals(ScoutFailure.UNKNOWN_COMMANDERY, reason(land("p1"), "PARENT-9"))
        assertEquals(ScoutFailure.POSITION_UNAVAILABLE, reason(null, "PARENT-2"))
        assertEquals(ScoutFailure.POSITION_UNAVAILABLE, reason(StrategicNodeRef.WaterZone("w1"), "PARENT-2"))
        assertEquals(ScoutFailure.WRONG_RULE_PROFILE, reason(land("p1"), "PARENT-2", RuleProfile.SAMMO))
        ScoutFailure.entries.forEach { assertTrue(HwihaScoutRules.reason(it).isNotBlank()) }
    }

    private fun projection() = DeploymentProjection(RuleProfile.HWIHA,
        listOf(DeploymentPerson(1, 1, false, land("p2"), false), DeploymentPerson(2, 2, false, land("p2"), false),
            DeploymentPerson(3, 2, false, land("p1"), false)),
        listOf(DeploymentUnit(10, 1, 999, null), DeploymentUnit(20, 2, 12000, null), DeploymentUnit(30, 3, 5000, null)),
        emptyList(),
        listOf(HwihaDeployedCorps("o1", 1, 1, null, 1, listOf(10), HwihaPhase(190, 1, 1)),
            HwihaDeployedCorps("o2", 2, 2, null, 2, listOf(20), HwihaPhase(190, 1, 1)),
            HwihaDeployedCorps("o3", 3, 3, null, 2, listOf(30), HwihaPhase(190, 1, 1))))

    @Test fun `capture records owners, warehouse presence and banded corps of the target only`() {
        val cities = listOf(ScoutCityFact(7, "p2", 2, true), ScoutCityFact(5, "p2", 0, false), ScoutCityFact(9, "p1", 1, true))
        val report = HwihaScoutCapture.capture(index.commanderies[2], index, cities, projection(), rules, HwihaPhase(190, 2, 1))
        assertEquals("PARENT-2", report.commanderyId)
        assertEquals(listOf(ScoutedCity(5, 0, false), ScoutedCity(7, 2, true)), report.cities)
        assertEquals(setOf("o1", "o2").map(HwihaScoutCapture::corpsKey).sorted(), report.corps.map { it.corpsKey })
        assertEquals(setOf("B1", "B4"), report.corps.map { it.troopsBand }.toSet())
        // The notebook never stores exact troops or raw order ids.
        val bytes = report.toMetaValue().toString()
        assertFalse("12000" in bytes || "\"o2\"" in bytes || "=o2" in bytes, bytes)
    }

    @Test fun `notebook round-trips, replaces per commandery and fails closed on any schema drift`() {
        val first = HwihaScoutCapture.capture(index.commanderies[2], index, emptyList(), projection(), rules, HwihaPhase(190, 2, 1))
        val notebook = HwihaScoutReports(hash, emptyList()).with(first)
        assertEquals(notebook, HwihaScoutReports.read(mapOf(HwihaScoutReports.META_KEY to notebook.toMetaValue())))
        val again = first.copy(seenAt = HwihaPhase(190, 3, 1), corps = emptyList())
        assertEquals(listOf(again), notebook.with(again).reports)
        assertNull(HwihaScoutReports.read(emptyMap()))
        val raw = notebook.toMetaValue()
        listOf(raw + ("version" to 2), raw + ("extra" to 1), raw - "tilesContentHash", mapOf("version" to 1)).forEach {
            assertFailsWith<IllegalArgumentException> { HwihaScoutReports.read(mapOf(HwihaScoutReports.META_KEY to it)) }
        }
        assertFailsWith<IllegalArgumentException> { HwihaScoutReports.read(mapOf(HwihaScoutReports.META_KEY to "x")) }
    }

    @Test fun `canonical vision rules load and the scout cost cannot become a fake non-zero cost`() {
        assertEquals(0, rules.radius(VisionSourceKind.SELF))
        assertEquals(1, rules.radius(VisionSourceKind.SCOUT_POST))
        assertEquals(HwihaResources(), rules.scoutCost)
        assertEquals("B1", rules.band(0).code); assertEquals("B2", rules.band(1000).code); assertEquals("B5", rules.band(Int.MAX_VALUE).code)
        val text = checkNotNull(javaClass.classLoader.getResource("hwiha/hwiha-vision-rules-v1.json")).readText()
        assertFailsWith<IllegalArgumentException> { HwihaVisionRules.parse(text.replace("\"money\": 0", "\"money\": 5")) }
        assertFailsWith<IllegalArgumentException> { HwihaVisionRules.parse(text.replace("SHARED_BORDER_4_NEIGHBOUR", "TOPOLOGY_EDGES")) }
        assertFailsWith<IllegalArgumentException> { HwihaVisionRules.parse(text.replace("\"SELF\": 0,", "")) }
        assertFailsWith<IllegalArgumentException> { HwihaVisionRules.parse(text.replace("\"minInclusive\": 1000", "\"minInclusive\": 0")) }
    }

    @Test fun `optional source records from the domestic stream never invent vision`() {
        val reader = HwihaMetaVisionSourceReader
        assertEquals(SourceRead(emptyList<HwihaScoutPost>(), 0), reader.scoutPosts(emptyMap()))
        val posts = mapOf(HwihaMetaVisionSourceReader.SCOUT_POSTS_KEY to mapOf("version" to 1, "posts" to listOf(
            mapOf("retainerId" to 4, "provinceId" to "p1", "status" to "ACTIVE"),
            mapOf("retainerId" to 5, "provinceId" to "p2", "status" to "EN_ROUTE"),
            mapOf("retainerId" to 6, "provinceId" to "p3"))))
        assertEquals(SourceRead(listOf(HwihaScoutPost(4, "p1")), 1), reader.scoutPosts(posts))
        assertEquals(SourceRead(emptyList<HwihaScoutPost>(), 1),
            reader.scoutPosts(mapOf(HwihaMetaVisionSourceReader.SCOUT_POSTS_KEY to mapOf("version" to 2, "posts" to emptyList<Any>()))))
        fun works(vararg rows: Map<String, Any>) = mapOf(HwihaMetaVisionSourceReader.COUNTY_WORKS_KEY to mapOf("version" to 1, "works" to rows.toList()))
        assertEquals(SourceRead(true, 0), reader.hasCompletedWatchtower(works(mapOf("kind" to "WATCHTOWER_BEACON", "status" to "COMPLETE"))))
        assertEquals(SourceRead(false, 0), reader.hasCompletedWatchtower(works(mapOf("kind" to "WATCHTOWER_BEACON", "status" to "IN_PROGRESS"),
            mapOf("kind" to "GRANARY", "status" to "COMPLETE"))))
        assertEquals(SourceRead(false, 1), reader.hasCompletedWatchtower(works(mapOf("kind" to "WATCHTOWER_BEACON"))))
        assertEquals(SourceRead(false, 0), reader.hasCompletedWatchtower(emptyMap()))
    }
}
