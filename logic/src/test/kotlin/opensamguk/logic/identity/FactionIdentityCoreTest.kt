package opensamguk.logic.identity

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FactionIdentityCoreTest {
    private val templatesPayload = Files.readString(Path.of("..", "data", "curated", "han", "identity-core-templates.json"))
    private val sourcesPayload = Files.readString(Path.of("..", "data", "curated", "han", "identity-presets.json"))
    private val templates = IdentityCoreTemplates.parse(templatesPayload, sourcesPayload).associateBy { it.source.id }
    private val legitimacy = IdentityAudience.entries.associateWith { 1 }

    @Test
    fun `first three seeds retain distinct stages and source grades`() {
        assertEquals(setOf("identity.confucian", "identity.taiping", "identity.bandit"), templates.keys)
        assertEquals(IdentityStage.TERRITORIAL_REGIME, templates.getValue("identity.confucian").initialStage)
        assertEquals(IdentityStage.MOVEMENT, templates.getValue("identity.taiping").initialStage)
        assertEquals(IdentityStage.CONFEDERATION, templates.getValue("identity.bandit").initialStage)
        assertEquals(PresetEvidenceGrade.GAME_TERM, templates.getValue("identity.confucian").source.grade)
        assertEquals(PresetEvidenceGrade.PRIMARY, templates.getValue("identity.taiping").source.grade)
        assertEquals(PresetEvidenceGrade.PRIMARY, templates.getValue("identity.bandit").source.grade)
    }

    @Test
    fun `covert Taiping network can remain in another nation's city`() {
        val network = NetworkPresence("fang-1", "fang", 7, "city-34", IdentityPlaceKind.CITY,
            IdentityNetworkVisibility.COVERT, 50, 1, 1, 20, 1, HostNationRelation.FOREIGN)
        val state = templates.getValue("identity.taiping").seedState(7, legitimacy,
            IdentityContentProfile.CHRONICLE, listOf(network))
        assertEquals(HostNationRelation.FOREIGN, state.networks.single().hostNationRelation)
        assertEquals(IdentityNetworkVisibility.COVERT, state.networks.single().visibility)
    }

    @Test
    fun `bandit confederation can exist with only a hideout`() {
        val hideout = NetworkPresence("mountain-1", "hideout", 9, "hideout-5", IdentityPlaceKind.HIDEOUT,
            IdentityNetworkVisibility.PUBLIC, 20, 1, 1, 5, 1, HostNationRelation.UNCLAIMED)
        val state = templates.getValue("identity.bandit").seedState(9, legitimacy,
            IdentityContentProfile.CHRONICLE, listOf(hideout))
        assertTrue(state.networks.none { it.placeKind == IdentityPlaceKind.CITY })
    }

    @Test
    fun `adoption retains old networks and records displaced practices`() {
        val original = templates.getValue("identity.bandit").seedProfile(9, legitimacy, IdentityContentProfile.CHRONICLE)
        val hideout = NetworkPresence("mountain-1", "hideout", 9, "hideout-5", IdentityPlaceKind.HIDEOUT,
            IdentityNetworkVisibility.SUPPRESSED, 20, 1, 1, 5, 1, HostNationRelation.UNCLAIMED)
        val state = FactionIdentityState(original, listOf(hideout))
        val adopted = state.adopt(templates.getValue("identity.confucian"), IdentityStage.TERRITORIAL_REGIME)
        assertEquals(state.networks, adopted.networks)
        assertEquals("identity.confucian", adopted.profile.presetId)
        assertEquals(setOf("chieftain-council", "mountain-refuge"), adopted.profile.institutionalTensions)
        assertEquals(original.version + 1, adopted.profile.version)
    }

    @Test
    fun `profile requires explicit legitimacy for every audience`() {
        assertFailsWith<IllegalArgumentException> {
            templates.getValue("identity.taiping").seedProfile(7, mapOf(IdentityAudience.COMMONERS to 1), IdentityContentProfile.CHRONICLE)
        }
    }

    @Test
    fun `bandit seed rejects a city school in place of a hideout`() {
        val school = NetworkPresence("school-1", "school", 9, "city-5", IdentityPlaceKind.CITY,
            IdentityNetworkVisibility.PUBLIC, 20, 1, 1, 5, 1, HostNationRelation.OWN)
        assertFailsWith<IllegalArgumentException> {
            templates.getValue("identity.bandit").seedState(9, legitimacy,
                IdentityContentProfile.CHRONICLE, listOf(school))
        }
    }

    @Test
    fun `template fails closed when a first profile is missing`() {
        val broken = templatesPayload.replaceFirst("\"id\": \"identity.bandit\"", "\"id\": \"identity.unknown\"")
        assertFailsWith<IllegalArgumentException> { IdentityCoreTemplates.parse(broken, sourcesPayload) }
    }
}
