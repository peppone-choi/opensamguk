package opensamguk.gateway.d101

import opensamguk.gateway.d101.domain.*
import opensamguk.gateway.d101.infra.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.URI
import java.time.Instant

class D101RootRecoveryResultClientTest {
    private val f=D101Fixture()
    private val begin="7".repeat(64)
    private val root="8".repeat(64)
    private val database="9".repeat(64)
    private val runtime="a".repeat(64)
    private fun execution(safe:D101ExecutionState=D101ExecutionState.REMOTE_SUCCEEDED):D101Execution {
        val tree=f.intentTree();val intent=f.intent(tree);val prepare=f.prepareBody(tree)
        return D101Execution(intent,f.mapper.writeValueAsBytes(tree),prepare,D101Fixture.hash(prepare),
            D101ExecutionState.RECOVERY_REQUIRED,safe,2,D101DispatchIntentCandidate(2,"4".repeat(64),"5".repeat(64),"6".repeat(64)),
            if(safe==D101ExecutionState.DISPATCH_INTENT) null else root,null,null,
            Instant.ofEpochSecond(f.now-1),Instant.ofEpochSecond(f.now))
    }
    private fun receipt(e:D101Execution)=f.mapper.valueToTree<com.fasterxml.jackson.databind.node.ObjectNode>(linkedMapOf<String,Any>(
        "schemaVersion" to 1,"kind" to "D101_RECOVERY_RESULT_V1","status" to "RECOVERED","serverId" to "pep","worldId" to 1,
        "operationId" to f.operation,"approvalIntentSha256" to e.intent.sha256,"targetFingerprint" to e.intent.targetFingerprint,
        "gatewayPayloadSha256" to e.gatewayPayloadSha256,"verifyingRevision" to "2","recoveryBeginReceiptSha256" to begin,
        "originalRootResultSha256" to root,"backupManifestSha256" to "b".repeat(64),"recoveryClaimSha256" to "c".repeat(64),
        "restoredDatabaseReceiptSha256" to database,"restoredRuntimeReceiptSha256" to runtime,"restoredSelectedMetadataReceiptSha256" to "d".repeat(64),
        "oldImageDigests" to f.pins,"restoreAttempt" to 1,"startedAtUtc" to Instant.ofEpochSecond(f.now-1).toString(),
        "completedAtUtc" to Instant.ofEpochSecond(f.now).toString(),"recoveryDeadlineUnix" to e.intent.recoveryDeadlineUnix,
        "oldGeneration" to 9,"oldScenarioCode" to "scenario_990002","oldPublicationReceiptSha256" to "e".repeat(64),
    )).also { result ->
        val registry=f.mapper.writeValueAsBytes(linkedMapOf("id" to "pep","name" to "actual old synthetic name",
            "gameApiUrl" to "http://spep-game-api:8081","gameEngineUrl" to "http://spep-game-engine:8082",
            "deployProject" to "opensamguk-spep","generation" to 9,"scenarioCode" to "scenario_990002"))
        val world=f.mapper.writeValueAsBytes(linkedMapOf("schemaVersion" to 1,"kind" to "D101_RESTORED_OLD_WORLD_V1",
            "operationId" to f.operation,"approvalIntentSha256" to e.intent.sha256,"targetFingerprint" to e.intent.targetFingerprint,
            "verifyingRevision" to "2","recoveryBeginReceiptSha256" to begin,"worldId" to 1,"generation" to 9,
            "scenarioCode" to "scenario_990002","tickSeconds" to 300,"oldImageDigests" to f.pins,
            "databaseReceiptSha256" to database,"runtimeReceiptSha256" to runtime,"observedAtUtc" to Instant.ofEpochSecond(f.now).toString()))
        result.put("oldRegistryReceiptSha256",D101Fixture.hash(registry));result.put("oldCanonicalRegistryBytesBase64url",D101Fixture.b64(registry))
        result.put("oldWorldReceiptSha256",D101Fixture.hash(world));result.put("restoredOldWorldBytesBase64url",D101Fixture.b64(world))
    }
    private fun read(e:D101Execution,tree:com.fasterxml.jackson.databind.node.ObjectNode,
        domain:ByteArray=D101RootRecoveryResultClient.DOMAIN):opensamguk.gateway.d101.security.D101VerifiedRecoveryClose {
        val wire=f.mapper.writeValueAsBytes(tree);val sha=D101Fixture.hash(wire)
        val transport=D101RootResultTransport { uri,token,_ ->
            assertEquals("/operations/${f.operation}/recovery-result/$sha",uri.path);assertEquals("synthetic-token",token)
            D101RootResultHttpResponse(200,wire,listOf("rfc8032-fixture."+D101Fixture.b64(f.sign(domain+wire))))
        }
        return D101RootRecoveryResultClient(URI("http://deployer:9000"),{"synthetic-token"},f.authority(),f.clock,
            f.mapper,transport).readVerified(e,begin,sha)
    }
    @Test fun `signed actual snapshots expose exact old canonical and world without rewriting original result`() {
        for(safe in listOf(D101ExecutionState.DISPATCH_INTENT,D101ExecutionState.REMOTE_SUCCEEDED,D101ExecutionState.REGISTRY_SETTLED)) {
            val e=execution(safe);val proof=read(e,receipt(e))
            assertEquals("actual old synthetic name",proof.oldCanonicalRegistry.name)
            assertEquals(9,proof.restoredWorld.generation);assertEquals(300,proof.restoredWorld.tickSeconds)
            assertEquals(root,proof.originalRootResultSha256)
            if(safe==D101ExecutionState.DISPATCH_INTENT) assertNull(e.rootResultReceiptSha256)
            proof.oldRegistryOriginalBytes().fill(0)
            assertEquals(proof.oldRegistryReceiptSha256,D101Fixture.hash(proof.oldRegistryOriginalBytes()))
            proof.requireMatches(e,begin,proof.recoveryResultReceiptSha256)
        }
    }
    @Test fun `signed wrong scope missing raw originals wrong hash and cross domain remain unavailable`() {
        val e=execution()
        for(change in listOf<(com.fasterxml.jackson.databind.node.ObjectNode)->Unit>(
            {it.put("restoreAttempt",2)},{it.put("originalRootResultSha256","f".repeat(64))},
            {it.put("recoveryBeginReceiptSha256","f".repeat(64))},{it.remove("oldCanonicalRegistryBytesBase64url")},
            {it.put("oldWorldReceiptSha256","f".repeat(64))},{it.put("oldGeneration",0)})) {
            val tree=receipt(e);change(tree)
            assertThrows(D101ObservationUnavailable::class.java) {read(e,tree)}
        }
        assertThrows(D101ObservationUnavailable::class.java) {read(e,receipt(e),"OPENSAMGUK-D101-RESULT-V1\n".toByteArray())}
    }
    @Test fun `missing authority and original recovery deadline close before exposure`() {
        val e=execution()
        assertThrows(D101ObservationUnavailable::class.java) {
            D101RootRecoveryResultClient(URI("http://deployer:9000"),{error("token read before authority")},clock=f.clock,
                transport=D101RootResultTransport {_,_,_->error("network before authority")}).readVerified(e,begin,"f".repeat(64))
        }
        f.clock.epoch=e.intent.recoveryDeadlineUnix
        assertThrows(D101ObservationUnavailable::class.java) {read(e,receipt(e))}
    }
}
