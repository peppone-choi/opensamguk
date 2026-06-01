package opensamguk.logic.event

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import opensamguk.logic.betting.BettingInfo
import opensamguk.logic.betting.BettingStatus
import opensamguk.logic.betting.BettingType
import opensamguk.logic.betting.CloseCondition
import opensamguk.logic.betting.YearMonth

/**
 * 국가 강약 베팅 개시 이벤트 액션 — `OpenNationBetting`.
 *
 * PHP `Betting::openBetting()`의 Kotlin 포팅.
 *
 * 동작:
 * 1. [BettingInfo]를 생성하여 KV 스토리지에 저장
 * 2. [EventTarget.DESTROY_NATION] 이벤트를 등록하여 마감 트리거 설정
 *
 * Factory args:
 * - args[0]: 대상 국가 ID 목록 (JsonArray of Int) — 생략 시 env의 모든 국가
 * - args[1]: 마감 조건 타입 ("RemainNationCount" / "SpecificDate" / "TargetDestroyed")
 * - args[2]: 마감 조건 값 (Int — RemainNationCount의 경우 목표 국가 수, 기본 1)
 *
 * 등록:
 * ```kotlin
 * factory.register(OpenNationBettingAction.NAME) { args ->
 *     OpenNationBettingAction(args)
 * }
 * ```
 */
class OpenNationBettingAction(
    private val targetNationsArg: JsonElement? = null,
    private val closeConditionType: String = "RemainNationCount",
    private val closeConditionValue: Int = 1,
) : EventAction {

    override fun run(ctx: EventActionContext) {
        // 1. env에서 연/월 추출
        val year = (ctx.env["year"] as? Number)?.toInt() ?: return
        val month = (ctx.env["month"] as? Number)?.toInt() ?: return
        val openYearMonth = YearMonth(year, month)

        // 2. 대상 국가 목록 결정
        val targetNations = if (targetNationsArg is JsonArray) {
            targetNationsArg.jsonArray.map { it.jsonPrimitive.int }
        } else {
            // env에서 모든 국가 ID 추출
            @Suppress("UNCHECKED_CAST")
            (ctx.env["nationIds"] as? List<Int>) ?: emptyList()
        }

        if (targetNations.isEmpty()) return

        // 3. 마감 조건 생성
        val closeCondition: CloseCondition = when (closeConditionType) {
            "SpecificDate" -> CloseCondition.SpecificDate(year, month)
            "TargetDestroyed" -> {
                val targetId = if (targetNations.isNotEmpty()) targetNations.first() else return
                CloseCondition.TargetDestroyed(targetId)
            }
            else -> CloseCondition.RemainNationCount(closeConditionValue)
        }

        // 4. BettingInfo 생성
        val bettingInfo = BettingInfo(
            bettingId = generateBettingId(year, month),
            type = BettingType.NATION_STRENGTH,
            openYearMonth = openYearMonth,
            targetNations = targetNations,
            odds = calculateInitialOdds(targetNations),
            closeCondition = closeCondition,
        )

        // 5. KV 스토리지에 저장
        @Suppress("UNCHECKED_CAST")
        val kvStorage = ctx.env["kvStorage"] as? MutableMap<String, Any>
        kvStorage?.let { storage ->
            storage[BettingInfo.KV_KEY_PREFIX + bettingInfo.bettingId] = bettingInfo
        }

        // 6. DESTROY_NATION 이벤트 등록 (FinishNationBetting 트리거)
        val eventStore = ctx.env[EventStoreRegistration.ENV_KEY] as? EventStore
        eventStore?.let { store ->
            // TODO: 이벤트 등록 — FinishNationBetting을 DESTROY_NATION 타겟으로
            // store.insertRaw(
            //     targetCode = "destroy_nation",
            //     priority = 5000,
            //     conditionJson = Json.parseToJsonElement("true"),
            //     actionJson = Json.parseToJsonElement("""[["FinishNationBetting","${bettingInfo.bettingId}"]]""")
            // )
        }

        // 7. 글로벌 로그
        @Suppress("UNCHECKED_CAST")
        val logger = ctx.env["actionLogger"] as? ActionLoggerSeam
        logger?.pushGlobalActionLog(
            "국가 강약 베팅이 개시되었습니다. (${bettingInfo.bettingId})"
        )
    }

    companion object {
        const val NAME = "OpenNationBetting"

        /** 베팅 ID 생성 — `nb_{year}{month:02d}_{random4}` 형식. PHP parity: 중복 방지를 위해 랜덤 접미사 포함. */
        private fun generateBettingId(year: Int, month: Int): String {
            val randomSuffix = (1000..9999).random()
            return "nb_${year}${month.toString().padStart(2, '0')}_${randomSuffix}"
        }

        /** 초기 배당률 계산 — 모든 대상 국가에 동일 배당 */
        private fun calculateInitialOdds(targetNations: List<Int>): Map<Int, Double> {
            if (targetNations.isEmpty()) return emptyMap()
            val baseOdds = 100.0 / targetNations.size
            return targetNations.associateWith { baseOdds }
        }

        /**
         * [EventActionFactory]에 `OpenNationBetting` 리프를 등록한다.
         *
         * Args: [targetNations(JsonArray)?, closeConditionType(String)?, closeConditionValue(Int)?]
         */
        fun register(factory: EventActionFactory): EventActionFactory =
            factory.register(NAME) { args ->
                val targetNationsArg = args.getOrNull(0)
                val closeConditionType = if (args.size > 1) {
                    (args[1] as JsonPrimitive).content
                } else "RemainNationCount"
                val closeConditionValue = if (args.size > 2) {
                    (args[2] as JsonPrimitive).content.toInt()
                } else 1
                OpenNationBettingAction(targetNationsArg, closeConditionType, closeConditionValue)
            }
    }
}

/**
 * 이벤트 스토어 등록용 seam — OpenNationBetting이 이벤트 스토어에 접근하기 위한 키.
 */
object EventStoreRegistration {
    const val ENV_KEY = "eventStore"
}

/**
 * ActionLogger 접합부 — 이벤트 액션에서 글로벌 로그를 남기기 위한 최소 인터페이스.
 */
interface ActionLoggerSeam {
    fun pushGlobalActionLog(msg: String)
    fun pushGlobalHistoryLog(msg: String, type: Int)
}
