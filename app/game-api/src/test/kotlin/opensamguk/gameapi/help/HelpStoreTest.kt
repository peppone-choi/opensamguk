package opensamguk.gameapi.help

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
    fun `search prefers title before body and never emits unknown topic`() {
        val store = HelpStore.parse(topics, reasons, catalog)
        val hits = store.search("출사", 20)
        assertTrue(hits.isNotEmpty())
        assertEquals("commands.action.enlist", hits.first().id)
        assertEquals("title", hits.first().matchedSection)
        assertTrue(hits.all { it.id in store.topics })
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
}
