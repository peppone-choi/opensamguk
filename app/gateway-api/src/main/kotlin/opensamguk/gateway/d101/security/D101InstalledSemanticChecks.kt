package opensamguk.gateway.d101.security

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import opensamguk.gateway.d101.domain.*
import opensamguk.gateway.d101.infra.D101NativeHostTrustSource
import java.math.BigInteger
import java.time.Clock
import java.nio.file.Path
import java.time.Instant
import java.util.Collections

/** Fixed partial consumer of the originals already authenticated by the host
 * authority. This does not authenticate a host, issue approval, or install a
 * production provider. Unresolved producer/custody consumers deliberately deny.
 * No caller registry, Boolean callback, environment switch or success default.
 * Receipt5 -> intent; intent/reference6 -> card; intent/card/provenance ->
 * signed manifest. References point to already frozen originals. Scope is
 * checked independently; this consumer never requires descendant hashes in
 * an upstream leaf. Missing leaf schemas remain closed, not inferred here.
 */
internal class D101InstalledSemanticChecks(
    originals: D101VerifiedHostOriginals,
    private val pins: D101DeploymentTrustPins,
    mapper: ObjectMapper = ObjectMapper(),
    private val fixedCommandSource: D101NativeHostTrustSource? = null,
    private val fixedSelectedProducerIdentity: D101FixedProducerIdentity? = null,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val json = D101StrictJson(mapper)
    private val codec = D101ApprovalIntentCodec(json)
    private val frozen = D101ApprovedPurposeAuthority.ORIGINAL_IDS.associateWith { id ->
        originals.original(id).also { if (it.isEmpty() || it.size > 64 * 1024) unavailable() }
    }
    private val manifestWire = originals.original("trustManifest")
    private val readerBindingsCheck by lazy {
        D101ReaderBindingsSemanticCheck(originals, pins, fixedCommandSource, mapper, clock)
    }

    /** Exact registry shape is necessary but never sufficient for readiness. */
    fun fixedChecks(): Map<String, D101HostOriginalSemanticCheck> = Collections.unmodifiableMap(
        D101ApprovedPurposeAuthority.ORIGINAL_IDS.associateWith { id ->
            D101HostOriginalSemanticCheck { original, intent ->
                try {
                    if (!original.contentEquals(frozen.getValue(id))) unavailable()
                    when (id) {
                        "approvalIntent" -> verifyIntent(original, intent)
                        "deploymentCard" -> verifyCard(original, intent)
                        "commandPlan" -> verifyNativeCommandPlan(original, intent)
                        "readerBindings" -> {
                            verifyCard(frozen.getValue("deploymentCard"), intent)
                            readerBindingsCheck.verify(original, intent)
                        }
                        else -> unavailable()
                    }
                } catch (_: Exception) {
                    unavailable()
                }
            }
        },
    )

    private fun verifyIntent(original: ByteArray, supplied: D101ApprovalIntent) {
        val decoded = codec.decode(original, pins.approvalIntentSha256)
        requireSameIntent(decoded, supplied)
        manifest(decoded)
        val receipts = mapOf(
            "approvalReceipt" to decoded.approvalReceiptSha256,
            "combinedCiReceipt" to decoded.combinedCiReceiptSha256,
            "selectedSourceReceipt" to decoded.selectedSourceReceiptSha256,
            "isolatedSeedTickReceipt" to decoded.isolatedSeedTickReceiptSha256,
            "spaceInventoryReceipt" to decoded.spaceInventoryReceiptSha256,
        )
        for ((id, expected) in receipts) {
            if (D101StrictJson.hash(frozen.getValue(id)) != expected) unavailable()
        }
        // Receipt hashes establish byte binding only. Each receipt's separate
        // fixed validator must still prove its actual producer and semantics.
    }

    private fun verifyCard(original: ByteArray, supplied: D101ApprovalIntent) {
        val intent = codec.decode(frozen.getValue("approvalIntent"), pins.approvalIntentSha256)
        requireSameIntent(intent, supplied)
        val manifest = manifest(intent)
        if (D101StrictJson.hash(original) != json.sha(manifest["deploymentCardSha256"])) unavailable()
        val card = json.objectBytes(original, CARD_KEYS, 32 * 1024)
        if (json.positiveLong(card["schemaVersion"]) != 1L ||
            json.text(card["kind"]) != "D101_PEP_EXECUTION_CARD" ||
            json.text(card["operationId"]) != pins.operationId ||
            json.sha(card["approvalIntentSha256"]) != intent.sha256 ||
            json.text(card["appSourceSha"]) != intent.appSourceSha ||
            json.text(card["dockerSourceSha"]) != json.text(manifest["dockerSourceSha"]) ||
            json.stringMap(card["oldImageDigests"], D101ApprovalIntentCodec.FIVE_IMAGES, true) != intent.oldImageDigests ||
            json.stringMap(card["newImageDigests"], D101ApprovalIntentCodec.FIVE_IMAGES, true) != intent.newImageDigests) unavailable()
        for (id in CARD_REFERENCES) {
            if (json.sha(card[id + "Sha256"]) != D101StrictJson.hash(frozen.getValue(id))) unavailable()
        }
    }

    /** Root a4b8544 command15/resource9 scope only. This alone never
     * authenticates the native supporting originals or approves the row. */
    internal fun validateCommandPlanScope(original: ByteArray, supplied: D101ApprovalIntent) {
        try {
            if (!original.contentEquals(frozen.getValue("commandPlan"))) unavailable()
            val intent = codec.decode(frozen.getValue("approvalIntent"), pins.approvalIntentSha256)
            requireSameIntent(intent, supplied)
            verifyCard(frozen.getValue("deploymentCard"), intent)
            val node = json.objectBytes(original, COMMAND_KEYS, 32 * 1024)
            if (json.positiveLong(node["schemaVersion"]) != 1L ||
                json.text(node["kind"]) != "D101_ROOT_CANDIDATE_COMMAND_PLAN_V1" ||
                json.text(node["operationId"]) != intent.operationId ||
                json.sha(node["approvalIntentSha256"]) != intent.sha256 ||
                json.sha(node["targetFingerprint"]) != intent.targetFingerprint ||
                json.text(node["appSourceSha"]) != intent.appSourceSha ||
                json.text(node["dockerSourceSha"]) != json.text(manifest(intent)["dockerSourceSha"]) ||
                json.stringMap(node["newImageDigests"], D101ApprovalIntentCodec.FIVE_IMAGES, true) != intent.newImageDigests ||
                json.sha(node["selectedSourceReceiptSha256"]) != intent.selectedSourceReceiptSha256 ||
                json.text(node["seedEntrypoint"]) != "opensamguk.engine.boot.D101SeedOnlyCli" ||
                json.positiveLong(node["destructiveCutoffUnix"]) != intent.destructiveCutoffUnix) unavailable()
            // These are only labels until separately verified actual originals
            // and native custody are supplied. They never become success facts.
            json.sha(node["selectedEnvelopeSha256"])
            json.sha(node["capsReaderSha256"])
            val stages = node["stages"]
            if (!stages.isArray || stages.map { json.text(it) } != COMMAND_STAGES) unavailable()
            commandResources(node["resources"], intent.operationId)
        } catch (_: Exception) {
            unavailable()
        }
    }

    private fun commandResources(node: JsonNode, operationId: String) {
        json.requireKeys(node, RESOURCE_KEYS)
        val prefix = "d101-candidate-" + operationId
        val fixed = mapOf("project" to prefix, "network" to prefix + "-net",
            "postgresVolume" to prefix + "-pgdata", "redisVolume" to prefix + "-redisdata")
        if (fixed.any { (key, value) -> json.text(node[key]) != value }) unavailable()
        if (cleanAbsolute(json.text(node["candidateComposeFile"])) == cleanAbsolute(json.text(node["liveComposeFile"]))) unavailable()
        val caps = cleanAbsolute(json.text(node["capsReaderFile"]))
        if (Path.of(caps).fileName.toString() != operationId + ".json") unavailable()
        json.sha(node["candidateComposeSha256"])
        json.sha(node["liveComposeSha256"])
    }

    private fun cleanAbsolute(value: String): String {
        val path = Path.of(value)
        if (!path.isAbsolute || path.normalize().toString() != value) unavailable()
        return value
    }

    private fun verifyNativeCommandPlan(original: ByteArray, intent: D101ApprovalIntent) {
        validateCommandPlanScope(original, intent)
        val source = fixedCommandSource ?: unavailable()
        val producer = fixedSelectedProducerIdentity ?: unavailable()
        if (producer.producerIdentity != pins.keyId || producer.publicKeySpkiSha256 != pins.purposeSpkiSha256 ||
            !producer.publicKeySpki().contentEquals(pins.purposeSpki())) unavailable()
        // Same pinned installation and exact immutable14, before/after raw4.
        fun sameInstallation() {
            val current = source.readOriginals().originals()
            if (current.keys != frozen.keys || current.any { (id, wire) -> !wire.contentEquals(frozen.getValue(id)) }) unavailable()
        }
        sameInstallation()
        val raw = source.readCommandOriginals(D101StrictJson.hash(original)).mapValues { it.value.copyOf() }
        if (raw.keys != setOf("capsReaderOriginal", "selectedEnvelope", "candidateCompose", "liveCompose")) unavailable()
        val plan = json.objectBytes(original, COMMAND_KEYS, 32 * 1024)
        val resources = plan["resources"]
        val hashes = mapOf("capsReaderOriginal" to json.sha(plan["capsReaderSha256"]),
            "selectedEnvelope" to json.sha(plan["selectedEnvelopeSha256"]),
            "candidateCompose" to json.sha(resources["candidateComposeSha256"]), "liveCompose" to json.sha(resources["liveComposeSha256"]))
        if (raw.any { (id, wire) -> D101StrictJson.hash(wire) != hashes.getValue(id) }) unavailable()
        verifyCapsReader(raw.getValue("capsReaderOriginal"), intent, json.text(resources["network"]))
        verifySelectedEnvelope(raw.getValue("selectedEnvelope"), intent, producer)
        val expected = composeOriginals(intent)
        for (id in listOf("candidateCompose", "liveCompose")) {
            val wire = raw.getValue(id)
            json.objectBytes(wire, if (id == "candidateCompose") setOf("name", "services", "volumes", "networks") else setOf("services", "networks"), 32 * 1024)
            if (!wire.contentEquals(expected.getValue(id))) unavailable()
        }
        sameInstallation()
        // The selectedSourceReceipt row still must independently prove raw5,
        // topology and actual importer facts. Native transport cannot do that.
    }

    private fun verifyCapsReader(wire: ByteArray, intent: D101ApprovalIntent, network: String) {
        val node = json.objectBytes(wire, CAPS_KEYS, 16 * 1024)
        if (json.positiveLong(node["schemaVersion"]) != 1L || json.text(node["kind"]) != "D101_CANDIDATE_DB_READER_V1" ||
            json.text(node["operationId"]) != intent.operationId || json.sha(node["approvalIntentSha256"]) != intent.sha256 ||
            json.sha(node["targetFingerprint"]) != intent.targetFingerprint || json.text(node["appSourceSha"]) != intent.appSourceSha ||
            json.stringMap(node["imagePins"], D101ApprovalIntentCodec.FIVE_IMAGES, true) != intent.newImageDigests || json.text(node["network"]) != network ||
            !DB_IDENTIFIER.matches(json.text(node["databaseName"])) || !DB_IDENTIFIER.matches(json.text(node["databaseUser"]))) unavailable()
        cleanAbsolute(json.text(node["localPassFile"]))
        val host = cleanAbsolute(json.text(node["hostPassFile"]))
        if (host.any { it == ',' || it == '\r' || it == '\n' }) unavailable()
        json.sha(node["passFileSha256"])
        // Logical custody references only; this consumer never reads secrets.
    }

    private fun verifySelectedEnvelope(wire: ByteArray, intent: D101ApprovalIntent, producer: D101FixedProducerIdentity) {
        val envelope = json.objectBytes(wire, setOf("schemaVersion", "originalBytesBase64url", "signatureBase64url"), 96 * 1024)
        if (json.positiveLong(envelope["schemaVersion"]) != 1L) unavailable()
        val receipt = json.base64url(json.text(envelope["originalBytesBase64url"]), 64 * 1024)
        val signature = json.base64url(json.text(envelope["signatureBase64url"]), 64)
        val key = D101Ed25519.decodePublicKey(producer.publicKeySpki(), producer.publicKeySpkiSha256)
        if (signature.size != 64 || !D101Ed25519.verify(key, SELECTED_DOMAIN.toByteArray(Charsets.US_ASCII) + receipt, signature) ||
            !receipt.contentEquals(frozen.getValue("selectedSourceReceipt")) || D101StrictJson.hash(receipt) != intent.selectedSourceReceiptSha256) unavailable()
        val node = json.objectBytes(receipt, SELECTED_KEYS, 64 * 1024)
        if (json.positiveLong(node["schemaVersion"]) != 1L || json.text(node["kind"]) != "D101_FINAL_SELECTED_SOURCE_V1" ||
            json.text(node["selectionStatus"]) != "FINAL_SELECTED" || json.text(node["originalOp"]) != intent.operationId ||
            json.sha(node["typedTargetFingerprint"]) != intent.targetFingerprint || json.text(node["appSourceSha"]) != intent.appSourceSha ||
            json.stringMap(node["imagePins"], D101ApprovalIntentCodec.FIVE_IMAGES, true) != intent.newImageDigests ||
            json.text(node["trustedProducerIdentity"]) != producer.producerIdentity || json.text(node["scenarioOrigin"]) !in setOf("CLASSPATH", "EXTERNAL")) unavailable()
        for (id in listOf("scenarioLogicalId", "classpathLogicalId", "artifactSetId", "variant", "topologyRevision")) json.text(node[id])
        for (id in listOf("topologyContentHash", "topologyContentHashProvenanceSha256", "configurationSha256", "parserBytecodeSha256", "resolverDecisionReceiptSha256")) json.sha(node[id])
        val options = node["effectiveOptions"]
        if (!options.isObject) unavailable()
        val names = options.fieldNames().asSequence().toSet()
        if (!names.containsAll(SEED_OPTIONS) || !(SEED_OPTIONS + "RESET_FICTION").containsAll(names)) unavailable()
        val effective = json.stringMap(options, names, false)
        if (effective.any { (id, value) -> intent.target.updates[id] != value } || effective["SERVER_GENERATION"] != "0") unavailable()
        val provenance = json.stringMap(node["optionProvenance"], names, false)
        if (provenance.values.any { !D101StrictJson.SHA.matches(it) }) unavailable()
        val originals = node["originalPins"]
        json.requireKeys(originals, SELECTED_ORIGINAL_IDS)
        for (id in SELECTED_ORIGINAL_IDS) {
            val pin = originals[id]
            json.requireKeys(pin, setOf("logicalArtifactId", "rawSha256", "byteLength"))
            val length = json.unsigned(pin["byteLength"])
            if (json.text(pin["logicalArtifactId"]) != id || length == BigInteger.ZERO || length > BigInteger.valueOf(64L * 1024 * 1024)) unavailable()
            json.sha(pin["rawSha256"])
        }
        val captured = json.text(node["capturedAtUtc"])
        if (!captured.matches(UTC) || Instant.parse(captured) > clock.instant()) unavailable()
    }

    /** Exact sorted ASCII JSON emitted by the reviewed Go map producer. */
    internal fun composeOriginals(intent: D101ApprovalIntent): Map<String, ByteArray> {
        val prefix = "d101-candidate-" + intent.operationId
        val postgres = mapOf("image" to "postgres@" + intent.newImageDigests.getValue("game-postgres"), "container_name" to "spep-game-postgres", "restart" to "no",
            "environment" to mapOf("POSTGRES_DB" to "${'$'}{GAME_POSTGRES_DB:?required}", "POSTGRES_USER" to "${'$'}{GAME_POSTGRES_USER:?required}", "POSTGRES_PASSWORD" to "${'$'}{GAME_POSTGRES_PASSWORD:?required}"),
            "volumes" to listOf("candidate-pgdata:/var/lib/postgresql/data"), "networks" to listOf("candidate"),
            "healthcheck" to mapOf("test" to listOf("CMD-SHELL", "pg_isready -U ${'$'}{GAME_POSTGRES_USER:?required}"), "interval" to "2s", "timeout" to "2s", "retries" to 10))
        val redis = mapOf("image" to "redis@" + intent.newImageDigests.getValue("game-redis"), "container_name" to "spep-game-redis", "restart" to "no",
            "command" to listOf("redis-server", "--appendonly", "yes", "--maxmemory", "256mb", "--maxmemory-policy", "allkeys-lru"),
            "volumes" to listOf("candidate-redisdata:/data"), "networks" to listOf("candidate"),
            "healthcheck" to mapOf("test" to listOf("CMD", "redis-cli", "ping"), "interval" to "2s", "timeout" to "2s", "retries" to 10))
        val candidate = mapOf("name" to prefix, "services" to mapOf("game-postgres" to postgres, "game-redis" to redis),
            "volumes" to mapOf("candidate-pgdata" to mapOf("name" to prefix + "-pgdata", "driver" to "local"), "candidate-redisdata" to mapOf("name" to prefix + "-redisdata", "driver" to "local")),
            "networks" to mapOf("candidate" to mapOf("name" to prefix + "-net", "internal" to true, "driver" to "bridge")))
        val apps = D101ApprovalIntentCodec.APP_IMAGES.associateWith { mapOf("networks" to listOf("opensamguk-net", "d101-candidate")) }
        val live = mapOf("services" to apps, "networks" to mapOf("d101-candidate" to mapOf("external" to true, "name" to prefix + "-net")))
        val producer = ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        return mapOf("candidateCompose" to producer.writeValueAsBytes(candidate), "liveCompose" to producer.writeValueAsBytes(live))
    }

    private fun manifest(intent: D101ApprovalIntent): JsonNode {
        if (D101StrictJson.hash(manifestWire) != pins.manifestSha256 ||
            intent.operationId != pins.operationId) unavailable()
        val node = json.objectBytes(manifestWire, MANIFEST_KEYS, 32 * 1024)
        if (json.positiveLong(node["schemaVersion"]) != 1L ||
            json.text(node["kind"]) != "D101_HOST_TRUST_V1" ||
            json.text(node["operationId"]) != intent.operationId ||
            json.sha(node["approvalIntentSha256"]) != intent.sha256 ||
            json.text(node["appSourceSha"]) != intent.appSourceSha ||
            !D101StrictJson.SHA40.matches(json.text(node["dockerSourceSha"])) ||
            json.text(node["rootPrivateOrigin"]) != pins.fixedPrivateOrigin.toString() ||
            json.text(node["keyId"]) != pins.keyId ||
            json.sha(node["publicKeySpkiSha256"]) != pins.purposeSpkiSha256 ||
            json.sha(node["signingKeyEnvelopeSha256"]) != pins.signingKeyEnvelopeSha256 ||
            json.sha(node["approvedReceiptProvenanceSha256"]) != D101StrictJson.hash(frozen.getValue("approvedReceiptProvenance")) ||
            json.stringMap(node["oldImageDigests"], D101ApprovalIntentCodec.FIVE_IMAGES, true) != intent.oldImageDigests ||
            json.stringMap(node["newImageDigests"], D101ApprovalIntentCodec.FIVE_IMAGES, true) != intent.newImageDigests) unavailable()
        return node
    }

    private fun requireSameIntent(actual: D101ApprovalIntent, supplied: D101ApprovalIntent) {
        if (actual.sha256 != supplied.sha256 || actual.operationId != supplied.operationId ||
            actual.targetFingerprint != supplied.targetFingerprint || actual.appSourceSha != supplied.appSourceSha ||
            actual.initialPublicRevision != supplied.initialPublicRevision ||
            actual.windowOpensAtUnix != supplied.windowOpensAtUnix ||
            actual.destructiveCutoffUnix != supplied.destructiveCutoffUnix || actual.recoveryDeadlineUnix != supplied.recoveryDeadlineUnix ||
            actual.approvalReceiptSha256 != supplied.approvalReceiptSha256 ||
            actual.combinedCiReceiptSha256 != supplied.combinedCiReceiptSha256 ||
            actual.selectedSourceReceiptSha256 != supplied.selectedSourceReceiptSha256 ||
            actual.isolatedSeedTickReceiptSha256 != supplied.isolatedSeedTickReceiptSha256 ||
            actual.spaceInventoryReceiptSha256 != supplied.spaceInventoryReceiptSha256 ||
            actual.spaceBudget != supplied.spaceBudget || actual.oldImageDigests != supplied.oldImageDigests ||
            actual.newImageDigests != supplied.newImageDigests ||
            !actual.target.originalBytes().contentEquals(supplied.target.originalBytes()) ||
            actual.target.imageDigests != supplied.target.imageDigests ||
            actual.target.storageImageDigests != supplied.target.storageImageDigests ||
            actual.target.updates != supplied.target.updates) unavailable()
    }

    private fun unavailable(): Nothing = throw D101PurposeAuthorityUnavailable()

    companion object {
        private val DB_IDENTIFIER = Regex("[a-z][a-z0-9_]{0,62}")
        private val UTC = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]{1,9})?Z")
        private const val SELECTED_DOMAIN = "OPENSAMGUK-D101-SELECTED-SOURCE-V1\n"
        private val CAPS_KEYS = setOf("schemaVersion", "kind", "operationId", "approvalIntentSha256", "targetFingerprint", "appSourceSha",
            "imagePins", "network", "databaseName", "databaseUser", "localPassFile", "hostPassFile", "passFileSha256")
        private val SELECTED_KEYS = setOf("schemaVersion", "kind", "selectionStatus", "originalOp", "typedTargetFingerprint", "appSourceSha", "imagePins",
            "scenarioOrigin", "scenarioLogicalId", "classpathLogicalId", "originalPins", "artifactSetId", "variant", "topologyRevision", "topologyContentHash",
            "topologyContentHashProvenanceSha256", "effectiveOptions", "optionProvenance", "configurationSha256", "parserBytecodeSha256",
            "resolverDecisionReceiptSha256", "capturedAtUtc", "trustedProducerIdentity")
        private val SELECTED_ORIGINAL_IDS = setOf("tiles.json", "world.json", "roads.json", "selected-scenario.json", "classpath-scenario.json")
        private val SEED_OPTIONS = setOf("SERVER_NAME", "SERVER_GENERATION", "SCENARIO_CODE", "SCENARIO_SEED_ENABLED", "SCENARIO_LOOKUP_DIR",
            "RESET_MAXGENERAL", "RESET_FIRST_TURN", "RESET_EXTEND", "RESET_TURNTERM", "RESET_BLOCK_GENERAL_CREATE", "RESET_NPCMODE", "RESET_SHOW_IMG_LEVEL")
        private val COMMAND_KEYS = setOf(
            "schemaVersion", "kind", "operationId", "approvalIntentSha256", "targetFingerprint", "appSourceSha",
            "dockerSourceSha", "newImageDigests", "selectedSourceReceiptSha256", "selectedEnvelopeSha256",
            "capsReaderSha256", "seedEntrypoint", "stages", "destructiveCutoffUnix", "resources",
        )
        private val RESOURCE_KEYS = setOf(
            "project", "network", "postgresVolume", "redisVolume", "candidateComposeFile",
            "candidateComposeSha256", "liveComposeFile", "liveComposeSha256", "capsReaderFile",
        )
        private val COMMAND_STAGES = listOf(
            "verify-current-authority-dispatch-freeze-space", "pull-pinned-candidate", "journal-before-env-down",
            "down-original-stack-once", "candidate-postgres-redis", "seed-only-child-exit-zero",
            "independent-both-db-numeric-50", "immutable-promotion-proof", "live-api-engine-web", "actual-runtime-observation",
        )
        private val CARD_REFERENCES = setOf(
            "configInventory", "commandPlan", "recoveryPlan", "readerBindings", "evidenceCatalog", "reviewBasis",
        )
        private val CARD_KEYS = setOf(
            "schemaVersion", "kind", "operationId", "approvalIntentSha256", "appSourceSha", "dockerSourceSha",
            "oldImageDigests", "newImageDigests", "configInventorySha256", "commandPlanSha256", "recoveryPlanSha256",
            "readerBindingsSha256", "evidenceCatalogSha256", "reviewBasisSha256",
        )
        private val MANIFEST_KEYS = setOf(
            "schemaVersion", "kind", "operationId", "approvalIntentSha256", "deploymentCardSha256",
            "approvedReceiptProvenanceSha256", "keyId", "publicKeySpkiSha256", "signingKeyEnvelopeSha256",
            "rootPrivateOrigin", "appSourceSha", "dockerSourceSha", "oldImageDigests", "newImageDigests",
        )
    }
}
