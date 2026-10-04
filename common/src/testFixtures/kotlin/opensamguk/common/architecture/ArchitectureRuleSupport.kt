package opensamguk.common.architecture

import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Test-only ArchUnit measurements. Existing violations are reported; detector faults still fail tests. */
object ArchitectureRuleSupport {
    private val modules = mapOf(
        "common" to setOf("A4", "A5"),
        "logic" to setOf("A4", "A5"),
        "infra" to setOf("A5", "A6"),
        "game-api" to setOf("A1", "A2", "A5", "A6"),
        "game-engine" to setOf("A1", "A2", "A3", "A5", "A6"),
        "gateway-api" to setOf("A1", "A2", "A5", "A6"),
        "board-api" to setOf("A1", "A2", "A5", "A6"),
    )

    data class Measurement(val count: Int, val scopedClasses: Int, val examples: List<String>)

    fun reportOnly(module: String, rootPackage: String) {
        val rules = requireNotNull(modules[module]) { "Unknown architecture module $module" }
        val mainOutputs = listOf(Path.of("build/classes/kotlin/main"), Path.of("build/classes/java/main"))
            .filter { Files.isDirectory(it) }
        check(mainOutputs.isNotEmpty()) { "ArchUnit found no main class directories for $module" }
        val classes = ClassFileImporter().importPaths(mainOutputs)
        val included = classes.toList().filter { it.packageName == rootPackage || it.packageName.startsWith("$rootPackage.") }
        check(included.isNotEmpty()) { "ArchUnit imported no main classes for $module ($rootPackage)" }
        val measured = detect(classes, rootPackage).filterKeys { it in rules }.toSortedMap()
        check(measured.keys == rules) { "ArchUnit rule scope drift for $module: ${measured.keys} != $rules" }
        val baseline = loadBaseline(module, rules)
        val json = buildJsonObject {
            put("module", module)
            put("rootPackage", rootPackage)
            put("importedClasses", included.size)
            put("scopedClasses", buildJsonObject { measured.forEach { (id, value) -> put(id, value.scopedClasses) } })
            put("measured", buildJsonObject { measured.forEach { (id, value) -> put(id, value.count) } })
            put("baseline", buildJsonObject { baseline.forEach { (id, value) ->
                if (value == null) put(id, JsonNull) else put(id, value)
            } })
            put("delta", buildJsonObject { measured.forEach { (id, value) ->
                val previous = baseline.getValue(id)
                if (previous == null) put(id, JsonNull) else put(id, value.count - previous)
            } })
        }
        val output = Path.of("build", "reports", "archunit", "measurements.json")
        Files.createDirectories(output.parent)
        Files.writeString(output, json.toString() + "\n")
        println("ARCHUNIT_REPORT $json")
        measured.forEach { (id, value) ->
            println("ARCHUNIT_DETAIL module=$module rule=$id scope=${value.scopedClasses} measured=${value.count} " +
                "baseline=${baseline.getValue(id) ?: "UNMEASURED"} " +
                "examples=${value.examples.take(3).joinToString(" | ")}")
        }
    }

    /** Public to the fixed probe test; never gates existing product violations in report-only mode. */
    fun detect(classes: JavaClasses, cycleRoot: String): Map<String, Measurement> {
        val hits = (1..6).associate { "A$it" to linkedMapOf<String, MutableSet<String>>() }
        val scoped = (1..6).associate { "A$it" to 0 }.toMutableMap()
        fun hit(rule: String, origin: JavaClass, target: String) {
            hits.getValue(rule).getOrPut(origin.name) { linkedSetOf() } += target
        }
        classes.forEach { origin ->
            val controller = origin.simpleName.endsWith("Controller") ||
                origin.annotations.any { it.rawType.name == "org.springframework.web.bind.annotation.RestController" }
            val engineApplication = origin.packageName.startsWith("opensamguk.engine.campaign") ||
                origin.packageName.startsWith("opensamguk.engine.intake") ||
                origin.packageName.startsWith("opensamguk.engine.turn") ||
                (origin.packageName.startsWith("opensamguk.engine.") &&
                    listOf("Handler", "Executor", "NpcSelector").any { origin.simpleName.endsWith(it) })
            val domain = origin.packageName.startsWith("opensamguk.logic") ||
                origin.packageName.startsWith("opensamguk.common")
            val adapter = origin.packageName.startsWith("opensamguk.infra") ||
                origin.packageName.startsWith("opensamguk.gameapi.read") ||
                origin.packageName.startsWith("opensamguk.boardapi.security") ||
                listOf("profile", "security", "config").any {
                    origin.packageName.startsWith("opensamguk.gateway.$it")
                } ||
                listOf("flush", "redis", "boot", "config").any {
                    origin.packageName.startsWith("opensamguk.engine.$it")
                }
            if (controller) {
                scoped["A1"] = scoped.getValue("A1") + 1
                scoped["A2"] = scoped.getValue("A2") + 1
            }
            if (engineApplication) scoped["A3"] = scoped.getValue("A3") + 1
            if (domain) scoped["A4"] = scoped.getValue("A4") + 1
            if (origin.packageName.startsWith("$cycleRoot.")) scoped["A5"] = scoped.getValue("A5") + 1
            if (adapter) scoped["A6"] = scoped.getValue("A6") + 1
            origin.directDependenciesFromSelf.forEach { dependency ->
                val target = dependency.targetClass.name
                val simple = target.substringAfterLast('.').substringBefore('$')
                if (controller && ((target.startsWith("opensamguk.") &&
                        (simple.endsWith("Repository") || simple.endsWith("Reader"))) ||
                        target.startsWith("org.springframework.jdbc.") ||
                        target.startsWith("org.springframework.data.redis.") ||
                        target == "jakarta.persistence.EntityManager")) hit("A1", origin, target)
                if (controller && target.startsWith("opensamguk.logic.")) hit("A2", origin, target)
                if (engineApplication && target.startsWith("opensamguk.infra.")) hit("A3", origin, target)
                if (domain && isForbiddenDomainDependency(target)) hit("A4", origin, target)
                if (adapter && isWebOrApplication(target)) hit("A6", origin, target)
            }
            if (domain) origin.methodCallsFromSelf.forEach { call ->
                val owner = call.target.owner.name
                val method = call.target.name
                if ((owner == "java.lang.System" && method in setOf("currentTimeMillis", "nanoTime", "getenv")) ||
                    (owner.startsWith("java.time.") && (method == "now" || method.startsWith("system")))) {
                    hit("A4", origin, "$owner.$method")
                }
            }
        }
        val cycleDetails = slices().matching("$cycleRoot.(**)").should().beFreeOfCycles()
            .evaluate(classes).failureReport.details
        return hits.mapValues { (rule, origins) ->
            Measurement(origins.size, scoped.getValue(rule), origins.entries.sortedBy { it.key }.take(3).map { (name, targets) ->
                "$name -> ${targets.sorted().take(2).joinToString()}"
            })
        } + ("A5" to Measurement(cycleDetails.size, scoped.getValue("A5"), cycleDetails.take(3)))
    }

    private fun isForbiddenDomainDependency(target: String): Boolean =
        listOf("org.springframework.", "jakarta.persistence.", "javax.persistence.", "java.sql.",
            "opensamguk.infra.", "opensamguk.engine.", "opensamguk.gameapi.",
            "opensamguk.gateway.", "opensamguk.boardapi.").any(target::startsWith)

    private fun isWebOrApplication(target: String): Boolean =
        (target.startsWith("opensamguk.gameapi.") && target.substringAfterLast('.').endsWith("Controller")) ||
        target.startsWith("opensamguk.gameapi.controller.") ||
            target.startsWith("opensamguk.gameapi.web.") ||
            target.startsWith("opensamguk.gameapi.reserve.") ||
            target.startsWith("opensamguk.gameapi.precheck.") ||
            target.startsWith("opensamguk.engine.campaign.") ||
            target.startsWith("opensamguk.engine.intake.") ||
            target.startsWith("opensamguk.engine.turn.") ||
            (target.startsWith("opensamguk.boardapi.") &&
                listOf("Controller", "Service", "Handler").any { target.substringAfterLast('.').endsWith(it) }) ||
            (target.startsWith("opensamguk.gateway.") &&
                listOf("Controller", "Service").any { target.substringAfterLast('.').endsWith(it) })

    private fun loadBaseline(module: String, rules: Set<String>): Map<String, Int?> {
        val resource = checkNotNull(javaClass.getResourceAsStream("/archunit-baseline.json")) {
            "Missing ArchUnit-only baseline resource"
        }
        val all = resource.bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
        check(all["schemaVersion"]?.jsonPrimitive?.int == 1) { "Unknown ArchUnit baseline schema" }
        val row = checkNotNull(all["modules"]?.jsonObject?.get(module)) { "Missing ArchUnit baseline for $module" }.jsonObject
        check(row.keys == rules) { "ArchUnit baseline rule scope drift for $module" }
        return row.mapValues { (id, value) ->
            if (value == JsonNull) null
            else value.jsonPrimitive.int.also { check(it >= 0) { "Negative ArchUnit baseline $module/$id" } }
        }
    }
}
