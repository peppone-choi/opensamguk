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
    private val registry = resource("topic-registry.json")

    @Test
    fun `every catalog topic and failure reason has one human help entry`() {
        val store = parse()
        val inputTopicIds = catalog.entries.map { it.helpTopicId }.toSet()
        val registrations = Json.parseToJsonElement(registry).jsonObject.getValue("topics").jsonArray
            .map { it.jsonObject }
        val registeredIds = registrations.map { it.getValue("id").jsonPrimitive.content }.toSet()
        assertEquals(inputTopicIds + registeredIds, store.topics.keys)
        val tutorialIds = registrations.filter { it.getValue("group").jsonPrimitive.content == "TUTORIAL" }
            .map { it.getValue("id").jsonPrimitive.content }
        assertTrue(tutorialIds.all { store.topic(it)?.reviewState == HelpReviewState.APPROVED })
        assertEquals(catalog.entries.flatMap { it.failureReasons }.toSet(), store.reasons.keys)
        assertEquals("STATE_UNAVAILABLE", store.reason("STATE_UNAVAILABLE")?.code)
        assertTrue(store.reason("STATE_UNAVAILABLE")!!.byInputId.containsKey("work.start"))
        assertTrue(store.reasons.values.all { it.reviewState == HelpReviewState.DRAFT })
    }

    @Test
    fun `missing topic and orphan topic both fail closed`() {
        val missing = topics.replaceFirst("\"id\": \"commands.action.enlist\"", "\"id\": \"commands.action.missing\"")
        assertFailsWith<IllegalArgumentException> { parse(missing) }
        val duplicate = topics.replaceFirst("\"id\": \"commands.action.deploy\"", "\"id\": \"commands.action.enlist\"")
        assertFailsWith<IllegalArgumentException> { parse(duplicate) }
    }

    @Test
    fun `missing failure explanation and undeclared context both fail closed`() {
        val blank = reasons.replaceFirst("행동할 장수를 찾을 수 없습니다.", "")
        assertFailsWith<IllegalArgumentException> { parse(reasonsJson = blank) }
        val removed = reasons.replaceFirst("\"code\": \"ACTOR_NOT_FOUND\"", "\"code\": \"REMOVED_REASON\"")
        assertFailsWith<IllegalArgumentException> { parse(reasonsJson = removed) }
        val badContext = reasons.replaceFirst("\"work.start\": {", "\"unknown.input\": {")
        assertFailsWith<IllegalStateException> { parse(reasonsJson = badContext) }
    }

    @Test
    fun `human prose must declare a known review state`() {
        val missing = topics.replaceFirst("\"reviewState\": \"DRAFT\",", "")
        assertFailsWith<IllegalArgumentException> { parse(missing) }
        val unknown = reasons.replaceFirst("\"reviewState\": \"DRAFT\"", "\"reviewState\": \"UNKNOWN\"")
        assertFailsWith<IllegalArgumentException> { parse(reasonsJson = unknown) }
        val approved = topics.replaceFirst("\"reviewState\": \"DRAFT\"", "\"reviewState\": \"APPROVED\"")
        assertEquals(HelpReviewState.APPROVED, parse(approved).topic("commands.action.enlist")?.reviewState)
    }

    @Test
    fun `search prefers title before body and never emits unknown topic`() {
        val store = parse()
        val hits = store.search("출사", 20)
        assertTrue(hits.isNotEmpty())
        assertEquals("commands.action.enlist", hits.first().id)
        assertEquals("title", hits.first().matchedSection)
        assertEquals(store.topic(hits.first().id)?.reviewState, hits.first().reviewState)
        assertTrue(hits.all { it.id in store.topics })
    }

    @Test
    fun `registered concepts and tutorial topics appear in the typed topic list`() {
        val store = HelpStore.parse(withExtraTopics(), reasons, catalog, withExtraRegistry())
        val inputSummaries = store.topicSummaries.filter { it.group == HelpTopicGroup.INPUT }
        assertEquals(catalog.entries.size, inputSummaries.size)
        assertEquals(catalog.entries.map { it.helpTopicId }.toSet(), inputSummaries.map { it.id }.toSet())
        val registeredIds = Json.parseToJsonElement(registry).jsonObject.getValue("topics").jsonArray
            .map { it.jsonObject.getValue("id").jsonPrimitive.content }.toSet()
        assertEquals(registeredIds + setOf("concepts.createGeneral", "tutorial.extraProbe"),
            store.topicSummaries.filter { it.group != HelpTopicGroup.INPUT }.map { it.id }.toSet())
        assertEquals(catalog.entries.size + registeredIds.size + 2, store.topicSummaries.size)
        val input = store.topicSummaries.first { it.id == "commands.action.enlist" }
        assertEquals(HelpTopicGroup.INPUT, input.group)
        assertEquals("action.enlist", input.inputId)
        assertEquals("GENERAL_ACTION", input.inputKind)
        val concept = store.topicSummaries.first { it.id == "concepts.createGeneral" }
        assertEquals(HelpTopicGroup.CONCEPT, concept.group)
        assertEquals(null, concept.inputId)
        assertEquals(null, concept.inputKind)
        assertEquals(HelpTopicGroup.TUTORIAL, store.topicSummaries.first { it.id == "tutorial.extraProbe" }.group)
        assertEquals(HelpReviewState.APPROVED, store.topic("tutorial.extraProbe")?.reviewState)
        assertTrue(store.topicSummariesEtag.startsWith('"') && store.topicSummariesEtag.endsWith('"'))
    }

    @Test
    fun `topic list excerpt stops after the first paragraph`() {
        val modified = changeExtraTopic("concepts.createGeneral") { row ->
            val sections = row.getValue("sections").jsonObject
            JsonObject(row + ("sections" to JsonObject(sections +
                ("explanation" to JsonPrimitive("첫 문단입니다.\n\n뒤 문단입니다.")))))
        }
        val store = HelpStore.parse(modified, reasons, catalog, withExtraRegistry())
        val summary = store.topicSummaries.single { it.id == "concepts.createGeneral" }
        assertEquals("첫 문단입니다.", summary.excerpt)
    }

    @Test
    fun `unregistered or mismatched extra topic fails closed`() {
        val extra = withExtraTopics()
        assertFailsWith<IllegalArgumentException> { HelpStore.parse(extra, reasons, catalog, registry) }
        val extraRegistry = withExtraRegistry()
        val wrongGroup = extraRegistry.replace("\"group\":\"CONCEPT\"", "\"group\":\"TUTORIAL\"")
        assertFailsWith<IllegalArgumentException> { HelpStore.parse(extra, reasons, catalog, wrongGroup) }
        val arbitrary = extraRegistry.replace("concepts.createGeneral", "concepts.arbitrary")
        assertFailsWith<IllegalArgumentException> { HelpStore.parse(extra, reasons, catalog, arbitrary) }
        val unknownType = extraRegistry.replace("\"CONCEPT\"", "\"INPUT\"")
        assertFailsWith<IllegalArgumentException> { HelpStore.parse(extra, reasons, catalog, unknownType) }
        val badId = extraRegistry.replace("concepts.createGeneral", "concepts../createGeneral")
        assertFailsWith<IllegalArgumentException> { HelpStore.parse(extra, reasons, catalog, badId) }
    }

    @Test
    fun `extra topic rejects unknown fields and broken related links`() {
        val registry = withExtraRegistry()
        val unknownField = changeExtraTopic("concepts.createGeneral") { row ->
            JsonObject(row + ("unreviewed" to JsonPrimitive(true)))
        }
        assertFailsWith<IllegalArgumentException> { HelpStore.parse(unknownField, reasons, catalog, registry) }
        val badLink = changeExtraTopic("tutorial.extraProbe") { row ->
            JsonObject(row + ("relatedTopicIds" to JsonArray(listOf(JsonPrimitive("concepts.unknown")))))
        }
        assertFailsWith<IllegalArgumentException> { HelpStore.parse(badLink, reasons, catalog, registry) }
    }

    @Test
    fun `human help sections do not duplicate catalog numeric rules`() {
        val store = parse()
        store.topics.values.forEach { topic ->
            val sections = topic.sections
            listOf(sections.explanation, sections.example, sections.successExample,
                sections.failureExample, sections.recoveryAdvice).forEach { prose ->
                assertTrue(prose.none { it.isDigit() }, "numeric rule copied into ${topic.id}")
            }
        }
    }

    private fun resource(name: String): String = checkNotNull(javaClass.classLoader.getResource("help/$name")).readText()

    private fun parse(topicsJson: String = topics, reasonsJson: String = reasons): HelpStore =
        HelpStore.parse(topicsJson, reasonsJson, catalog, registry)

    private fun withExtraRegistry(): String {
        val root = Json.parseToJsonElement(registry).jsonObject
        val extra = Json.parseToJsonElement(resource("topic-registry-registered.json")).jsonObject
            .getValue("topics").jsonArray.map { item ->
                val row = item.jsonObject
                if (row.getValue("id").jsonPrimitive.content == "tutorial.createGeneral")
                    JsonObject(row + ("id" to JsonPrimitive("tutorial.extraProbe"))) else item
            }
        return JsonObject(root + ("topics" to JsonArray(root.getValue("topics").jsonArray + extra))).toString()
    }

    private fun withExtraTopics(): String {
        val root = Json.parseToJsonElement(topics).jsonObject
        val extra = Json.parseToJsonElement(resource("extra-topics.json")).jsonArray.map { item ->
            val row = item.jsonObject
            if (row.getValue("id").jsonPrimitive.content == "tutorial.createGeneral")
                JsonObject(row + ("id" to JsonPrimitive("tutorial.extraProbe")) +
                    ("reviewState" to JsonPrimitive("APPROVED"))) else item
        }
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
