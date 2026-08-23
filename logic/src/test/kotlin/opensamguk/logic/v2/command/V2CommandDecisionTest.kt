package opensamguk.logic.v2.command

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class V2CommandDecisionTest {
    @Test
    fun `recruit context denies a missing actor with a stable reason`() {
        val decision = decideGarrisonRecruit(
            V2GarrisonRecruitArgs(cityId = 5, amount = 100),
            V2GarrisonRecruitContext.missingGeneral(),
        )

        val denied = assertIs<V2GarrisonRecruitDecision.Denied>(decision)
        assertEquals("GENERAL_NOT_FOUND", denied.code)
        assertEquals("장수를 찾을 수 없습니다.", denied.reason)
    }

    @Test
    fun `recruit context evaluates ownership population and ledger balance`() {
        val args = V2GarrisonRecruitArgs(cityId = 5, amount = 100)
        val context = V2GarrisonRecruitContext(
            generalCityId = 5,
            generalNationId = 1,
            leadership = 80,
            cityNationId = 1,
            cityPopulation = 31_000,
            cityTrust = 80.0,
            ledgerGold = 8,
        )

        val denied = assertIs<V2GarrisonRecruitDecision.Denied>(decideGarrisonRecruit(args, context))

        assertEquals("CITY_GOLD_INSUFFICIENT", denied.code)
        assertEquals("도시의 금이 부족합니다.", denied.reason)
    }

    @Test
    fun `transport context evaluates adjacency and ledger balance`() {
        val args = V2CityTransportArgs(1, 2, 100, 0, 0, 7)
        val context = V2CityTransportContext(
            generalCityId = 1,
            generalNationId = 1,
            escortCrew = 2_000,
            fromNationId = 1,
            toNationId = 1,
            hopDistance = 2,
            fromGold = 1_000,
            fromRice = 0,
            fromGarrison = 0,
        )

        val denied = assertIs<V2CityTransportDecision.Denied>(decideCityTransport(args, context))

        assertEquals("ROUTE_NOT_ADJACENT", denied.code)
        assertEquals("인접한 도시로만 수송할 수 있습니다.", denied.reason)
    }
}
