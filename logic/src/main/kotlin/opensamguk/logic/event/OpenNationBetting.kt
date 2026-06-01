package opensamguk.logic.event

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import opensamguk.logic.betting.BettingInfo
import opensamguk.logic.betting.SelectItem

/**
 * 국가 강약 베팅 개시 이벤트 액션 — `OpenNationBetting`.
 *
 * PHP `Betting::openBetting()` 의 Kotlin 포팅.
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
        val openYearMonth = year * 12 + month

        // 2. 대상 국가 목록 결정
        val targetNations = if (targetNationsArg is JsonArray) {
            targetNationsArg.jsonArray.map { it.jsonPrimitive.int }
        } else {
            @Suppress("UNCHECKED_CAST")
            (ctx.env["nationIds"] as? List<Int>) ?: emptyList()
        }

        if (targetNations.isEmpty()) return

        // 3. candidates 구성 — nation_id → SelectItem
        val candidates = linkedMapOf<Int, SelectItem>()
        for ((idx, nationId) in targetNations.withIndex()) {
            candidates[idx] = SelectItem(
                title = "국가 $nationId",
                aux = linkedMapOf("nation" to nationId),
            )
        }

        // 4. BettingInfo 생성
        val bettingInfo = BettingInfo(
            id = generateBettingId(ctx),
            type = "bettingNation",
            name = "${year}년 ${month}월 국가 강약 내기",
            selectCnt = targetNations.size,
            isExclusive = null,
            reqInheritancePoint = false,
            openYearMonth = openYearMonth,
            closeYearMonth = openYearMonth + 120,
            candidates = candidates,
        )

        // 5. KV 스토리지에 저장
        val kvStorage = ctx.env["kvStorage"] as? MutableMap<String, Any>
        kvStorage?.let { storage ->
            storage[BettingInfo.KV_KEY_PREFIX + bettingInfo.id] = bettingInfo
        }

        // 6. DESTROY_NATION 이벤트 등록 (FinishNationBetting 트리거)
        // TODO(P3-coupled): EventStore에 FinishNationBetting 트리거 등록
        // val eventStore = ctx.env[DeleteEventContext.ENV_KEY] as? EventStore
        // eventStore?.insertRaw(
        //     targetCode = "destroy_nation",
        //     priority = 5000,
        //     conditionJson = Json.parseToJsonElement("true"),
        //     actionJson = Json.parseToJsonElement("""[["FinishNationBetting","${bettingInfo.id}"]]""")
        // )

        // 7. 글로벌 로그
        val world = ctx.env[LightActionWorld.ENV_KEY] as? LightActionWorld
        world?.pushGlobalActionLog(
            "국가 강약 내기가 개시되었습니다. (${bettingInfo.name})"
        )
    }

    companion object {
        const val NAME = "OpenNationBetting"

        /** 베팅 ID 생성 — PHP parity: `Betting::genNextBettingID()` 와 유사한 순차 ID. */
        private fun generateBettingId(ctx: EventActionContext): Int {
            @Suppress("UNCHECKED_CAST")
            val kvStorage = ctx.env["kvStorage"] as? MutableMap<String, Any>
            val lastKey = "last_betting_id"
            val lastId = (kvStorage?.get(lastKey) as? Number)?.toInt() ?: 0
            val nextId = lastId + 1
            kvStorage?.put(lastKey, nextId)
            return nextId
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
