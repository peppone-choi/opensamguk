package opensamguk.infra.seed

import opensamguk.logic.world.WorldMapVariant
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.HexFormat
import kotlin.test.*

/** Published RFC8032 key and repository bundle; synthetic signed receipt only. */
class D101SelectedSourceReceiptSourceTest {
    private class Fixture {
        val f=D101OptionFactsFixture()
        val world=D101WorldArtifactCapture.capture(selectedWorld)
        val algorithm="synthetic algorithm bytes".toByteArray()
        val clock=Clock.fixed(Instant.parse("2026-10-06T09:00:00Z"),ZoneOffset.UTC)
        val der=HexFormat.of().parseHex("302a300506032b6570032100d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")
        private val privateDer=HexFormat.of().parseHex("302e020100300506032b6570042204209d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
        val rawScenario="synthetic selected original".toByteArray()
        val raw=world.mapOriginals()+mapOf("selected-scenario.json" to rawScenario,"classpath-scenario.json" to rawScenario)
        val originals=SelectedCapturedOriginals(raw,SelectedScenarioOrigin.CLASSPATH,"scenario/scenario_3190.json","scenario/scenario_3190.json")
        val facts=f.facts()
        val decision=f.mapper.writeValueAsBytes(linkedMapOf("schemaVersion" to 1,"kind" to "D101_RESOLVER_DECISION_V1",
            "originalOp" to f.op,"typedTargetFingerprint" to f.target,"appSourceSha" to f.app,"imagePins" to f.images,
            "selectedOrigin" to originals.selectedOrigin.name,"selectedLogicalId" to originals.selectedLogicalId,
            "classpathLogicalId" to originals.classpathLogicalId,"originalPins" to originals.pins()))
        fun receipt()=f.mapper.valueToTree<com.fasterxml.jackson.databind.node.ObjectNode>(linkedMapOf<String,Any>(
            "schemaVersion" to 1,"kind" to "D101_FINAL_SELECTED_SOURCE_V1","selectionStatus" to "FINAL_SELECTED",
            "originalOp" to f.op,"typedTargetFingerprint" to f.target,"appSourceSha" to f.app,"imagePins" to f.images,
            "scenarioOrigin" to originals.selectedOrigin.name,"scenarioLogicalId" to originals.selectedLogicalId,
            "classpathLogicalId" to originals.classpathLogicalId,"originalPins" to originals.pins(),"artifactSetId" to world.artifactSetId,
            "variant" to world.variant,"topologyRevision" to world.topologyRevision,"topologyContentHash" to world.topologyContentHash,
            "topologyContentHashProvenanceSha256" to selectedOriginalSha(f.mapper.writeValueAsBytes(linkedMapOf(
                "artifactSetId" to world.artifactSetId,"variant" to world.variant,"topologyRevision" to world.topologyRevision,
                "contentHash" to world.topologyContentHash,"algorithmBytecodeSha256" to selectedOriginalSha(algorithm),
                "canonicalInputSha256" to selectedOriginalSha(world.canonicalTopologyBytes()),
                "inputArtifacts" to world.topologyOriginals().toSortedMap().mapValues {(id,wire)->
                    linkedMapOf("logicalArtifactId" to id,"rawSha256" to selectedOriginalSha(wire),"byteLength" to wire.size.toLong())}))),
            "effectiveOptions" to facts.effectiveOptions(),"optionProvenance" to facts.optionProvenance(),
            "configurationSha256" to facts.configurationSha256,"parserBytecodeSha256" to facts.parserBytecodeSha256,
            "resolverDecisionReceiptSha256" to selectedOriginalSha(decision),"capturedAtUtc" to clock.instant().toString(),
            "trustedProducerIdentity" to "synthetic-rfc8032"))
        fun source(tree:com.fasterxml.jackson.databind.node.ObjectNode=receipt(),domain:String=D101SelectedSourceReceiptSource.DOMAIN,
            present:Boolean=true):D101SelectedSourceReceiptSource {
            val wire=f.mapper.writeValueAsBytes(tree)
            val signature=Signature.getInstance("Ed25519").run {
                initSign(KeyFactory.getInstance("Ed25519").generatePrivate(PKCS8EncodedKeySpec(privateDer)))
                update(domain.toByteArray(Charsets.US_ASCII)+wire);sign()
            }
            fun b64(bytes:ByteArray)=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
            val envelope=f.mapper.writeValueAsBytes(mapOf("schemaVersion" to 1,"originalBytesBase64url" to b64(wire),"signatureBase64url" to b64(signature)))
            return D101SelectedSourceReceiptSource(if(present) D101SignedSelectedReceiptReader {envelope} else null,
                der,selectedOriginalSha(der),"synthetic-rfc8032",world,facts,algorithm,f.targetOriginal,decision,clock)
        }
        fun read(source:D101SelectedSourceReceiptSource,actual:SelectedCapturedOriginals=originals)=source.readVerified(f.op,f.target,f.app,f.images,actual)
    }
    @Test fun `same capture raw five selected world options actual target and producer signature bind receipt`() {
        val f=Fixture();val proof=f.read(f.source())
        assertEquals(f.originals.pins(),proof.originals());assertEquals(f.facts.effectiveOptions(),proof.effectiveOptions())
        assertEquals(f.world.topologyContentHash,proof.topologyContentHash)
        assertEquals(selectedOriginalSha(proof.originalReceipt()),proof.selectedSourceReceiptSha256)
        proof.originalReceipt().fill(0)
        assertEquals(selectedOriginalSha(proof.originalReceipt()),proof.selectedSourceReceiptSha256)
    }
    @Test fun `missing producer signed scope drift provenance drift raw replacement and other domain remain unavailable`() {
        val f=Fixture()
        assertFailsWith<SelectedSourceUnavailable> {f.read(f.source(present=false))}
        assertFailsWith<SelectedSourceUnavailable> {f.read(f.source(domain="OPENSAMGUK-D101-RESULT-V1\n"))}
        for(field in listOf("originalOp","artifactSetId","topologyContentHashProvenanceSha256","configurationSha256","resolverDecisionReceiptSha256")) {
            val tree=f.receipt();tree.put(field,"changed")
            assertFailsWith<SelectedSourceUnavailable>(field) {f.read(f.source(tree))}
        }
        val changed=SelectedCapturedOriginals(f.raw+mapOf("roads.json" to "changed".toByteArray()),f.originals.selectedOrigin,
            f.originals.selectedLogicalId,f.originals.classpathLogicalId)
        assertFailsWith<SelectedSourceUnavailable> {f.read(f.source(),changed)}
    }
    companion object { private val selectedWorld by lazy {
        WorldArtifactsResolver(Path.of("..").toAbsolutePath().normalize()).artifacts(WorldMapVariant.PROVINCE_WORLD)
    }}
}
