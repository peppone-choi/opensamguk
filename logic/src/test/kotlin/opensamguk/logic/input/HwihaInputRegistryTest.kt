package opensamguk.logic.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.actions.RestAction
import opensamguk.logic.stats.GeneralActionPipeline

/** 계약: docs/superpowers/specs/2026-09-17-input-registry-contract.md §2·§5·§8. */
class HwihaInputRegistryTest {
    private val catalog = HwihaInputCatalog.load()
    private val registry = HwihaInputRegistry(catalog, handlers = emptyMap())

    private fun reject(profile: RuleProfile, raw: String): InputRejection =
        assertIs<InputResolution.Rejected>(registry.resolve(profile, raw)).reason

    @Test
    fun `unknown input is an explicit failure, never rest`() {
        assertEquals(InputRejection.UNKNOWN_INPUT, reject(RuleProfile.HWIHA, "action.doesNotExist"))
    }

    @Test
    fun `legacy command code in a hwiha world is rejected, not rested`() {
        assertEquals(InputRejection.WRONG_RULE_PROFILE, reject(RuleProfile.HWIHA, "che_농지개간"))
    }

    @Test
    fun `hwiha input in a sammo world is rejected`() {
        assertEquals(InputRejection.WRONG_RULE_PROFILE, reject(RuleProfile.SAMMO, "action.enlist"))
    }

    @Test
    fun `malformed ids and kind-prefix mismatches are rejected`() {
        assertEquals(InputRejection.MALFORMED_INPUT_ID, reject(RuleProfile.HWIHA, "enlist"))
        assertEquals(InputRejection.MALFORMED_INPUT_ID, reject(RuleProfile.HWIHA, "action."))
        assertEquals(InputRejection.MALFORMED_INPUT_ID, reject(RuleProfile.HWIHA, "nonsense.enlist"))
    }

    @Test
    fun `catalogued input without a handler is NOT_DELIVERED, not success`() {
        assertEquals(InputRejection.NOT_DELIVERED, reject(RuleProfile.HWIHA, "action.enlist"))
    }

    @Test
    fun `input with a registered handler resolves`() {
        val handler = InputHandler { }
        val wired = HwihaInputRegistry(catalog, mapOf("action.enlist" to handler), requireDelivered = false)
        val resolved = assertIs<InputResolution.Resolved>(wired.resolve(RuleProfile.HWIHA, "action.enlist"))
        assertEquals(InputKind.GENERAL_ACTION, resolved.entry.kind)
        assertTrue(resolved.handler === handler)
    }

    @Test
    fun `handler for an id missing from the ledger fails construction`() {
        assertFailsWith<IllegalArgumentException> {
            HwihaInputRegistry(catalog, mapOf("action.ghost" to InputHandler { }), requireDelivered = false)
        }
    }

    @Test
    fun `ledger delivery state and handler wiring must agree`() {
        // PLANNED 인데 핸들러가 있으면(또는 그 반대면) 원장과 코드가 어긋난 것이다.
        assertFailsWith<IllegalArgumentException> {
            HwihaInputRegistry(catalog, mapOf("action.enlist" to InputHandler { }))
        }
    }

    @Test
    fun `rule profile defaults to SAMMO only when absent and fails closed on unknown text`() {
        assertEquals(RuleProfile.SAMMO, RuleProfile.fromWorldConfig(null))
        assertEquals(RuleProfile.HWIHA, RuleProfile.fromWorldConfig("HWIHA"))
        assertFailsWith<IllegalArgumentException> { RuleProfile.fromWorldConfig("hwiha") }
        assertFailsWith<IllegalArgumentException> { RuleProfile.fromWorldConfig("") }
    }

    @Test
    fun `every inputId prefix matches its kind and ids are unique`() {
        assertEquals(catalog.entries.size, catalog.entries.map { it.inputId }.toSet().size)
        catalog.entries.forEach { assertEquals(it.kind, InputKind.ofPrefix(it.inputId.substringBefore('.'))) }
    }

    @Test
    fun `replacesLegacy names only commands the SAMMO registry really has`() {
        // CommandRegistry.resolve 는 모르는 코드를 RestAction 으로 돌려준다 — 그 폴백에 걸리면 지어낸 코드다.
        val sammo = CommandRegistry(GeneralActionPipeline())
        catalog.entries.flatMap { it.replacesLegacy }.forEach { code ->
            assertTrue(sammo.resolve(code) !== RestAction, "원장의 replacesLegacy 가 없는 명령을 가리킨다: $code")
        }
    }

    @Test
    fun `duplicate or unknown ledger fields fail closed`() {
        val dup = """{"schemaVersion":1,"inputs":[
            {"inputId":"action.a","kind":"GENERAL_ACTION","layer":1,"deliveryState":"PLANNED","replacesLegacy":[]},
            {"inputId":"action.a","kind":"GENERAL_ACTION","layer":1,"deliveryState":"PLANNED","replacesLegacy":[]}]}"""
        assertFailsWith<IllegalArgumentException> { HwihaInputCatalog.parse(dup) }
        val badState = dup.replace("\"PLANNED\"", "\"DONE\"")
        assertFailsWith<IllegalArgumentException> { HwihaInputCatalog.parse(badState) }
        val badKind = """{"schemaVersion":1,"inputs":[{"inputId":"policy.a","kind":"GENERAL_ACTION","layer":1,"deliveryState":"PLANNED","replacesLegacy":[]}]}"""
        assertFailsWith<IllegalArgumentException> { HwihaInputCatalog.parse(badKind) }
    }
}
