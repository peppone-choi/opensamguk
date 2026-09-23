package opensamguk.gameapi.read

import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.gameapi.dto.HwihaCorpsResponse
import opensamguk.gameapi.dto.HwihaScoutOptionsResponse
import opensamguk.gameapi.dto.HwihaVisibilityResponse
import opensamguk.gameapi.web.HwihaVisionController
import opensamguk.infra.seed.HanWorldArtifactsResolver
import opensamguk.logic.input.*
import opensamguk.logic.world.*
import org.mockito.Mockito.*
import java.nio.file.Path
import java.util.Optional
import kotlin.test.*

/**
 * #343 leak boundary for the vision reads: a FOG corps must not exist in the serialized bytes at all, while a
 * FULL corps in the very same response proves the check is looking at real output (positive control).
 */
class HwihaVisionReaderTest {
    private val bundle = HanWorldArtifactsResolver(Path.of("../..")).artifacts(HanWorldVariant.V3_1133)
    private val index = bundle.commanderyIndex
    private val topology = bundle.projection.topology

    private val generals = mock(GeneralReadRepository::class.java)
    private val worlds = mock(WorldStateReadRepository::class.java)
    private val nations = mock(NationReadRepository::class.java)
    private val retainers = mock(RetainerReadRepository::class.java)
    private val resolver = mock(ActiveWorldArtifactResolver::class.java)
    private val spatial = mock(SpatialStateReadRepository::class.java)
    private val reader = HwihaVisionReader(generals, worlds, nations, retainers, resolver, spatial,
        HwihaMetaVisionSourceReader, HwihaVisionRules.CANON)
    private val controller = HwihaVisionController(reader)
    private val json = ObjectMapper().findAndRegisterModules()

    // home ─ next (neighbour, scouted in one test) ; far (not adjacent, FOG)
    private val provinces = index.provinceIds.sorted().groupBy { index.commanderyOf(it)!! }
    private val home = provinces.keys.sorted().first { index.neighbours(it).size >= 2 }
    private val next = index.neighbours(home).first()
    private val far = provinces.keys.sorted().first { it != home && it != next && !index.adjacent(home, it) && !index.adjacent(next, it) }
    private fun province(no: Int) = provinces.getValue(no).first()

    private val world = WorldStateReadEntity(id = 1, config = mapOf("ruleProfile" to "HWIHA")).apply {
        currentYear = 190; currentMonth = 3; currentPhase = 2
    }

    private fun corpsMeta(owner: Int, order: String, unit: Int, nation: Int) = mapOf(HwihaDeploymentState.META_KEY to
        HwihaDeploymentState(listOf(HwihaDeployedCorps(order, owner, owner, null, nation, listOf(unit), HwihaPhase(190, 1, 1)))).toMetaValue())

    private fun setup(profile: String = "HWIHA", actorMeta: Map<String, Any?> = emptyMap(), fullEnemyAtHome: Boolean = true) {
        world.config = mapOf("ruleProfile" to profile)
        `when`(worlds.findProcessWorld()).thenReturn(world)
        val actor = GeneralReadEntity(id = 1, worldId = 1, name = "주인공", nationId = 1, userId = "41",
            meta = corpsMeta(1, "req-own-order", 11, 1) + actorMeta)
        val visible = GeneralReadEntity(id = 2, worldId = 1, name = "보이는장수", nationId = 2, userId = "42",
            meta = corpsMeta(2, "req-visible-order", 21, 2))
        val secret = GeneralReadEntity(id = 3, worldId = 1, name = "비밀장수", nationId = 3, userId = "43",
            meta = corpsMeta(3, "req-FOG-SECRET", 31, 3))
        val neighbour = GeneralReadEntity(id = 4, worldId = 1, name = "옆장수", nationId = 3, userId = "44",
            meta = corpsMeta(4, "req-next-live", 41, 3))
        val people = listOf(actor, visible, secret, neighbour)
        people.forEach { `when`(generals.findById(it.id)).thenReturn(Optional.of(it)) }
        `when`(generals.findById(99)).thenReturn(Optional.empty())
        `when`(generals.findAll()).thenReturn(people)
        `when`(retainers.findAll()).thenReturn(emptyList())
        `when`(retainers.allBugoks()).thenReturn(listOf(
            GeneralBugokReadEntity(worldId = 1, id = 11, masterGeneralId = 1, name = "내 부곡", troops = 1234),
            GeneralBugokReadEntity(worldId = 1, id = 21, masterGeneralId = 2, name = "보이는 부곡", troops = 6400),
            GeneralBugokReadEntity(worldId = 1, id = 31, masterGeneralId = 3, name = "비밀 부곡", troops = 7777),
            GeneralBugokReadEntity(worldId = 1, id = 41, masterGeneralId = 4, name = "옆 부곡", troops = 45000)))
        `when`(nations.findAll()).thenReturn(listOf(
            NationReadEntity(id = 1, worldId = 1, name = "아", color = "#111111"),
            NationReadEntity(id = 2, worldId = 1, name = "보", color = "#222222"),
            NationReadEntity(id = 3, worldId = 1, name = "비", color = "#333333")))
        `when`(resolver.resolve()).thenReturn(ActiveWorldArtifactSnapshot(world, emptyList(), bundle))
        val at = mapOf(1 to province(home), 2 to province(if (fullEnemyAtHome) home else far), 3 to province(far), 4 to province(next))
        `when`(spatial.readSnapshot(1, topology)).thenReturn(SpatialStateReadSnapshot(
            ProvinceControlSnapshot.fromTopology(topology, listOf(
                ProvinceControlState(topology.topologyRevision, topology.contentHash, province(home), 1, 1))),
            GeneralPositionSnapshot.fromTopology(topology, at.map { (id, p) ->
                GeneralPositionState(topology.topologyRevision, topology.contentHash, id, StrategicNodeRef.LandProvince(p), 1) })))
    }

    private fun bytes(body: Any?) = json.writeValueAsString(body)

    // ── 관문 ─────────────────────────────────────────────────────────────

    @Test fun `unauthenticated is 401, someone else's general is 403, other rule profiles say so`() {
        setup()
        assertEquals(401, controller.corps(null, 1).statusCode.value())
        assertEquals(403, controller.corps(42, 1).statusCode.value())
        assertEquals(403, controller.visibility(41, 99).statusCode.value())
        assertEquals("no-store", controller.visibility(41, 1).headers.cacheControl)
        setup(profile = "SAMMO")
        assertEquals("WRONG_RULE_PROFILE", (controller.visibility(41, 1).body as HwihaVisibilityResponse).status)
        assertEquals("""{"status":"WRONG_RULE_PROFILE"}""", bytes(controller.corps(41, 1).body))
    }

    // ── 누출 ─────────────────────────────────────────────────────────────

    @Test fun `a fog corps is absent from the corps bytes while a full enemy corps is present and banded`() {
        setup()
        val body = controller.corps(41, 1).body as HwihaCorpsResponse
        val text = bytes(body)
        // Positive control: the FULL enemy in the actor's own commandery is in these very bytes.
        assertTrue("보이는장수" in text && HwihaScoutCapture.corpsKey("req-visible-order") in text, text)
        assertFalse("req-visible-order" in text || "6400" in text, "other corps leak raw id or exact troops: $text")
        // The FOG corps: no name, no id, no key, no troops, no province.
        listOf("비밀장수", "req-FOG-SECRET", HwihaScoutCapture.corpsKey("req-FOG-SECRET"), "7777", "\"${province(far)}\"")
            .forEach { assertFalse(it in text, "FOG corps leaked '$it': $text") }
        // Neighbour is FOG too (no scouting yet): its 45000-strong corps is not in the bytes.
        assertFalse("옆장수" in text || "45000" in text, text)
        val own = body.corps!!.single { it.own }
        assertEquals("req-own-order", own.corpsId); assertEquals(1234, own.troops); assertNull(own.troopsBand)
        val other = body.corps!!.single { !it.own }
        assertNull(other.troops); assertEquals("B3", other.troopsBand!!.code); assertEquals("#222222", other.nationColor)
        assertFalse("\"troops\"" in bytes(other), "banded corps must not carry a troops key")
    }

    @Test fun `scouted neighbour shows only the snapshot and its age, never the live army there`() {
        val seen = ScoutedCorps(HwihaScoutCapture.corpsKey("req-next-old"), 4, 4, 3, province(next), "B1")
        val notebook = HwihaScoutReports(index.tilesContentHash, listOf(
            HwihaScoutReport(index.commanderies[next].id, HwihaPhase(190, 2, 1), listOf(ScoutedCity(500, 3, true)), listOf(seen))))
        setup(actorMeta = mapOf(HwihaScoutReports.META_KEY to notebook.toMetaValue()))
        val corps = controller.corps(41, 1).body as HwihaCorpsResponse
        val intel = corps.corps!!.single { it.visibility == "INTEL" }
        assertEquals(seen.corpsKey, intel.corpsId); assertEquals("B1", intel.troopsBand!!.code)
        assertEquals(4, intel.ageTurns); assertEquals(2, intel.lastSeenStamp!!.month)
        val text = bytes(corps)
        assertFalse("45000" in text || HwihaScoutCapture.corpsKey("req-next-live") in text, "live INTEL corps leaked: $text")
        val visibility = controller.visibility(41, 1).body as HwihaVisibilityResponse
        val row = visibility.commanderies!!.single { it.no == next }
        assertEquals("INTEL", row.tier); assertEquals(4, row.ageTurns)
        assertEquals("FULL", visibility.commanderies!!.single { it.no == home }.tier)
        assertEquals(index.commanderies.size, visibility.commanderies!!.size)
        val fog = visibility.commanderies!!.single { it.no == far }
        assertEquals("FOG", fog.tier)
        assertFalse("seenAtStamp" in bytes(fog) || "ageTurns" in bytes(fog))
        assertEquals(setOf("SELF", "OWN_CORPS", "TERRITORY"), visibility.sources!!.map { it.kind }.toSet())
    }

    @Test fun `corrupt authority yields nothing rather than a partial map`() {
        setup(actorMeta = mapOf(HwihaDeploymentState.META_KEY to mapOf("version" to 7)))
        assertEquals("""{"status":"UNAVAILABLE"}""", bytes(controller.corps(41, 1).body))
        assertEquals("""{"status":"UNAVAILABLE"}""", bytes(controller.visibility(41, 1).body))
    }

    // ── 첩보 선택지 ────────────────────────────────────────────────────────

    @Test fun `scout options list exactly the neighbours of where the actor stands with a zero provisional cost`() {
        setup()
        val options = controller.scoutOptions(41, 1).body as HwihaScoutOptionsResponse
        assertEquals("READY", options.status); assertTrue(options.available)
        assertEquals(home, options.origin!!.commanderyNo)
        assertEquals(index.neighbours(home), options.options!!.map { it.no })
        assertTrue(options.options!!.all { it.available && it.code == null })
        assertEquals(0L, options.cost!!.money)
        assertEquals("action.scout", options.inputId)
        assertIs<ScoutAssessment.Eligible>(reader.assessScout(1, 41, index.commanderies[next].id))
        assertEquals(ScoutFailure.NOT_ADJACENT,
            assertIs<ScoutAssessment.Rejected>(reader.assessScout(1, 41, index.commanderies[far].id)).reason)
        assertFailsWith<HwihaVisionForbidden> { reader.assessScout(1, 42, index.commanderies[next].id) }
    }
}
