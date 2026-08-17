package opensamguk.engine.v2

import kotlinx.serialization.json.JsonElement
import opensamguk.logic.event.EventAction
import opensamguk.logic.event.EventActionContext
import opensamguk.logic.event.EventActionFactory

/**
 * OPENSAM-152 (v2 R3) — 도시병사 감소·공백지화 월간 leaf.
 *
 * 설계 정본: `docs/loops/v2-planning-2026-07-12/round3-proposal-city-guanxi.md` §2.4 · §2.6 · §8(R3 행).
 *
 * ## draw 0 — 타입 수준 보장
 *
 * 이 파일의 어떤 함수도 `RandUtil`을 인자로 받지 않고 [V2CityGarrisonAttritionAction]의 생성자에도
 * rng 파라미터가 없다. v1 draw 순서·횟수에 영향을 줄 물리적 경로 자체가 없다.
 *
 * ## 트리거 — 명시하는 divergence
 *
 * **오픈삼국 v2의 공백지화 트리거는 묘섭의 "도적"이 아니라 v1의 "재난"이다.** devsam PHP에는 도적 침입
 * 월드 이벤트가 없다(`che_도적`은 국가 성향 `ActionNationType.kt:107-108`이지 이벤트가 아니다). 그래서
 * v2 leaf는 v1 [opensamguk.logic.world.RaiseDisasterAction]이 이미 확정해 `city.state`에 써 둔 재난 코드를
 * **읽기만** 한다. 새 시드 0 · 새 확률표 0.
 *
 * 부수 효과: `RaiseDisaster`가 `startYear + 3` 이전을 통째로 건너뛰므로(`RaiseDisaster.kt:26,151`)
 * **개막 3년 동안 공백지화가 원천적으로 일어나지 않는다** — 유저가 적은 오픈 직후에 필요한 유예(§2.6).
 *
 * ## 알려진 천장 (숨기지 않는다)
 *
 * `v2_city_ledger.garrison`의 DB 기본값은 0이고(`V901__v2_city_ledger.sql:14`) 이를 채우는 유일한 경로는
 * 아직 없는 R4(병사보충) 커맨드다. 묘섭 원문이 *"도시병사가 **없거나**, 너무 적은 상태에서"*(§2.4 인용)를
 * 공백지 조건으로 명시하므로 이 leaf는 `before == 0`도 공백지화 대상으로 삼는다 — 즉 R4가 없는 상태에서
 * 개막 3년이 지나면 재난을 맞은 도시가 곧바로 공백지가 된다. 이것은 구현 실수가 아니라 R4가 붙기 전까지의
 * 실제 상태이며, §2.6의 "게임 내 3년 이내" 마감이 걸린 항목이 바로 이것이다.
 */

/** `RaiseDisaster.kt:104-127`의 `DISASTER_TEXT`가 실제로 쓰는 stateCode 집합. */
val BAD_STATE_CODES: Set<Int> = setOf(3, 4, 5, 6, 7, 8, 9)

/** v2 leaf가 발화하는 달 — `EventStore.kt:171,180,190,197`의 `a("RaiseDisaster")` 4행과 같다. */
val ATTRITION_MONTHS: Set<Int> = setOf(1, 4, 7, 10)

/**
 * 묘섭 **원문 값** — 장수 300명 기준 감소량 3000명
 * (`help__start__other__etcetera.md:67` "300명 기준 3000명이며, 장수수가 적은 경우, 500명까지로 줄어듭니다").
 *
 * OPENSAM-193 교정: R3(OPENSAM-152)는 이 줄을 놓치고 "묘섭 미명시"라고 적은 채 정률 12.5% + 하한
 * 100명을 **지어냈다**. 원문은 정률이 아니라 **절대 수량**이고, 하한 500명은 3000의 정확히 1/6이라
 * 바로 앞 줄("감소량이 최저 6분의 1까지 감소")과 정확히 맞물린다 — 즉 스케일 곡선의 두 끝값이
 * 원문에서 이미 정해져 있었다. 정률 잔재는 남기지 않는다.
 */
const val ATTRITION_BASE_LOSS: Int = 3000

/** 묘섭 원문 값 — "장수수 300명 기준"(`help__start__other__etcetera.md:64`). */
const val ATTRITION_REFERENCE_GENERALS: Int = 300

/** 묘섭 원문 값 — "최저 6분의 1까지 감소"(같은 줄). */
const val ATTRITION_MIN_SCALE: Double = 1.0 / 6.0

/**
 * 도시병사 감소량 — 순수 함수, draw 0.
 *
 * 감소량은 `garrison`에 비례하지 않는다 — 묘섭 원문이 절대 수량으로 못박았다(OPENSAM-193).
 * 장수 수 스케일의 두 끝값(기준 300명에서 3000 · 장수 0에서 500 = 1/6)도 원문 값이고,
 * **그 사이의 보간 곡선만 원문에 없어 선형으로 정한다 — 묘섭 미명시, 오픈삼국 결정**(§2.6).
 *
 *   scale = 1/6 + (5/6) * min(count, 300)/300     // count=300 → 1.0, count=0 → 1/6
 *   loss  = floor(3000 * scale)                   // count=300 → 3000, count=0 → 500
 *
 * 마지막에 `garrison`으로 자른다: 남은 병사보다 많이 줄일 수는 없고, 이 절단이 0 도달(=공백지화)을
 * 보장한다(정률만 쓰면 0에 도달하지 못한다).
 */
fun attritionLoss(garrison: Int, activeGeneralCount: Int): Int {
    if (garrison <= 0) return 0
    val capped = activeGeneralCount.coerceIn(0, ATTRITION_REFERENCE_GENERALS)
    val scale = ATTRITION_MIN_SCALE +
        (1.0 - ATTRITION_MIN_SCALE) * capped.toDouble() / ATTRITION_REFERENCE_GENERALS
    return kotlin.math.floor(ATTRITION_BASE_LOSS * scale).toInt().coerceAtMost(garrison)
}

/** leaf 입력 1행 — `city_id ASC` 순으로 공급된다. */
data class V2AttritionCity(
    val cityId: Int,
    val name: String,
    val nationId: Int,
    val state: Int,
    val garrison: Int,
)

/** 도시 하나의 판정 결과. */
data class V2AttritionCityOutcome(
    val cityId: Int,
    val before: Int,
    val after: Int,
    /** true면 `city.nationId = 0`을 써야 한다 — 감소 직후 **같은 반복 안에서** 결정된 값이다. */
    val vacated: Boolean,
    val logLines: List<String>,
)

/** leaf 전체 결과. 월 게이트에 걸리면 [outcomes]가 비어 있다. */
data class V2AttritionResult(val outcomes: List<V2AttritionCityOutcome>)

private fun attritionLine(name: String, before: Int, after: Int): String =
    "<M><b>【도시병사】</b></> <G><b>$name</b></>의 도시병사가 ${before}에서 ${after}(으)로 줄었습니다."

private fun vacatedLine(name: String, before: Int): String =
    "<M><b>【공백지】</b></> <G><b>$name</b></>의 도시병사가 ${before}에서 0이 되어 공백지가 되었습니다."

/**
 * 판정 절차(§2.4 의사코드 그대로). 호출자는 [cities]를 `city_id ASC`로 준다.
 *
 * 공백지화는 `city.nationId = 0`만 쓴다. 관직·부대 등 부수 정리는 v1 `ConquerCity`의 영역이라
 * **재사용하지 않는다** — v1 정복 경로를 부르면 그 경로의 로그·draw를 끌어들이게 된다.
 */
fun v2CityGarrisonAttrition(
    month: Int,
    cities: List<V2AttritionCity>,
    activeGeneralCount: Int,
): V2AttritionResult {
    if (month !in ATTRITION_MONTHS) return V2AttritionResult(emptyList())

    val outcomes = ArrayList<V2AttritionCityOutcome>()
    for (city in cities) {
        if (city.state !in BAD_STATE_CODES) continue

        val before = city.garrison
        val after = (before - attritionLoss(before, activeGeneralCount)).coerceAtLeast(0)
        val vacated = after == 0 && city.nationId != 0

        val lines = ArrayList<String>(2)
        if (after != before) lines.add(attritionLine(city.name, before, after))
        if (vacated) lines.add(vacatedLine(city.name, before))

        if (after == before && !vacated) continue
        outcomes.add(V2AttritionCityOutcome(city.cityId, before, after, vacated, lines))
    }
    return V2AttritionResult(outcomes)
}

/**
 * v2 월간 leaf. **생성자에 `RandUtil` 파라미터가 없다** — draw 0이 타입 수준에서 보장된다.
 *
 * 컨텍스트가 [V2CityGarrisonAttritionContext]가 아니면 **죽는다.** 무음 no-op이면 "재난이 나도 병사가
 * 안 줄어드는 월드"가 테스트 그린으로 보인다 — R2가 같은 이유로 같은 선택을 했다.
 */
class V2CityGarrisonAttritionAction : EventAction {
    override fun run(ctx: EventActionContext) {
        val vc = ctx as? V2CityGarrisonAttritionContext
            ?: error("V2CityGarrisonAttritionAction requires a V2CityGarrisonAttritionContext (v2 도시 원장 없음)")
        vc.applyV2Attrition(
            v2CityGarrisonAttrition(vc.attritionMonth(), vc.attritionCities(), vc.activeGeneralCount()),
        )
    }

    companion object {
        const val NAME = "V2CityGarrisonAttrition"

        fun register(factory: EventActionFactory): EventActionFactory =
            factory.register(NAME) { _: List<JsonElement> -> V2CityGarrisonAttritionAction() }
    }
}

/** leaf가 필요로 하는 월드 seam. 프로덕션 구현자는 `WorldActionContext` 하나다. */
interface V2CityGarrisonAttritionContext : EventActionContext {
    fun attritionMonth(): Int

    /** `city_id ASC` 순서로 준다 — 순회 순서가 결과의 일부다. */
    fun attritionCities(): List<V2AttritionCity>

    /** 묘섭의 "등록 장수" 대응 — 월드에 살아 있는 장수 수. */
    fun activeGeneralCount(): Int

    fun applyV2Attrition(result: V2AttritionResult)
}
