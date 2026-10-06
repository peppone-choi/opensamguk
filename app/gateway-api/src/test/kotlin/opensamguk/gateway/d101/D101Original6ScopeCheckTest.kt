package opensamguk.gateway.d101

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import opensamguk.gateway.d101.domain.*
import opensamguk.gateway.d101.security.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigInteger
import java.net.URI

/** Synthetic C9 metadata. No issuer, GitHub, review or native installation evidence. */
class D101Original6ScopeCheckTest {
    @Test
    fun `four exact metadata contracts accept only as subchecks and registry still denies`() {
        val p = Packet()
        for (id in FOUR) p.validate(id, p.candidate(id))
        val checks = p.registry()
        checks.getValue("approvalIntent").verify(p.originals.getValue("approvalIntent"), p.intent)
        checks.getValue("deploymentCard").verify(p.originals.getValue("deploymentCard"), p.intent)
        for (id in FOUR + listOf("spaceInventoryReceipt", "approvedReceiptProvenance")) {
            assertThrows<D101PurposeAuthorityUnavailable>(id) { checks.getValue(id).verify(p.originals.getValue(id), p.intent) }
        }
        assertThrows<D101PurposeAuthorityUnavailable> {
            D101HostSemanticVerifier(p.f.codec, p.intent.sha256, checks).verifyOriginals(p.verified)
        }
    }

    @Test
    fun `scope rejects raw target drift even when fingerprint label remains unchanged`() {
        val p = Packet()
        for (change in listOf<(ObjectNode) -> Unit>(
            { (it["typedTargetRef"] as ObjectNode).put("sha256", "f".repeat(64)) },
            { (it["typedTargetRef"] as ObjectNode).put("byteLength", p.intent.target.originalBytes().size + 1) },
            { it.put("targetFingerprint", "e".repeat(64)) },
        )) {
            val n = p.candidate("approvalReceipt")
            change(n["scope"] as ObjectNode)
            assertThrows<D101RequestInvalid> { p.validate("approvalReceipt", n) }
        }
    }

    @Test
    fun `every scope member binds the existing typed intent and signed Docker source`() {
        val p = Packet()
        val changes = listOf<(ObjectNode) -> Unit>(
            { it.put("operationId", "f".repeat(32)) }, { it.put("serverId", "other") }, { it.put("worldId", 2) },
            { it.put("appSourceSha", "f".repeat(40)) }, { it.put("dockerSourceSha", "e".repeat(40)) },
            { (it["oldImageDigests"] as ObjectNode).put("game-api", "sha256:" + "e".repeat(64)) },
            { (it["newImageDigests"] as ObjectNode).put("game-redis", "sha256:" + "e".repeat(64)) },
            { it.put("initialPublicRevision", "2") },
            { (it["window"] as ObjectNode).put("windowOpensAtUnix", p.intent.windowOpensAtUnix + 1) },
            { (it["window"] as ObjectNode).put("destructiveCutoffUnix", p.intent.destructiveCutoffUnix + 1) },
            { (it["window"] as ObjectNode).put("recoveryDeadlineUnix", p.intent.recoveryDeadlineUnix + 1) },
            { (it["spaceBudget"] as ObjectNode).put("RecoveryBytes", 1) },
        )
        for ((i, change) in changes.withIndex()) {
            val n = p.candidate("reviewBasis")
            change(n["scope"] as ObjectNode)
            assertThrows<D101RequestInvalid>("scope $i") { p.validate("reviewBasis", n) }
        }
    }

    @Test
    fun `integral budget comparison retains precision above double range`() {
        val large = BigInteger.ONE.shiftLeft(53).add(BigInteger.ONE)
        val p = Packet(candidateBytes = large)
        p.validate("approvalReceipt", p.candidate("approvalReceipt"))
        val n = p.candidate("approvalReceipt")
        ((n["scope"]["spaceBudget"]) as ObjectNode).put("CandidateUnpackedBytes", large.subtract(BigInteger.ONE))
        assertThrows<D101RequestInvalid> { p.validate("approvalReceipt", n) }
    }

    @Test
    fun `unknown null boolean float duplicate UTF8 and bound violations reject`() {
        val p = Packet()
        for (id in FOUR) {
            val mutations = listOf<(ObjectNode) -> Unit>(
                { it.put("extra", true) }, { it.putNull("scope") }, { it.put("schemaVersion", true) },
                { it.put("schemaVersion", 1.0) }, { it.put("kind", "OTHER") }, { it.put("sourceAuthority", "SELF_ASSERTED") },
                { (it["scope"] as ObjectNode).put("worldId", 1.0) },
                { (it["scope"]["typedTargetRef"] as ObjectNode).put("byteLength", 67108865) },
                { (it["scope"]["typedTargetRef"] as ObjectNode).put("logicalId", "/operating/path") },
                { (it["scope"]["spaceBudget"] as ObjectNode).put("BackupBytes", D101StrictJson.UINT64_MAX.add(BigInteger.ONE)) },
            )
            for (change in mutations) {
                val n = p.candidate(id); change(n)
                assertThrows<D101RequestInvalid>(id) { p.validate(id, n) }
            }
            val wire = p.f.mapper.writeValueAsBytes(p.candidate(id))
            val bad = listOf(wire.toString(Charsets.UTF_8).replaceFirst("{", "{\"schemaVersion\":1,").toByteArray(),
                wire + " {}".toByteArray(), byteArrayOf(0xc3.toByte(), 0x28),
                byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + wire, ByteArray(65537))
            for (bytes in bad) assertThrows<D101RequestInvalid> { p.validateWire(id, bytes) }
        }
    }

    @Test
    fun `approval rejects contradictory slice parents ranges and caller issuer labels`() {
        val p = Packet()
        val changes = listOf<(ObjectNode) -> Unit>(
            { (it["decisionSlices"][0] as ObjectNode).put("parentLogicalId", "raw:absent") },
            { (it["decisionSlices"][0] as ObjectNode).put("endByte", 100) },
            { (it["decisionSlices"][0] as ObjectNode).put("byteLength", 1) },
            { (it["decisionSlices"][1] as ObjectNode).put("decisionId", "decision-0") },
            { (it["issuer"] as ObjectNode).put("identitySource", "CALLER") },
            { (it["issuer"] as ObjectNode).put("role", "HOST_SPACE_ISSUER") },
            { (it["issuer"] as ObjectNode).put("signatureVerified", true) },
            { (it["originalScopeRef"] as ObjectNode).put("logicalId", "approvalIntent") },
        )
        for (change in changes) { val n = p.candidate("approvalReceipt"); change(n)
            assertThrows<D101RequestInvalid> { p.validate("approvalReceipt", n) } }
    }

    @Test
    fun `CI refuses old attempt source partial checks skipped tests and storage build claims`() {
        val p = Packet()
        val changes = listOf<(ObjectNode) -> Unit>(
            { (it["runs"][0]["requiredChecks"][0] as ObjectNode).put("attempt", 2) },
            { (it["runs"][0]["artifacts"][0] as ObjectNode).put("attempt", 2) },
            { (it["runs"][0]["artifacts"][0] as ObjectNode).put("artifactName", "xml-attempt-2") },
            { (it["runs"][0]["artifacts"][0] as ObjectNode).put("headSourceSha", "f".repeat(40)) },
            { (it["runs"][0]["artifacts"][0] as ObjectNode).put("skipped", 1) },
            { (it["runs"][0]["requiredChecks"] as ArrayNode).remove(0); Unit },
            { (it["runs"][0]["requiredChecks"][0] as ObjectNode).put("conclusion", "cancelled") },
            { (it["imageBuilds"][3] as ObjectNode).put("sourceRelation", "BUILT_FROM_SOURCE") },
        )
        for ((i, change) in changes.withIndex()) { val n = p.candidate("combinedCiReceipt"); change(n)
            assertThrows<D101RequestInvalid>("CI metadata $i") { p.validate("combinedCiReceipt", n) } }
    }

    @Test
    fun `catalog denies rebinding original bytes and self descendant inventory`() {
        val p = Packet()
        for (id in listOf("evidenceCatalog", "reviewBasis", "deploymentCard", "approvedReceiptProvenance")) {
            val n = p.candidate("evidenceCatalog")
            (n["entries"][0]["originalRef"] as ObjectNode).put("logicalId", id)
            assertThrows<D101RequestInvalid> { p.validate("evidenceCatalog", n) }
            val m = p.candidate("evidenceCatalog")
            (m["entries"][0]["verificationOriginalRefs"][0] as ObjectNode).put("logicalId", id)
            assertThrows<D101RequestInvalid> { p.validate("evidenceCatalog", m) }
        }
        for (key in listOf("sha256", "byteLength")) {
            val n = p.candidate("evidenceCatalog")
            val ref = n["entries"][0]["originalRef"] as ObjectNode
            if (key == "sha256") ref.put(key, "f".repeat(64)) else ref.put(key, 99)
            assertThrows<D101RequestInvalid> { p.validate("evidenceCatalog", n) }
        }
    }

    @Test
    fun `review denies self author expired issuance old head changed files and original bindings`() {
        val p = Packet()
        val changes = listOf<(ObjectNode) -> Unit>(
            { it.put("authorSessionId", "implementation-session") },
            { it.put("issuedAtUnix", p.f.now + 10) }, { it.put("windowEndsAtUnix", p.f.now - 1) },
            { it.put("headSourceSha", "f".repeat(40)) },
            { (it["fileScopes"][0] as ObjectNode).put("prHeadSha", "f".repeat(40)) },
            { (it["fileScopes"][0]["paths"] as ArrayNode).set(0, p.f.mapper.nodeFactory.textNode("../foreign")); Unit },
            { (it["reviewedOriginals"]["configInventory"] as ObjectNode).put("sha256", "f".repeat(64)) },
            { (it["reviewedOriginals"] as ObjectNode).put("deploymentCard", "forged") },
        )
        for (change in changes) { val n = p.candidate("reviewBasis"); change(n["records"][0] as ObjectNode)
            assertThrows<D101RequestInvalid> { p.validate("reviewBasis", n) } }
        val preinstall = p.candidate("reviewBasis")
        (preinstall["records"][0] as ObjectNode).put("reviewClass", "D101_PREINSTALL_SCOPE")
        p.validate("reviewBasis", preinstall) // Still only metadata; registry always denies both classes.
    }

    private class Packet(candidateBytes: BigInteger = BigInteger.ZERO) {
        val f = D101Fixture()
        val docker = "c".repeat(40)
        val tree = f.intentTree().also { (it["spaceBudget"] as ObjectNode).put("CandidateUnpackedBytes", candidateBytes) }
        var intent = f.intent(tree)
        val originals = D101ApprovedPurposeAuthority.ORIGINAL_IDS.associateWith {
            f.mapper.writeValueAsBytes(mapOf("syntheticUnproduced" to it)) }.toMutableMap()
        lateinit var verified: D101VerifiedHostOriginals
        lateinit var pins: D101DeploymentTrustPins

        fun obj(vararg pairs: Pair<String, Any>): ObjectNode = f.mapper.valueToTree(linkedMapOf(*pairs))
        fun ref(id: String, bytes: ByteArray = "{}".toByteArray(), media: String = "application/json") =
            obj("logicalId" to id, "sha256" to D101Fixture.hash(bytes), "byteLength" to bytes.size, "mediaType" to media)
        fun raw(id: String, media: String = "application/json") = ref("raw:$id", media = media)
        fun scope() = obj("operationId" to intent.operationId, "serverId" to "pep", "worldId" to 1,
            "targetFingerprint" to intent.targetFingerprint, "typedTargetRef" to ref("raw:target", intent.target.originalBytes()),
            "appSourceSha" to intent.appSourceSha, "dockerSourceSha" to docker, "oldImageDigests" to intent.oldImageDigests,
            "newImageDigests" to intent.newImageDigests, "initialPublicRevision" to intent.initialPublicRevision.toString(),
            "window" to obj("windowOpensAtUnix" to intent.windowOpensAtUnix, "destructiveCutoffUnix" to intent.destructiveCutoffUnix,
                "recoveryDeadlineUnix" to intent.recoveryDeadlineUnix), "spaceBudget" to tree["spaceBudget"])

        fun candidate(id: String): ObjectNode {
            val kind = mapOf("approvalReceipt" to "D101_APPROVAL_RECEIPT_V1", "combinedCiReceipt" to "D101_COMBINED_CI_RECEIPT_V1",
                "evidenceCatalog" to "D101_EVIDENCE_CATALOG_V1", "reviewBasis" to "D101_REVIEW_BASIS_V1").getValue(id)
            val n = obj("schemaVersion" to 1, "kind" to kind, "scope" to scope())
            when (id) {
                "approvalReceipt" -> {
                    n.put("sourceAuthority", "EXISTING_USER_DECISIONS_AND_FIXED_ISSUER"); n.put("documentCollectorId", "synthetic-collector")
                    n.set<JsonNode>("decisionParents", f.mapper.valueToTree((0..4).map { raw("parent-$it") }))
                    n.set<JsonNode>("decisionSlices", f.mapper.valueToTree((0..19).map { obj("decisionId" to "decision-$it",
                        "parentLogicalId" to "raw:parent-${it / 4}", "startByte" to 0, "endByte" to 2,
                        "startLine" to 1, "byteLength" to 2, "sha256" to D101Fixture.hash("{}".toByteArray())) }))
                    n.set<JsonNode>("originalScopeRef", raw("original-scope")); n.put("issuedAtUnix", f.now)
                    n.set<JsonNode>("issuer", obj("role" to "APPROVAL_ISSUER", "identitySource" to "FIXED_INSTALLED_ISSUER",
                        "issuerId" to "synthetic-issuer", "issuerSourceSha" to f.app, "publicKeySpkiSha256" to "a".repeat(64),
                        "scopeAttestationRef" to raw("attestation"), "nativeCustodyRef" to raw("custody")))
                }
                "combinedCiReceipt" -> {
                    n.put("sourceAuthority", "GITHUB_ACTIONS"); n.put("collectedAtUnix", f.now)
                    n.set<JsonNode>("runs", f.mapper.valueToTree(listOf("app", "docker").mapIndexed { i, project ->
                        val source = if (project == "app") f.app else docker
                        val r = obj("project" to project, "repository" to ("peppone-choi/opensamguk" + if (i == 1) "-docker" else ""),
                            "workflowId" to 1, "runId" to i + 1, "attempt" to 3, "event" to "pull_request", "sourceSha" to source,
                            "checkoutSha" to "f".repeat(40), "runRawRef" to raw("run-$i"), "checkoutEvidenceRef" to raw("checkout-$i"),
                            "protectionRawRef" to raw("protection-$i"), "executionInventoryRef" to raw("execution-$i"),
                            "completeExecutionEvidenceRef" to raw("complete-$i"))
                        r.set<JsonNode>("requiredChecks", f.mapper.valueToTree((0 until if (i == 0) 9 else 2).map { j ->
                            obj("name" to "check-$j", "checkRunId" to j + 1, "jobId" to j + 1, "runId" to i + 1,
                                "attempt" to 3, "status" to "completed", "conclusion" to "success", "checkRawRef" to raw("check-$i-$j"),
                                "jobRawRef" to raw("job-$i-$j")) }))
                        r.set<JsonNode>("artifacts", f.mapper.valueToTree(if (i == 1) emptyList() else listOf(obj("artifactId" to 10,
                            "runId" to 1, "attempt" to 3, "headSourceSha" to source, "jobId" to 1, "artifactName" to "xml-attempt-3",
                            "jobRawRef" to raw("job-0-0"), "artifactMetadataRef" to raw("artifact-meta"), "archiveRef" to raw("zip", "application/zip"),
                            "junitInventoryRef" to raw("junit"), "tests" to 1, "failures" to 0, "errors" to 0, "skipped" to 0))))
                        r }))
                    n.set<JsonNode>("imageBuilds", f.mapper.valueToTree(IMAGES.map { service ->
                        val app = service in D101ApprovalIntentCodec.APP_IMAGES
                        obj("service" to service, "sourceProject" to if (app) "app" else "docker", "sourceSha" to if (app) f.app else docker,
                            "imageDigest" to f.pins.getValue(service), "sourceRelation" to if (app) "BUILT_FROM_SOURCE" else "PINNED_UPSTREAM_IMAGE",
                            "buildProvenanceRef" to raw("image-$service")) }))
                }
                "evidenceCatalog" -> {
                    n.put("sourceAuthority", "UPSTREAM_RAW_INVENTORY"); n.put("inventoryAssemblerId", "synthetic-collector")
                    n.set<JsonNode>("entries", f.mapper.valueToTree(CATALOG.map { name -> obj("originalRef" to ref(name, originals.getValue(name)),
                        "producerClass" to "PRODUCT_SOURCE", "producerId" to "synthetic-source", "producerSourceRef" to raw("source"),
                        "observedAtUnix" to f.now, "scopeOriginalRef" to raw("scope"), "verificationOriginalRefs" to listOf(raw("verify"))) }))
                }
                "reviewBasis" -> {
                    n.put("sourceAuthority", "ACTUAL_INDEPENDENT_REVIEW_RECORDS")
                    n.set<JsonNode>("records", f.mapper.valueToTree(listOf(obj("backend" to "codex-independent", "reviewerId" to "synthetic-reviewer",
                        "authorSessionId" to "review-session", "implementerSessionIds" to listOf("implementation-session"),
                        "independenceEvidenceRef" to raw("separation"), "reviewClass" to "CODE_MERGE", "repository" to "peppone-choi/opensamguk",
                        "prNumber" to 1, "headSourceSha" to f.app, "verdictCommentId" to 1, "verdict" to "MERGEABLE", "verdictRawRef" to raw("verdict"),
                        "reviewRawRefs" to emptyList<String>(), "windowId" to "synthetic-window", "windowStartsAtUnix" to f.now - 10,
                        "windowEndsAtUnix" to f.now + 10, "issuedAtUnix" to f.now, "windowOriginalRef" to raw("window"),
                        "fileScopes" to listOf(obj("project" to "app", "sourceSha" to f.app, "prNumber" to 1, "prHeadSha" to f.app,
                            "baseSha" to "f".repeat(40), "paths" to listOf("synthetic.kt"), "filesRawRef" to raw("files"))),
                        "reviewedOriginals" to (listOf("configInventory", "commandPlan", "recoveryPlan", "readerBindings").associateWith {
                            ref(it, originals.getValue(it)) } + mapOf("preinstallEvidence" to raw("preinstall")))))))
                }
            }
            return n
        }
        fun validate(id: String, n: ObjectNode) = validateWire(id, f.mapper.writeValueAsBytes(n))
        fun validateWire(id: String, wire: ByteArray) = D101Original6HeaderChecks(f.json).verify(id, wire, intent, docker, originals)
        fun registry(): Map<String, D101HostOriginalSemanticCheck> {
            for (id in listOf("approvalReceipt", "combinedCiReceipt")) originals[id] = f.mapper.writeValueAsBytes(candidate(id))
            for (id in listOf("approvalReceipt", "combinedCiReceipt", "selectedSourceReceipt", "isolatedSeedTickReceipt", "spaceInventoryReceipt"))
                tree.put(id + "Sha256", D101Fixture.hash(originals.getValue(id)))
            originals["approvalIntent"] = f.mapper.writeValueAsBytes(tree); intent = f.intent(tree)
            for (id in listOf("evidenceCatalog", "reviewBasis")) originals[id] = f.mapper.writeValueAsBytes(candidate(id))
            val card = obj("schemaVersion" to 1, "kind" to "D101_PEP_EXECUTION_CARD", "operationId" to f.operation,
                "approvalIntentSha256" to intent.sha256, "appSourceSha" to f.app, "dockerSourceSha" to docker,
                "oldImageDigests" to f.pins, "newImageDigests" to f.pins)
            for (id in listOf("configInventory", "commandPlan", "recoveryPlan", "readerBindings", "evidenceCatalog", "reviewBasis"))
                card.put(id + "Sha256", D101Fixture.hash(originals.getValue(id)))
            originals["deploymentCard"] = f.mapper.writeValueAsBytes(card)
            val manifest = f.mapper.writeValueAsBytes(obj("schemaVersion" to 1, "kind" to "D101_HOST_TRUST_V1", "operationId" to f.operation,
                "approvalIntentSha256" to intent.sha256, "deploymentCardSha256" to D101Fixture.hash(originals.getValue("deploymentCard")),
                "approvedReceiptProvenanceSha256" to D101Fixture.hash(originals.getValue("approvedReceiptProvenance")),
                "keyId" to "rfc8032-fixture", "publicKeySpkiSha256" to D101Fixture.hash(f.publicDer), "signingKeyEnvelopeSha256" to "e".repeat(64),
                "rootPrivateOrigin" to "http://deployer:8080", "appSourceSha" to f.app, "dockerSourceSha" to docker,
                "oldImageDigests" to f.pins, "newImageDigests" to f.pins))
            verified = D101VerifiedHostOriginals(originals + mapOf("trustManifest" to manifest))
            pins = D101DeploymentTrustPins(f.operation, intent.sha256, D101Fixture.hash(manifest), URI("http://deployer:8080"),
                "rfc8032-fixture", f.publicDer, D101Fixture.hash(f.publicDer), f.publicDer, D101Fixture.hash(f.publicDer), "e".repeat(64))
            return D101InstalledSemanticChecks(verified, pins, f.mapper).fixedChecks()
        }
    }
    companion object {
        private val FOUR = listOf("approvalReceipt", "combinedCiReceipt", "evidenceCatalog", "reviewBasis")
        private val IMAGES = listOf("game-api", "game-engine", "web-game", "game-postgres", "game-redis")
        private val CATALOG = listOf("approvalReceipt", "combinedCiReceipt", "selectedSourceReceipt", "isolatedSeedTickReceipt", "spaceInventoryReceipt",
            "approvalIntent", "configInventory", "commandPlan", "recoveryPlan", "readerBindings")
    }
}
