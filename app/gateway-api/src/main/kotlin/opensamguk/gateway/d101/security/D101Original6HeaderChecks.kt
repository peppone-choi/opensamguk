package opensamguk.gateway.d101.security

import com.fasterxml.jackson.databind.JsonNode
import opensamguk.gateway.d101.domain.*

/** Fixed four C9 contracts: shape and metadata contradictions, never live authority.
 * Raw upstream signatures, official job/XML execution, decision byte slices and
 * actual review/window authenticity still need their fixed producer transports.
 * Callers MUST deny after this subcheck until those consumers are installed.
 */
internal class D101Original6HeaderChecks(private val json: D101StrictJson) {
    private val scope = D101Original6ScopeCheck(json)

    fun verify(id: String, wire: ByteArray, intent: D101ApprovalIntent, dockerSourceSha: String,
               originals: Map<String, ByteArray>) {
        val header = HEADERS[id] ?: json.invalid()
        val node = json.objectBytes(wire, header.keys, 64 * 1024)
        if (json.positiveLong(node["schemaVersion"]) != 1L || json.text(node["kind"]) != header.kind ||
            json.text(node["sourceAuthority"]) != header.authority) json.invalid()
        scope.verify(node["scope"], intent, dockerSourceSha)
        when (id) {
            "approvalReceipt" -> approval(node)
            "combinedCiReceipt" -> ci(node, intent, dockerSourceSha)
            "evidenceCatalog" -> catalog(node, originals)
            "reviewBasis" -> review(node, intent, dockerSourceSha, originals)
            else -> json.invalid()
        }
    }

    private fun approval(node: JsonNode) {
        identity(node["documentCollectorId"])
        json.positiveLong(node["issuedAtUnix"])
        val parents = array(node["decisionParents"], 5, 5).map { upstream(it) }
        unique(parents.map { it.logicalId })
        val byId = parents.associateBy { it.logicalId }
        val slices = array(node["decisionSlices"], 20, 20)
        unique(slices.map { identity(it["decisionId"]) })
        for (slice in slices) {
            json.requireKeys(slice, SLICE_KEYS)
            val parent = byId[identity(slice["parentLogicalId"])] ?: json.invalid()
            val start = bounded(slice["startByte"], 0, 64L * 1024 * 1024)
            val end = bounded(slice["endByte"], 1, 64L * 1024 * 1024)
            val length = bounded(slice["byteLength"], 1, 64L * 1024 * 1024)
            bounded(slice["startLine"], 1, 1_000_000)
            json.sha(slice["sha256"])
            if (end <= start || end > parent.byteLength || length != end - start) json.invalid()
        }
        upstream(node["originalScopeRef"])
        val issuer = node["issuer"]
        json.requireKeys(issuer, ISSUER_KEYS)
        if (json.text(issuer["role"]) != "APPROVAL_ISSUER" ||
            json.text(issuer["identitySource"]) != "FIXED_INSTALLED_ISSUER") json.invalid()
        identity(issuer["issuerId"])
        scope.source(issuer["issuerSourceSha"])
        json.sha(issuer["publicKeySpkiSha256"])
        upstream(issuer["scopeAttestationRef"])
        upstream(issuer["nativeCustodyRef"])
    }

    private fun ci(node: JsonNode, intent: D101ApprovalIntent, docker: String) {
        json.positiveLong(node["collectedAtUnix"])
        val runs = array(node["runs"], 2, 2)
        if (runs.map { json.text(it["project"]) }.toSet() != setOf("app", "docker")) json.invalid()
        for (run in runs) {
            json.requireKeys(run, RUN_KEYS)
            val project = json.text(run["project"])
            val source = if (project == "app") intent.appSourceSha else docker
            if (json.text(run["repository"]) != repository(project) || scope.source(run["sourceSha"]) != source ||
                json.text(run["event"]) !in setOf("pull_request", "push", "workflow_dispatch")) json.invalid()
            scope.source(run["checkoutSha"])
            json.positiveLong(run["workflowId"])
            val runId = json.positiveLong(run["runId"])
            val attempt = bounded(run["attempt"], 1, 100_000)
            for (key in RUN_REFS) upstream(run[key])
            val checks = array(run["requiredChecks"], if (project == "app") 9 else 1, if (project == "app") 9 else 64)
            unique(checks.map { printable(it["name"], 128) })
            unique(checks.map { json.positiveLong(it["checkRunId"]) })
            for (check in checks) {
                json.requireKeys(check, CHECK_KEYS)
                json.positiveLong(check["jobId"])
                if (json.positiveLong(check["runId"]) != runId || bounded(check["attempt"], 1, 100_000) != attempt ||
                    json.text(check["status"]) != "completed" || json.text(check["conclusion"]) != "success") json.invalid()
                upstream(check["checkRawRef"])
                upstream(check["jobRawRef"])
            }
            val artifacts = array(run["artifacts"], if (project == "app") 1 else 0, 64)
            unique(artifacts.map { json.positiveLong(it["artifactId"]) })
            for (artifact in artifacts) {
                json.requireKeys(artifact, ARTIFACT_KEYS)
                val name = printable(artifact["artifactName"], 256)
                val suffix = ATTEMPT_SUFFIX.find(name)?.groupValues?.get(1)
                if (json.positiveLong(artifact["runId"]) != runId ||
                    bounded(artifact["attempt"], 1, 100_000) != attempt ||
                    scope.source(artifact["headSourceSha"]) != source ||
                    (suffix != null && suffix.toLongOrNull() != attempt)) json.invalid()
                json.positiveLong(artifact["jobId"])
                json.positiveLong(artifact["tests"])
                for (key in listOf("failures", "errors", "skipped")) bounded(artifact[key], 0, 0)
                for (key in listOf("jobRawRef", "artifactMetadataRef", "archiveRef", "junitInventoryRef")) upstream(artifact[key])
                if (scope.ref(artifact["archiveRef"]).mediaType != "application/zip" ||
                    scope.ref(artifact["junitInventoryRef"]).mediaType != "application/json") json.invalid()
            }
        }
        val images = array(node["imageBuilds"], 5, 5)
        if (images.map { json.text(it["service"]) }.toSet() != D101ApprovalIntentCodec.FIVE_IMAGES) json.invalid()
        for (image in images) {
            json.requireKeys(image, IMAGE_KEYS)
            val service = json.text(image["service"])
            val app = service in D101ApprovalIntentCodec.APP_IMAGES
            if (json.text(image["sourceProject"]) != if (app) "app" else "docker") json.invalid()
            if (scope.source(image["sourceSha"]) != if (app) intent.appSourceSha else docker) json.invalid()
            if (json.text(image["sourceRelation"]) != if (app) "BUILT_FROM_SOURCE" else "PINNED_UPSTREAM_IMAGE") json.invalid()
            if (json.text(image["imageDigest"]) != intent.newImageDigests.getValue(service)) json.invalid()
            upstream(image["buildProvenanceRef"])
        }
    }

    private fun catalog(node: JsonNode, originals: Map<String, ByteArray>) {
        identity(node["inventoryAssemblerId"])
        val entries = array(node["entries"], 10, 10)
        if (entries.map { scope.ref(it["originalRef"]).logicalId }.toSet() != CATALOG_IDS) json.invalid()
        for (entry in entries) {
            json.requireKeys(entry, ENTRY_KEYS)
            bound(entry["originalRef"], originals, CATALOG_IDS)
            if (json.text(entry["producerClass"]) !in PRODUCER_CLASSES) json.invalid()
            identity(entry["producerId"])
            json.positiveLong(entry["observedAtUnix"])
            allowed(entry["producerSourceRef"], CATALOG_IDS)
            allowed(entry["scopeOriginalRef"], CATALOG_IDS)
            for (ref in array(entry["verificationOriginalRefs"], 1, 32)) allowed(ref, CATALOG_IDS)
        }
    }

    private fun review(node: JsonNode, intent: D101ApprovalIntent, docker: String, originals: Map<String, ByteArray>) {
        for (record in array(node["records"], 1, 16)) {
            json.requireKeys(record, REVIEW_KEYS)
            if (json.text(record["backend"]) !in setOf("claude-independent", "codex-independent") ||
                json.text(record["reviewClass"]) !in setOf("CODE_MERGE", "D101_PREINSTALL_SCOPE") ||
                json.text(record["verdict"]) != "MERGEABLE") json.invalid()
            identity(record["reviewerId"])
            val author = identity(record["authorSessionId"])
            val implementers = array(record["implementerSessionIds"], 1, 32).map { identity(it) }
            unique(implementers)
            if (author in implementers) json.invalid()
            val repo = json.text(record["repository"])
            val project = when (repo) { repository("app") -> "app"; repository("docker") -> "docker"; else -> json.invalid() }
            val expectedSource = if (project == "app") intent.appSourceSha else docker
            if (scope.source(record["headSourceSha"]) != expectedSource) json.invalid()
            val pr = json.positiveLong(record["prNumber"])
            json.positiveLong(record["verdictCommentId"])
            identity(record["windowId"])
            val start = json.positiveLong(record["windowStartsAtUnix"])
            val end = json.positiveLong(record["windowEndsAtUnix"])
            val issued = json.positiveLong(record["issuedAtUnix"])
            if (start >= end || issued < start || issued >= end) json.invalid()
            for (key in listOf("independenceEvidenceRef", "verdictRawRef", "windowOriginalRef")) upstream(record[key])
            for (ref in array(record["reviewRawRefs"], 0, 16)) upstream(ref)
            val files = array(record["fileScopes"], 1, 8)
            for (file in files) {
                json.requireKeys(file, FILE_KEYS)
                val fileProject = json.text(file["project"])
                if (fileProject !in setOf("app", "docker")) json.invalid()
                val source = if (fileProject == "app") intent.appSourceSha else docker
                if (scope.source(file["sourceSha"]) != source || scope.source(file["prHeadSha"]) != source) json.invalid()
                json.positiveLong(file["prNumber"])
                scope.source(file["baseSha"])
                val paths = array(file["paths"], 1, 4096).map { json.text(it) }
                unique(paths)
                if (paths.any { it.length > 512 || !PATH.matches(it) || it.split('/').any { part -> part == ".." } }) json.invalid()
                upstream(file["filesRawRef"])
            }
            if (files.none { json.text(it["project"]) == project && json.positiveLong(it["prNumber"]) == pr }) json.invalid()
            val reviewed = record["reviewedOriginals"]
            json.requireKeys(reviewed, REVIEWED_IDS + "preinstallEvidence")
            for (id in REVIEWED_IDS) {
                val ref = bound(reviewed[id], originals, REVIEWED_IDS)
                if (ref.logicalId != id || ref.mediaType != "application/json" || ref.byteLength > 64 * 1024) json.invalid()
            }
            upstream(reviewed["preinstallEvidence"])
        }
        // CODE_MERGE remains format-valid metadata here. It is never promoted
        // to D101_PREINSTALL_SCOPE, and this subcheck creates no row success.
    }

    private fun bound(node: JsonNode, originals: Map<String, ByteArray>, ids: Set<String>): D101Original6ScopeCheck.RawRef {
        val ref = scope.ref(node)
        if (ref.logicalId !in ids) json.invalid()
        val bytes = originals[ref.logicalId] ?: json.invalid()
        if (ref.sha256 != D101StrictJson.hash(bytes) || ref.byteLength != bytes.size.toLong()) json.invalid()
        return ref
    }

    private fun upstream(node: JsonNode) = allowed(node, emptySet())
    private fun allowed(node: JsonNode, ids: Set<String>): D101Original6ScopeCheck.RawRef = scope.ref(node).also {
        if (!it.logicalId.startsWith("raw:") && it.logicalId !in ids) json.invalid()
        // raw: aliases require separate original-byte DAG inspection; not proven here.
    }
    private fun identity(node: JsonNode?): String = json.text(node).also { if (!IDENTITY.matches(it)) json.invalid() }
    private fun printable(node: JsonNode?, max: Int): String = json.text(node).also {
        if (it.length > max || it.any { char -> char.code < 32 || char.code == 127 }) json.invalid()
    }
    private fun bounded(node: JsonNode?, min: Long, max: Long): Long {
        if (node == null || !node.isIntegralNumber || !node.canConvertToLong()) json.invalid()
        return node.longValue().also { if (it < min || it > max) json.invalid() }
    }
    private fun array(node: JsonNode?, min: Int, max: Int): List<JsonNode> {
        if (node == null || !node.isArray || node.size() !in min..max || node.any { it.isNull }) json.invalid()
        return node.toList()
    }
    private fun <T> unique(items: List<T>) { if (items.toSet().size != items.size) json.invalid() }
    private fun repository(project: String) = "peppone-choi/opensamguk" + if (project == "docker") "-docker" else ""

    companion object {
        private data class Header(val kind: String, val authority: String, val keys: Set<String>)
        private fun header(kind: String, authority: String, vararg keys: String) =
            Header(kind, authority, setOf("schemaVersion", "kind", "scope", "sourceAuthority") + keys)
        private val HEADERS = mapOf(
            "approvalReceipt" to header("D101_APPROVAL_RECEIPT_V1", "EXISTING_USER_DECISIONS_AND_FIXED_ISSUER",
                "documentCollectorId", "decisionParents", "decisionSlices", "originalScopeRef", "issuer", "issuedAtUnix"),
            "combinedCiReceipt" to header("D101_COMBINED_CI_RECEIPT_V1", "GITHUB_ACTIONS", "collectedAtUnix", "runs", "imageBuilds"),
            "evidenceCatalog" to header("D101_EVIDENCE_CATALOG_V1", "UPSTREAM_RAW_INVENTORY", "inventoryAssemblerId", "entries"),
            "reviewBasis" to header("D101_REVIEW_BASIS_V1", "ACTUAL_INDEPENDENT_REVIEW_RECORDS", "records"))
        private val IDENTITY = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
        private val PATH = Regex("(?!/)[A-Za-z0-9_./@+\\-]{1,512}")
        private val ATTEMPT_SUFFIX = Regex("-attempt-([0-9]+)$")
        private val SLICE_KEYS = setOf("decisionId", "parentLogicalId", "startByte", "endByte", "startLine", "byteLength", "sha256")
        private val ISSUER_KEYS = setOf("role", "identitySource", "issuerId", "issuerSourceSha", "publicKeySpkiSha256", "scopeAttestationRef", "nativeCustodyRef")
        private val RUN_REFS = setOf("runRawRef", "checkoutEvidenceRef", "protectionRawRef", "executionInventoryRef", "completeExecutionEvidenceRef")
        private val RUN_KEYS = setOf("project", "repository", "workflowId", "runId", "attempt", "event", "sourceSha", "checkoutSha", "requiredChecks", "artifacts") + RUN_REFS
        private val CHECK_KEYS = setOf("name", "checkRunId", "jobId", "runId", "attempt", "status", "conclusion", "checkRawRef", "jobRawRef")
        private val ARTIFACT_KEYS = setOf("artifactId", "runId", "attempt", "headSourceSha", "jobId", "artifactName", "jobRawRef", "artifactMetadataRef", "archiveRef", "junitInventoryRef", "tests", "failures", "errors", "skipped")
        private val IMAGE_KEYS = setOf("service", "sourceProject", "sourceSha", "imageDigest", "sourceRelation", "buildProvenanceRef")
        private val CATALOG_IDS = setOf("approvalReceipt", "combinedCiReceipt", "selectedSourceReceipt", "isolatedSeedTickReceipt", "spaceInventoryReceipt", "approvalIntent", "configInventory", "commandPlan", "recoveryPlan", "readerBindings")
        private val ENTRY_KEYS = setOf("originalRef", "producerClass", "producerId", "producerSourceRef", "observedAtUnix", "scopeOriginalRef", "verificationOriginalRefs")
        private val PRODUCER_CLASSES = setOf("USER_DECISION_DOCUMENT", "GITHUB_ACTIONS", "FIXED_HOST_ISSUER", "INDEPENDENT_REVIEWER", "PRODUCT_SOURCE")
        private val REVIEW_KEYS = setOf("backend", "reviewerId", "authorSessionId", "implementerSessionIds", "independenceEvidenceRef", "reviewClass", "repository", "prNumber", "headSourceSha", "verdictCommentId", "verdict", "verdictRawRef", "reviewRawRefs", "windowId", "windowStartsAtUnix", "windowEndsAtUnix", "issuedAtUnix", "windowOriginalRef", "fileScopes", "reviewedOriginals")
        private val FILE_KEYS = setOf("project", "sourceSha", "prNumber", "prHeadSha", "baseSha", "paths", "filesRawRef")
        private val REVIEWED_IDS = setOf("configInventory", "commandPlan", "recoveryPlan", "readerBindings")
    }
}
