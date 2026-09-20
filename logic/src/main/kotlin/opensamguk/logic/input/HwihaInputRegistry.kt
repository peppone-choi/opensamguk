package opensamguk.logic.input

import kotlinx.serialization.json.Json
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
    val deliveryState: InputDeliveryState,
    /**
     * 기존(삼모) 명령 역참조(#837). 「대체」가 아니다 — 직접 행동은 기존 이름을 그대로 잇고 위임 행(방침·배치·공사)도
     * 같은 기존 명령을 가리킨다. 그래서 기존 명령 하나를 여러 행이 가리켜도 된다(다대일). 한 행 안의 중복만 금지한다.
     */
    val legacyCommands: List<String>,
)

class HwihaInputCatalog internal constructor(val entries: List<HwihaInputEntry>) {
    private val byId = entries.associateBy { it.inputId }
    operator fun get(inputId: String): HwihaInputEntry? = byId[inputId]

    /** 기존 명령 → 그것을 가리키는 원장 행들(원장 순서). 다대일이므로 값은 목록이다. */
    val legacyIndex: Map<String, List<HwihaInputEntry>> by lazy {
        val index = linkedMapOf<String, MutableList<HwihaInputEntry>>()
        entries.forEach { entry -> entry.legacyCommands.forEach { index.getOrPut(it) { mutableListOf() }.add(entry) } }
        index
    }

    /** [legacy] 가운데 어느 행도 가리키지 않는 기존 명령(입력 순서 유지). 몇 행이 가리키든 하나 이상이면 대응된 것이다. */
    fun uncoveredLegacyCommands(legacy: Iterable<String>): List<String> = legacy.filter { it !in legacyIndex }

    /** 역참조 가운데 [isRealCommand] 가 아니라고 답한 이름(지어낸 명령). */
    fun unknownLegacyCommands(isRealCommand: (String) -> Boolean): List<String> = legacyIndex.keys.filterNot(isRealCommand)

    companion object {
        private const val RESOURCE = "command-catalog/hwiha-input-catalog.json"

        fun load(): HwihaInputCatalog = parse(
            checkNotNull(HwihaInputCatalog::class.java.classLoader.getResource(RESOURCE)) {
                "hwiha input catalog resource is missing: $RESOURCE"
            }.readText(),
        )

        fun parse(payload: String): HwihaInputCatalog {
            val root = Json.parseToJsonElement(payload).jsonObject
            require(root.getValue("schemaVersion").jsonPrimitive.int == 1) { "unsupported hwiha input catalog schemaVersion" }
            val entries = root.getValue("inputs").jsonArray.map { element ->
                val row = element.jsonObject
                val inputId = row.getValue("inputId").jsonPrimitive.content
                val kind = enumValueOfOrFail<InputKind>(row.getValue("kind").jsonPrimitive.content, inputId)
                val parsed = parseInputId(inputId)
                require(parsed != null && parsed.first == kind) { "inputId prefix does not match kind: $inputId / $kind" }
                HwihaInputEntry(
                    inputId = inputId,
                    kind = kind,
                    layer = row.getValue("layer").jsonPrimitive.int.also { require(it in 1..3) { "layer must be 1..3: $inputId" } },
                    deliveryState = enumValueOfOrFail(row.getValue("deliveryState").jsonPrimitive.content, inputId),
                    legacyCommands = requireNotNull(row["legacyCommands"]) {
                        "missing legacyCommands for $inputId (replacesLegacy was renamed, #837)"
                    }.jsonArray.map { it.jsonPrimitive.content }.also {
                        require(it.toSet().size == it.size) { "duplicate legacyCommands within one row: $inputId" }
                    },
                )
            }
            require(entries.map { it.inputId }.toSet().size == entries.size) { "duplicate inputId in hwiha input catalog" }
            return HwihaInputCatalog(entries)
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

enum class InputRejection { MALFORMED_INPUT_ID, WRONG_RULE_PROFILE, UNKNOWN_INPUT, NOT_DELIVERED }

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
        // 삼모 명령 코드(che_*·cr_*·event_* 와 「휴식」)는 점이 없다. 꼴보다 프로필 불일치가 더 정확한 사유다.
        val parsed = parseInputId(rawInputId)
        if (profile != RuleProfile.HWIHA) return reject(InputRejection.WRONG_RULE_PROFILE, rawInputId)
        if (parsed == null) {
            val legacyShaped = LEGACY_CODE.matches(rawInputId)
            return reject(if (legacyShaped) InputRejection.WRONG_RULE_PROFILE else InputRejection.MALFORMED_INPUT_ID, rawInputId)
        }
        val entry = catalog[rawInputId] ?: return reject(InputRejection.UNKNOWN_INPUT, rawInputId)
        val handler = handlers[rawInputId] ?: return reject(InputRejection.NOT_DELIVERED, rawInputId)
        return InputResolution.Resolved(entry, handler)
    }

    private fun reject(reason: InputRejection, raw: String) = InputResolution.Rejected(reason, raw)

    companion object {
        /** 같은 모듈의 테스트만 쓴다: 원장이 아직 PLANNED 인 입력에 핸들러를 물려 resolve 경로를 본다. */
        internal fun forWiringTest(catalog: HwihaInputCatalog, handlers: Map<String, InputHandler>) =
            HwihaInputRegistry(catalog, handlers, false)

        private val LEGACY_CODE = Regex("^(che|cr|event)_.+$|^휴식$")
    }
}
