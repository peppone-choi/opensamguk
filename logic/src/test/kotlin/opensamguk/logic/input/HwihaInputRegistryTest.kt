package opensamguk.logic.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import opensamguk.common.constants.GameConst
import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.actions.RestAction
import opensamguk.logic.stats.GeneralActionPipeline

/** 계약: docs/superpowers/specs/2026-09-17-input-registry-contract.md §2·§5·§8. */
class HwihaInputRegistryTest {
    private val catalog = HwihaInputCatalog.load()
    private var enlistCalls = 0
    private fun handlers(enlist: InputHandler) = mapOf("action.enlist" to enlist, "action.deploy" to InputHandler {},
        "court.dispatch" to InputHandler {}, "court.dispatchReply" to InputHandler {})
    private val registry = HwihaInputRegistry(catalog, handlers(InputHandler { enlistCalls++ }))

    // 작업 디렉터리가 모듈이든 저장소 루트든(IDE 러너) 같은 파일을 찾는다 — CommandContractMatrixTest 의 관례.
    private fun repoRoot(): java.nio.file.Path {
        var path = java.nio.file.Path.of("").toAbsolutePath()
        while (!java.nio.file.Files.exists(path.resolve("settings.gradle.kts"))) {
            path = path.parent ?: error("Could not locate repo root")
        }
        return path
    }

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
    fun `every legacy command code is WRONG_RULE_PROFILE in a hwiha world`() {
        // 한 개만 보면 정규식을 `^che_.+$` 로 좁혀도 초록이다(교차 비평 변이 M7). 실제 코드 전수를 돈다.
        val source = repoRoot().resolve("logic/src/main/kotlin/opensamguk/logic/actions/CommandRegistry.kt").toFile().readText()
        val branchKeys = Regex("""^\s+"([^"]+)"\s*->""", RegexOption.MULTILINE).findAll(source).map { it.groupValues[1] }.toSet()
        val constKeys = (GameConst.availableGeneralCommand.values + GameConst.availableChiefCommand.values).flatten().toSet()
        assertTrue(branchKeys.size >= 90 && constKeys.size >= 60, "코드 목록을 못 읽었다: ${branchKeys.size}/${constKeys.size}")
        assertTrue(branchKeys.any { it.startsWith("cr_") } && branchKeys.any { it.startsWith("event_") })
        (branchKeys + constKeys).forEach { code ->
            assertEquals(InputRejection.WRONG_RULE_PROFILE, reject(RuleProfile.HWIHA, code), code)
        }
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
        assertEquals(InputRejection.NOT_DELIVERED, reject(RuleProfile.HWIHA, "placement.assign"))
    }

    @Test
    fun `input with a registered handler resolves`() {
        var called = false
        val handler = InputHandler { called = true }
        val wired = HwihaInputRegistry(catalog, handlers(handler))
        val resolved = assertIs<InputResolution.Resolved>(wired.resolve(RuleProfile.HWIHA, "action.enlist"))
        assertEquals(InputKind.GENERAL_ACTION, resolved.entry.kind)
        assertTrue(resolved.handler === handler)
        resolved.handler.handle()
        assertTrue(called)
        assertEquals(InputDeliveryState.HANDLER_READY, resolved.entry.deliveryState)
    }

    @Test
    fun `handler for an id missing from the ledger fails construction`() {
        assertFailsWith<IllegalArgumentException> {
            HwihaInputRegistry.forWiringTest(catalog, mapOf("action.ghost" to InputHandler { }))
        }
    }

    @Test
    fun `ledger delivery state and handler wiring must agree`() {
        // PLANNED 인데 핸들러가 있으면(또는 그 반대면) 원장과 코드가 어긋난 것이다.
        assertFailsWith<IllegalArgumentException> {
            HwihaInputRegistry(catalog, emptyMap())
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

    private val sammo = CommandRegistry(GeneralActionPipeline())

    // CommandRegistry.resolve 는 모르는 코드를 RestAction 으로 돌려준다 — 그 폴백에 걸리면 지어낸 코드다.
    private fun isRealSammoCommand(code: String): Boolean = sammo.resolve(code) !== RestAction

    /** 기존 명령 70개(장수 46 + 사령턴 24) — 재설계 §12 의 대응 대상. 두 목록에 함께 있는 「휴식」 때문에 고유 코드는 69다. */
    private val legacy70Slots = (GameConst.availableGeneralCommand.values + GameConst.availableChiefCommand.values).flatten()
    private val legacy70 = legacy70Slots.distinct()

    private fun row(inputId: String, kind: String, legacy: String) =
        """{"inputId":"$inputId","kind":"$kind","layer":1,"deliveryState":"PLANNED","legacyCommands":[$legacy]}"""

    private fun ledger(vararg rows: String) = HwihaInputCatalog.parse("""{"schemaVersion":1,"inputs":[${rows.joinToString(",")}]}""")

    @Test
    fun `legacyCommands names only commands the SAMMO registry really has`() {
        assertEquals(emptyList(), catalog.unknownLegacyCommands(::isRealSammoCommand), "원장의 legacyCommands 가 없는 명령을 가리킨다")
        assertTrue(catalog.legacyIndex.keys.all { it in legacy70 }, "역참조가 기존 명령 70개 밖을 가리킨다: ${catalog.legacyIndex.keys - legacy70.toSet()}")
        assertEquals(70, legacy70Slots.size, "기존 명령 70개를 못 읽었다")
        assertEquals(listOf("휴식"), legacy70Slots.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.toList())
    }

    @Test
    fun `a legacy name that is not a real command fails the check`() {
        // 적색 프로브: 검사가 살아 있으면 지어낸 이름이 반드시 걸린다. 진짜 명령은 같은 원장에서 통과해야 한다.
        val fake = ledger(row("action.drill", "GENERAL_ACTION", "\"che_징병\",\"che_없는명령\""))
        assertEquals(listOf("che_없는명령"), fake.unknownLegacyCommands(::isRealSammoCommand))
        assertTrue(isRealSammoCommand("che_징병"))
    }

    @Test
    fun `one legacy command may be referenced by a direct-action row and a delegation row`() {
        // #837: 직접 행동(기존 이름 그대로)과 위임(방침)이 같은 기존 명령을 함께 가리킨다 — 다대일은 정상이다.
        val manyToOne = ledger(
            row("action.conscript", "GENERAL_ACTION", "\"che_징병\""),
            row("policy.conscript", "POLICY", "\"che_징병\""),
        )
        assertEquals(listOf("action.conscript", "policy.conscript"), manyToOne.legacyIndex.getValue("che_징병").map { it.inputId })
        assertEquals(emptyList(), manyToOne.unknownLegacyCommands(::isRealSammoCommand))
        val uncovered = manyToOne.uncoveredLegacyCommands(legacy70)
        assertTrue("che_징병" !in uncovered)
        assertEquals(legacy70.size - 1, uncovered.size)
    }

    @Test
    fun `legacy coverage is computed over the real 70 commands`() {
        val covered = legacy70.size - catalog.uncoveredLegacyCommands(legacy70).size
        assertEquals(catalog.legacyIndex.keys.size, covered)
    }

    @Test
    fun `stale replacesLegacy field and in-row duplicates fail closed`() {
        val stale = """{"schemaVersion":1,"inputs":[{"inputId":"action.a","kind":"GENERAL_ACTION","layer":1,"deliveryState":"PLANNED","replacesLegacy":[]}]}"""
        assertFailsWith<IllegalArgumentException> { HwihaInputCatalog.parse(stale) }
        assertFailsWith<IllegalArgumentException> { ledger(row("action.a", "GENERAL_ACTION", "\"che_징병\",\"che_징병\"")) }
    }

    @Test
    fun `duplicate or unknown ledger fields fail closed`() {
        val dup = """{"schemaVersion":1,"inputs":[
            {"inputId":"action.a","kind":"GENERAL_ACTION","layer":1,"deliveryState":"PLANNED","legacyCommands":[]},
            {"inputId":"action.a","kind":"GENERAL_ACTION","layer":1,"deliveryState":"PLANNED","legacyCommands":[]}]}"""
        assertFailsWith<IllegalArgumentException> { HwihaInputCatalog.parse(dup) }
        val badState = dup.replace("\"PLANNED\"", "\"DONE\"")
        assertFailsWith<IllegalArgumentException> { HwihaInputCatalog.parse(badState) }
        val badKind = """{"schemaVersion":1,"inputs":[{"inputId":"policy.a","kind":"GENERAL_ACTION","layer":1,"deliveryState":"PLANNED","legacyCommands":[]}]}"""
        assertFailsWith<IllegalArgumentException> { HwihaInputCatalog.parse(badKind) }
    }
}
