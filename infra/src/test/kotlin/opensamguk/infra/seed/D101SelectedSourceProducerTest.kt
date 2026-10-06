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

class D101SelectedSourceProducerTest {
    private class Inputs {
        companion object { private val selectedWorld by lazy {
            WorldArtifactsResolver(Path.of("..").toAbsolutePath().normalize()).artifacts(WorldMapVariant.PROVINCE_WORLD)
        } }
        val f=D101OptionFactsFixture()
        val world=D101WorldArtifactCapture.capture(selectedWorld)
        val originals=SelectedCapturedOriginals(world.mapOriginals()+mapOf("selected-scenario.json" to "synthetic selected".toByteArray(),
            "classpath-scenario.json" to "synthetic selected".toByteArray()),SelectedScenarioOrigin.CLASSPATH,"scenario/scenario_3190.json","scenario/scenario_3190.json")
        val algorithm="synthetic algorithm bytes".toByteArray()
        val clock=Clock.fixed(Instant.parse("2026-10-06T09:00:00Z"),ZoneOffset.UTC)
        val decision=f.mapper.writeValueAsBytes(linkedMapOf("schemaVersion" to 1,"kind" to "D101_RESOLVER_DECISION_V1","originalOp" to f.op,
            "typedTargetFingerprint" to f.target,"appSourceSha" to f.app,"imagePins" to f.images,"selectedOrigin" to originals.selectedOrigin.name,
            "selectedLogicalId" to originals.selectedLogicalId,"classpathLogicalId" to originals.classpathLogicalId,"originalPins" to originals.pins()))
        fun produce(decisionOriginal:ByteArray=decision)=D101SelectedSourceProducer(clock).produce(originals,world,f.facts(),algorithm,f.targetOriginal,decisionOriginal,"synthetic-rfc8032")
    }
    @Test fun `actual capture producer output is consumed only after independent signature and source comparison`() {
        val i=Inputs();val original=i.produce()
        val privateDer=HexFormat.of().parseHex("302e020100300506032b6570042204209d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
        val publicDer=HexFormat.of().parseHex("302a300506032b6570032100d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")
        val signature=Signature.getInstance("Ed25519").run {initSign(KeyFactory.getInstance("Ed25519").generatePrivate(PKCS8EncodedKeySpec(privateDer)));update(D101SelectedSourceReceiptSource.DOMAIN.toByteArray(Charsets.US_ASCII)+original);sign()}
        fun b64(wire:ByteArray)=Base64.getUrlEncoder().withoutPadding().encodeToString(wire)
        val envelope=i.f.mapper.writeValueAsBytes(mapOf("schemaVersion" to 1,"originalBytesBase64url" to b64(original),"signatureBase64url" to b64(signature)))
        val source=D101SelectedSourceReceiptSource(D101SignedSelectedReceiptReader {envelope},publicDer,selectedOriginalSha(publicDer),"synthetic-rfc8032",i.world,i.f.facts(),i.algorithm,i.f.targetOriginal,i.decision,i.clock)
        val binding=source.readVerified(i.f.op,i.f.target,i.f.app,i.f.images,i.originals)
        assertEquals(selectedOriginalSha(original),binding.selectedSourceReceiptSha256)
        assertEquals(i.f.options,binding.effectiveOptions())
        assertEquals(i.originals.pins(),binding.originals())
    }
    @Test fun `source producer refuses resolver drift and malformed decision originals`() {
        val i=Inputs()
        for(wire in listOf(i.decision.toString(Charsets.UTF_8).replace(i.f.app,"f".repeat(40)),i.decision.toString(Charsets.UTF_8)+"{}")) {
            assertFailsWith<SelectedSourceUnavailable> {i.produce(wire.toByteArray())}
        }
        assertFailsWith<SelectedSourceUnavailable> {D101SelectedSourceProducer(i.clock).produce(i.originals,i.world,i.f.facts(),byteArrayOf(),i.f.targetOriginal,i.decision,"synthetic")}
    }
}
