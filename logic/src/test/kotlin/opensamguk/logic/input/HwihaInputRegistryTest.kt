package opensamguk.logic.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import opensamguk.common.constants.GameConst
import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.actions.RestAction
import opensamguk.logic.stats.GeneralActionPipeline

/** 계약: docs/superpowers/specs/2026-09-17-input-registry-contract.md §2·§5·§8. */
class HwihaInputRegistryTest {
    private val catalog = HwihaInputCatalog.load()
    private var enlistCalls = 0
    private fun handlers(enlist: InputHandler) = (mapOf("action.enlist" to enlist, "action.deploy" to InputHandler {},
        "action.scout" to InputHandler {}, "action.assault" to InputHandler {}, "action.demandSurrender" to InputHandler {},
        "action.siegeRoadFort" to InputHandler {},
        "placement.assign" to InputHandler {}, "policy.set" to InputHandler {}, "work.start" to InputHandler {},
        "work.reduce" to InputHandler {},
        "court.dispatch" to InputHandler {}, "court.dispatchReply" to InputHandler {}, "court.reward" to InputHandler {},
        HwihaPoliticalConsent.COURT_INPUT_ID to InputHandler {},
        "action.move" to InputHandler {}, "action.forcedMarch" to InputHandler {}, "action.return" to InputHandler {},
        "action.farm" to InputHandler {}, "action.commerce" to InputHandler {}, "action.fortify" to InputHandler {},
        "action.repairWall" to InputHandler {}, "action.security" to InputHandler {}, "action.settle" to InputHandler {},
        "action.selectResidents" to InputHandler {}, "action.tour" to InputHandler {},
        "action.conscript" to InputHandler {}, "action.raiseVolunteers" to InputHandler {},
        "action.train" to InputHandler {}, "action.boostMorale" to InputHandler {},
        "action.demobilize" to InputHandler {}, "action.muster" to InputHandler {},
        "action.search" to InputHandler {}, "action.employ" to InputHandler {},
        "action.travel" to InputHandler {},
        "action.selfTrain" to InputHandler {}, "action.recuperate" to InputHandler {},
        "action.foundState" to InputHandler {}, "action.abdicate" to InputHandler {}, "action.oath" to InputHandler {},
        "action.gift" to InputHandler {},
        "action.convertProficiency" to InputHandler {}, "action.tradeEquipment" to InputHandler {},
        "action.tradeGrain" to InputHandler {}, "action.transport" to InputHandler {},
        "court.releaseCorps" to InputHandler {}, "court.diplomacy" to InputHandler {},
        "court.abandonCounty" to InputHandler {}, "court.institution" to InputHandler {},
        "court.moveCapital" to InputHandler {}, "court.confiscate" to InputHandler {},
        "court.nonAggression" to InputHandler {}, "court.declareWar" to InputHandler {},
        "court.offerPeace" to InputHandler {}, "court.breakNonAggression" to InputHandler {}) +
        HwihaLegacyStratagemInput.INPUT_IDS.associateWith { InputHandler {} }).filterKeys { catalog[it]?.deliveryState?.hasHandler == true }
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
    fun `old stratagems remain planned until card ownership exists`() {
        for (id in HwihaLegacyStratagemInput.INPUT_IDS + "stratagem.play") {
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
                HwihaInputRegistry(catalog, handlers(InputHandler { }) - id)
            }
        }
    }

    @Test
    fun `every direct field action is UI ready and has a handler`() {
        for (id in HwihaFieldInput.INPUT_IDS) {
            assertEquals(InputDeliveryState.UI_READY, catalog[id]!!.deliveryState, id)
            assertEquals(HwihaFieldFailure.entries.map { it.name }.toSet(),
                catalog[id]!!.failureReasons.toSet() - setOf("UNKNOWN_INPUT", "NOT_DELIVERED", "UNAUTHORIZED",
                    "FORBIDDEN", "INVALID_TURN_SLOT"), id)
            assertIs<InputResolution.Resolved>(registry.resolve(RuleProfile.HWIHA, id))
            assertFailsWith<IllegalArgumentException>(id) {
                HwihaInputRegistry(catalog, handlers(InputHandler { }) - id)
            }
        }
    }

    @Test
    fun `direct military actions expose their delivery state and have a handler`() {
        for (id in HwihaMilitaryInput.INPUT_IDS) {
            assertEquals(if (id == HwihaMilitaryInput.MUSTER) InputDeliveryState.HANDLER_READY
                else InputDeliveryState.UI_READY, catalog[id]!!.deliveryState, id)
            assertIs<InputResolution.Resolved>(registry.resolve(RuleProfile.HWIHA, id))
            assertFailsWith<IllegalArgumentException>(id) {
                HwihaInputRegistry(catalog, handlers(InputHandler { }) - id)
            }
        }
    }

    @Test
    fun `people actions expose only delivered handlers`() {
        for (id in HwihaPeopleInput.INPUT_IDS) {
            assertEquals(HwihaPeopleFailure.entries.map { it.name }.toSet(),
                catalog[id]!!.failureReasons.toSet() - setOf("UNKNOWN_INPUT", "NOT_DELIVERED", "UNAUTHORIZED",
                    "FORBIDDEN", "INVALID_TURN_SLOT"), id)
            if (id == HwihaPeopleInput.PERSUADE_CAPTIVE) {
                assertEquals(InputDeliveryState.PLANNED, catalog[id]!!.deliveryState)
                assertIs<InputResolution.Rejected>(registry.resolve(RuleProfile.HWIHA, id))
            } else {
                assertEquals(InputDeliveryState.UI_READY, catalog[id]!!.deliveryState, id)
                assertIs<InputResolution.Resolved>(registry.resolve(RuleProfile.HWIHA, id))
                assertFailsWith<IllegalArgumentException>(id) {
                    HwihaInputRegistry(catalog, handlers(InputHandler { }) - id)
                }
            }
        }
    }

    @Test
    fun `political actions expose only delivered handlers`() {
        for (id in HwihaPoliticalRules.SUPPORTED_IDS) {
            assertEquals(HwihaPoliticalFailure.entries.map { it.name }.toSet(),
                catalog[id]!!.failureReasons.toSet() - setOf("UNKNOWN_INPUT", "NOT_DELIVERED", "UNAUTHORIZED",
                    "FORBIDDEN", "INVALID_TURN_SLOT"), id)
            if (id in setOf(HwihaPoliticalInput.FOUND_STATE, HwihaPoliticalInput.ABDICATE, HwihaPoliticalInput.OATH)) {
                assertEquals(InputDeliveryState.UI_READY, catalog[id]!!.deliveryState, id)
                assertIs<InputResolution.Resolved>(registry.resolve(RuleProfile.HWIHA, id))
                assertFailsWith<IllegalArgumentException>(id) {
                    HwihaInputRegistry(catalog, handlers(InputHandler { }) - id)
                }
            } else {
                assertEquals(InputDeliveryState.PLANNED, catalog[id]!!.deliveryState, id)
                assertIs<InputResolution.Rejected>(registry.resolve(RuleProfile.HWIHA, id))
            }
        }
    }

    @Test
    fun `gift is delivered and donation waits for a warehouse destination`() {
        for (id in HwihaTransferInput.INPUT_IDS) {
            assertEquals(HwihaTransferFailure.entries.map { it.name }.toSet(),
                catalog[id]!!.failureReasons.toSet() - setOf("UNKNOWN_INPUT", "NOT_DELIVERED", "UNAUTHORIZED",
                    "FORBIDDEN", "INVALID_TURN_SLOT"), id)
            if (id == HwihaTransferInput.GIFT) {
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
        for (id in HwihaLegacyDirectInput.INPUT_IDS) {
            assertEquals(if (id == HwihaLegacyDirectInput.EQUIPMENT) InputDeliveryState.PLANNED else InputDeliveryState.UI_READY,
                catalog[id]!!.deliveryState, id)
            assertEquals(HwihaLegacyDirectFailure.entries.map { it.name }.toSet(),
                catalog[id]!!.failureReasons.toSet() - setOf("UNKNOWN_INPUT", "NOT_DELIVERED", "UNAUTHORIZED",
                    "FORBIDDEN", "INVALID_TURN_SLOT"), id)
            if (id == HwihaLegacyDirectInput.EQUIPMENT)
                assertEquals(InputRejection.NOT_DELIVERED, reject(RuleProfile.HWIHA, id))
            else assertIs<InputResolution.Resolved>(registry.resolve(RuleProfile.HWIHA, id))
        }
    }

    @Test
    fun `legacy court and stratagem rows match their shared failure vocabularies`() {
        val channelFailures = setOf("UNKNOWN_INPUT", "NOT_DELIVERED", "UNAUTHORIZED", "FORBIDDEN")
        for (id in HwihaLegacyCourtInput.INPUT_IDS) {
            val delivered = id in setOf("court.releaseCorps", "court.abandonCounty", "court.moveCapital")
            assertEquals(if (delivered) InputDeliveryState.UI_READY else InputDeliveryState.PLANNED,
                catalog[id]!!.deliveryState, id)
            assertEquals(HwihaLegacyCourtFailure.entries.map { it.name }.toSet(),
                catalog[id]!!.failureReasons.toSet() - channelFailures, id)
            if (delivered) assertIs<InputResolution.Resolved>(registry.resolve(RuleProfile.HWIHA, id))
            else assertEquals(InputRejection.NOT_DELIVERED, reject(RuleProfile.HWIHA, id))
        }
        for (id in HwihaLegacyStratagemInput.INPUT_IDS) {
            assertEquals(InputDeliveryState.PLANNED, catalog[id]!!.deliveryState, id)
            assertEquals(HwihaLegacyStratagemFailure.entries.map { it.name }.toSet(),
                catalog[id]!!.failureReasons.toSet() - channelFailures, id)
            assertEquals(InputRejection.NOT_DELIVERED, reject(RuleProfile.HWIHA, id))
        }
        assertEquals(InputDeliveryState.PLANNED, catalog[HwihaDomesticInput.REDUCE]!!.deliveryState)
        assertEquals(InputRejection.NOT_DELIVERED, reject(RuleProfile.HWIHA, HwihaDomesticInput.REDUCE))
        val reduceFailures = setOf("WRONG_RULE_PROFILE", "INVALID_REQUEST", "ACTOR_NOT_FOUND", "INVALID_COUNTY",
            "NOT_COUNTY_AUTHORITY", "WORK_IN_PROGRESS", "WORK_NOT_COMPLETED", "STATE_UNAVAILABLE")
        assertEquals(reduceFailures, catalog[HwihaDomesticInput.REDUCE]!!.failureReasons.toSet() - channelFailures)
    }

    @Test
    fun `every field personal action is UI ready and has a handler`() {
        for (id in HwihaPersonalInput.FIELD_IDS) {
            assertEquals(InputDeliveryState.UI_READY, catalog[id]!!.deliveryState, id)
            assertEquals(HwihaPersonalFailure.entries.map { it.name }.toSet(),
                catalog[id]!!.failureReasons.toSet() - setOf("UNKNOWN_INPUT", "NOT_DELIVERED", "UNAUTHORIZED",
                    "FORBIDDEN", "INVALID_TURN_SLOT"), id)
            assertIs<InputResolution.Resolved>(registry.resolve(RuleProfile.HWIHA, id))
            assertFailsWith<IllegalArgumentException>(id) {
                HwihaInputRegistry(catalog, handlers(InputHandler { }) - id)
            }
        }
    }

    @Test
    fun `retirement remains planned until succession state can be preserved`() {
        val id = HwihaRetireInput.INPUT_ID
        assertEquals(InputDeliveryState.PLANNED, catalog[id]!!.deliveryState)
        assertEquals("POLITICS", catalog[id]!!.timing.getValue("phase").jsonPrimitive.content)
        assertEquals(HwihaRetireFailure.entries.map { it.name }.toSet(),
            catalog[id]!!.failureReasons.toSet() - setOf("UNKNOWN_INPUT", "NOT_DELIVERED", "UNAUTHORIZED",
                "FORBIDDEN", "INVALID_TURN_SLOT"))
        assertIs<InputResolution.Rejected>(registry.resolve(RuleProfile.HWIHA, id))
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

    private fun missingDirectActions(source: HwihaInputCatalog, names: List<String>): List<String> =
        names.filter { name -> source.legacyIndex[name].orEmpty().none { it.kind == InputKind.GENERAL_ACTION } }

    private fun missingChiefKinds(source: HwihaInputCatalog, names: List<String>, kind: InputKind): List<String> =
        names.map { "che_$it" }.filter { name -> source.legacyIndex[name].orEmpty().none { it.kind == kind } }

    private fun withoutLegacyReference(name: String): HwihaInputCatalog {
        val source = repoRoot().resolve("data/commands/hwiha-input-catalog.json").toFile().readText()
        val root = Json.parseToJsonElement(source).jsonObject
        val changed = if (name in catalog.retiredLegacyCommands) {
            JsonObject(root + mapOf(
                "retiredLegacyCommands" to JsonArray(root.getValue("retiredLegacyCommands").jsonArray.filterNot { it.jsonPrimitive.content == name }),
                "retiredLegacyReasons" to JsonObject(root.getValue("retiredLegacyReasons").jsonObject.filterKeys { it != name }),
            ))
        } else {
            val rows = root.getValue("inputs").jsonArray.map { element ->
                val row = element.jsonObject
                JsonObject(row + ("legacyCommands" to JsonArray(row.getValue("legacyCommands").jsonArray.filterNot {
                    it.jsonPrimitive.content == name
                })))
            }
            JsonObject(root + ("inputs" to JsonArray(rows)))
        }
        return HwihaInputCatalog.parse(changed.toString())
    }

    private fun row(inputId: String, kind: String, legacy: String): String {
        val timing = if (kind == "GENERAL_ACTION")
            """{"phase":"FIELD","turnSlots":12,"perPhaseLimit":1}"""
        else """{"phase":"NEXT_CARD_TURN","turnSlots":null,"perPhaseLimit":null}"""
        return """{"inputId":"$inputId","kind":"$kind","layer":1,"actor":"GENERAL","authorityRule":"SUBJECT_OWNER",
            "targetSchema":{"status":"PLANNED","source":"test"},"costSchema":{"status":"PLANNED","source":"test","money":null,"grain":null,"iron":null,"timber":null,"horses":null},
            "timing":$timing,"effectScope":"ACTOR_LOCATION","failureReasons":[],"resultType":"InputResolved",
            "replayContract":{"status":"PLANNED","key":"requestId"},"aiPolicyId":"ai.test","helpTopicId":"help.test","tutorialObjectiveId":"N/A",
            "deliveryState":"PLANNED","legacyCommands":[$legacy]}"""
    }

    private fun ledger(vararg rows: String, retired: String = "", reasons: String = "") =
        HwihaInputCatalog.parse("""{"schemaVersion":2,"catalogId":"test","status":"DRAFT","note":"test",
        "inputs":[${rows.joinToString(",")}],"retiredLegacyCommands":[$retired],"retiredLegacyReasons":{$reasons}}""")

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
    fun `all direct legacy actions have a GENERAL_ACTION row`() {
        val direct = listOf(
            "농지개간", "상업투자", "수비강화", "성벽보수", "치안강화", "정착장려", "주민선정",
            "징병", "모병", "훈련", "사기진작", "출병", "집합", "소집해제", "첩보",
            "이동", "강행", "인재탐색", "등용", "귀환", "임관", "랜덤임관", "장수대상임관",
            "견문", "단련", "요양", "은퇴", "증여", "헌납", "하야", "거병", "건국", "선양", "해산",
            "숙련전환", "장비매매", "군량매매", "물자조달",
        ).map { "che_$it" }
        assertEquals(38, direct.size)
        assertEquals(emptyList(), missingDirectActions(catalog, direct))
        val mutated = withoutLegacyReference("che_농지개간")
        assertEquals(listOf("che_농지개간"), missingDirectActions(mutated, direct))
    }

    @Test
    fun `all 70 legacy menu slots are either live or retired`() {
        assertEquals(emptyList(), catalog.invalidLegacyCoverage(legacy70))
        assertTrue(catalog.retiredLegacyCommands.all { it in legacy70 }, "폐지 목록이 기존 명령 70개 밖을 가리킨다")
        assertEquals(setOf("휴식", "che_내정특기초기화", "che_전투특기초기화", "che_국기변경", "che_국호변경"),
            catalog.retiredLegacyCommands.toSet())
        assertTrue(catalog.entries.all { it.inputId == "action.enlist" || it.legacyCommands.size <= 1 }, "출사 외 기존 명령은 명령별 한 행으로 둔다")
        assertEquals(74, catalog.entries.size)
        val mutation = ledger(row("action.farm", "GENERAL_ACTION", "\"che_농지개간\""))
        assertTrue(mutation.invalidLegacyCoverage(legacy70).isNotEmpty())
        for (name in legacy70Slots) {
            val mutation = withoutLegacyReference(name)
            assertEquals(listOf(name), mutation.invalidLegacyCoverage(legacy70), name)
        }
        val overlap = ledger(row("action.farm", "GENERAL_ACTION", "\"che_농지개간\""),
            retired = "\"che_징병\"", reasons = "\"che_징병\":\"test\"")
        assertEquals(emptyList(), overlap.invalidLegacyCoverage(listOf("che_농지개간", "che_징병")))
        val court = listOf("발령", "포상", "부대탈퇴지시", "물자원조", "초토화", "천도", "몰수", "불가침제의", "선전포고", "종전제의", "불가침파기제의")
        val work = listOf("증축", "감축")
        val stratagem = listOf("필사즉생", "백성동원", "수몰", "허보", "의병모집", "이호경식", "급습", "피장파장")
        val chief = GameConst.availableChiefCommand.values.flatten().filter { it != "휴식" && it !in catalog.retiredLegacyCommands }
        assertEquals(21, chief.size)
        assertEquals(chief.toSet(), (court + work + stratagem).map { "che_$it" }.toSet())
        for ((names, kind) in listOf(court to InputKind.COURT_DECISION, work to InputKind.WORK,
            stratagem to InputKind.STRATAGEM)) {
            assertEquals(emptyList(), missingChiefKinds(catalog, names, kind), kind.name)
        }
        assertEquals(listOf("che_발령"), missingChiefKinds(withoutLegacyReference("che_발령"), court, InputKind.COURT_DECISION))
        assertTrue("che_기술연구" in GameConst.availableGeneralCommand.values.flatten())
        assertTrue("che_기술연구" !in chief)
        // Old general-command provenance and the new 3rd-layer court.institution mapping are separate contracts.
        assertTrue(catalog.legacyIndex["che_기술연구"].orEmpty().any { it.kind == InputKind.COURT_DECISION })
    }

    @Test
    fun `stale replacesLegacy field and in-row duplicates fail closed`() {
        val stale = row("action.a", "GENERAL_ACTION", "").replace("\"legacyCommands\":[]", "\"legacyCommands\":[],\"replacesLegacy\":[]")
        assertFailsWith<IllegalArgumentException> { ledger(stale) }
        assertFailsWith<IllegalArgumentException> { ledger(row("action.a", "GENERAL_ACTION", "\"che_징병\",\"che_징병\"")) }
    }

    @Test
    fun `string arrays reject null and numeric members`() {
        assertFailsWith<IllegalArgumentException> { ledger(row("action.a", "GENERAL_ACTION", "null")) }
        assertFailsWith<IllegalArgumentException> { ledger(row("action.a", "GENERAL_ACTION", "123")) }
        val row = row("action.a", "GENERAL_ACTION", "")
        for (bad in listOf("null", "123")) {
            assertFailsWith<IllegalArgumentException> {
                ledger(row.replace("\"failureReasons\":[]", "\"failureReasons\":[$bad]"))
            }
            assertFailsWith<IllegalArgumentException> {
                HwihaInputCatalog.parse("""{"schemaVersion":2,"catalogId":"test","status":"DRAFT","note":"test",
                    "inputs":[],"retiredLegacyCommands":[$bad],"retiredLegacyReasons":{}}""")
            }
        }
    }

    @Test
    fun `duplicate or unknown ledger fields fail closed`() {
        assertFailsWith<IllegalArgumentException> { ledger(row("action.a", "GENERAL_ACTION", ""), row("action.a", "GENERAL_ACTION", "")) }
        val dup = row("action.a", "GENERAL_ACTION", "")
        val badState = dup.replace("\"PLANNED\"", "\"DONE\"")
        assertFailsWith<IllegalArgumentException> { ledger(badState) }
        assertFailsWith<IllegalArgumentException> { ledger(row("policy.a", "GENERAL_ACTION", "")) }
        assertFailsWith<IllegalArgumentException> { ledger(dup.replace("\"aiPolicyId\":\"ai.test\",", "")) }
        assertFailsWith<IllegalArgumentException> { ledger(dup.replace("\"aiPolicyId\":\"ai.test\"", "\"aiPolicyId\":null")) }
        assertFailsWith<IllegalArgumentException> { ledger(dup.replace("\"actor\":\"GENERAL\"", "\"actor\":\"GENERAL\",\"actor\":\"GENERAL\"")) }
        assertFailsWith<IllegalArgumentException> { ledger(dup.replace("\"actor\":\"GENERAL\"", "\"actor\":\"UNKNOWN\"")) }
        assertFailsWith<IllegalArgumentException> { ledger(dup.replace("\"phase\":\"FIELD\"", "\"phase\":\"UNKNOWN\"")) }
        assertFailsWith<IllegalArgumentException> { ledger(dup.replace("\"resultType\":\"InputResolved\"", "\"resultType\":\"Other\"")) }
        assertFailsWith<IllegalArgumentException> { ledger(dup.replace("\"turnSlots\":12", "\"turnSlots\":11")) }
        assertFailsWith<IllegalArgumentException> { ledger(dup.replace("\"perPhaseLimit\":1", "\"perPhaseLimit\":2")) }
        assertFailsWith<IllegalArgumentException> { ledger(dup.replace("\"failureReasons\":[]", "\"failureReasons\":[\"BAD\",\"BAD\"]")) }
        assertFailsWith<IllegalArgumentException> { ledger(dup, retired = "\"che_징병\"", reasons = "") }
        assertFailsWith<IllegalArgumentException> { ledger(dup, retired = "\"che_징병\",\"che_징병\"", reasons = "\"che_징병\":\"test\"") }
        assertFailsWith<IllegalArgumentException> { ledger(row("action.a", "GENERAL_ACTION", "\"che_징병\""),
            retired = "\"che_징병\"", reasons = "\"che_징병\":\"test\"") }
    }

    @Test
    fun `numeric and nested contracts reject type pollution`() {
        val valid = row("action.a", "GENERAL_ACTION", "")
        for ((from, to) in listOf(
            "\"schemaVersion\":2" to "\"schemaVersion\":\"2\"",
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
            assertFailsWith<IllegalArgumentException>("$from -> $to") { HwihaInputCatalog.parse(original.replace(from, to)) }
        }
        assertFailsWith<IllegalArgumentException> {
            ledger(row("policy.a", "POLICY", "").replace("\"turnSlots\":null", "\"turnSlots\":12"))
        }
    }

    private fun ledgerPayload(row: String) = """{"schemaVersion":2,"catalogId":"test","status":"DRAFT","note":"test",
        "inputs":[$row],"retiredLegacyCommands":[],"retiredLegacyReasons":{}}"""
}
