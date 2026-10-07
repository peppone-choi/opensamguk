package opensamguk.logic.input

import opensamguk.logic.domestic.FieldInput
import opensamguk.logic.domestic.FieldFailure

import opensamguk.logic.domestic.DomesticInput

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import opensamguk.common.constants.GameConst

/** 계약: docs/superpowers/specs/2026-09-17-input-registry-contract.md §2·§5·§8. */
class InputRegistryTest {
    private val catalog = InputCatalog.load()
    private var enlistCalls = 0
    private fun handlers(enlist: InputHandler) = (mapOf("action.enlist" to enlist, "action.deploy" to InputHandler {},
        "action.scout" to InputHandler {}, "action.assault" to InputHandler {}, "action.demandSurrender" to InputHandler {},
        "action.siegeRoadFort" to InputHandler {},
        "placement.assign" to InputHandler {}, "policy.set" to InputHandler {}, "work.start" to InputHandler {},
        "work.reduce" to InputHandler {},
        "court.dispatch" to InputHandler {}, "court.dispatchReply" to InputHandler {}, "court.reward" to InputHandler {},
        CaptiveReleaseInput.INPUT_ID to InputHandler {},
        PoliticalConsent.COURT_INPUT_ID to InputHandler {},
        "action.move" to InputHandler {}, "action.forcedMarch" to InputHandler {}, "action.return" to InputHandler {},
        "action.farm" to InputHandler {}, "action.commerce" to InputHandler {}, "action.fortify" to InputHandler {},
        "action.repairWall" to InputHandler {}, "action.security" to InputHandler {}, "action.settle" to InputHandler {},
        "action.selectResidents" to InputHandler {}, "action.tour" to InputHandler {},
        "action.conscript" to InputHandler {}, "action.raiseVolunteers" to InputHandler {},
        "action.train" to InputHandler {}, "action.boostMorale" to InputHandler {},
        "action.demobilize" to InputHandler {}, "action.muster" to InputHandler {},
        "action.search" to InputHandler {}, "action.employ" to InputHandler {}, PeopleInput.PERSUADE_CAPTIVE to InputHandler {},
        "action.travel" to InputHandler {},
        "action.selfTrain" to InputHandler {}, "action.recuperate" to InputHandler {},
        "action.foundState" to InputHandler {}, "action.abdicate" to InputHandler {}, "action.oath" to InputHandler {},
        "action.resign" to InputHandler {},
        "action.gift" to InputHandler {},
        "action.convertProficiency" to InputHandler {}, "action.tradeEquipment" to InputHandler {},
        "action.tradeGrain" to InputHandler {}, "action.transport" to InputHandler {},
        "court.releaseCorps" to InputHandler {}, "court.diplomacy" to InputHandler {},
        "court.abandonCounty" to InputHandler {}, "court.institution" to InputHandler {},
        "court.moveCapital" to InputHandler {}, "court.confiscate" to InputHandler {},
        "court.nonAggression" to InputHandler {}, "court.declareWar" to InputHandler {},
        "court.offerPeace" to InputHandler {}, "court.breakNonAggression" to InputHandler {}) +
        StratagemInput.INPUT_IDS.associateWith { InputHandler {} }).filterKeys { catalog[it]?.deliveryState?.hasHandler == true }
    private val registry = InputRegistry(catalog, handlers(InputHandler { enlistCalls++ }))

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
    fun `old stratagems remain planned until card ownership exists`() {
        for (id in StratagemInput.INPUT_IDS + "stratagem.play") {
            assertEquals(InputDeliveryState.PLANNED, catalog[id]!!.deliveryState, id)
            assertEquals(InputRejection.NOT_DELIVERED, reject(RuleProfile.HWIHA, id))
        }
    }

    @Test
    fun `catalog classification is shared by engine dispatch and API precheck`() {
        for (id in listOf("che_요양", "action.ghost", "stratagem.play", "invalid")) {
            assertEquals(reject(RuleProfile.HWIHA, id), catalog.rejectionFor(RuleProfile.HWIHA, id), id)
        }
        assertEquals(null, catalog.rejectionFor(RuleProfile.HWIHA, "court.dispatch"))
    }

    @Test
    fun `domestic standing inputs are handler ready and must be wired`() {
        for (id in listOf("placement.assign", "policy.set", "work.start")) {
            assertEquals(InputDeliveryState.HANDLER_READY, catalog[id]!!.deliveryState, id)
            assertIs<InputResolution.Resolved>(registry.resolve(RuleProfile.HWIHA, id))
            // Dropping one handler must break construction: a delivered row cannot fall back to NOT_DELIVERED.
            assertFailsWith<IllegalArgumentException>(id) {
                InputRegistry(catalog, handlers(InputHandler { }) - id)
            }
        }
    }

    @Test
    fun `every direct field action is UI ready and has a handler`() {
        for (id in FieldInput.INPUT_IDS) {
            assertEquals(InputDeliveryState.UI_READY, catalog[id]!!.deliveryState, id)
            assertEquals(FieldFailure.entries.map { it.name }.toSet(),
                catalog[id]!!.failureReasons.toSet() - setOf("UNKNOWN_INPUT", "NOT_DELIVERED", "UNAUTHORIZED",
                    "FORBIDDEN", "INVALID_TURN_SLOT"), id)
            assertIs<InputResolution.Resolved>(registry.resolve(RuleProfile.HWIHA, id))
            assertFailsWith<IllegalArgumentException>(id) {
                InputRegistry(catalog, handlers(InputHandler { }) - id)
            }
        }
    }

    @Test
    fun `direct military actions expose their delivery state and have a handler`() {
        for (id in MilitaryInput.INPUT_IDS) {
            assertEquals(if (id == MilitaryInput.MUSTER) InputDeliveryState.HANDLER_READY
                else InputDeliveryState.UI_READY, catalog[id]!!.deliveryState, id)
            assertIs<InputResolution.Resolved>(registry.resolve(RuleProfile.HWIHA, id))
            assertFailsWith<IllegalArgumentException>(id) {
                InputRegistry(catalog, handlers(InputHandler { }) - id)
            }
        }
    }

    @Test
    fun `people actions expose only delivered handlers`() {
        for (id in PeopleInput.INPUT_IDS) {
            assertEquals(PeopleFailure.entries.map { it.name }.toSet(),
                catalog[id]!!.failureReasons.toSet() - setOf("UNKNOWN_INPUT", "NOT_DELIVERED", "UNAUTHORIZED",
                    "FORBIDDEN", "INVALID_TURN_SLOT"), id)
            if (id == PeopleInput.PERSUADE_CAPTIVE) {
                assertEquals(InputDeliveryState.HANDLER_READY, catalog[id]!!.deliveryState)
                assertIs<InputResolution.Resolved>(registry.resolve(RuleProfile.HWIHA, id))
            } else {
                assertEquals(InputDeliveryState.UI_READY, catalog[id]!!.deliveryState, id)
                assertIs<InputResolution.Resolved>(registry.resolve(RuleProfile.HWIHA, id))
                assertFailsWith<IllegalArgumentException>(id) {
                    InputRegistry(catalog, handlers(InputHandler { }) - id)
                }
            }
        }
    }

    @Test
    fun `captive release is a delivered standing decision with a required handler`() {
        val id = CaptiveReleaseInput.INPUT_ID
        assertEquals("court.releaseCaptive", id)
        assertEquals(InputKind.COURT_DECISION, catalog[id]!!.kind)
        assertEquals(InputDeliveryState.HANDLER_READY, catalog[id]!!.deliveryState)
        assertIs<InputResolution.Resolved>(registry.resolve(RuleProfile.HWIHA, id))
        assertFailsWith<IllegalArgumentException> { InputRegistry(catalog, handlers(InputHandler { }) - id) }
    }

    @Test
    fun `political actions expose only delivered handlers`() {
        for (id in PoliticalRules.SUPPORTED_IDS) {
            assertEquals(PoliticalFailure.entries.map { it.name }.toSet(),
                catalog[id]!!.failureReasons.toSet() - setOf("UNKNOWN_INPUT", "NOT_DELIVERED", "UNAUTHORIZED",
                    "FORBIDDEN", "INVALID_TURN_SLOT"), id)
            if (id in setOf(PoliticalInput.FOUND_STATE, PoliticalInput.ABDICATE, PoliticalInput.OATH,
                    PoliticalInput.RESIGN)) {
                assertEquals(InputDeliveryState.UI_READY, catalog[id]!!.deliveryState, id)
                assertIs<InputResolution.Resolved>(registry.resolve(RuleProfile.HWIHA, id))
                assertFailsWith<IllegalArgumentException>(id) {
                    InputRegistry(catalog, handlers(InputHandler { }) - id)
                }
            } else {
                assertEquals(InputDeliveryState.PLANNED, catalog[id]!!.deliveryState, id)
                assertIs<InputResolution.Rejected>(registry.resolve(RuleProfile.HWIHA, id))
            }
        }
    }

    @Test
    fun `gift is delivered and donation waits for a warehouse destination`() {
        for (id in TransferInput.INPUT_IDS) {
            assertEquals(TransferFailure.entries.map { it.name }.toSet(),
                catalog[id]!!.failureReasons.toSet() - setOf("UNKNOWN_INPUT", "NOT_DELIVERED", "UNAUTHORIZED",
                    "FORBIDDEN", "INVALID_TURN_SLOT"), id)
            if (id == TransferInput.GIFT) {
                assertEquals(InputDeliveryState.UI_READY, catalog[id]!!.deliveryState)
                assertIs<InputResolution.Resolved>(registry.resolve(RuleProfile.HWIHA, id))
            } else {
                assertEquals(InputDeliveryState.PLANNED, catalog[id]!!.deliveryState)
                assertIs<InputResolution.Rejected>(registry.resolve(RuleProfile.HWIHA, id))
            }
        }
    }

    @Test
    fun `the four legacy direct actions are delivered through the common executor`() {
        for (id in DirectInput.INPUT_IDS) {
            assertEquals(InputDeliveryState.UI_READY,
                catalog[id]!!.deliveryState, id)
            val equipmentFailures = setOf("EQUIPMENT_UNAVAILABLE", "EQUIPMENT_SLOT_OCCUPIED", "EQUIPMENT_NOT_OWNED", "INSUFFICIENT_SECURITY")
            assertEquals(DirectFailure.entries.map { it.name }.toSet() - if (id == DirectInput.EQUIPMENT) emptySet() else equipmentFailures,
                catalog[id]!!.failureReasons.toSet() - setOf("UNKNOWN_INPUT", "NOT_DELIVERED", "UNAUTHORIZED",
                    "FORBIDDEN", "INVALID_TURN_SLOT"), id)
            assertIs<InputResolution.Resolved>(registry.resolve(RuleProfile.HWIHA, id))
        }
    }

    @Test
    fun `legacy court and stratagem rows match their shared failure vocabularies`() {
        val channelFailures = setOf("UNKNOWN_INPUT", "NOT_DELIVERED", "UNAUTHORIZED", "FORBIDDEN")
        for (id in CourtInput.INPUT_IDS) {
            val delivered = id in setOf("court.releaseCorps", "court.abandonCounty", "court.moveCapital")
            assertEquals(if (delivered) InputDeliveryState.UI_READY else InputDeliveryState.PLANNED,
                catalog[id]!!.deliveryState, id)
            assertEquals(CourtFailure.entries.map { it.name }.toSet(),
                catalog[id]!!.failureReasons.toSet() - channelFailures, id)
            if (delivered) assertIs<InputResolution.Resolved>(registry.resolve(RuleProfile.HWIHA, id))
            else assertEquals(InputRejection.NOT_DELIVERED, reject(RuleProfile.HWIHA, id))
        }
        for (id in StratagemInput.INPUT_IDS) {
            assertEquals(InputDeliveryState.PLANNED, catalog[id]!!.deliveryState, id)
            assertEquals(StratagemFailure.entries.map { it.name }.toSet(),
                catalog[id]!!.failureReasons.toSet() - channelFailures, id)
            assertEquals(InputRejection.NOT_DELIVERED, reject(RuleProfile.HWIHA, id))
        }
        assertEquals(InputDeliveryState.PLANNED, catalog[DomesticInput.REDUCE]!!.deliveryState)
        assertEquals(InputRejection.NOT_DELIVERED, reject(RuleProfile.HWIHA, DomesticInput.REDUCE))
        val reduceFailures = setOf("WRONG_RULE_PROFILE", "INVALID_REQUEST", "ACTOR_NOT_FOUND", "INVALID_COUNTY",
            "NOT_COUNTY_AUTHORITY", "WORK_IN_PROGRESS", "WORK_NOT_COMPLETED", "STATE_UNAVAILABLE")
        assertEquals(reduceFailures, catalog[DomesticInput.REDUCE]!!.failureReasons.toSet() - channelFailures)
    }

    @Test
    fun `every field personal action is UI ready and has a handler`() {
        for (id in PersonalInput.FIELD_IDS) {
            assertEquals(InputDeliveryState.UI_READY, catalog[id]!!.deliveryState, id)
            assertEquals(PersonalFailure.entries.map { it.name }.toSet(),
                catalog[id]!!.failureReasons.toSet() - setOf("UNKNOWN_INPUT", "NOT_DELIVERED", "UNAUTHORIZED",
                    "FORBIDDEN", "INVALID_TURN_SLOT"), id)
            assertIs<InputResolution.Resolved>(registry.resolve(RuleProfile.HWIHA, id))
            assertFailsWith<IllegalArgumentException>(id) {
                InputRegistry(catalog, handlers(InputHandler { }) - id)
            }
        }
    }

    @Test
    fun `retirement remains planned until succession state can be preserved`() {
        val id = RetireInput.INPUT_ID
        assertEquals(InputDeliveryState.PLANNED, catalog[id]!!.deliveryState)
        assertEquals("POLITICS", catalog[id]!!.timing.getValue("phase").jsonPrimitive.content)
        assertEquals(RetireFailure.entries.map { it.name }.toSet(),
            catalog[id]!!.failureReasons.toSet() - setOf("UNKNOWN_INPUT", "NOT_DELIVERED", "UNAUTHORIZED",
                "FORBIDDEN", "INVALID_TURN_SLOT"))
        assertIs<InputResolution.Rejected>(registry.resolve(RuleProfile.HWIHA, id))
    }

    @Test
    fun `input with a registered handler resolves`() {
        var called = false
        val handler = InputHandler { called = true }
        val wired = InputRegistry(catalog, handlers(handler))
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
            InputRegistry.forWiringTest(catalog, mapOf("action.ghost" to InputHandler { }))
        }
    }

    @Test
    fun `ledger delivery state and handler wiring must agree`() {
        // PLANNED 인데 핸들러가 있으면(또는 그 반대면) 원장과 코드가 어긋난 것이다.
        assertFailsWith<IllegalArgumentException> {
            InputRegistry(catalog, emptyMap())
        }
    }

    @Test
    fun `AI ready row must bind a concrete NPC selector`() {
        val promoted = InputCatalog(catalog.entries.map { entry ->
            if (entry.inputId == "action.scout") entry.copy(deliveryState = InputDeliveryState.AI_READY) else entry
        })
        assertFailsWith<IllegalArgumentException> { AiPolicyRegistry.validate(promoted) }
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

    private fun row(inputId: String, kind: String): String {
        val timing = if (kind == "GENERAL_ACTION")
            """{"phase":"FIELD","turnSlots":12,"perPhaseLimit":1}"""
        else """{"phase":"NEXT_CARD_TURN","turnSlots":null,"perPhaseLimit":null}"""
        val displayName = if (kind == "GENERAL_ACTION") """"displayName":"테스트",""" else ""
        return """{"inputId":"$inputId","kind":"$kind","layer":1,"actor":"GENERAL","authorityRule":"SUBJECT_OWNER",
            "targetSchema":{"status":"PLANNED","source":"test"},"costSchema":{"status":"PLANNED","source":"test","money":null,"grain":null,"iron":null,"timber":null,"horses":null},
            "timing":$timing,"effectScope":"ACTOR_LOCATION","failureReasons":[],"resultType":"InputResolved",
            "replayContract":{"status":"PLANNED","key":"requestId"},"aiPolicyId":"ai.test","helpTopicId":"help.test","tutorialObjectiveId":"N/A",
            "tutorialNaReason":"E9_PENDING_U3","evidence":{},$displayName"deliveryState":"PLANNED"}"""
    }

    private fun ledger(vararg rows: String) =
        InputCatalog.parse("""{"schemaVersion":4,"catalogId":"test","status":"DRAFT","note":"test",
        "inputs":[${rows.joinToString(",")}]}""")

    private fun v5(row: String, step: String = "UNMAPPED", reason: String? = null): String =
        row.replace("\"tutorialObjectiveId\":\"N/A\"", "\"firstStepsExplanationStepId\":\"$step\"")
            .replace("\"tutorialNaReason\":\"E9_PENDING_U3\"",
                "\"firstStepsExplanationNaReason\":" + (reason?.let { "\"$it\"" } ?: "null"))

    private fun ledgerV5(vararg rows: String) =
        InputCatalog.parse("""{"schemaVersion":5,"catalogId":"test","status":"DRAFT","note":"test",
        "inputs":[${rows.joinToString(",")}]}""")

    private fun assertStratagemRows(source: InputCatalog) {
        assertEquals(StratagemInput.INPUT_IDS,
            source.entries.filter { it.kind == InputKind.STRATAGEM && it.inputId != "stratagem.play" }
                .map { it.inputId }.toSet())
    }

    @Test
    fun `ledger preserves pinned and registered candidate inputs and names every direct action`() {
        val pinned = Json.parseToJsonElement(repoRoot()
            .resolve("data/commands/input-delivery-baseline-v3.json").toFile().readText())
            .jsonObject.getValue("entries").jsonArray
            .map { it.jsonObject.getValue("inputId").jsonPrimitive.content }
        assertEquals(74, pinned.size)
        assertEquals(pinned.size, pinned.toSet().size)
        val ids = catalog.entries.map { it.inputId }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.toSet().containsAll(pinned), "pinned input missing: ${pinned.toSet() - ids.toSet()}")
        val candidates = setOf("court.appoint", "court.dismiss", "court.foundVassal",
            "court.amendVassal", "court.endVassal", "court.officeClaim", "court.officeRecognize",
            "court.edictPropose", "court.edictReview", "court.edictRegister", "court.edictSeal",
            "court.edictReply", "court.settlementPolicy", "court.proclaimEmperor",
            "court.identityAdopt", "court.unitTraditionAdopt")
        assertTrue(ids.toSet().containsAll(candidates), "registered candidate missing: ${candidates - ids.toSet()}")
        val direct = catalog.entries.filter { it.kind == InputKind.GENERAL_ACTION }
        assertEquals(43, direct.size)
        assertTrue(direct.all { !it.displayName.isNullOrBlank() })
        assertEquals("농지개간", catalog["action.farm"]?.displayName)
        assertEquals("출사", catalog["action.enlist"]?.displayName)
        assertEquals(12, StratagemInput.INPUT_IDS.size)
        assertStratagemRows(catalog)
        val rogue = catalog["stratagem.rumor"]!!.copy(inputId = "stratagem.newCard")
        val mutated = InputCatalog(catalog.entries + rogue)
        assertFailsWith<AssertionError> { assertStratagemRows(mutated) }
    }

    @Test
    fun `d32 office inputs stay planned until their handler and evidence exist`() {
        val ids = setOf("court.offerReply", "court.officeNominate", "court.officeNominationReview",
            "court.officeNominationReply", "court.appointSubordinate", "court.dismissSubordinate")
        ids.forEach { id ->
            val entry = catalog[id]!!
            assertEquals(InputDeliveryState.PLANNED, entry.deliveryState)
            assertEquals("N/A", entry.firstStepsExplanationStepId)
            assertEquals("INPUT_PLANNED", entry.firstStepsExplanationNaReason)
            assertTrue(entry.evidence.isEmpty())
            assertIs<AiPolicyBinding.Unused>(AiPolicyRegistry.bindings[entry.aiPolicyId])
        }
        assertEquals("OFFICE_HOLDER", catalog["court.appointSubordinate"]?.actor)
        assertEquals("GENERAL", catalog["court.dismissSubordinate"]?.actor)
        assertTrue(catalog.entries.none { it.inputId.startsWith("court.subordinate") })
    }

    @Test
    fun `direct action display names are required and old correspondence fields fail closed`() {
        val direct = row("action.a", "GENERAL_ACTION")
        assertFailsWith<IllegalArgumentException> {
            ledger(direct.replace("\"displayName\":\"테스트\",", ""))
        }
        assertFailsWith<IllegalArgumentException> {
            ledger(direct.replace("\"displayName\":\"테스트\"", "\"displayName\":\" \""))
        }
        assertFailsWith<IllegalArgumentException> {
            ledger(direct.replace("\"displayName\":\"테스트\"", "\"displayName\":\"테스트\",\"legacyCommands\":[]"))
        }
        assertFailsWith<IllegalArgumentException> {
            InputCatalog.parse(ledgerPayload(direct).replace("\"inputs\":", "\"retiredLegacyCommands\":[],\"inputs\":"))
        }
    }

    @Test
    fun `string arrays reject null and numeric members`() {
        val valid = row("action.a", "GENERAL_ACTION")
        for (bad in listOf("null", "123")) {
            assertFailsWith<IllegalArgumentException> {
                ledger(valid.replace("\"failureReasons\":[]", "\"failureReasons\":[$bad]"))
            }
        }
    }

    @Test
    fun `duplicate or unknown ledger fields fail closed`() {
        assertFailsWith<IllegalArgumentException> { ledger(row("action.a", "GENERAL_ACTION"), row("action.a", "GENERAL_ACTION")) }
        val dup = row("action.a", "GENERAL_ACTION")
        val badState = dup.replace("\"PLANNED\"", "\"DONE\"")
        assertFailsWith<IllegalArgumentException> { ledger(badState) }
        assertFailsWith<IllegalArgumentException> { ledger(row("policy.a", "GENERAL_ACTION")) }
        assertFailsWith<IllegalArgumentException> { ledger(dup.replace("\"aiPolicyId\":\"ai.test\",", "")) }
        assertFailsWith<IllegalArgumentException> { ledger(dup.replace("\"aiPolicyId\":\"ai.test\"", "\"aiPolicyId\":null")) }
        assertFailsWith<IllegalArgumentException> { ledger(dup.replace("\"actor\":\"GENERAL\"", "\"actor\":\"GENERAL\",\"actor\":\"GENERAL\"")) }
        assertFailsWith<IllegalArgumentException> { ledger(dup.replace("\"actor\":\"GENERAL\"", "\"actor\":\"UNKNOWN\"")) }
        assertFailsWith<IllegalArgumentException> { ledger(dup.replace("\"phase\":\"FIELD\"", "\"phase\":\"UNKNOWN\"")) }
        assertFailsWith<IllegalArgumentException> { ledger(dup.replace("\"resultType\":\"InputResolved\"", "\"resultType\":\"Other\"")) }
        assertFailsWith<IllegalArgumentException> { ledger(dup.replace("\"turnSlots\":12", "\"turnSlots\":11")) }
        assertFailsWith<IllegalArgumentException> { ledger(dup.replace("\"perPhaseLimit\":1", "\"perPhaseLimit\":2")) }
        assertFailsWith<IllegalArgumentException> { ledger(dup.replace("\"failureReasons\":[]", "\"failureReasons\":[\"BAD\",\"BAD\"]")) }
    }

    @Test
    fun `v4 requires a tutorial reason and typed evidence map`() {
        val valid = row("action.a", "GENERAL_ACTION")
        assertFailsWith<IllegalArgumentException> { ledger(valid.replace("\"tutorialNaReason\":\"E9_PENDING_U3\",", "")) }
        assertFailsWith<IllegalArgumentException> { ledger(valid.replace("\"tutorialNaReason\":\"E9_PENDING_U3\"", "\"tutorialNaReason\":null")) }
        assertFailsWith<IllegalArgumentException> { ledger(valid.replace("\"evidence\":{}", "\"evidence\":null")) }
        assertFailsWith<IllegalArgumentException> { ledger(valid.replace("\"evidence\":{}", "\"evidence\":{\"UNKNOWN\":[\"x\"]}")) }
        assertFailsWith<IllegalArgumentException> { ledger(valid.replace("\"evidence\":{}", "\"evidence\":{\"UI_READY\":[]}")) }
    }

    @Test
    fun `legacy v4 progress metadata reads as unmapped but cannot claim explanation readiness`() {
        val old = ledger(row("action.a", "GENERAL_ACTION")).entries.single()
        assertEquals("UNMAPPED", old.firstStepsExplanationStepId)
        assertEquals(null, old.firstStepsExplanationNaReason)
        val oldProgressLink = row("action.a", "GENERAL_ACTION")
            .replace("\"tutorialObjectiveId\":\"N/A\"", "\"tutorialObjectiveId\":\"tutorial.enlist\"")
            .replace("\"tutorialNaReason\":\"E9_PENDING_U3\"", "\"tutorialNaReason\":null")
        assertEquals("UNMAPPED", ledger(oldProgressLink).entries.single().firstStepsExplanationStepId)
        val claimed = row("action.a", "GENERAL_ACTION")
            .replace("\"deliveryState\":\"PLANNED\"", "\"deliveryState\":\"TUTORIAL_READY\"")
        assertFailsWith<IllegalArgumentException> { ledger(claimed) }
    }

    @Test
    fun `v5 explanation mapping has separate pending linked and not applicable states`() {
        val source = row("action.a", "GENERAL_ACTION")
        assertEquals("UNMAPPED", ledgerV5(v5(source)).entries.single().firstStepsExplanationStepId)
        assertEquals("tutorial.enlist", ledgerV5(v5(source, "tutorial.enlist")).entries.single()
            .firstStepsExplanationStepId)
        val excluded = ledgerV5(v5(source, "N/A", "NOT_IN_FIRST_STEPS_EXPLANATION")).entries.single()
        assertEquals("NOT_IN_FIRST_STEPS_EXPLANATION", excluded.firstStepsExplanationNaReason)
        assertFailsWith<IllegalArgumentException> { ledgerV5(v5(source, "N/A")) }
        assertFailsWith<IllegalArgumentException> { ledgerV5(v5(source, "unknown.step")) }
        assertFailsWith<IllegalArgumentException> { ledgerV5(v5(source, "N/A", "E9_PENDING_U3")) }
        assertFailsWith<IllegalArgumentException> {
            ledgerV5(v5(source).replace("\"firstStepsExplanationStepId\"",
                "\"tutorialObjectiveId\":\"N/A\",\"firstStepsExplanationStepId\""))
        }
    }

    @Test
    fun `numeric and nested contracts reject type pollution`() {
        val valid = row("action.a", "GENERAL_ACTION")
        for ((from, to) in listOf(
            "\"schemaVersion\":4" to "\"schemaVersion\":\"2\"",
            "\"layer\":1" to "\"layer\":\"1\"",
            "\"turnSlots\":12" to "\"turnSlots\":\"12\"",
            "\"money\":null" to "\"money\":\"100\"",
            "\"money\":null" to "\"money\":-5",
            "\"money\":null" to "\"money\":{}",
            "\"source\":\"test\"}" to "\"source\":\"test\",\"extra\":1}",
            "\"key\":\"requestId\"}" to "\"key\":\"requestId\",\"extra\":1}",
            "\"phase\":\"FIELD\"" to "\"phase\":\"NEXT_CARD_TURN\"",
        )) {
            val original = ledgerPayload(valid)
            assertFailsWith<IllegalArgumentException>("$from -> $to") { InputCatalog.parse(original.replace(from, to)) }
        }
        assertFailsWith<IllegalArgumentException> {
            ledger(row("policy.a", "POLICY").replace("\"turnSlots\":null", "\"turnSlots\":12"))
        }
    }

    private fun ledgerPayload(row: String) = """{"schemaVersion":4,"catalogId":"test","status":"DRAFT","note":"test",
        "inputs":[$row]}"""
}
