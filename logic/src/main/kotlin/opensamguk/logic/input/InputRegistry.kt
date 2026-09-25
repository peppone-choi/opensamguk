package opensamguk.logic.input

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 「장수·휘하」 월드의 입력 registry (ADR-LITE-057, 계약: docs/superpowers/specs/2026-09-17-input-registry-contract.md).
 *
 * 삼모 월드의 `CommandRegistry.resolve` 는 모르는 코드를 `RestAction` 으로 돌려준다. 이 registry 는 그 반대다 —
 * 미등록·다른 규칙 프로필·미배달 입력은 전부 사유가 붙은 [InputResolution.Rejected] 이고, 휴식이나 성공으로
 * 떨어지는 경로가 없다. 순수 로직이며 삼모 경로(`logic.actions` 패키지)를 건드리지 않는다.
 */
enum class InputKind(val prefix: String) {
    GENERAL_ACTION("action"),
    PLACEMENT("placement"),
    POLICY("policy"),
    WORK("work"),
    STRATAGEM("stratagem"),
    COURT_DECISION("court");

    companion object {
        fun ofPrefix(prefix: String): InputKind? = entries.firstOrNull { it.prefix == prefix }
    }
}

/** 월드마다 하나. 시나리오가 선언하고 시드 때 `world_state` 에 적힌 값을 읽는다. */
enum class RuleProfile {
    SAMMO,
    HWIHA;

    companion object {
        /** 값이 없으면(기존 월드) SAMMO. 값이 있는데 모르는 글자면 조용히 SAMMO 로 떨어지지 않고 실패한다. */
        fun fromWorldConfig(value: String?): RuleProfile =
            if (value == null) SAMMO
            else requireNotNull(entries.firstOrNull { it.name == value }) { "unknown ruleProfile in world config: '$value'" }
    }
}

/** 원장의 배달 단계. PLANNED 는 핸들러가 아직 없다는 뜻이고, 나머지는 재기준선 §3 파이프라인 그대로다. */
enum class InputDeliveryState {
    PLANNED, DOMAIN_READY, HANDLER_READY, UI_READY, AI_READY, HELP_READY, TUTORIAL_READY, REPLAY_READY, VERIFIED;

    val hasHandler: Boolean get() = this >= HANDLER_READY
}

data class HwihaInputEntry(
    val inputId: String,
    val kind: InputKind,
    val layer: Int,
    val actor: String,
    val authorityRule: String,
    val targetSchema: JsonObject,
    val costSchema: JsonObject,
    val timing: JsonObject,
    val effectScope: String,
    val failureReasons: List<String>,
    val resultType: String,
    val replayContract: JsonObject,
    val aiPolicyId: String,
    val helpTopicId: String,
    val tutorialObjectiveId: String,
    val deliveryState: InputDeliveryState,
    val displayName: String?,
)

class HwihaInputCatalog internal constructor(
    val entries: List<HwihaInputEntry>,
) {
    private val byId = entries.associateBy { it.inputId }
    operator fun get(inputId: String): HwihaInputEntry? = byId[inputId]

    /** Shared syntax, profile, ledger and delivery classification for API precheck and engine dispatch. */
    fun rejectionFor(profile: RuleProfile, rawInputId: String): InputRejection? {
        if (profile != RuleProfile.HWIHA) return InputRejection.WRONG_RULE_PROFILE
        val parsed = parseInputId(rawInputId)
        if (parsed == null) {
            return if (LEGACY_CODE.matches(rawInputId)) InputRejection.WRONG_RULE_PROFILE
            else InputRejection.MALFORMED_INPUT_ID
        }
        val entry = byId[rawInputId] ?: return InputRejection.UNKNOWN_INPUT
        return if (entry.deliveryState.hasHandler) null else InputRejection.NOT_DELIVERED
    }

    companion object {
        private const val RESOURCE = "command-catalog/hwiha-input-catalog.json"
        private val LEGACY_CODE = Regex("^(che|cr|event)_.+$|^휴식$")

        fun load(): HwihaInputCatalog = parse(
            checkNotNull(HwihaInputCatalog::class.java.classLoader.getResource(RESOURCE)) {
                "hwiha input catalog resource is missing: $RESOURCE"
            }.readText(),
        )

        fun parse(payload: String): HwihaInputCatalog {
            HwihaCatalogDuplicateKeys(payload).check()
            val root = Json.parseToJsonElement(payload).jsonObject
            require(root.requiredInt("schemaVersion") == 3) { "unsupported hwiha input catalog schemaVersion" }
            require(root.keys == setOf("schemaVersion", "catalogId", "status", "note", "inputs")) {
                "unexpected or missing hwiha catalog field"
            }
            root.requiredText("catalogId")
            root.requiredText("status")
            root.requiredText("note")
            val entries = root.getValue("inputs").jsonArray.map { element ->
                val row = element.jsonObject
                val inputId = row.getValue("inputId").jsonPrimitive.content
                val kind = enumValueOfOrFail<InputKind>(row.getValue("kind").jsonPrimitive.content, inputId)
                val requiredFields = if (kind == InputKind.GENERAL_ACTION) ENTRY_FIELDS + "displayName" else ENTRY_FIELDS
                require(row.keys == requiredFields) { "unexpected or missing field for $inputId: ${requiredFields - row.keys} / ${row.keys - requiredFields}" }
                val parsed = parseInputId(inputId)
                require(parsed != null && parsed.first == kind) { "inputId prefix does not match kind: $inputId / $kind" }
                val cost = row.getValue("costSchema").jsonObject
                require(cost.keys == COST_FIELDS) { "costSchema fields missing or unknown: $inputId" }
                cost.requiredText("status")
                cost.requiredText("source")
                (COST_FIELDS - setOf("status", "source")).forEach { cost.requiredNonNegativeCost(it) }
                val target = row.getValue("targetSchema").jsonObject
                require(target.keys == TARGET_FIELDS) { "targetSchema fields missing or unknown: $inputId" }
                target.requiredText("status")
                target.requiredText("source")
                val replay = row.getValue("replayContract").jsonObject
                require(replay.keys == REPLAY_FIELDS) { "replayContract fields missing or unknown: $inputId" }
                replay.requiredText("status")
                replay.requiredText("key")
                val failureReasons = row.getValue("failureReasons").stringArray("failureReasons")
                require(failureReasons.size == failureReasons.toSet().size) { "duplicate failureReasons: $inputId" }
                val actor = row.requiredText("actor")
                require(actor in ACTORS) { "unknown actor: $inputId / $actor" }
                val timing = row.getValue("timing").jsonObject
                require(timing.keys == TIMING_FIELDS) { "timing fields missing or unknown: $inputId" }
                require(timing.getValue("phase").jsonPrimitive.content in PHASES) { "unknown timing phase: $inputId" }
                if (kind == InputKind.GENERAL_ACTION) {
                    require(timing.requiredText("phase") in GENERAL_PHASES &&
                        timing.requiredInt("turnSlots") == 12 && timing.requiredInt("perPhaseLimit") == 1) {
                        "general action must use 12 slots and one action per phase: $inputId"
                    }
                } else {
                    require(timing.getValue("turnSlots") == JsonNull && timing.getValue("perPhaseLimit") == JsonNull) {
                        "standing input must not use turn slots: $inputId"
                    }
                }
                require(row.requiredText("resultType") == "InputResolved") { "wrong resultType: $inputId" }
                HwihaInputEntry(
                    inputId = inputId,
                    kind = kind,
                    layer = row.requiredInt("layer").also { require(it in 1..3) { "layer must be 1..3: $inputId" } },
                    actor = actor,
                    authorityRule = row.requiredText("authorityRule"),
                    targetSchema = target,
                    costSchema = cost,
                    timing = timing,
                    effectScope = row.requiredText("effectScope"),
                    failureReasons = failureReasons,
                    resultType = row.requiredText("resultType"),
                    replayContract = replay,
                    aiPolicyId = row.requiredText("aiPolicyId"),
                    helpTopicId = row.requiredText("helpTopicId"),
                    tutorialObjectiveId = row.requiredText("tutorialObjectiveId"),
                    deliveryState = enumValueOfOrFail(row.getValue("deliveryState").jsonPrimitive.content, inputId),
                    displayName = if (kind == InputKind.GENERAL_ACTION) row.requiredText("displayName") else null,
                )
            }
            require(entries.map { it.inputId }.toSet().size == entries.size) { "duplicate inputId in hwiha input catalog" }
            return HwihaInputCatalog(entries)
        }

        private val ENTRY_FIELDS = setOf("inputId", "kind", "layer", "actor", "authorityRule", "targetSchema",
            "costSchema", "timing", "effectScope", "failureReasons", "resultType", "replayContract",
            "aiPolicyId", "helpTopicId", "tutorialObjectiveId", "deliveryState")
        private val COST_FIELDS = setOf("status", "source", "money", "grain", "iron", "timber", "horses")
        private val TARGET_FIELDS = setOf("status", "source")
        private val REPLAY_FIELDS = setOf("status", "key")
        private val TIMING_FIELDS = setOf("phase", "turnSlots", "perPhaseLimit")
        private val ACTORS = setOf("GENERAL", "LORD", "RULER", "OFFICE_HOLDER")
        private val PHASES = setOf("POLITICS", "MOVE", "SIEGE", "FIELD", "NEXT_CARD_TURN", "NEXT_PHASE_BOUNDARY", "CARD_TRIGGER", "DECISION_TURN")
        private val GENERAL_PHASES = setOf("POLITICS", "MOVE", "SIEGE", "FIELD")

        private fun JsonObject.requiredInt(key: String): Int {
            val value = getValue(key) as? JsonPrimitive
            require(value != null && !value.isString) { "$key must be an integer" }
            return value.int
        }

        private fun JsonObject.requiredNonNegativeCost(key: String) {
            val value = getValue(key)
            if (value == JsonNull) return
            val primitive = value as? JsonPrimitive
            require(primitive != null && !primitive.isString && primitive.content.matches(Regex("[0-9]+"))) {
                "$key must be a non-negative number or null"
            }
        }

        private fun JsonElement.stringArray(field: String): List<String> = jsonArray.map { item ->
            val value = item as? JsonPrimitive
            require(value != null && value.isString) { "$field must contain strings only" }
            value.content
        }

        private fun JsonObject.requiredText(key: String): String {
            val value = getValue(key) as? JsonPrimitive
            require(value != null && value.isString && value.content.isNotBlank()) { "missing or invalid text field $key" }
            return value.content
        }

        private inline fun <reified T : Enum<T>> enumValueOfOrFail(text: String, inputId: String): T =
            requireNotNull(enumValues<T>().firstOrNull { it.name == text }) { "unknown ${T::class.simpleName} '$text' for $inputId" }
    }
}

/** "<kind prefix>.<name>" → (kind, name). 꼴이 아니면 null. */
internal fun parseInputId(raw: String): Pair<InputKind, String>? {
    val dot = raw.indexOf('.')
    if (dot <= 0 || dot == raw.length - 1) return null
    val kind = InputKind.ofPrefix(raw.substring(0, dot)) ?: return null
    return kind to raw.substring(dot + 1)
}

enum class InputRejection(val message: String) {
    MALFORMED_INPUT_ID("입력 식별자가 올바르지 않습니다."),
    WRONG_RULE_PROFILE("이 월드의 규칙에서 사용할 수 없는 입력입니다."),
    UNKNOWN_INPUT("등록되지 않은 입력입니다."),
    NOT_DELIVERED("아직 제공되지 않는 입력입니다."),
    INVALID_INPUT_CHANNEL("이 입력 경로에서는 사용할 수 없습니다."),
}

/** 핸들러의 실제 시그니처는 엔진 배선 단계에서 정한다(계약 §6). 여기서는 등록 여부만 다룬다. */
fun interface InputHandler {
    fun handle()
}

sealed interface InputResolution {
    data class Resolved(val entry: HwihaInputEntry, val handler: InputHandler) : InputResolution
    data class Rejected(val reason: InputRejection, val rawInputId: String) : InputResolution
}

/**
 * 원장 ↔ 코드 일치를 강제한다: 핸들러가 있는 입력은 원장에서 HANDLER_READY 이상이어야 하고,
 * HANDLER_READY 이상인 입력은 핸들러가 있어야 한다.
 */
class HwihaInputRegistry private constructor(
    private val catalog: HwihaInputCatalog,
    private val handlers: Map<String, InputHandler>,
    requireDelivered: Boolean,
) {
    /** 프로덕션 배선은 이 생성자만 쓴다 — 원장 ↔ 코드 일치 검사를 끌 수 없다. */
    constructor(catalog: HwihaInputCatalog, handlers: Map<String, InputHandler>) : this(catalog, handlers, true)

    init {
        val unknown = handlers.keys.filter { catalog[it] == null }
        require(unknown.isEmpty()) { "handlers registered for inputs missing from the ledger: $unknown" }
        if (requireDelivered) {
            val mismatched = catalog.entries.filter { it.deliveryState.hasHandler != (it.inputId in handlers) }.map { it.inputId }
            require(mismatched.isEmpty()) { "ledger deliveryState and handler wiring disagree: $mismatched" }
        }
    }

    fun resolve(profile: RuleProfile, rawInputId: String): InputResolution {
        catalog.rejectionFor(profile, rawInputId)?.let { return reject(it, rawInputId) }
        val entry = checkNotNull(catalog[rawInputId])
        val handler = handlers[rawInputId] ?: return reject(InputRejection.NOT_DELIVERED, rawInputId)
        return InputResolution.Resolved(entry, handler)
    }

    private fun reject(reason: InputRejection, raw: String) = InputResolution.Rejected(reason, raw)

    companion object {
        /** 같은 모듈의 테스트만 쓴다: 원장이 아직 PLANNED 인 입력에 핸들러를 물려 resolve 경로를 본다. */
        internal fun forWiringTest(catalog: HwihaInputCatalog, handlers: Map<String, InputHandler>) =
            HwihaInputRegistry(catalog, handlers, false)

    }
}
