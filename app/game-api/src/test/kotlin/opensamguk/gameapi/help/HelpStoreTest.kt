package opensamguk.gameapi.help

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import opensamguk.logic.input.InputCatalog
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HelpStoreTest {
    private val catalog = InputCatalog.load()
    private val topics = resource("topics.json")
    private val reasons = resource("failure-reasons.json")

    @Test
    fun `every catalog topic and failure reason has one human help entry`() {
        val store = HelpStore.parse(topics, reasons, catalog)
        assertEquals(catalog.entries.map { it.helpTopicId }.toSet(), store.topics.keys)
        assertEquals(catalog.entries.flatMap { it.failureReasons }.toSet(), store.reasons.keys)
        assertEquals("STATE_UNAVAILABLE", store.reason("STATE_UNAVAILABLE")?.code)
        assertTrue(store.reason("STATE_UNAVAILABLE")!!.byInputId.containsKey("work.start"))
        assertTrue(store.topics.values.all { it.reviewState == HelpReviewState.DRAFT })
        assertTrue(store.reasons.values.all { it.reviewState == HelpReviewState.DRAFT })
    }

    @Test
    fun `missing topic and orphan topic both fail closed`() {
        val missing = topics.replaceFirst("\"id\": \"commands.action.enlist\"", "\"id\": \"commands.action.missing\"")
        assertFailsWith<IllegalArgumentException> { HelpStore.parse(missing, reasons, catalog) }
        val duplicate = topics.replaceFirst("\"id\": \"commands.action.deploy\"", "\"id\": \"commands.action.enlist\"")
        assertFailsWith<IllegalArgumentException> { HelpStore.parse(duplicate, reasons, catalog) }
    }

    @Test
    fun `missing failure explanation and undeclared context both fail closed`() {
        val blank = reasons.replaceFirst("행동할 장수를 찾을 수 없습니다.", "")
        assertFailsWith<IllegalArgumentException> { HelpStore.parse(topics, blank, catalog) }
        val removed = reasons.replaceFirst("\"code\": \"ACTOR_NOT_FOUND\"", "\"code\": \"REMOVED_REASON\"")
        assertFailsWith<IllegalArgumentException> { HelpStore.parse(topics, removed, catalog) }
        val badContext = reasons.replaceFirst("\"work.start\": {", "\"unknown.input\": {")
        assertFailsWith<IllegalStateException> { HelpStore.parse(topics, badContext, catalog) }
    }

    @Test
    fun `human prose must declare a known review state`() {
        val missing = topics.replaceFirst("\"reviewState\": \"DRAFT\",", "")
        assertFailsWith<IllegalArgumentException> { HelpStore.parse(missing, reasons, catalog) }
        val unknown = reasons.replaceFirst("\"reviewState\": \"DRAFT\"", "\"reviewState\": \"UNKNOWN\"")
        assertFailsWith<IllegalArgumentException> { HelpStore.parse(topics, unknown, catalog) }
    }

    @Test
    fun `search prefers title before body and never emits unknown topic`() {
        val store = HelpStore.parse(topics, reasons, catalog)
        val hits = store.search("출사", 20)
        assertTrue(hits.isNotEmpty())
        assertEquals("commands.action.enlist", hits.first().id)
        assertEquals("title", hits.first().matchedSection)
        assertEquals(HelpReviewState.DRAFT, hits.first().reviewState)
        assertTrue(hits.all { it.id in store.topics })
    }

    @Test
    fun `registered concepts and tutorial topics appear in the typed topic list`() {
        val store = HelpStore.parse(withExtraTopics(), reasons, catalog, resource("topic-registry-registered.json"))
        assertEquals(92, store.topicSummaries.size)
        val input = store.topicSummaries.first { it.id == "commands.action.enlist" }
        assertEquals(HelpTopicGroup.INPUT, input.group)
        assertEquals("action.enlist", input.inputId)
        assertEquals("GENERAL_ACTION", input.inputKind)
        val concept = store.topicSummaries.first { it.id == "concepts.createGeneral" }
        assertEquals(HelpTopicGroup.CONCEPT, concept.group)
        assertEquals(null, concept.inputId)
        assertEquals(null, concept.inputKind)
        assertEquals(HelpTopicGroup.TUTORIAL, store.topicSummaries.first { it.id == "tutorial.createGeneral" }.group)
        assertTrue(store.topicSummariesEtag.startsWith('"') && store.topicSummariesEtag.endsWith('"'))
    }

    @Test
    fun `topic list excerpt stops after the first paragraph`() {
        val modified = changeExtraTopic("concepts.createGeneral") { row ->
            val sections = row.getValue("sections").jsonObject
            JsonObject(row + ("sections" to JsonObject(sections +
                ("explanation" to JsonPrimitive("첫 문단입니다.\n\n뒤 문단입니다.")))))
        }
        val store = HelpStore.parse(modified, reasons, catalog, resource("topic-registry-registered.json"))
        val summary = store.topicSummaries.single { it.id == "concepts.createGeneral" }
        assertEquals("첫 문단입니다.", summary.excerpt)
    }

    @Test
    fun `unregistered or mismatched extra topic fails closed`() {
        val extra = withExtraTopics()
        assertFailsWith<IllegalArgumentException> { HelpStore.parse(extra, reasons, catalog) }
        val registry = resource("topic-registry-registered.json")
        val wrongGroup = registry.replace("\"group\": \"CONCEPT\"", "\"group\": \"TUTORIAL\"")
        assertFailsWith<IllegalArgumentException> { HelpStore.parse(extra, reasons, catalog, wrongGroup) }
        val arbitrary = registry.replace("concepts.createGeneral", "concepts.arbitrary")
        assertFailsWith<IllegalArgumentException> { HelpStore.parse(extra, reasons, catalog, arbitrary) }
        val unknownType = registry.replace("\"CONCEPT\"", "\"INPUT\"")
        assertFailsWith<IllegalArgumentException> { HelpStore.parse(extra, reasons, catalog, unknownType) }
        val badId = registry.replace("concepts.createGeneral", "concepts../createGeneral")
        assertFailsWith<IllegalArgumentException> { HelpStore.parse(extra, reasons, catalog, badId) }
    }

    @Test
    fun `extra topic rejects unknown fields and broken related links`() {
        val registry = resource("topic-registry-registered.json")
        val unknownField = changeExtraTopic("concepts.createGeneral") { row ->
            JsonObject(row + ("unreviewed" to JsonPrimitive(true)))
        }
        assertFailsWith<IllegalArgumentException> { HelpStore.parse(unknownField, reasons, catalog, registry) }
        val badLink = changeExtraTopic("tutorial.createGeneral") { row ->
            JsonObject(row + ("relatedTopicIds" to JsonArray(listOf(JsonPrimitive("concepts.unknown")))))
        }
        assertFailsWith<IllegalArgumentException> { HelpStore.parse(badLink, reasons, catalog, registry) }
    }

    @Test
    fun `human help sections do not duplicate catalog numeric rules`() {
        val store = HelpStore.parse(topics, reasons, catalog)
        store.topics.values.forEach { topic ->
            val sections = topic.sections
            listOf(sections.explanation, sections.example, sections.successExample,
                sections.failureExample, sections.recoveryAdvice).forEach { prose ->
                assertTrue(prose.none { it.isDigit() }, "numeric rule copied into ${topic.id}")
            }
        }
    }

    private fun resource(name: String): String = checkNotNull(javaClass.classLoader.getResource("help/$name")).readText()

    private fun withExtraTopics(): String {
        val root = Json.parseToJsonElement(topics).jsonObject
        val extra = Json.parseToJsonElement(resource("extra-topics.json")).jsonArray
        return JsonObject(root + ("topics" to JsonArray(root.getValue("topics").jsonArray + extra))).toString()
    }

    private fun changeExtraTopic(id: String, change: (JsonObject) -> JsonObject): String {
        val root = Json.parseToJsonElement(withExtraTopics()).jsonObject
        val rows = root.getValue("topics").jsonArray.map { item ->
            val row = item.jsonObject
            if (row.getValue("id").jsonPrimitive.content == id) change(row) else item
        }
        return JsonObject(root + ("topics" to JsonArray(rows))).toString()
    }
}
