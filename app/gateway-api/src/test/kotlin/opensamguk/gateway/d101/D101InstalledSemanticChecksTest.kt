package opensamguk.gateway.d101

import com.fasterxml.jackson.databind.node.ObjectNode
import opensamguk.gateway.d101.domain.*
import opensamguk.gateway.d101.security.*
import opensamguk.gateway.d101.infra.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.URI

/** Synthetic format/binding fixtures only; never actual production evidence. */
class D101InstalledSemanticChecksTest {
    @Test
    fun `known intent and card consume exact scoped bytes but do not approve other originals`() {
        val packet = Packet()
        val checks = packet.checks()
        assertEquals(D101ApprovedPurposeAuthority.ORIGINAL_IDS, checks.keys)
        for (id in listOf("approvalIntent", "deploymentCard")) {
            checks.getValue(id).verify(packet.originals.getValue(id).copyOf(), packet.intent)
        }
        for (id in D101ApprovedPurposeAuthority.ORIGINAL_IDS - setOf("approvalIntent", "deploymentCard")) {
            assertThrows<D101PurposeAuthorityUnavailable>(id) {
                checks.getValue(id).verify(packet.originals.getValue(id).copyOf(), packet.intent)
            }
        }
    }

    @Test
    fun `complete registry and matching raw hashes cannot make synthetic upstream evidence succeed`() {
        val packet = Packet()
        val verifier = D101HostSemanticVerifier(packet.f.codec, packet.intent.sha256, packet.checks())
        assertThrows<D101PurposeAuthorityUnavailable> {
            verifier.verifyOriginals(packet.verified())
        }
    }

    @Test
    fun `card semantic drifts reject even after its raw hash and host manifest are rebound`() {
        val changes: List<(ObjectNode) -> Unit> = listOf<(ObjectNode) -> Unit>(
            { it.put("schemaVersion", 2) },
            { it.put("schemaVersion", "1") },
            { it.put("kind", "UNKNOWN") },
            { it.put("operationId", "f".repeat(32)) },
            { it.put("approvalIntentSha256", "f".repeat(64)) },
            { it.put("appSourceSha", "f".repeat(40)) },
            { it.put("dockerSourceSha", "f".repeat(40)) },
            { it.put("unknown", "ignored") },
            { it.putNull("commandPlanSha256") },
            { it.remove("recoveryPlanSha256"); Unit },
            { (it["oldImageDigests"] as ObjectNode).put("game-api", "sha256:" + "f".repeat(64)) },
            { (it["newImageDigests"] as ObjectNode).put("web-game", "sha256:" + "f".repeat(64)) },
        ) + REFERENCES.map { id -> { node: ObjectNode -> node.put(id + "Sha256", "f".repeat(64)); Unit } }
        for ((index, change) in changes.withIndex()) {
            val packet = Packet(changeCard = change)
            assertThrows<D101PurposeAuthorityUnavailable>("card case $index") {
                packet.checks().getValue("deploymentCard").verify(packet.originals.getValue("deploymentCard"), packet.intent)
            }
        }
    }

    @Test
    fun `valid independently decoded intent from another source cannot substitute for pinned scope`() {
        val packet = Packet()
        val otherTree = packet.f.mapper.readTree(packet.originals.getValue("approvalIntent")) as ObjectNode
        otherTree.put("initialPublicRevision", "2")
        val other = packet.f.intent(otherTree)
        for (id in listOf("approvalIntent", "deploymentCard")) {
            assertThrows<D101PurposeAuthorityUnavailable>(id) {
                packet.checks().getValue(id).verify(packet.originals.getValue(id), other)
            }
        }
    }

    @Test
    fun `raw original mismatch and mutable caller data cannot replace the frozen source`() {
        val packet = Packet()
        val checks = packet.checks()
        val intentOriginal = packet.originals.getValue("approvalIntent").copyOf()
        packet.originals.getValue("approvalIntent").fill(0)
        checks.getValue("approvalIntent").verify(intentOriginal, packet.intent)
        assertThrows<D101PurposeAuthorityUnavailable> {
            checks.getValue("approvalIntent").verify(packet.originals.getValue("approvalIntent"), packet.intent)
        }
        assertThrows<UnsupportedOperationException> {
            (checks as MutableMap<String, D101HostOriginalSemanticCheck>).remove("reviewBasis")
        }
    }

    @Test
    fun `intent receipt labels must equal actual original bytes even under a rebound manifest`() {
        val packet = Packet(changeIntent = { it.put("spaceInventoryReceiptSha256", "f".repeat(64)) })
        assertThrows<D101PurposeAuthorityUnavailable> {
            packet.checks().getValue("approvalIntent").verify(packet.originals.getValue("approvalIntent"), packet.intent)
        }
    }

    @Test
    fun `missing host manifest or an original never creates the fixed registry`() {
        val packet = Packet()
        for (id in D101ApprovedPurposeAuthority.ORIGINAL_IDS + "trustManifest") {
            val candidate = packet.originals + mapOf("trustManifest" to packet.manifestWire)
            assertThrows<D101PurposeAuthorityUnavailable>(id) {
                D101InstalledSemanticChecks(D101VerifiedHostOriginals(candidate - id), packet.pins())
            }
        }
    }

    @Test
    fun `duplicate trailing unknown and null card fields reject despite exact raw hash binding`() {
        val malformed: List<(String) -> String> = listOf<(String) -> String>(
            { it.replaceFirst("{", "{\"schemaVersion\":1,") },
            { it + " {}" },
            { it.replaceFirst("{", "{\"extra\":true,") },
            { it.replace("\"schemaVersion\":1", "\"schemaVersion\":null") },
        )
        for ((index, change) in malformed.withIndex()) {
            val packet = Packet(changeCardWire = { change(it.toString(Charsets.UTF_8)).toByteArray() })
            assertThrows<D101PurposeAuthorityUnavailable>("wire case $index") {
                packet.checks().getValue("deploymentCard").verify(packet.originals.getValue("deploymentCard"), packet.intent)
            }
        }
    }

    @Test
    fun `provenance can bind completed intent and card without upstream descendant hashes`() {
        val packet = Packet(provenanceAfterCard = true)
        val checks = packet.checks()
        for (id in listOf("approvalIntent", "deploymentCard")) {
            checks.getValue(id).verify(packet.originals.getValue(id), packet.intent)
        }
        val provenance = packet.f.mapper.readTree(packet.originals.getValue("approvedReceiptProvenance"))
        assertEquals(packet.intent.sha256, provenance["syntheticIntentOriginalSha256"].textValue())
        assertEquals(D101Fixture.hash(packet.originals.getValue("deploymentCard")),
            provenance["syntheticCardOriginalSha256"].textValue())
        // This is an assembly fixture, not an approved provenance schema or
        // real issuer. Matching final hashes cannot install its validator.
        assertThrows<D101PurposeAuthorityUnavailable> {
            checks.getValue("approvedReceiptProvenance").verify(packet.originals.getValue("approvedReceiptProvenance"), packet.intent)
        }
        assertThrows<D101PurposeAuthorityUnavailable> {
            D101HostSemanticVerifier(packet.f.codec, packet.intent.sha256, checks).verifyOriginals(packet.verified())
        }
    }

    @Test
    fun `Root command15 scope can match while absent native supporting originals keep the row closed`() {
        val packet = Packet(commandPlanAfterIntent = true)
        val consumer = packet.consumer()
        consumer.validateCommandPlanScope(packet.originals.getValue("commandPlan"), packet.intent)
        assertThrows<D101PurposeAuthorityUnavailable> {
            consumer.fixedChecks().getValue("commandPlan").verify(packet.originals.getValue("commandPlan"), packet.intent)
        }
    }

    @Test
    fun `command scope stage and resource drift reject after card and manifest hash rebinding`() {
        val changes = listOf<(ObjectNode) -> Unit>(
            { it.put("schemaVersion", "1") }, { it.put("kind", "UNKNOWN") },
            { it.put("operationId", "f".repeat(32)) }, { it.put("approvalIntentSha256", "f".repeat(64)) },
            { it.put("targetFingerprint", "f".repeat(64)) }, { it.put("appSourceSha", "f".repeat(40)) },
            { it.put("dockerSourceSha", "f".repeat(40)) }, { it.put("selectedSourceReceiptSha256", "f".repeat(64)) },
            { it.put("selectedEnvelopeSha256", "not-a-sha") }, { it.putNull("capsReaderSha256") },
            { it.put("seedEntrypoint", "other.Main") }, { it.put("destructiveCutoffUnix", 1) },
            { it.put("destructiveCutoffUnix", "1") }, { it.put("extra", true) },
            { (it["newImageDigests"] as ObjectNode).put("game-engine", "sha256:" + "f".repeat(64)) },
            { it.set<com.fasterxml.jackson.databind.JsonNode>("stages", D101Fixture().mapper.valueToTree(STAGES.reversed())) },
            { it.set<com.fasterxml.jackson.databind.JsonNode>("stages", D101Fixture().mapper.valueToTree(STAGES + "fallback-live")) },
            { (it["resources"] as ObjectNode).put("project", "opensamguk-spep") },
            { (it["resources"] as ObjectNode).put("network", "other-net") },
            { (it["resources"] as ObjectNode).put("postgresVolume", "old-pgdata") },
            { (it["resources"] as ObjectNode).put("redisVolume", "old-redisdata") },
            { (it["resources"] as ObjectNode).put("candidateComposeFile", "relative.json") },
            { (it["resources"] as ObjectNode).put("candidateComposeFile", "/synthetic/./candidate.json") },
            { (it["resources"] as ObjectNode).put("liveComposeFile", "/synthetic/candidate.json") },
            { (it["resources"] as ObjectNode).put("candidateComposeSha256", "unknown") },
            { (it["resources"] as ObjectNode).put("liveComposeSha256", "unknown") },
            { (it["resources"] as ObjectNode).put("allow", true) },
            { (it["resources"] as ObjectNode).put("capsReaderFile", "relative.json") },
            { (it["resources"] as ObjectNode).put("capsReaderFile", "/synthetic/caps/other.json") },
        )
        for ((index, change) in changes.withIndex()) {
            val packet = Packet(commandPlanAfterIntent = true, changeCommandPlan = change)
            assertThrows<D101PurposeAuthorityUnavailable>("command scope case $index") {
                packet.consumer().validateCommandPlanScope(packet.originals.getValue("commandPlan"), packet.intent)
            }
        }
    }

    @Test
    fun `command duplicate trailing null and malformed utf8 reject under matching parent hashes`() {
        val changes = listOf<(ByteArray) -> ByteArray>(
            { it.toString(Charsets.UTF_8).replaceFirst("{", "{\"schemaVersion\":1,").toByteArray() },
            { it + " {}".toByteArray() },
            { it.toString(Charsets.UTF_8).replace("\"schemaVersion\":1", "\"schemaVersion\":null").toByteArray() },
            { byteArrayOf(0xc3.toByte(), 0x28) },
        )
        for ((index, change) in changes.withIndex()) {
            val packet = Packet(commandPlanAfterIntent = true, changeCommandPlanWire = change)
            assertThrows<D101PurposeAuthorityUnavailable>("command wire case $index") {
                packet.consumer().validateCommandPlanScope(packet.originals.getValue("commandPlan"), packet.intent)
            }
        }
    }

    @Test
    fun `synthetic native originals exercise command semantics while full14 stays closed`() {
        val packet = Packet(nativeCommand = true)
        assertThrows<D101PurposeAuthorityUnavailable> {
            packet.consumer().fixedChecks().getValue("commandPlan").verify(packet.originals.getValue("commandPlan"), packet.intent)
        }
        packet.consumer(true).fixedChecks().getValue("commandPlan").verify(packet.originals.getValue("commandPlan"), packet.intent)
        assertThrows<D101PurposeAuthorityUnavailable> {
            packet.consumer(true, null).fixedChecks().getValue("commandPlan").verify(packet.originals.getValue("commandPlan"), packet.intent)
        }
        assertThrows<D101PurposeAuthorityUnavailable> {
            packet.consumer(true).fixedChecks().getValue("selectedSourceReceipt").verify(packet.originals.getValue("selectedSourceReceipt"), packet.intent)
        }
    }

    @Test
    fun `a valid fixed identity from another deployment pin cannot substitute for current manifest pin`() {
        val packet = Packet(nativeCommand = true)
        val pins = packet.pins()
        val other = D101DeploymentTrustPins(pins.operationId, pins.approvalIntentSha256, pins.manifestSha256,
            pins.fixedPrivateOrigin, "foreign-producer", pins.anchorSpki(), pins.approvalAnchorSpkiSha256,
            pins.purposeSpki(), pins.purposeSpkiSha256, pins.signingKeyEnvelopeSha256)
        assertThrows<D101PurposeAuthorityUnavailable> {
            packet.consumer(true, D101FixedProducerIdentity(other)).fixedChecks().getValue("commandPlan")
                .verify(packet.originals.getValue("commandPlan"), packet.intent)
        }
    }

    @Test
    fun `hash rebound native Compose DB reader and selected signature drift never become success`() {
        val changes = listOf<(MutableMap<String, ByteArray>) -> Unit>(
            { it["candidateCompose"] = it.getValue("candidateCompose").toString(Charsets.UTF_8).replace("\"internal\":true", "\"internal\":false").toByteArray() },
            { it["liveCompose"] = it.getValue("liveCompose").toString(Charsets.UTF_8).replace("opensamguk-net", "foreign-network").toByteArray() },
            { it["capsReaderOriginal"] = it.getValue("capsReaderOriginal").toString(Charsets.UTF_8).replace("synthetic_db", "bad-name").toByteArray() },
            { it["capsReaderOriginal"] = it.getValue("capsReaderOriginal").toString(Charsets.UTF_8).replace("-net", "-other").toByteArray() },
            { it["selectedEnvelope"] = D101Fixture().mapper.writeValueAsBytes(mapOf("schemaVersion" to 1, "originalBytesBase64url" to "e30",
                "signatureBase64url" to D101Fixture.b64(ByteArray(64)))) },
        )
        for ((index, change) in changes.withIndex()) {
            val packet = Packet(nativeCommand = true, changeNative = change)
            assertThrows<D101PurposeAuthorityUnavailable>("native semantic case $index") {
                packet.consumer(true).fixedChecks().getValue("commandPlan").verify(packet.originals.getValue("commandPlan"), packet.intent)
            }
        }
        val wrongProducer = Packet(nativeCommand = true, selectedProducer = "foreign-producer")
        assertThrows<D101PurposeAuthorityUnavailable> {
            wrongProducer.consumer(true).fixedChecks().getValue("commandPlan").verify(wrongProducer.originals.getValue("commandPlan"), wrongProducer.intent)
        }
        val packet = Packet(nativeCommand = true)
        packet.driftAfterNative = true
        assertThrows<D101PurposeAuthorityUnavailable> {
            packet.consumer(true).fixedChecks().getValue("commandPlan").verify(packet.originals.getValue("commandPlan"), packet.intent)
        }
    }

    private class Packet(
        changeCard: (ObjectNode) -> Unit = {},
        changeIntent: (ObjectNode) -> Unit = {},
        changeCardWire: (ByteArray) -> ByteArray = { it },
        provenanceAfterCard: Boolean = false,
        commandPlanAfterIntent: Boolean = false,
        changeCommandPlan: (ObjectNode) -> Unit = {},
        changeCommandPlanWire: (ByteArray) -> ByteArray = { it },
        nativeCommand: Boolean = false,
        changeNative: (MutableMap<String, ByteArray>) -> Unit = {},
        selectedProducer: String = "rfc8032-fixture",
    ) {
        val f = D101Fixture()
        val originals = D101ApprovedPurposeAuthority.ORIGINAL_IDS.associateWith { id ->
            f.mapper.writeValueAsBytes(mapOf("syntheticNotProduced" to id))
        }.toMutableMap()
        val intent: D101ApprovalIntent
        val manifestWire: ByteArray
        val native = mutableMapOf<String, ByteArray>()
        var driftAfterNative = false
        private var originalReads = 0

        init {
            val tree = f.intentTree()
            if (nativeCommand) {
                val target = f.mapper.readTree(f.json.base64url(tree["rootTargetBytesBase64url"].textValue(), 16 * 1024)) as ObjectNode
                (target["target"]["updates"] as ObjectNode).put("RESET_NPCMODE", "1").put("RESET_SHOW_IMG_LEVEL", "0")
                val targetWire = f.mapper.writeValueAsBytes(target)
                tree.put("rootTargetBytesBase64url", D101Fixture.b64(targetWire)).put("targetFingerprint", D101Fixture.hash(targetWire))
                val scope = f.intent(tree)
                val optionNames = listOf("SERVER_NAME", "SERVER_GENERATION", "SCENARIO_CODE", "SCENARIO_SEED_ENABLED", "SCENARIO_LOOKUP_DIR",
                    "RESET_MAXGENERAL", "RESET_FIRST_TURN", "RESET_EXTEND", "RESET_TURNTERM", "RESET_BLOCK_GENERAL_CREATE", "RESET_NPCMODE", "RESET_SHOW_IMG_LEVEL")
                val selected = linkedMapOf<String, Any>("schemaVersion" to 1, "kind" to "D101_FINAL_SELECTED_SOURCE_V1", "selectionStatus" to "FINAL_SELECTED",
                    "originalOp" to f.operation, "typedTargetFingerprint" to scope.targetFingerprint, "appSourceSha" to f.app, "imagePins" to f.pins,
                    "scenarioOrigin" to "CLASSPATH", "scenarioLogicalId" to "scenario_3190.json", "classpathLogicalId" to "scenario_3190.json",
                    "originalPins" to listOf("tiles.json", "world.json", "roads.json", "selected-scenario.json", "classpath-scenario.json").associateWith {
                        mapOf("logicalArtifactId" to it, "rawSha256" to "a".repeat(64), "byteLength" to 1) },
                    "artifactSetId" to "synthetic", "variant" to "synthetic", "topologyRevision" to "synthetic", "topologyContentHash" to "b".repeat(64),
                    "topologyContentHashProvenanceSha256" to "c".repeat(64), "effectiveOptions" to optionNames.associateWith { scope.target.updates.getValue(it) },
                    "optionProvenance" to optionNames.associateWith { "d".repeat(64) }, "configurationSha256" to "e".repeat(64),
                    "parserBytecodeSha256" to "f".repeat(64), "resolverDecisionReceiptSha256" to "1".repeat(64),
                    "capturedAtUtc" to java.time.Instant.ofEpochSecond(f.now).toString(), "trustedProducerIdentity" to selectedProducer)
                originals["selectedSourceReceipt"] = f.mapper.writeValueAsBytes(selected)
            }
            for (id in RECEIPTS) tree.put(id + "Sha256", D101Fixture.hash(originals.getValue(id)))
            changeIntent(tree)
            originals["approvalIntent"] = f.mapper.writeValueAsBytes(tree)
            intent = f.codec.decode(originals.getValue("approvalIntent"), D101Fixture.hash(originals.getValue("approvalIntent")))
            if (nativeCommand) {
                native["selectedEnvelope"] = f.mapper.writeValueAsBytes(mapOf("schemaVersion" to 1,
                    "originalBytesBase64url" to D101Fixture.b64(originals.getValue("selectedSourceReceipt")),
                    "signatureBase64url" to D101Fixture.b64(f.sign("OPENSAMGUK-D101-SELECTED-SOURCE-V1\n".toByteArray() + originals.getValue("selectedSourceReceipt")))))
                native["capsReaderOriginal"] = f.mapper.writeValueAsBytes(mapOf("schemaVersion" to 1, "kind" to "D101_CANDIDATE_DB_READER_V1",
                    "operationId" to f.operation, "approvalIntentSha256" to intent.sha256, "targetFingerprint" to intent.targetFingerprint,
                    "appSourceSha" to f.app, "imagePins" to f.pins, "network" to ("d101-candidate-" + f.operation + "-net"),
                    "databaseName" to "synthetic_db", "databaseUser" to "synthetic_user", "localPassFile" to "/synthetic/passref",
                    "hostPassFile" to "/synthetic/passref", "passFileSha256" to "d".repeat(64)))
            }
            if (commandPlanAfterIntent || nativeCommand) {
                val prefix = "d101-candidate-" + f.operation
                val command: ObjectNode = f.mapper.valueToTree(linkedMapOf(
                    "schemaVersion" to 1, "kind" to "D101_ROOT_CANDIDATE_COMMAND_PLAN_V1",
                    "operationId" to f.operation, "approvalIntentSha256" to intent.sha256,
                    "targetFingerprint" to intent.targetFingerprint, "appSourceSha" to f.app,
                    "dockerSourceSha" to "c".repeat(40), "newImageDigests" to f.pins,
                    "selectedSourceReceiptSha256" to intent.selectedSourceReceiptSha256,
                    "selectedEnvelopeSha256" to "d".repeat(64), "capsReaderSha256" to "e".repeat(64),
                    "seedEntrypoint" to "opensamguk.engine.boot.D101SeedOnlyCli", "stages" to STAGES,
                    "destructiveCutoffUnix" to intent.destructiveCutoffUnix,
                    "resources" to linkedMapOf("project" to prefix, "network" to prefix + "-net",
                        "postgresVolume" to prefix + "-pgdata", "redisVolume" to prefix + "-redisdata",
                        "candidateComposeFile" to "/synthetic/candidate.json", "candidateComposeSha256" to "1".repeat(64),
                        "liveComposeFile" to "/synthetic/live.json", "liveComposeSha256" to "2".repeat(64),
                        "capsReaderFile" to ("/synthetic/caps/" + f.operation + ".json")),
                ))
                if (nativeCommand) {
                    // Public deterministic source bytes only, no native IO.
                    // Semantic drift tests rebound hashes after changing them.
                    native.putAll(D101InstalledSemanticChecks(D101VerifiedHostOriginals(originals + mapOf("trustManifest" to "{}".toByteArray())),
                        D101DeploymentTrustPins(f.operation, intent.sha256, "a".repeat(64), URI("http://deployer:8080"), "rfc8032-fixture",
                            f.publicDer, D101Fixture.hash(f.publicDer), f.publicDer, D101Fixture.hash(f.publicDer), "e".repeat(64))).composeOriginals(intent))
                    changeNative(native)
                    command.put("selectedEnvelopeSha256", D101Fixture.hash(native.getValue("selectedEnvelope")))
                    command.put("capsReaderSha256", D101Fixture.hash(native.getValue("capsReaderOriginal")))
                    (command["resources"] as ObjectNode).put("candidateComposeSha256", D101Fixture.hash(native.getValue("candidateCompose")))
                        .put("liveComposeSha256", D101Fixture.hash(native.getValue("liveCompose")))
                }
                changeCommandPlan(command)
                originals["commandPlan"] = changeCommandPlanWire(f.mapper.writeValueAsBytes(command))
            }
            val card: ObjectNode = f.mapper.valueToTree(linkedMapOf(
                "schemaVersion" to 1, "kind" to "D101_PEP_EXECUTION_CARD", "operationId" to f.operation,
                "approvalIntentSha256" to intent.sha256, "appSourceSha" to f.app, "dockerSourceSha" to "c".repeat(40),
                "oldImageDigests" to f.pins, "newImageDigests" to f.pins,
            ))
            for (id in REFERENCES) card.put(id + "Sha256", D101Fixture.hash(originals.getValue(id)))
            changeCard(card)
            originals["deploymentCard"] = changeCardWire(f.mapper.writeValueAsBytes(card))
            if (provenanceAfterCard) {
                originals["approvedReceiptProvenance"] = f.mapper.writeValueAsBytes(linkedMapOf(
                    "syntheticNotProduced" to "approvedReceiptProvenance",
                    "syntheticIntentOriginalSha256" to intent.sha256,
                    "syntheticCardOriginalSha256" to D101Fixture.hash(originals.getValue("deploymentCard")),
                ))
            }
            manifestWire = f.mapper.writeValueAsBytes(linkedMapOf(
                "schemaVersion" to 1, "kind" to "D101_HOST_TRUST_V1", "operationId" to f.operation,
                "approvalIntentSha256" to intent.sha256,
                "deploymentCardSha256" to D101Fixture.hash(originals.getValue("deploymentCard")),
                "approvedReceiptProvenanceSha256" to D101Fixture.hash(originals.getValue("approvedReceiptProvenance")),
                "keyId" to "rfc8032-fixture", "publicKeySpkiSha256" to D101Fixture.hash(f.publicDer),
                "signingKeyEnvelopeSha256" to "e".repeat(64), "rootPrivateOrigin" to "http://deployer:8080",
                "appSourceSha" to f.app, "dockerSourceSha" to "c".repeat(40),
                "oldImageDigests" to f.pins, "newImageDigests" to f.pins,
            ))
        }

        fun verified() = D101VerifiedHostOriginals(originals + mapOf("trustManifest" to manifestWire))
        fun pins() = D101DeploymentTrustPins(f.operation, intent.sha256, D101Fixture.hash(manifestWire),
            URI("http://deployer:8080"), "rfc8032-fixture", f.publicDer, D101Fixture.hash(f.publicDer),
            f.publicDer, D101Fixture.hash(f.publicDer), "e".repeat(64))
        fun consumer(withNative: Boolean = false, producer: D101FixedProducerIdentity? = D101FixedProducerIdentity(pins())): D101InstalledSemanticChecks {
            val source = if (!withNative) null else D101NativeHostTrustSource(D101NativeHostReader { action ->
                val install = D101Fixture.hash(originals.getValue("readerBindings"))
                if (action == "read-originals") {
                    originalReads++
                    val current = originals.toMutableMap()
                    if (driftAfterNative && originalReads > 1) current["reviewBasis"] = "drift".toByteArray()
                    f.mapper.writeValueAsBytes(mapOf("schemaVersion" to 1, "installationSha256" to install,
                        "manifestEnvelopeBase64url" to D101Fixture.b64("synthetic".toByteArray()), "clockEnvelopeBase64url" to D101Fixture.b64("synthetic".toByteArray()),
                        "originals" to current.mapValues { D101Fixture.b64(it.value) }))
                } else if (action == "read-command-originals") f.mapper.writeValueAsBytes(mapOf("schemaVersion" to 1, "installationSha256" to install,
                    "commandPlanSha256" to D101Fixture.hash(originals.getValue("commandPlan")), "originals" to native.mapValues { D101Fixture.b64(it.value) }))
                else error("unapproved synthetic action")
            }, D101Fixture.hash(originals.getValue("readerBindings")), f.mapper)
            return D101InstalledSemanticChecks(verified(), pins(), f.mapper, source, producer, f.clock)
        }
        fun checks() = consumer().fixedChecks()
    }

    companion object {
        private val STAGES = listOf(
            "verify-current-authority-dispatch-freeze-space", "pull-pinned-candidate", "journal-before-env-down",
            "down-original-stack-once", "candidate-postgres-redis", "seed-only-child-exit-zero",
            "independent-both-db-numeric-50", "immutable-promotion-proof", "live-api-engine-web", "actual-runtime-observation",
        )
        private val REFERENCES = listOf("configInventory", "commandPlan", "recoveryPlan", "readerBindings", "evidenceCatalog", "reviewBasis")
        private val RECEIPTS = listOf("approvalReceipt", "combinedCiReceipt", "selectedSourceReceipt", "isolatedSeedTickReceipt", "spaceInventoryReceipt")
    }
}
