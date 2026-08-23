package opensamguk.engine.v2

import opensamguk.logic.v2.command.V2CityTransportArgs
import opensamguk.logic.v2.command.V2CityTransportContext
import opensamguk.logic.v2.command.decideCityTransport

/**
 * OPENSAM-154 (v2 R5) — 도시 자원 수송 순수 판정. draw 0, 세계 접근 0.
 *
 * 근거는 묘섭 원문 `docs/wiki/raw/myosam-help/help__start__intermediate__intermediatebattle.md`:
 *
 * > ":361  군사 탭 의 수송 커맨드를 통해 인접 도시로 도시의 금과 병량, 그리고 도시 병사를 수송할 수 있습니다."
 * > ":364  수송의 최대치는 금, 병량이 각각 5만 씩 가능하며 수송에 필요한 최소병사량은 2000명 입니다."
 *
 * 주민은 수송 대상이 아니다(원문이 금·병량·도시병사 셋만 든다).
 */
sealed interface V2TransportDecision {
    data class Denied(val reason: String) : V2TransportDecision
    data class Applied(val gold: Long, val rice: Long, val garrison: Int) : V2TransportDecision
}

/** 묘섭 원문 값 — 금·병량 각각 5만(`:364`). */
const val TRANSPORT_MAX_GOLD: Long = opensamguk.logic.v2.command.V2_TRANSPORT_MAX_GOLD

/** 묘섭 원문 값 — 금·병량 각각 5만(`:364`). */
const val TRANSPORT_MAX_RICE: Long = opensamguk.logic.v2.command.V2_TRANSPORT_MAX_RICE

/**
 * **묘섭 미명시 · 임시값(U6 UNKNOWN)** — 도시병사 수송 상한.
 *
 * 원문 `:364`는 금·병량의 상한만 적고 도시병사 상한은 말하지 않는다. 지어내지 않고, 같은 문장이 정한
 * 5만을 **임시로** 쓴 뒤 v2 밸런싱에서 확정한다(설계안 §11 U6). 값이 바뀌어도 구조는 불변이다.
 */
const val TRANSPORT_MAX_GARRISON: Int = opensamguk.logic.v2.command.V2_TRANSPORT_MAX_GARRISON

/**
 * 묘섭 원문 값 — "수송에 필요한 최소병사량은 2000명"(`:364`).
 *
 * **해석을 적어 둔다(원문이 한 문장뿐이라 두 갈래로 읽힌다):** 여기서는 *수송을 수행하는 장수가
 * 거느린 병사(호송 병력)의 하한*으로 읽었다 — "수송에 **필요한**" 이 수송 행위의 전제 조건을 가리키기
 * 때문이다. 다른 갈래("도시병사를 수송할 때 한 번에 2000명 이상")도 문장상 가능하며, 그쪽이 맞다고
 * 판명되면 판정 한 줄의 대상만 바뀐다. 리뷰 문서에 이 모호성을 남긴다.
 */
const val TRANSPORT_MIN_ESCORT_CREW: Int = opensamguk.logic.v2.command.V2_TRANSPORT_MIN_ESCORT_CREW

/**
 * 수송 판정 — 순수 함수, draw 0.
 *
 * @param hopDistance `CalcCityDistance.calcCityDistance(from, to)` 결과. `null`이면 도달 불가.
 * @param escortCrew 명령을 낸 장수의 병사 수.
 * @param from 출발 도시의 **현재 원장**. 잔액 판정은 여기서 하고, 원장 자체는 손대지 않는다.
 */
fun transportDecision(
    gold: Long,
    rice: Long,
    garrison: Int,
    hopDistance: Int?,
    escortCrew: Int,
    from: V2CityLedgerEntry,
): V2TransportDecision {
    return when (
        val decision = decideCityTransport(
            V2CityTransportArgs(1, 2, gold, rice, garrison, null),
            V2CityTransportContext(
                generalCityId = 1,
                generalNationId = 1,
                escortCrew = escortCrew,
                fromNationId = 1,
                toNationId = 1,
                hopDistance = hopDistance,
                fromGold = from.gold,
                fromRice = from.rice,
                fromGarrison = from.garrison,
            ),
        )
    ) {
        is opensamguk.logic.v2.command.V2CityTransportDecision.Denied -> V2TransportDecision.Denied(decision.reason)
        is opensamguk.logic.v2.command.V2CityTransportDecision.Applied ->
            V2TransportDecision.Applied(decision.gold, decision.rice, decision.garrison)
    }
}
