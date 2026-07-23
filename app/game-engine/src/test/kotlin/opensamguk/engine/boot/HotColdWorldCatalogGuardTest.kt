package opensamguk.engine.boot

import opensamguk.logic.memory.AccessBound
import opensamguk.logic.memory.DataTemperature
import opensamguk.logic.memory.HotColdCatalog
import java.io.File
import java.util.regex.Pattern
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HotColdWorldCatalogGuardTest {
    private fun source(path: String): String = listOf(
        File(path),
        File("app/game-engine/$path"),
        File(path.removePrefix("app/game-engine/")),
    ).firstOrNull { it.isFile }?.readText()
        ?: error("$path source not found from ${File(".").absolutePath}")

    private fun engineFile(path: String): File = listOf(
        File(path),
        File(path.removePrefix("app/game-engine/")),
    ).firstOrNull { it.exists() }
        ?: error("$path not found from ${File(".").absolutePath}")

    private fun runtimeSourceFiles(): List<Pair<String, String>> =
        HotColdCatalog.runtimeSourceDirectories.flatMap { dirPath ->
            val dir = engineFile(dirPath)
            dir.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .map { file -> moduleRelativeEnginePath(file) to file.readText() }
                .toList()
        } + listOf(
            "app/game-engine/src/main/kotlin/opensamguk/engine/config/DaemonLoopConfig.kt" to
                source("app/game-engine/src/main/kotlin/opensamguk/engine/config/DaemonLoopConfig.kt"),
        )

    private fun moduleRelativeEnginePath(file: File): String {
        val normalized = file.path.replace(File.separatorChar, '/')
        val marker = "app/game-engine/"
        val index = normalized.indexOf(marker)
        if (index >= 0) return normalized.substring(index)
        return "app/game-engine/$normalized"
    }

    @Test
    fun `snapshot loader data reads are cataloged`() {
        val loader = source("src/main/kotlin/opensamguk/engine/boot/WorldSnapshotLoader.kt")
        val loaderMethods = Regex("""private fun (load[A-Z][A-Za-z0-9]*|resolveActiveGame)\b""")
            .findAll(loader)
            .mapTo(linkedSetOf()) { it.groupValues[1] }

        assertEquals(loaderMethods, HotColdCatalog.snapshotMethodNames)
        assertEquals(
            HotColdCatalog.snapshotAccesses.size,
            HotColdCatalog.snapshotMethodNames.size,
            "snapshot catalog must not contain duplicate method entries",
        )
        for (entry in HotColdCatalog.snapshotAccesses) {
            assertFalse(entry.relation.isBlank(), "${entry.methodName} must name its relation")
            assertFalse(entry.ordering.isBlank(), "${entry.methodName} must declare deterministic ordering")
        }
    }

    @Test
    fun `snapshot loader SQL calls stay inside cataloged helpers`() {
        val loader = source("src/main/kotlin/opensamguk/engine/boot/WorldSnapshotLoader.kt")
        val queryCalls = Regex("""jdbc\.(?:query|queryForObject)\b""").findAll(loader).toList()

        assertTrue(queryCalls.isNotEmpty(), "expected snapshot loader to contain JDBC query calls")
        for (match in queryCalls) {
            val method = enclosingPrivateMethod(loader, match.range.first)
            assertTrue(
                method in HotColdCatalog.snapshotMethodNames,
                "JDBC call in uncataloged WorldSnapshotLoader helper $method",
            )
        }
    }

    @Test
    fun `legacy full scans are cold and assigned to S5 T2`() {
        val legacy = HotColdCatalog.snapshotAccesses
            .filter { it.bound == AccessBound.LEGACY_FULL_SCAN_PENDING_S5_T2 }

        assertTrue(legacy.isNotEmpty(), "current loader still has cataloged full-history boot scans")
        for (entry in legacy) {
            assertEquals(DataTemperature.QUERY_ONLY_COLD, entry.temperature, entry.methodName)
            assertTrue(
                entry.followUp?.contains("S5-T2") == true,
                "${entry.methodName} must name its follow-up removal ticket",
            )
        }
    }

    @Test
    fun `phase hot candidates are explicit non activation work`() {
        val phaseHot = HotColdCatalog.runtimeReadSeams
            .filter { it.temperature == DataTemperature.PHASE_HOT }

        assertTrue(phaseHot.isNotEmpty(), "catalog must name current phase-hot candidates")
        for (entry in phaseHot) {
            assertFalse(entry.relation.isBlank(), entry.accessType)
            assertFalse(entry.ordering.isBlank(), entry.accessType)
            assertFalse(entry.activationReady, "${entry.accessType} must not imply runtime prefetch activation")
            assertTrue(
                entry.followUp?.contains("S5-T1") == true,
                "${entry.accessType} must name the ordered prefetch migration follow-up",
            )
        }
    }

    @Test
    fun `runtime infra read calls are cataloged before loop use`() {
        val discovered = runtimeSourceFiles()
            .flatMap { (path, text) -> runtimeReadCalls(path, text) }
            .groupingBy { it }
            .eachCount()

        assertEquals(HotColdCatalog.runtimeCallKeys, discovered.keys)
        assertEquals(HotColdCatalog.runtimeCallCounts, discovered)
    }

    @Test
    fun `adversarial repository detection is method name default deny`() {
        val path = "app/game-engine/src/main/kotlin/opensamguk/engine/probe/Probe.kt"
        val probe = """
            package opensamguk.engine.probe

            import opensamguk.infra.read.AuctionRepository

            class Probe(private val auctionRepository: AuctionRepository?) {
                fun run() {
                    auctionRepository!!.loadOpenForPhase()
                    val repo = auctionRepository ?: return
                    repo.findOpenForPhase()
                    repo.fetchOpenForPhase()
                    repo.lookupOpenForPhase()
                    repo.getOpenForPhase()
                    repo.streamOpenForPhase()
                    repo.existsOpenForPhase()
                }
            }
        """.trimIndent()

        val calls = runtimeReadCalls(path, probe).toSet()

        assertTrue("$path:auctionRepository.loadOpenForPhase" in calls, calls.toString())
        assertTrue("$path:repo.findOpenForPhase" in calls, calls.toString())
        assertTrue("$path:repo.fetchOpenForPhase" in calls, calls.toString())
        assertTrue("$path:repo.lookupOpenForPhase" in calls, calls.toString())
        assertTrue("$path:repo.getOpenForPhase" in calls, calls.toString())
        assertTrue("$path:repo.streamOpenForPhase" in calls, calls.toString())
        assertTrue("$path:repo.existsOpenForPhase" in calls, calls.toString())
    }

    @Test
    fun `direct SQL calls stay in cataloged cold boundaries`() {
        val directSqlSources = runtimeSourceFiles()
            .flatMap { (path, text) -> directSqlCalls(path, text) }
            .mapTo(linkedSetOf()) { it.substringBeforeLast(":") }

        assertEquals(HotColdCatalog.runtimeDirectSqlBoundarySources, directSqlSources)
        for (entry in HotColdCatalog.runtimeDirectSqlBoundaries) {
            assertEquals(DataTemperature.QUERY_ONLY_COLD, entry.temperature, entry.sourceFile)
            assertFalse(entry.relation.isBlank(), entry.sourceFile)
            assertFalse(entry.ordering.isBlank(), entry.sourceFile)
            assertTrue(
                entry.followUp.contains("S5-T2"),
                "${entry.sourceFile} must name the cold-boundary follow-up",
            )
        }
    }

    @Test
    fun `adversarial direct SQL detection covers alternate APIs`() {
        val path = "app/game-engine/src/main/kotlin/opensamguk/engine/probe/SqlProbe.kt"
        val probe = """
            package opensamguk.engine.probe

            class SqlProbe(
                private val jdbc: org.springframework.jdbc.core.JdbcOperations,
                private val named: org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations,
                private val connection: java.sql.Connection,
            ) {
                fun run() {
                    jdbc.queryForList("select 1")
                    named.queryForList("select 1", mapOf<String, Any>())
                    named.queryForStream("select 1", mapOf<String, Any>()) { rs, _ -> rs.getInt(1) }
                    named.queryForRowSet("select 1", mapOf<String, Any>())
                    connection.prepareStatement("select 1")
                }
            }
        """.trimIndent()

        val calls = directSqlCalls(path, probe).toSet()

        assertTrue("$path:jdbc.queryForList" in calls, calls.toString())
        assertTrue("$path:named.queryForList" in calls, calls.toString())
        assertTrue("$path:named.queryForStream" in calls, calls.toString())
        assertTrue("$path:named.queryForRowSet" in calls, calls.toString())
        assertTrue("$path:connection.prepareStatement" in calls, calls.toString())
    }

    @Test
    fun `turn runtime source scope includes reachable helper directories`() {
        assertTrue(
            "app/game-engine/src/main/kotlin/opensamguk/engine/turn" in HotColdCatalog.runtimeSourceDirectories,
        )
        assertTrue(
            "app/game-engine/src/main/kotlin/opensamguk/engine/redis" in HotColdCatalog.runtimeSourceDirectories,
        )
    }

    private fun enclosingPrivateMethod(source: String, offset: Int): String {
        val prefix = source.substring(0, offset)
        return Regex("""private fun ([A-Za-z0-9_]+)\b""")
            .findAll(prefix)
            .lastOrNull()
            ?.groupValues
            ?.get(1)
            ?: "<top-level>"
    }

    private fun runtimeReadCalls(path: String, text: String): List<String> {
        val normalized = stripComments(text).replace(Regex("""\n\s*\."""), ".")
        val receivers = repositoryReceivers(normalized).toMutableSet()
        var changed = true
        while (changed) {
            changed = false
            for ((alias, source) in repositoryAliases(normalized)) {
                if (source in receivers && receivers.add(alias)) {
                    changed = true
                }
            }
        }

        val receiverCalls = if (receivers.isEmpty()) {
            emptySequence()
        } else {
            val receiverPattern = receivers.joinToString("|") { Pattern.quote(it) }
            Regex("""\b($receiverPattern)\??(?:!!)?\s*\.\s*([A-Za-z_][A-Za-z0-9_]*)\s*\(""")
                .findAll(normalized)
                .mapNotNull { match ->
                    val method = match.groupValues[2]
                    if (isRuntimeReadMethod(method)) {
                        "$path:${match.groupValues[1]}.$method"
                    } else {
                        null
                    }
                }
        }

        val factoryCalls = repositoryFactoryNames(normalized).asSequence().flatMap { factoryName ->
            Regex("""\b${Pattern.quote(factoryName)}\(\)(?:!!)?\??\s*\.\s*([A-Za-z_][A-Za-z0-9_]*)\s*\(""")
                .findAll(normalized)
                .mapNotNull { match ->
                    val method = match.groupValues[1]
                    if (isRuntimeReadMethod(method)) {
                        "$path:${factoryName}().$method"
                    } else {
                        null
                    }
                }
        }
        val implicitCalls = if (hasRepositoryExtension(normalized)) {
            Regex("""(?<![.A-Za-z0-9_])((?:find|read|claim|count|sum|target|mark|load)[A-Za-z0-9_]*)\s*\(""")
                .findAll(normalized)
                .map { match -> "$path:<implicit>.${match.groupValues[1]}" }
        } else {
            emptySequence()
        }
        return (receiverCalls + factoryCalls + implicitCalls).toList()
    }

    private fun directSqlCalls(path: String, text: String): List<String> {
        val normalized = stripComments(text).replace(Regex("""\n\s*\."""), ".")
        val receivers = sqlReceivers(normalized)
        if (receivers.isEmpty()) return emptyList()
        val receiverPattern = receivers.joinToString("|") { Pattern.quote(it) }
        return Regex("""\b($receiverPattern)\??(?:!!)?\s*\.\s*([A-Za-z_][A-Za-z0-9_]*)\s*\(""")
            .findAll(normalized)
            .mapNotNull { match ->
                val method = match.groupValues[2]
                if (isDirectSqlMethod(method)) {
                    "$path:${match.groupValues[1]}.$method"
                } else {
                    null
                }
            }
            .toList()
    }

    private fun repositoryReceivers(source: String): Set<String> {
        return Regex("""\b(?:private\s+)?(?:val|var)?\s*([a-z][A-Za-z0-9_]*)\s*:\s*(?:[A-Za-z0-9_.]+\.)?[A-Za-z0-9_]*(?:Repository|Reader)\??""")
            .findAll(source)
            .mapTo(linkedSetOf()) { it.groupValues[1] }
    }

    private fun repositoryAliases(source: String): List<Pair<String, String>> {
        val direct = Regex("""(?m)\bval\s+([a-z][A-Za-z0-9_]*)\s*=\s*([a-z][A-Za-z0-9_]*)[ \t]*$""")
            .findAll(source)
            .map { it.groupValues[1] to it.groupValues[2] }
        val elvisReturn = Regex("""\bval\s+([a-z][A-Za-z0-9_]*)\s*=\s*([a-z][A-Za-z0-9_]*)\s*\?:\s*return\b""")
            .findAll(source)
            .map { it.groupValues[1] to it.groupValues[2] }
        val required = Regex("""\bval\s+([a-z][A-Za-z0-9_]*)\s*=\s*requireNotNull\(\s*([a-z][A-Za-z0-9_]*)\s*\)""")
            .findAll(source)
            .map { it.groupValues[1] to it.groupValues[2] }
        val letAliases = Regex("""\b([a-z][A-Za-z0-9_]*)\??\s*\.\s*let\s*\{\s*([a-z][A-Za-z0-9_]*)\s*->""")
            .findAll(source)
            .map { it.groupValues[2] to it.groupValues[1] }
        return (direct + elvisReturn + required + letAliases).toList()
    }

    private fun repositoryFactoryNames(source: String): List<String> =
        Regex("""\bfun\s+([a-z][A-Za-z0-9_]*)\(\)\s*:\s*(?:[A-Za-z0-9_.]+\.)?[A-Za-z0-9_]*(?:Repository|Reader)\??""")
            .findAll(source)
            .map { it.groupValues[1] }
            .toList()

    private fun hasRepositoryExtension(source: String): Boolean =
        Regex("""\bfun\s+(?:[A-Za-z0-9_.]+\.)?[A-Za-z0-9_]*(?:Repository|Reader)\.""").containsMatchIn(source)

    private fun isRuntimeReadMethod(method: String): Boolean =
        method !in setOf(
            "also",
            "apply",
            "bettingInfoReader",
            "equals",
            "hashCode",
            "lastBettingIdReader",
            "let",
            "previousPointReader",
            "run",
            "toString",
        )

    private fun isDirectSqlMethod(method: String): Boolean =
        method !in setOf("also", "apply", "equals", "hashCode", "let", "run", "toString")

    private fun sqlReceivers(source: String): Set<String> {
        val sqlType = """(?:(?:org\.springframework\.jdbc\.core\.)?(?:JdbcTemplate|JdbcOperations)|(?:org\.springframework\.jdbc\.core\.namedparam\.)?(?:NamedParameterJdbcTemplate|NamedParameterJdbcOperations)|(?:java\.sql\.)?Connection|(?:javax\.sql\.)?DataSource)"""
        return Regex("""\b(?:private\s+)?(?:val|var)?\s*([a-z][A-Za-z0-9_]*)\s*:\s*$sqlType\??""")
            .findAll(source)
            .mapTo(linkedSetOf()) { it.groupValues[1] }
    }

    private fun stripComments(text: String): String =
        text.replace(Regex("""(?s)/\*.*?\*/"""), "")
            .lines()
            .joinToString("\n") { it.substringBefore("//") }
}
