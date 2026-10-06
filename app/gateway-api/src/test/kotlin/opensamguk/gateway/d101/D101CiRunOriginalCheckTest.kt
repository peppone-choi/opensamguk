package opensamguk.gateway.d101

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import opensamguk.gateway.d101.domain.D101RequestInvalid
import opensamguk.gateway.d101.domain.D101StrictJson
import opensamguk.gateway.d101.security.D101CiRunOriginalCheck
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class D101CiRunOriginalCheckTest {
    private val mainChecks = listOf(
        "contracts", "jvm", "web (game)", "web (gateway)", "web-shared", "external-places-drift",
        "map-slow-tests (test_build_han_parent_reconciliation)",
        "map-slow-tests (test_territory_disconnection_adjudications)", "naming-lint",
    )

    @Test fun `two synthetic runs bind same source attempt protected checks and complete XML bytes only as a subcheck`() {
        Packet().verify()
    }

    @Test fun `main protection may list the same nine checks in contexts and checks`() {
        val p = Packet()
        p.renameAppChecks(mainChecks)
        p.appProtection(mainChecks, mainChecks.map { mapOf("context" to it, "app_id" to null) })
        p.verify()
    }

    @Test fun `duplicate names inside either protection representation are rejected`() {
        val contexts = Packet()
        contexts.renameAppChecks(mainChecks)
        contexts.appProtection(mainChecks + mainChecks.first(),
            mainChecks.map { mapOf("context" to it, "app_id" to null) })
        assertThrows<D101RequestInvalid> { contexts.verify() }

        val checks = Packet()
        checks.renameAppChecks(mainChecks)
        checks.appProtection(mainChecks,
            (mainChecks + mainChecks.first()).map { mapOf("context" to it, "app_id" to null) })
        assertThrows<D101RequestInvalid> { checks.verify() }
    }

    @Test fun `missing required check or malformed protection context is rejected`() {
        val missing = Packet()
        missing.renameAppChecks(mainChecks)
        missing.appProtection(mainChecks.dropLast(1),
            mainChecks.dropLast(1).map { mapOf("context" to it, "app_id" to null) })
        assertThrows<D101RequestInvalid> { missing.verify() }

        val malformed = Packet()
        malformed.renameAppChecks(mainChecks)
        malformed.appProtection(mainChecks, mainChecks.dropLast(1).map {
            mapOf<String, Any?>("context" to it)
        } + mapOf<String, Any?>("context" to 9))
        assertThrows<D101RequestInvalid> { malformed.verify() }
    }

    @Test fun `rebound run attempt check job and protection set cannot forge a green tuple`() {
        val p = Packet()
        p.replace("raw:app-run", p.f.obj("id" to 10, "run_attempt" to 1, "head_sha" to p.app,
            "event" to "push", "workflow_id" to 5, "repository" to mapOf("full_name" to "peppone-choi/opensamguk"),
            "status" to "completed", "conclusion" to "success"), p.appRun, "runRawRef")
        assertThrows<D101RequestInvalid> { p.verify() }
        val q = Packet()
        q.replace("raw:app-protection", q.f.obj("required_status_checks" to mapOf("contexts" to (0 until 8).map { "app-check-$it" },
            "checks" to emptyList<String>())), q.appRun, "protectionRawRef")
        assertThrows<D101RequestInvalid> { q.verify() }
        val r = Packet()
        val check = r.appRun["requiredChecks"][0] as ObjectNode
        r.replace("raw:app-check-job-0", r.f.obj("id" to 200, "run_id" to 10, "run_attempt" to 1,
            "status" to "completed", "conclusion" to "success"), check, "jobRawRef")
        assertThrows<D101RequestInvalid> { r.verify() }
        val s = Packet()
        (s.appRun["requiredChecks"][0] as ObjectNode).put("status", "queued")
        assertThrows<D101RequestInvalid> { s.verify() }
    }

    @Test fun `old artifact metadata missing XML and extra tests are rejected even when SHA labels are rebound`() {
        val p = Packet()
        val artifact = p.appRun["artifacts"][0] as ObjectNode
        p.replace("raw:app-artifact-meta", p.f.obj("id" to 300, "expired" to false,
            "workflow_run" to mapOf("id" to 10, "head_sha" to p.app), "name" to "jvm-attempt-1",
            "digest" to "sha256:${(artifact["archiveRef"])["sha256"].textValue()}",
            "created_at" to "2026-10-06T00:00:10Z"), artifact, "artifactMetadataRef")
        assertThrows<D101RequestInvalid> { p.verify() }
        val q = Packet()
        q.raw.remove("raw:app-xml")
        assertThrows<D101RequestInvalid> { q.verify() }
        val r = Packet()
        (r.appRun["artifacts"][0] as ObjectNode).put("tests", 2)
        assertThrows<D101RequestInvalid> { r.verify() }
    }

    private class Packet {
        val f = D101RawEvidenceFixture()
        val app = "a".repeat(40)
        val docker = "b".repeat(40)
        val raw = mutableMapOf<String, ByteArray>()
        val appRun = run("app", 10, app, 9, true)
        val dockerRun = run("docker", 20, docker, 1, false)
        val receipt = f.obj("schemaVersion" to 1, "kind" to "D101_COMBINED_CI_RECEIPT_V1",
            "scope" to f.obj("appSourceSha" to app, "dockerSourceSha" to docker), "sourceAuthority" to "GITHUB_ACTIONS",
            "collectedAtUnix" to 1, "runs" to listOf(appRun, dockerRun), "imageBuilds" to emptyList<String>())

        fun verify() {
            receipt.set<JsonNode>("runs", f.mapper.valueToTree(listOf(appRun, dockerRun)))
            D101CiRunOriginalCheck().verify(f.wire(receipt), raw)
        }
        fun replace(id: String, value: JsonNode, holder: ObjectNode, key: String) {
            val bytes = f.wire(value)
            raw[id] = bytes
            holder.set<ObjectNode>(key, f.ref(id, bytes))
        }
        fun renameAppChecks(names: List<String>) {
            require(names.size == 9)
            names.forEachIndexed { i, name ->
                val check = appRun["requiredChecks"][i] as ObjectNode
                check.put("name", name)
                replace("raw:app-check-$i", f.obj("id" to (100 + i), "name" to name,
                    "head_sha" to app, "status" to "completed", "conclusion" to "success"),
                    check, "checkRawRef")
            }
        }
        fun appProtection(contexts: List<Any?>, checks: List<Any?>) {
            replace("raw:app-protection", f.obj("required_status_checks" to mapOf(
                "contexts" to contexts, "checks" to checks)), appRun, "protectionRawRef")
        }
        private fun original(id: String, value: Any, media: String = "application/json"): ObjectNode {
            val bytes = if (value is ByteArray) value else f.wire(f.mapper.valueToTree(value))
            raw[id] = bytes
            return f.ref(id, bytes, media)
        }
        private fun run(project: String, runId: Int, source: String, count: Int, hasArtifact: Boolean): ObjectNode {
            val repo = "peppone-choi/opensamguk" + if (project == "docker") "-docker" else ""
            val checks = (0 until count).map { i ->
                val name = "$project-check-$i"
                val job = f.obj("id" to (200 + i), "run_id" to runId, "run_attempt" to 2,
                    "status" to "completed", "conclusion" to "success")
                val check = f.obj("id" to (100 + i), "name" to name, "head_sha" to source,
                    "status" to "completed", "conclusion" to "success")
                f.obj("name" to name, "checkRunId" to (100 + i), "jobId" to (200 + i), "runId" to runId,
                    "attempt" to 2, "status" to "completed", "conclusion" to "success",
                    "checkRawRef" to original("raw:$project-check-$i", check),
                    "jobRawRef" to original("raw:$project-check-job-$i", job))
            }
            val artifacts = if (!hasArtifact) emptyList() else listOf(artifact(runId, source))
            val run = f.obj("project" to project, "repository" to repo, "workflowId" to 5, "runId" to runId,
                "attempt" to 2, "event" to "push", "sourceSha" to source, "checkoutSha" to "c".repeat(40),
                "runRawRef" to original("raw:$project-run", f.obj("id" to runId, "run_attempt" to 2,
                    "head_sha" to source, "event" to "push", "workflow_id" to 5,
                    "repository" to mapOf("full_name" to repo), "status" to "completed", "conclusion" to "success")),
                "checkoutEvidenceRef" to original("raw:$project-checkout", f.obj("synthetic" to "not official provenance")),
                "protectionRawRef" to original("raw:$project-protection", f.obj("required_status_checks" to mapOf(
                    "contexts" to (0 until count).map { "$project-check-$it" }, "checks" to emptyList<String>()))),
                "requiredChecks" to checks, "artifacts" to artifacts,
                "executionInventoryRef" to original("raw:$project-execution", f.obj("synthetic" to "not complete union")),
                "completeExecutionEvidenceRef" to original("raw:$project-complete", f.obj("synthetic" to "not complete union")))
            return run
        }
        private fun artifact(runId: Int, source: String): ObjectNode {
            val xml = "<testsuite tests=\"1\"><testcase name=\"x\"/></testsuite>".toByteArray()
            val xmlRef = original("raw:app-xml", xml, "application/xml")
            val zip = ByteArrayOutputStream().also { out -> ZipOutputStream(out).use { stream ->
                stream.putNextEntry(ZipEntry("TEST-a.xml")); stream.write(xml); stream.closeEntry()
                stream.putNextEntry(ZipEntry("note.bin")); stream.write(byteArrayOf(1)); stream.closeEntry()
            } }.toByteArray()
            val archiveRef = original("raw:app-archive", zip, "application/zip")
            val inventory = f.obj("schemaVersion" to 1, "kind" to "D101_JUNIT_INVENTORY_V1", "runId" to runId,
                "attempt" to 2, "jobId" to 400, "headSourceSha" to source, "entries" to listOf(xmlRef))
            val job = f.obj("id" to 400, "run_id" to runId, "run_attempt" to 2,
                "status" to "completed", "conclusion" to "success",
                "started_at" to "2026-10-06T00:00:00Z", "completed_at" to "2026-10-06T00:01:00Z")
            val metadata = f.obj("id" to 300, "expired" to false,
                "workflow_run" to mapOf("id" to runId, "head_sha" to source), "name" to "jvm-attempt-2",
                "digest" to "sha256:${D101StrictJson.hash(zip)}", "created_at" to "2026-10-06T00:00:10Z")
            return f.obj("artifactId" to 300, "runId" to runId, "attempt" to 2, "headSourceSha" to source,
                "jobId" to 400, "artifactName" to "jvm-attempt-2", "jobRawRef" to original("raw:app-artifact-job", job),
                "artifactMetadataRef" to original("raw:app-artifact-meta", metadata), "archiveRef" to archiveRef,
                "junitInventoryRef" to original("raw:app-inventory", inventory), "tests" to 1,
                "failures" to 0, "errors" to 0, "skipped" to 0)
        }
    }
}
