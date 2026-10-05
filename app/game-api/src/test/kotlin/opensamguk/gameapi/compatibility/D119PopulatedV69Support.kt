package opensamguk.gameapi.compatibility

import com.fasterxml.jackson.databind.JsonNode
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import org.springframework.jdbc.datasource.DriverManagerDataSource

/** Composition keeps the original immutable empty-queue fixture and its IT unchanged. */
internal class D119PopulatedV69Support(private val fixture: D119LegacyV69Fixture) {
    private val resources = fixture.root.resolve("app/game-api/src/test/resources/compatibility/d119-v69")
    private val contractPath = resources.resolve("populated-contract.json")
    val contract: JsonNode = fixture.mapper.readTree(contractPath.toFile())
    private var candidateFacts: Map<String, Any?> = emptyMap()
    private var populationFacts: Map<String, Any?> = emptyMap()

    fun verifyCandidateSource() {
        check(System.getenv("GITHUB_ACTIONS") == "true"
            && System.getenv("GITHUB_REPOSITORY") == "peppone-choi/opensamguk")
        val checkout = git("rev-parse", "HEAD").trim()
        val proofSource = System.getenv("GITHUB_EVENT_PATH")?.let {
            fixture.mapper.readTree(Path.of(it).toFile()).path("pull_request").path("head").path("sha").asText()
        }?.takeIf { it.matches(Regex("[a-f0-9]{40}")) } ?: checkout
        // A slot can pin a final card. Future normal CI still revalidates its own current candidate.
        val expected = System.getenv("D119_CANDIDATE_SHA") ?: checkout
        check(expected.matches(Regex("[a-f0-9]{40}")))
        if (runCatching { git("cat-file", "-e", "$expected^{commit}") }.isFailure) {
            git("fetch", "--depth=1", "origin", expected)
        }
        val expectedTree = productionTree(expected)
        val checkoutTree = productionTree(checkout)
        check(expectedTree.isNotEmpty() && checkoutTree == expectedTree) {
            "CI checkout production sources differ from the specified candidate $expected"
        }
        val dirty = (git("diff", "--name-only", "HEAD") +
            git("ls-files", "--others", "--exclude-standard")).lines().filter(::productionPath)
        check(dirty.isEmpty()) { "candidate production working tree differs from its committed sources: $dirty" }
        candidateFacts = mapOf("candidateSourceSha" to expected, "ciCheckoutMergeRef" to checkout,
            "proofImplementationSourceSha" to proofSource,
            "planningCandidateSourceSha" to contract["planningCandidateSourceSha"].asText(),
            "productionSourceTreeSha256" to digest(expectedTree.toByteArray()),
            "productionSourcePaths" to expectedTree.lines().count { it.isNotBlank() },
            "candidateProductionIdentity" to "EXACT_MATCH")
    }

    fun populateFromOldProducer(): List<JsonNode> {
        check(candidateFacts.isNotEmpty())
        check(contract["caseKind"].asText() == "CONTROLLED_POPULATED_OLD_V69")
        check(contract["expectedPopulatedRows"].asInt() == 2)
        check(reservations().isEmpty()) { "old immutable fixture must still have zero reservations" }
        val prepared = fixture.mapper.readTree(fixture.output.resolve("prepared-runtime.json").toFile())
        check(prepared["sourceSha"].asText() == contract["oldSourceSha"].asText())
        val oldSource = Path.of(prepared["sourceRoot"].asText())
        check(git("-C", oldSource.toString(), "rev-parse", "HEAD").trim() == contract["oldSourceSha"].asText())
        check(git("-C", oldSource.toString(), "status", "--porcelain", "--untracked-files=no").isBlank())
        val original = fixture.fingerprint()
        val seed = prepared["seedCommand"].map { it.asText() }
        check(seed.size == 6 && seed[2] == "-cp" && seed[4] == "D119PublicSeed")
        val javaHome = Path.of(requireNotNull(System.getenv("JAVA_HOME")))
        val classes = fixture.output.resolve("controlled-reservation-classes")
        Files.createDirectory(classes)
        val helper = resources.resolve("D119PopulatedReservationSeed.java")
        run(listOf(javaHome.resolve("bin/javac").toString(), "-J-Xmx256m", "-cp", seed[3],
            "-d", classes.toString(), helper.toString()), "controlled-reservation-compile.log")
        val ds = fixture.dataSource as DriverManagerDataSource
        val environment = System.getenv().filterKeys { it in setOf("PATH", "JAVA_HOME", "LANG", "TZ") }
            .toMutableMap().apply {
                put("D119_ISOLATED_URL", requireNotNull(ds.url))
                put("D119_ISOLATED_USER", requireNotNull(ds.username))
                put("D119_ISOLATED_PASSWORD", requireNotNull(ds.password))
            }
        run(listOf(seed[0], "-Xmx256m", "-cp", "$classes${java.io.File.pathSeparator}${seed[3]}",
            "D119PopulatedReservationSeed", contractPath.toString()), "controlled-reservation-producer.log", environment)
        check(git("-C", oldSource.toString(), "status", "--porcelain", "--untracked-files=no").isBlank())
        val rows = reservations()
        check(rows.size == 2)
        check(rows.map { it["turn_idx"].asInt() } == listOf(0, 3))
        rows.zip(contract["reservations"]).forEach { (actual, expected) ->
            check(actual["world_id"].asInt() == 1 && actual["general_id"].asInt() == 1001)
            check(actual["action_code"].asText() == expected["actionCode"].asText())
            check(actual["arg"] == expected["arg"] && actual["brief"].asText() == expected["brief"].asText())
            check(actual["request_id"].isNull)
        }
        val log = Files.readString(fixture.output.resolve("controlled-reservation-producer.log"))
        val producerLine = log.lineSequence().single { it.startsWith("D119_CONTROLLED_OLD_PRODUCER ") }
        val producer = fixture.mapper.readTree(producerLine.substringAfter("D119_CONTROLLED_OLD_PRODUCER "))
        check(producer["actualCount"].asInt() == rows.size)
        val after = fixture.fingerprint()
        check(original.filterKeys { it != "general_turn" } == after.filterKeys { it != "general_turn" }) {
            "controlled producer changed schema or tables beyond general_turn"
        }
        populationFacts = mapOf("immutableEmptyRows" to 0,
            "immutableEmptyFingerprint" to original, "controlledPopulatedFingerprint" to after,
            "oldProducer" to producer, "actualReservationCount" to rows.size, "reservationRows" to rows,
            "oldSourceTree" to git("-C", oldSource.toString(), "rev-parse", "HEAD^{tree}").trim(),
            "oldRuntimeJarSha256" to prepared["runtimeJarSha256"].asText(),
            "oldRuntimeJarBytes" to prepared["runtimeJarBytes"].asLong(),
            "oldSourceManifestSha256" to prepared["sourceManifestSha256"].asText())
        writeProof("CONTROLLED_POPULATED_READY", emptyMap())
        return rows
    }

    fun reservations(): List<JsonNode> = fixture.jdbc.queryForList(
        "SELECT to_jsonb(t)::text FROM general_turn t WHERE world_id=1 ORDER BY general_id,turn_idx",
        String::class.java).map { fixture.mapper.readTree(it) }

    fun writeProof(status: String, facts: Map<String, Any?>) {
        val proof = linkedMapOf<String, Any?>("status" to status,
            "caseKind" to "CONTROLLED_POPULATED_OLD_V69", "oldSourceSha" to contract["oldSourceSha"].asText(),
            "contractSha256" to digest(Files.readAllBytes(contractPath)),
            "helperSha256" to digest(Files.readAllBytes(resources.resolve("D119PopulatedReservationSeed.java"))),
            "candidateApplicationClassSha256" to digest(requireNotNull(
                opensamguk.gameapi.GameApiApplication::class.java.getResourceAsStream("GameApiApplication.class"))
                .use { it.readBytes() }),
            "actualGatewayPublication" to "UNVERIFIED", "engineReservationExecution" to "UNVERIFIED",
            "operatingAccess" to false)
        proof.putAll(candidateFacts)
        proof.putAll(populationFacts)
        proof.putAll(facts)
        fixture.mapper.writerWithDefaultPrettyPrinter().writeValue(
            fixture.output.resolve("populated-compatibility-receipt.json").toFile(), proof)
        fixture.writeReceipt(status, proof)
    }

    private fun productionPath(path: String): Boolean =
        (listOf("common/", "logic/", "infra/", "app/").any { path.startsWith(it) } &&
            ("/src/main/" in path || path.endsWith(".gradle.kts") || path.endsWith(".gradle") || path.endsWith("/gradle.properties"))) ||
            path.startsWith("data/") || path.startsWith("assets/") || path.startsWith("gradle/") ||
            path in setOf("build.gradle.kts", "settings.gradle.kts", "gradle.properties", "gradlew", "gradlew.bat")

    private fun productionTree(ref: String): String = git("-c", "core.quotepath=false", "ls-tree", "-r", "--full-tree", ref)
        .lineSequence().filter { productionPath(it.substringAfter('\t', "")) }.joinToString("\n")

    private fun git(vararg args: String): String {
        val log = Files.createTempFile("d119-populated-git-", ".log")
        try {
            val process = ProcessBuilder(listOf("git") + args).directory(fixture.root.toFile())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start()
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.toHandle().descendants().forEach { it.destroyForcibly() }
                process.destroyForcibly().waitFor()
                error("candidate/source Git read timed out")
            }
            check(process.exitValue() == 0) { "candidate/source Git read failed" }
            return Files.readString(log)
        } finally {
            Files.deleteIfExists(log)
        }
    }

    private fun run(command: List<String>, name: String, environment: Map<String, String>? = null) {
        val log = fixture.output.resolve(name)
        val builder = ProcessBuilder(command).directory(fixture.root.toFile()).redirectErrorStream(true).redirectOutput(log.toFile())
        if (environment != null) { builder.environment().clear(); builder.environment().putAll(environment) }
        val process = builder.start()
        val completed = process.waitFor(2100, TimeUnit.SECONDS)
        if (!completed) {
            process.toHandle().descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly().waitFor()
        }
        val password = requireNotNull((fixture.dataSource as DriverManagerDataSource).password)
        Files.writeString(log, Files.readString(log).replace(password, "[fixture password]"))
        check(completed) { "owned controlled reservation helper timed out; redacted log retained at $log" }
        check(process.exitValue() == 0) { "controlled old producer exit=${process.exitValue()}; ${Files.readString(log).takeLast(8000)}" }
    }

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
