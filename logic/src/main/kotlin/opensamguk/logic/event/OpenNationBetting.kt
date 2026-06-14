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
        // PHP `Util::joinYearMonth` (src/sammo/Util.php:710): `$year * 12 + $month - 1`.
        // openYearMonth 는 `Util::joinYearMonth($year, $month)` 와 byte-동일해야 한다
        // (Event/Action/OpenNationBetting.php:47) — `-1` 누락은 1개월 off-by-one 발산.
        val openYearMonth = year * 12 + month - 1

        // 2. 대상 국가 목록 결정
        val targetNations = if (targetNationsArg is JsonArray) {
            targetNationsArg.jsonArray.map { it.jsonPrimitive.int }
        } else {
            @Suppress("UNCHECKED_CAST")
            (ctx.env["nationIds"] as? List<Int>) ?: emptyList()
        }

        if (targetNations.isEmpty()) return

        // 3. candidates 구성 — 후보 인덱스(0,1,2,…) → SelectItem.
        // PHP `Event/Action/OpenNationBetting.php:56,74`: `$candidates = []; … $candidates[] = new SelectItem(…)`
        // 는 0-기준 정수 인덱스로 append 한다(국가 id 가 아니라 삽입순 인덱스). 베팅 선택 검증
        // (`Betting::purifyBettingKey` → `key_exists($bettingKey, $candidates)`, Betting.php:28)도
        // 이 정수 인덱스를 키로 사용한다. LinkedHashMap 으로 삽입순(=PHP append 순)을 보존한다.
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
            // PHP `Event/Action/OpenNationBetting.php:90`: `reqInheritancePoint: true` — 국가 강약
            // 베팅은 유산포인트 베팅이다(금이 아니다). false 는 misport.
            reqInheritancePoint = true,
            openYearMonth = openYearMonth,
            // PHP `Event/Action/OpenNationBetting.php:48`: `$closeYearMonth = $openYearMonth + 24;`
            // (24개월 = 2년). +120 은 fabricate 였다.
            closeYearMonth = openYearMonth + 24,
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
