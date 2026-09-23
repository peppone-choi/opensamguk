package opensamguk.engine.hwiha

import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.logic.input.HwihaDomesticMerit
import opensamguk.logic.input.HwihaGovernanceMeritEvent
import opensamguk.logic.input.HwihaGovernanceMeritSink
import opensamguk.logic.input.HwihaRenownEventSource
import opensamguk.logic.input.HwihaRenownEvents

/**
 * 내정 스트림의 치적 사건([HwihaGovernanceMeritEvent] — 縣令 카드가 앉은 縣 의 지표 상승)을 기록 스트림의
 * 월단평 사건(치적, [HwihaRenownEventSource.COUNTY_INDICATOR_RISE])으로 잇는다. 쓰기는 [HwihaRenownEventRecorder]
 * 를 거쳐 ChangeRecorder 경로로만 간다.
 *
 * ### 도장은 비교를 마친 달이 아니라 지표가 오른 달이다
 *
 * 내정 경계는 M+1 월 경계(상순)에서 M 월 경계에 적어 둔 값과 지금 값을 비교하고 [HwihaGovernanceMeritEvent.monthStamp]
 * 에 M+1 을 싣는다. 지표가 오른 것은 M 이므로 치적은 M 도장으로 쌓는다. 그래야
 *
 * - 기록 스트림의 치적 창([HwihaCountyMeritWindow], 같은 경계에서 M 도장으로 닫는다)과 같은 달 같은 종류가 되어
 *   「한 달에 종류당 한 번」 규칙이 두 경로의 중복을 한 건으로 접는다 — 발령 관할 장수이면서 縣令 카드 주인인
 *   장수도 그 달 치적은 한 건이다.
 * - 같은 경계 뒤쪽의 월단평(M+1 을 여는 평가는 M 이전 사건을 적용한다)이 바로 적용한다.
 *
 * ### 문턱은 기록 스트림의 치적 판정을 따른다
 *
 * 내정 사건은 일곱 지표 가운데 하나라도 1 이라도 오르면 난다. 치적이 명망으로 얼마나 이어지는지는 기록 스트림이 정하므로
 * 여기서 [HwihaDomesticMerit.risen](호구·전답·시장 가운데 하나가 상한의 2% 이상, 2026-09-23 확정)을 한 번 더 건다 — 두 경로의
 * 치적 기준을 하나로 둔다.
 *
 * 월 경계에서 던지면 턴 루프가 영구히 멈추므로 읽을 수 없는 도장·사라진 縣 은 무동작이다.
 */
class HwihaGovernanceMeritRenownSink(
    private val world: InMemoryTurnWorld,
    private val recorder: ChangeRecorder,
) : HwihaGovernanceMeritSink {
    override fun onCountyIndicatorsRose(event: HwihaGovernanceMeritEvent) {
        val stamp = meritStamp(event.monthStamp) ?: return
        val city = world.getCityById(event.countyId) ?: return
        val risen = HwihaDomesticMerit.risen(
            HwihaDomesticMerit.Indicators(event.previous.population, event.previous.agriculture, event.previous.commerce),
            HwihaDomesticMerit.Indicators(event.current.population, event.current.agriculture, event.current.commerce),
            HwihaDomesticMerit.Indicators(city.populationMax, city.agricultureMax, city.commerceMax),
        )
        if (!risen) return
        HwihaRenownEventRecorder(world, recorder).record(event.ownerGeneralId, HwihaRenownEventSource.COUNTY_INDICATOR_RISE, stamp)
    }

    companion object {
        /** 비교를 마친 달 `YYYY-MM` → 지표가 오른 그 앞 달. 읽을 수 없거나 앞 달이 없으면 null. */
        fun meritStamp(comparedMonth: String): String? {
            val ordinal = try { HwihaRenownEvents.monthOrdinal(comparedMonth) } catch (_: IllegalArgumentException) { return null }
            if (ordinal <= 0) return null
            val previous = ordinal - 1
            return HwihaRenownEvents.stampOf(previous / 12, previous % 12 + 1)
        }
    }
}
