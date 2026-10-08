package opensamguk.gameapi.reserve

import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.gameapi.precheck.DispatchPrecheckService
import opensamguk.gameapi.read.*
import opensamguk.logic.input.InputCatalog
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import java.util.Optional
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CourtRewardAdmissionTest {
    @Test
    fun `minimum and current loyalty cap reject before canonical admission while exact bounds pass`() {
        for ((loyalty, maximum) in listOf(0 to 1000L, 50 to 1000L, 90 to 1000L,
            91 to 900L, 95 to 500L, 99 to 100L, 100 to 100L)) {
            val fixture = fixture(loyalty)
            for (money in listOf(100L, maximum)) {
                assertEquals(body(money), fixture.admission.canonicalArguments(10, 42, "court.reward", body(money)))
            }
            assertEquals("TOO_SMALL", denied(fixture, 99).code)
            val over = denied(fixture, maximum + 1)
            assertEquals("REWARD_OVER_CAP", over.code)
            assertEquals("현재 충성에서 내릴 수 있는 상사 금을 넘었습니다.", over.message)
            verifyNoInteractions(*fixture.unrelatedReads)
        }
    }

    @Test
    fun `remainder money is preserved and each admission reads current loyalty`() {
        val fixture = fixture(50)
        assertEquals(body(150), fixture.admission.canonicalArguments(10, 42, "court.reward", body(150)))
        assertEquals(body(1000), fixture.admission.canonicalArguments(10, 42, "court.reward", body(1000)))
        fixture.card.loyalty = 95
        assertEquals("REWARD_OVER_CAP", denied(fixture, 1000).code)
        assertEquals(body(500), fixture.admission.canonicalArguments(10, 42, "court.reward", body(500)))
        fixture.card.loyalty = 100
        assertEquals(body(100), fixture.admission.canonicalArguments(10, 42, "court.reward", body(100)))
        assertEquals("REWARD_OVER_CAP", denied(fixture, 101).code)
        verifyNoInteractions(*fixture.unrelatedReads)
    }

    @Test
    fun `foreign or absent actor cannot read card loyalty`() {
        val fixture = fixture(95)
        assertEquals("FORBIDDEN", assertFailsWith<AdmissionDenied> {
            fixture.admission.canonicalArguments(10, 43, "court.reward", body(100))
        }.code)
        `when`(fixture.generals.findById(10)).thenReturn(Optional.empty())
        assertEquals("FORBIDDEN", denied(fixture, 100).code)
        verifyNoInteractions(fixture.retainers, *fixture.unrelatedReads)
    }

    @Test
    fun `missing foreign and unbound cards never grant an admission`() {
        val fixture = fixture(95)
        for (cards in listOf(emptyList(),
            listOf(GeneralRetainerReadEntity(id = 5, masterGeneralId = 99, generalId = 20, loyalty = 95)),
            listOf(GeneralRetainerReadEntity(id = 5, masterGeneralId = 10, generalId = null, loyalty = 95)))) {
            `when`(fixture.retainers.retainersOf(10)).thenReturn(cards)
            assertEquals("CARD_UNAVAILABLE", denied(fixture, 100).code)
        }
        verifyNoInteractions(*fixture.unrelatedReads)
    }

    @Test
    fun `malformed or unauthenticated reward is rejected before ownership and card reads`() {
        val fixture = fixture(95)
        assertEquals("UNAUTHORIZED", assertFailsWith<AdmissionDenied> {
            fixture.admission.canonicalArguments(10, 0, "court.reward", body(100))
        }.code)
        for (raw in listOf("{}", """{"retainerId":5,"money":0}""",
            """{"retainerId":5,"money":"100"}""", """{"retainerId":5,"money":100.5}""",
            """{"retainerId":5,"money":100,"actorId":10}""")) {
            assertEquals("INVALID_REQUEST", assertFailsWith<AdmissionDenied> {
                fixture.admission.canonicalArguments(10, 42, "court.reward", raw)
            }.code)
        }
        verifyNoInteractions(fixture.generals, fixture.retainers, *fixture.unrelatedReads)
    }

    @Test
    fun `unavailable admission reader fails closed`() {
        val admission = CourtAdmission(mock(DispatchPrecheckService::class.java))
        assertEquals("STATE_UNAVAILABLE", assertFailsWith<AdmissionDenied> {
            admission.canonicalArguments(10, 42, "court.reward", body(100))
        }.code)
    }

    private fun body(money: Long) = """{"retainerId":5,"money":$money}"""
    private fun denied(fixture: Fixture, money: Long) = assertFailsWith<AdmissionDenied> {
        fixture.admission.canonicalArguments(10, 42, "court.reward", body(money))
    }

    private data class Fixture(val admission: CourtAdmission, val generals: GeneralReadRepository,
        val retainers: RetainerReadRepository, val card: GeneralRetainerReadEntity,
        val unrelatedReads: Array<Any>)

    private fun fixture(loyalty: Int): Fixture {
        val generals = mock(GeneralReadRepository::class.java)
        val retainers = mock(RetainerReadRepository::class.java)
        val nations = mock(NationReadRepository::class.java)
        val artifacts = mock(ActiveWorldArtifactResolver::class.java)
        val spatial = mock(SpatialStateReadRepository::class.java)
        val geography = mock(CityGeography::class.java)
        val kv = mock(GameKvReadRepository::class.java)
        val diplomacy = mock(DiplomacyReadRepository::class.java)
        val sieges = mock(SiegeReadRepository::class.java)
        val troops = mock(TroopReadRepository::class.java)
        `when`(generals.findById(10)).thenReturn(Optional.of(GeneralReadEntity(id = 10, userId = "42")))
        val card = GeneralRetainerReadEntity(id = 5, masterGeneralId = 10, generalId = 20, loyalty = loyalty)
        `when`(retainers.retainersOf(10)).thenReturn(listOf(card))
        val reader = DomesticReader(generals, retainers, nations, artifacts, spatial, geography, kv,
            ObjectMapper(), diplomacy, sieges, troops)
        val admission = CourtAdmission(mock(DispatchPrecheckService::class.java),
            catalog = InputCatalog.load(), reader = reader)
        return Fixture(admission, generals, retainers, card,
            arrayOf(nations, artifacts, spatial, geography, kv, diplomacy, sieges, troops))
    }
}
