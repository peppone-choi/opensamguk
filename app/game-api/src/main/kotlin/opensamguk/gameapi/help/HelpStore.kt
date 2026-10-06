package opensamguk.gameapi.help

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import opensamguk.logic.input.InputCatalog
import opensamguk.logic.input.InputEntry
import org.springframework.stereotype.Component
import java.security.MessageDigest

data class HelpSection(
    val explanation: String,
    val example: String,
    val successExample: String,
    val failureExample: String,
    val recoveryAdvice: String,
    val historicalContext: String?,
)

data class HistoricalSource(
    val tradition: String,
    val work: String,
    val book: String,
    val passage: String?,
)

enum class HelpReviewState { DRAFT, APPROVED }
enum class HelpTopicGroup { INPUT, CONCEPT, TUTORIAL }

data class HelpTopicSummary(
    val id: String,
    val title: String,
    val reviewState: HelpReviewState,
    val group: HelpTopicGroup,
    val inputId: String?,
    val inputKind: String?,
    val excerpt: String,
)

data class HelpTopic(
    val id: String,
    val title: String,
    val reviewState: HelpReviewState,
    val sections: HelpSection,
    val sources: List<HistoricalSource>,
    val relatedTopicIds: List<String>,
)

data class FailureReasonHelp(
    val code: String,
    val reviewState: HelpReviewState,
    val explanation: String,
    val recoveryAdvice: String,
    val byInputId: Map<String, FailureReasonOverride>,
)

data class FailureReasonOverride(val explanation: String, val recoveryAdvice: String)

/** 사람 글은 저장소 파일에서 읽고, 수치·권한 같은 입력 계약은 InputCatalog에서 읽는다. */
class HelpStore private constructor(
    val catalog: InputCatalog,
    val topics: Map<String, HelpTopic>,
    val reasons: Map<String, FailureReasonHelp>,
    val topicGroups: Map<String, HelpTopicGroup>,
) {
    fun topic(id: String): HelpTopic? = topics[id]
    fun input(id: String): InputEntry? = catalog[id]
    fun reason(code: String): FailureReasonHelp? = reasons[code]

    val topicSummaries: List<HelpTopicSummary> = topics.values.map { topic ->
        val input = catalog.entries.singleOrNull { it.helpTopicId == topic.id }
        HelpTopicSummary(
            topic.id, topic.title, topic.reviewState, topicGroups.getValue(topic.id),
            input?.inputId, input?.kind?.name, topic.sections.explanation.substringBefore("\n\n").trim(),
        )
    }.sortedWith(compareBy<HelpTopicSummary> { it.group.ordinal }.thenBy { it.id })

    val topicSummariesEtag: String = "\"" + MessageDigest.getInstance("SHA-256")
        .digest(topicSummaries.joinToString("\u001f") { summary ->
            listOf(summary.id, summary.title, summary.reviewState.name, summary.group.name,
                summary.inputId.orEmpty(), summary.inputKind.orEmpty(), summary.excerpt).joinToString("\u001e")
        }.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) } + "\""

    fun search(query: String, limit: Int): List<HelpSearchHit> = topics.values.mapNotNull { topic ->
        val q = query.lowercase()
        val section = when {
            q in topic.title.lowercase() -> "title"
            q in topic.sections.explanation.lowercase() -> "explanation"
            q in topic.sections.example.lowercase() -> "example"
            else -> null
        } ?: return@mapNotNull null
        val excerpt = when (section) {
            "title" -> topic.sections.explanation
            "explanation" -> topic.sections.explanation
            else -> topic.sections.example
        }
        HelpSearchHit(topic.id, topic.title, topic.reviewState, excerpt, section)
    }.sortedWith(compareBy<HelpSearchHit> { when (it.matchedSection) {
        "title" -> 0; "explanation" -> 1; else -> 2
    } }.thenBy { it.id }).take(limit)

    companion object {
        fun load(): HelpStore {
            val loader = HelpStore::class.java.classLoader
            fun read(name: String): String = checkNotNull(loader.getResource("help/$name")) {
                "help resource missing: $name"
            }.readText()
            return parse(read("topics.json"), read("failure-reasons.json"), InputCatalog.load(), read("topic-registry.json"))
        }

        fun parse(
            topicsJson: String,
            reasonsJson: String,
            catalog: InputCatalog,
            registryJson: String = """{"schemaVersion":1,"topics":[]}""",
        ): HelpStore {
            val topicRoot = Json.parseToJsonElement(topicsJson).jsonObject
            val reasonRoot = Json.parseToJsonElement(reasonsJson).jsonObject
            val registryRoot = Json.parseToJsonElement(registryJson).jsonObject
            require(topicRoot.version() == 1 && reasonRoot.version() == 1 && registryRoot.version() == 1) {
                "unsupported help schemaVersion"
            }
            require(topicRoot.keys == setOf("schemaVersion", "topics")) { "invalid help topic file schema" }
            require(registryRoot.keys == setOf("schemaVersion", "topics")) { "invalid help topic registry schema" }
            val registrations = registryRoot.getValue("topics").jsonArray.map { rowElement ->
                val row = rowElement.jsonObject
                require(row.keys == setOf("id", "group")) { "invalid help topic registration" }
                val id = row.text("id")
                val group = HelpTopicGroup.valueOf(row.text("group"))
                require(group != HelpTopicGroup.INPUT && extraTopicId.matches(id)) { "invalid extra help topic: $id" }
                require((group == HelpTopicGroup.CONCEPT) == id.startsWith("concepts.")) {
                    "help topic group does not match id: $id"
                }
                id to group
            }
            val registered = registrations.toMap()
            require(registered.size == registrations.size) { "duplicate help topic registration" }
            val topicRows = topicRoot.getValue("topics").jsonArray.map(::parseTopic)
            val reasonRows = reasonRoot.getValue("reasons").jsonArray.map(::parseReason)
            val topics = topicRows.associateBy { it.id }
            val reasons = reasonRows.associateBy { it.code }
            require(topics.size == topicRows.size) { "duplicate help topic" }
            require(reasons.size == reasonRows.size) { "duplicate failure reason" }
            val expectedTopics = catalog.entries.map { it.helpTopicId }.toSet()
            val expectedReasons = catalog.entries.flatMap { it.failureReasons }.toSet()
            require(expectedTopics.size == catalog.entries.size) { "duplicate catalog help topic" }
            require(registered.keys.intersect(expectedTopics).isEmpty()) { "extra help topic replaces catalog input topic" }
            require(topics.keys == expectedTopics + registered.keys) {
                "help topics differ from input catalog and registry: missing=${(expectedTopics + registered.keys) - topics.keys}, " +
                    "orphan=${topics.keys - expectedTopics - registered.keys}"
            }
            require(reasons.keys == expectedReasons) {
                "failure reasons differ from input catalog: missing=${expectedReasons - reasons.keys}, orphan=${reasons.keys - expectedReasons}"
            }
            topics.values.forEach { topic ->
                require(topic.relatedTopicIds.all { it in topics }) { "unknown related topic: ${topic.id}" }
                require(topic.sources.all { it.tradition in setOf("CHRONICLE", "ROMANCE") }) { "unknown source tradition: ${topic.id}" }
            }
            reasons.values.forEach { reason ->
                reason.byInputId.keys.forEach { id ->
                    require(reason.code in checkNotNull(catalog[id]) { "unknown context input: $id" }.failureReasons) {
                        "reason override not declared by input: ${reason.code}/$id"
                    }
                }
            }
            val groups = expectedTopics.associateWith { HelpTopicGroup.INPUT } + registered
            return HelpStore(catalog, topics, reasons, groups)
        }

        private val extraTopicId = Regex("^(concepts|tutorial)\\.[a-z][A-Za-z0-9]*$")

        private fun JsonObject.version(): Int = getValue("schemaVersion").jsonPrimitive.content.toInt()
        private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.let { value ->
            require(value.isString && value.content.isNotBlank()) { "invalid help text field: $key" }
            value.content
        }
        private fun JsonObject.optionalText(key: String): String? = get(key)?.takeUnless { it == JsonNull }?.jsonPrimitive?.content
        private fun JsonObject.texts(key: String): List<String> = getValue(key).jsonArray.map { it.jsonPrimitive.content }
        private fun JsonObject.reviewState(): HelpReviewState = HelpReviewState.valueOf(text("reviewState"))

        private fun parseTopic(element: JsonElement): HelpTopic {
            val row = element.jsonObject
            require(row.keys == setOf("id", "title", "reviewState", "sections", "sources", "relatedTopicIds")) {
                "invalid help topic schema"
            }
            val section = row.getValue("sections").jsonObject
            require(section.keys - setOf("explanation", "example", "successExample", "failureExample", "recoveryAdvice", "historicalContext") == emptySet<String>() &&
                section.keys.containsAll(setOf("explanation", "example", "successExample", "failureExample", "recoveryAdvice"))) {
                "invalid help section schema"
            }
            val sources = row.getValue("sources").jsonArray.map {
                val source = it.jsonObject
                require(source.keys - setOf("tradition", "work", "book", "passage") == emptySet<String>() &&
                    source.keys.containsAll(setOf("tradition", "work", "book"))) { "invalid help source schema" }
                HistoricalSource(source.text("tradition"), source.text("work"), source.text("book"), source.optionalText("passage"))
            }
            return HelpTopic(
                row.text("id"), row.text("title"), row.reviewState(),
                HelpSection(section.text("explanation"), section.text("example"), section.text("successExample"),
                    section.text("failureExample"), section.text("recoveryAdvice"), section.optionalText("historicalContext")),
                sources, row.texts("relatedTopicIds"),
            )
        }

        private fun parseReason(element: JsonElement): FailureReasonHelp {
            val row = element.jsonObject
            val overrides = row.getValue("byInputId").jsonObject.mapValues { (_, value) ->
                val item = value.jsonObject
                FailureReasonOverride(item.text("explanation"), item.text("recoveryAdvice"))
            }
            return FailureReasonHelp(row.text("code"), row.reviewState(), row.text("explanation"), row.text("recoveryAdvice"), overrides)
        }
    }
}

data class HelpSearchHit(val id: String, val title: String, val reviewState: HelpReviewState, val excerpt: String, val matchedSection: String)

@Component
class HelpStoreProvider {
    val store: HelpStore = HelpStore.load()
}
