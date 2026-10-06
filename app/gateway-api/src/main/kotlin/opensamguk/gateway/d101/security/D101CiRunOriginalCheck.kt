package opensamguk.gateway.d101.security

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.gateway.d101.domain.D101StrictJson
import java.time.Instant

/** Actual-byte CI tuple subcheck. Official origin, checkout provenance and complete execution remain unverified. */
internal class D101CiRunOriginalCheck(private val mapper: ObjectMapper = ObjectMapper()) {
    private val json = D101StrictJson(mapper)
    private val refs = D101Original6ScopeCheck(json)
    private val official = mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
    private val archive = D101CiArchiveOriginalCheck(mapper)

    fun verify(receiptOriginal: ByteArray, originals: Map<String, ByteArray>) {
        val receipt = json.objectBytes(receiptOriginal, TOP_KEYS, 64 * 1024)
        if (json.positiveLong(receipt["schemaVersion"]) != 1L ||
            json.text(receipt["kind"]) != "D101_COMBINED_CI_RECEIPT_V1" ||
            json.text(receipt["sourceAuthority"]) != "GITHUB_ACTIONS") json.invalid()
        json.positiveLong(receipt["collectedAtUnix"])
        val scope = receipt["scope"]
        val app = refs.source(scope?.get("appSourceSha"))
        val docker = refs.source(scope?.get("dockerSourceSha"))
        val runs = receipt["runs"]
        if (!runs.isArray || runs.size() != 2 || runs.any { it.isNull }) json.invalid()
        if (runs.map { json.text(it["project"]) }.toSet() != setOf("app", "docker")) json.invalid()
        for (run in runs) verifyRun(run, originals, if (json.text(run["project"]) == "app") app else docker)
    }

    private fun verifyRun(run: JsonNode, originals: Map<String, ByteArray>, source: String) {
        json.requireKeys(run, RUN_KEYS)
        val project = json.text(run["project"])
        val repository = if (project == "app") "peppone-choi/opensamguk" else "peppone-choi/opensamguk-docker"
        val runId = json.positiveLong(run["runId"])
        val attempt = json.positiveLong(run["attempt"])
        val workflow = json.positiveLong(run["workflowId"])
        if (attempt > 100_000 || json.text(run["repository"]) != repository || refs.source(run["sourceSha"]) != source ||
            json.text(run["event"]) !in setOf("pull_request", "push", "workflow_dispatch")) json.invalid()
        refs.source(run["checkoutSha"])
        // These three actual originals are bound as bytes. Their official
        // provenance, checkout identity, filter/skip union and completeness
        // require a future fixed collector contract; this class grants none.
        for (key in listOf("checkoutEvidenceRef", "executionInventoryRef", "completeExecutionEvidenceRef")) bound(run[key], originals)
        val actual = objectOriginal(run["runRawRef"], originals)
        if (json.positiveLong(actual["id"]) != runId || json.positiveLong(actual["run_attempt"]) != attempt ||
            refs.source(actual["head_sha"]) != source || json.text(actual["event"]) != json.text(run["event"]) ||
            json.positiveLong(actual["workflow_id"]) != workflow ||
            json.text(actual["repository"]?.get("full_name")) != repository ||
            json.text(actual["status"]) != "completed" || json.text(actual["conclusion"]) != "success") json.invalid()
        val protection = objectOriginal(run["protectionRawRef"], originals)
        val expected = protectedNames(protection)
        val checks = run["requiredChecks"]
        if (!checks.isArray || checks.size() !in (if (project == "app") 9..9 else 1..64) || checks.any { it.isNull }) json.invalid()
        val names = HashSet<String>()
        val checkIds = HashSet<Long>()
        for (check in checks) {
            json.requireKeys(check, CHECK_KEYS)
            val name = json.text(check["name"])
            val id = json.positiveLong(check["checkRunId"])
            val jobId = json.positiveLong(check["jobId"])
            if (name.length > 128 || name.any { it.code < 32 || it.code == 127 } || !names.add(name) || !checkIds.add(id) ||
                json.positiveLong(check["runId"]) != runId || json.positiveLong(check["attempt"]) != attempt ||
                json.text(check["status"]) != "completed" || json.text(check["conclusion"]) != "success") json.invalid()
            val original = objectOriginal(check["checkRawRef"], originals)
            val job = objectOriginal(check["jobRawRef"], originals)
            if (json.positiveLong(original["id"]) != id || json.text(original["name"]) != name ||
                refs.source(original["head_sha"]) != source || json.text(original["status"]) != "completed" ||
                json.text(original["conclusion"]) != "success" || !jobMatches(job, jobId, runId, attempt)) json.invalid()
        }
        if (names != expected) json.invalid()
        val artifacts = run["artifacts"]
        if (!artifacts.isArray || artifacts.size() !in (if (project == "app") 1..64 else 0..64) || artifacts.any { it.isNull }) json.invalid()
        val artifactIds = HashSet<Long>()
        for (artifact in artifacts) {
            json.requireKeys(artifact, ARTIFACT_KEYS)
            val id = json.positiveLong(artifact["artifactId"])
            val jobId = json.positiveLong(artifact["jobId"])
            val name = json.text(artifact["artifactName"])
            if (!artifactIds.add(id) || name.length > 256 || name.any { it.code < 32 || it.code == 127 } ||
                json.positiveLong(artifact["runId"]) != runId || json.positiveLong(artifact["attempt"]) != attempt ||
                refs.source(artifact["headSourceSha"]) != source ||
                ATTEMPT_SUFFIX.find(name)?.groupValues?.get(1)?.toLongOrNull()?.let { it != attempt } == true) json.invalid()
            val job = objectOriginal(artifact["jobRawRef"], originals)
            val metadata = objectOriginal(artifact["artifactMetadataRef"], originals)
            val archiveRef = refs.ref(artifact["archiveRef"])
            if (!jobMatches(job, jobId, runId, attempt) || json.positiveLong(metadata["id"]) != id ||
                metadata["expired"]?.isBoolean != true || metadata["expired"].booleanValue() ||
                json.positiveLong(metadata["workflow_run"]?.get("id")) != runId ||
                refs.source(metadata["workflow_run"]?.get("head_sha")) != source ||
                json.text(metadata["name"]) != name || json.text(metadata["digest"]) != "sha256:${archiveRef.sha256}" ||
                utc(job["started_at"]).isAfter(utc(metadata["created_at"])) ||
                utc(metadata["created_at"]).isAfter(utc(job["completed_at"]))) json.invalid()
            archive.verify(artifact, run, originals)
        }
    }

    private fun jobMatches(job: JsonNode, id: Long, runId: Long, attempt: Long): Boolean =
        json.positiveLong(job["id"]) == id && json.positiveLong(job["run_id"]) == runId &&
            json.positiveLong(job["run_attempt"]) == attempt && json.text(job["status"]) == "completed" &&
            json.text(job["conclusion"]) == "success"

    private fun protectedNames(protection: JsonNode): Set<String> {
        val required = protection["required_status_checks"] ?: json.invalid()
        val contexts = required["contexts"]
        val checks = required["checks"]
        if ((contexts != null && !contexts.isArray) || (checks != null && !checks.isArray)) json.invalid()
        val names = (contexts?.map { json.text(it) } ?: emptyList()) +
            (checks?.map { json.text(it["context"]) } ?: emptyList())
        if (names.isEmpty() || names.size != names.toSet().size) json.invalid()
        return names.toSet()
    }

    private fun bound(refNode: JsonNode?, originals: Map<String, ByteArray>): ByteArray {
        val ref = refs.ref(refNode)
        val raw = originals[ref.logicalId] ?: json.invalid()
        return D101RawEvidenceBinding.bind(refNode!!, raw, mapper).originalBytes()
    }

    private fun objectOriginal(refNode: JsonNode?, originals: Map<String, ByteArray>): JsonNode {
        val ref = refs.ref(refNode)
        if (ref.mediaType != "application/json") json.invalid()
        val bytes = bound(refNode, originals)
        if (bytes.take(3) == listOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte())) json.invalid()
        D101RawEvidenceBinding.requireUtf8(bytes)
        return try { official.readTree(bytes)?.takeIf { it.isObject } ?: json.invalid() }
        catch (_: Exception) { json.invalid() }
    }

    private fun utc(node: JsonNode?): Instant {
        val value = json.text(node)
        if (!value.endsWith('Z')) json.invalid()
        return try { Instant.parse(value) } catch (_: Exception) { json.invalid() }
    }

    companion object {
        private val TOP_KEYS = setOf("schemaVersion", "kind", "scope", "sourceAuthority", "collectedAtUnix", "runs", "imageBuilds")
        private val RUN_KEYS = setOf("project", "repository", "workflowId", "runId", "attempt", "event", "sourceSha", "checkoutSha",
            "runRawRef", "checkoutEvidenceRef", "protectionRawRef", "requiredChecks", "artifacts", "executionInventoryRef", "completeExecutionEvidenceRef")
        private val CHECK_KEYS = setOf("name", "checkRunId", "jobId", "runId", "attempt", "status", "conclusion", "checkRawRef", "jobRawRef")
        private val ARTIFACT_KEYS = setOf("artifactId", "runId", "attempt", "headSourceSha", "jobId", "artifactName", "jobRawRef",
            "artifactMetadataRef", "archiveRef", "junitInventoryRef", "tests", "failures", "errors", "skipped")
        private val ATTEMPT_SUFFIX = Regex("-attempt-([0-9]+)$")
    }
}
