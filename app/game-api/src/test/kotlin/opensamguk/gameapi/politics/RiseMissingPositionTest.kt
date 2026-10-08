package opensamguk.gameapi.politics

import java.nio.file.Path
import java.util.Optional
import com.fasterxml.jackson.databind.ObjectMapper
import kotlin.test.*
import opensamguk.gameapi.dto.*
import opensamguk.gameapi.read.*
import opensamguk.gameapi.precheck.PoliticalOptionsService
import opensamguk.gameapi.reserve.*
import opensamguk.infra.seed.WorldArtifactsResolver
import opensamguk.logic.input.*
import opensamguk.logic.world.*
import org.mockito.Mockito.*

/** Actual read projection regression; injected delivery is synthetic and approves no rise policy. */
class RiseMissingPositionTest {
    private fun reader(): DomesticReader {
        val bundle = WorldArtifactsResolver(Path.of("../..")).artifacts(WorldMapVariant.V3_1133)
        val topology = bundle.projection.topology
        val county = bundle.projection.administrativeCountyIds.sorted().first {
            bundle.projection.bindingsByCityId[it]?.landProvinceId != null }
        val node = bundle.projection.bindingsByCityId.getValue(county).landProvinceId!!
        val generals = mock(GeneralReadRepository::class.java)
        val cards = mock(RetainerReadRepository::class.java)
        val artifacts = mock(ActiveWorldArtifactResolver::class.java)
        val spatial = mock(SpatialStateReadRepository::class.java)
        val actor = GeneralReadEntity(id=1, worldId=7, userId="42", name="actor", nationId=0,
            troopId=0, meta=mapOf(LordStatus.META_KEY to false, PersonPolicyState.META_KEY to
                PersonPolicyState(PoliticalDesign.CANON.riseMinimumRenown, true,
                    "synthetic-independent-position", "1", 1).toMetaValue()))
        val child = GeneralReadEntity(id=2, worldId=7, name="child", nationId=0, troopId=0,
            meta=mapOf(LordStatus.META_KEY to false))
        `when`(generals.findAll()).thenReturn(listOf(actor,child))
        `when`(generals.findById(1)).thenReturn(Optional.of(actor))
        `when`(cards.findAll()).thenReturn(listOf(GeneralRetainerReadEntity(id=20, worldId=7,
            masterGeneralId=1, generalId=2)))
        `when`(artifacts.resolve()).thenReturn(ActiveWorldArtifactSnapshot(
            WorldStateReadEntity(id=7, currentYear=200, currentMonth=1, currentPhase=1,
                config=mapOf("ruleProfile" to "HWIHA", "mapName" to "han-world-v3")),
            listOf(CityReadEntity(id=county, worldId=7, nationId=0)),bundle))
        val positions = GeneralPositionSnapshot.fromTopology(topology, listOf(GeneralPositionState(
            topology.topologyRevision,topology.contentHash,1,StrategicNodeRef.LandProvince(node),1)))
        assertNull(positions.stateFor(2))
        `when`(spatial.readSnapshot(7,topology)).thenReturn(SpatialStateReadSnapshot(
            ProvinceControlSnapshot.fromTopology(topology),positions))
        return DomesticReader(generals,cards,mock(NationReadRepository::class.java),artifacts,spatial,
            mock(CityGeography::class.java),mock(GameKvReadRepository::class.java),ObjectMapper(),
            mock(DiplomacyReadRepository::class.java),mock(SiegeReadRepository::class.java),
            mock(TroopReadRepository::class.java))
    }
    private fun delivered(): InputCatalog {
        val source=checkNotNull(javaClass.classLoader.getResource("command-catalog/input-catalog.json")).readText()
        val row=Regex("""("inputId":\s*"action\.rise"[\s\S]*?"deliveryState":\s*")(PLANNED|DOMAIN_READY|HANDLER_READY|UI_READY)(")""")
        return InputCatalog.parse(row.replace(source,"${'$'}1HANDLER_READY${'$'}3"))
    }
    @Test fun `actual reader missing child spatial row must be rejected by pure guard`() {
        val state=assertNotNull(reader().snapshot().state)
        val child=assertNotNull(state.person(2))
        assertNull(child.node)
        assertFalse(child.inBattle)
        assertFalse(child.spatialStateAvailable)
        assertEquals(PoliticalFailure.STATE_UNAVAILABLE, assertIs<PoliticalAssessment.Rejected>(
            PoliticalRules.assess(PoliticalRequest(1,PoliticalInput.RISE),state)).reason)
    }
    @Test fun `options must deny unknown child battlefield state`() {
        val option=PoliticalOptionsService(reader(),delivered()).options(1,42L)
            .single { it.inputId==PoliticalInput.RISE }
        assertFalse(option.available)
    }
    @Test fun `admission must deny unknown child battlefield state`() {
        assertFailsWith<AdmissionDenied> {
            PoliticalAdmission(reader(),delivered()).canonicalArguments(PoliticalInput.RISE,1,42,0,"{}")
        }
    }
}
