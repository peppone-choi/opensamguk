package opensamguk.gameapi.precheck

import opensamguk.gameapi.read.DomesticForbidden
import opensamguk.gameapi.read.DomesticReader
import opensamguk.gameapi.read.DomesticSnapshot
import opensamguk.gameapi.read.ProvinceNamesCacheReader
import opensamguk.gameapi.reserve.AdmissionDenied
import opensamguk.gameapi.reserve.PeopleAdmission
import opensamguk.gameapi.reserve.CourtAdmission
import opensamguk.logic.domestic.DomesticCounty
import opensamguk.logic.domestic.DomesticPerson
import opensamguk.logic.domestic.DomesticProjection
import opensamguk.logic.input.PeopleFailure
import opensamguk.logic.input.CaptiveState
import opensamguk.logic.input.CaptiveReleaseInput
import opensamguk.logic.input.PeopleAssessment
import opensamguk.logic.input.PeopleInput
import opensamguk.logic.input.PeopleRequest
import opensamguk.logic.input.PeopleRules
import opensamguk.logic.input.PersonPolicyState
import opensamguk.logic.input.Phase
import opensamguk.logic.input.RuleProfile
import opensamguk.logic.input.TalentDiscovery
import org.mockito.Mockito.`when`
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PeopleOptionsServiceTest {
    private val actor = DomesticPerson(7, "주인", 1, true, 0, 1, 60, 60, 60, 60, 60,
        "province-a", false, mapOf(PersonPolicyState.META_KEY to
            PersonPolicyState(30, true, "test", "1", 7).toMetaValue()))
    private val free = actor.copy(id = 8, name = "재야", nationId = 0, userOwned = false, npcState = 2)
    private val known = actor.copy(meta = TalentDiscovery.add(actor.meta, free.id))
    private val county = DomesticCounty(11, "縣", 1, "province-a", "郡", emptyMap())
    private val state = DomesticProjection(RuleProfile.HWIHA, Phase(200, 1, 1),
        listOf(known, free), emptyList(), listOf(county), emptyList(), setOf("province-a", "province-b"))
    private val reader = mock(DomesticReader::class.java)
    private val provinceNames = mock(ProvinceNamesCacheReader::class.java)
    private val service = PeopleOptionsService(reader, provinceNames)

    private fun options(projection: DomesticProjection = state): PeopleOptions {
        `when`(reader.snapshot()).thenReturn(DomesticSnapshot(state = projection))
        return service.options(PeopleInput.EMPLOY, actor.id, 42L)
    }

    @Test fun `discovered eligible target is offered before selection and its numeric arg is admitted`() {
        val result = options()
        assertTrue(result.available)
        assertNull(result.code)
        assertNull(result.reason)
        assertEquals(listOf(PeopleTargetOption(free.id, free.name, true)), result.targets)
        verify(reader).requireOwner(actor.id, 42L)
        assertEquals("""{"targetGeneralId":8}""", PeopleAdmission(reader).canonicalArguments(
            PeopleInput.EMPLOY, actor.id, 42, 0, """{"targetGeneralId":8}"""))
    }

    @Test fun `unaffiliated player can read and reserve search then employ only after discovery`() {
        val wanderer = actor.copy(nationId = 0)
        `when`(reader.snapshot()).thenReturn(DomesticSnapshot(state = state.copy(people = listOf(wanderer, free))))
        val search = service.options(PeopleInput.SEARCH, wanderer.id, 42L)
        assertTrue(search.available)
        assertEquals(1, search.undiscoveredCount)
        val admission = PeopleAdmission(reader)
        assertEquals("{}", admission.canonicalArguments(PeopleInput.SEARCH, wanderer.id, 42, 0, "{}"))
        assertEquals(PeopleFailure.TARGET_NOT_DISCOVERED.name, assertFailsWith<AdmissionDenied> {
            admission.canonicalArguments(PeopleInput.EMPLOY, wanderer.id, 42, 1,
                """{"targetGeneralId":8}""")
        }.code)

        val discovered = wanderer.copy(meta = TalentDiscovery.add(wanderer.meta, free.id))
        `when`(reader.snapshot()).thenReturn(DomesticSnapshot(state = state.copy(people = listOf(discovered, free))))
        val employ = service.options(PeopleInput.EMPLOY, wanderer.id, 42L)
        assertTrue(employ.available)
        assertEquals(listOf(PeopleTargetOption(free.id, free.name, true)), employ.targets)
        assertEquals("""{"targetGeneralId":8}""", admission.canonicalArguments(PeopleInput.EMPLOY,
            wanderer.id, 42, 1, """{"targetGeneralId":8}"""))
        val captive = free.copy(nationId = 2, meta = free.meta + (CaptiveState.META_KEY to
            CaptiveState(wanderer.id, "province-a", Phase(200, 1, 1), "battle-8").toMetaValue()))
        `when`(reader.snapshot()).thenReturn(DomesticSnapshot(state = state.copy(people = listOf(discovered, captive))))
        assertEquals(PeopleFailure.STATE_UNAVAILABLE.name,
            service.options(PeopleInput.PERSUADE_CAPTIVE, wanderer.id, 42L).code)
    }

    @Test fun `missing unknown and other-location targets are unavailable rather than malformed input`() {
        for (people in listOf(listOf(known), listOf(actor, free),
            listOf(known, free.copy(node = "province-b")))) {
            val result = options(state.copy(people = people))
            assertFalse(result.available)
            assertEquals(PeopleFailure.TARGET_UNAVAILABLE.name, result.code)
            assertEquals(PeopleFailure.TARGET_UNAVAILABLE.message, result.reason)
            assertTrue(result.targets.isEmpty())
        }
    }

    @Test fun `current actor location battle and county failures remain specific`() {
        val cases = listOf(
            known.copy(node = null) to PeopleFailure.POSITION_UNAVAILABLE,
            known.copy(inBattle = true) to PeopleFailure.BATTLE_PENDING,
            known.copy(node = "province-b") to PeopleFailure.COUNTY_UNAVAILABLE,
            known.copy(nationId = -1) to PeopleFailure.STATE_UNAVAILABLE,
        )
        for ((changed, failure) in cases) {
            val result = options(state.copy(people = listOf(changed, free)))
            assertFalse(result.available)
            assertEquals(failure.name, result.code)
            assertEquals(failure.message, result.reason)
            assertTrue(result.targets.isEmpty())
        }
    }

    @Test fun `search and employ share actor and location rejection gates before target validation`() {
        val cases = listOf(
            state.copy(people = listOf(free)) to PeopleFailure.ACTOR_NOT_FOUND,
            state.copy(people = listOf(known.copy(nationId = -1), free)) to PeopleFailure.STATE_UNAVAILABLE,
            state.copy(people = listOf(known.copy(inBattle = true), free)) to PeopleFailure.BATTLE_PENDING,
            state.copy(people = listOf(known.copy(node = null), free)) to PeopleFailure.POSITION_UNAVAILABLE,
            state.copy(landProvinceIds = emptySet()) to PeopleFailure.STATE_UNAVAILABLE,
            state.copy(counties = emptyList()) to PeopleFailure.COUNTY_UNAVAILABLE,
        )
        for ((projection, failure) in cases) {
            for (inputId in listOf(PeopleInput.SEARCH, PeopleInput.EMPLOY)) {
                assertEquals(PeopleAssessment.Rejected(failure),
                    PeopleRules.assess(PeopleRequest(actor.id, inputId, null), projection))
            }
        }
    }

    @Test fun `missing actor and unavailable projection fail closed`() {
        val absent = options(state.copy(people = listOf(free)))
        assertFalse(absent.available)
        assertEquals(PeopleFailure.ACTOR_NOT_FOUND.name, absent.code)
        `when`(reader.snapshot()).thenReturn(DomesticSnapshot(failure = "UNAVAILABLE"))
        val unavailable = service.options(PeopleInput.EMPLOY, actor.id, 42L)
        assertFalse(unavailable.available)
        assertEquals(PeopleFailure.STATE_UNAVAILABLE.name, unavailable.code)
    }

    @Test fun `target capacity and free status checks still determine the offered candidate`() {
        val noCapacity = known.copy(meta = known.meta + (PersonPolicyState.META_KEY to
            PersonPolicyState(0, true, "test", "1", 7).toMetaValue()))
        for ((people, failure) in listOf(
            listOf(noCapacity, free) to PeopleFailure.CAPACITY_UNAVAILABLE,
            listOf(known, free.copy(nationId = 2)) to PeopleFailure.TARGET_NOT_FREE,
        )) {
            val result = options(state.copy(people = people))
            assertFalse(result.available)
            assertEquals(failure.name, result.code)
            val candidate = result.targets.single()
            assertEquals(free.id, candidate.generalId)
            assertFalse(candidate.available)
            assertEquals(failure.message, candidate.reason)
        }
    }

    @Test fun `foreign and invalid actors cannot read candidates`() {
        for (id in listOf(actor.id, 0, -1)) {
            doThrow(DomesticForbidden()).`when`(reader).requireOwner(id, 99L)
            assertFailsWith<DomesticForbidden> { service.options(PeopleInput.EMPLOY, id, 99L) }
        }
        verify(reader, never()).snapshot()
    }

    @Test fun `candidate discovery does not relax required numeric target in reservation`() {
        assertTrue(options().available)
        val admission = PeopleAdmission(reader)
        for (raw in listOf("{}", """{"targetGeneralId":null}""", """{"targetGeneralId":"8"}""",
            """{"targetGeneralId":7}""")) {
            val rejected = assertFailsWith<AdmissionDenied> {
                admission.canonicalArguments(PeopleInput.EMPLOY, actor.id, 42, 0, raw)
            }
            assertEquals(PeopleFailure.INVALID_INPUT.name, rejected.code)
        }
    }

    @Test fun `actual held captive is readable and both turn and no-turn intake recheck custody`() {
        val captive = free.copy(nationId = 2, meta = free.meta + (CaptiveState.META_KEY to
            CaptiveState(actor.id, "province-a", Phase(200, 1, 1), "battle-8").toMetaValue()))
        `when`(reader.snapshot()).thenReturn(DomesticSnapshot(state = state.copy(people = listOf(known, captive))))
        val offered = service.options(PeopleInput.PERSUADE_CAPTIVE, actor.id, 42L)
        assertTrue(offered.available)
        assertEquals(captive.id, offered.targets.single().generalId)
        val held = service.captives(actor.id, 42L).targets.single()
        assertEquals("province-a", held.heldProvinceId)
        assertEquals("province-a", held.actualProvinceId)
        assertEquals("NONE", held.expiry)
        assertTrue(held.releaseAvailable)
        assertEquals("""{"targetGeneralId":8}""", PeopleAdmission(reader).canonicalArguments(
            PeopleInput.PERSUADE_CAPTIVE, actor.id, 42, 0, """{"targetGeneralId":8}"""))
        val release = CourtAdmission(mock(DispatchPrecheckService::class.java), reader = reader)
        assertEquals("""{"targetGeneralId":8}""", release.canonicalArguments(actor.id, 42,
            CaptiveReleaseInput.INPUT_ID, """{"targetGeneralId":8}"""))
        `when`(reader.snapshot()).thenReturn(DomesticSnapshot(state = state.copy(people = listOf(known,
            captive.copy(node = "province-b")))))
        assertEquals(PeopleFailure.TARGET_UNAVAILABLE.name, assertFailsWith<AdmissionDenied> {
            release.canonicalArguments(actor.id, 42, CaptiveReleaseInput.INPUT_ID, """{"targetGeneralId":8}""")
        }.code)
    }

    @Test fun `old captive marker cannot become a valid option or release target`() {
        val old = free.copy(nationId = 2, meta = free.meta + (CaptiveState.META_KEY to
            mapOf("captorGeneralId" to actor.id)))
        `when`(reader.snapshot()).thenReturn(DomesticSnapshot(state = state.copy(people = listOf(known, old))))
        assertFalse(service.options(PeopleInput.PERSUADE_CAPTIVE, actor.id, 42L).available)
        assertTrue(service.captives(actor.id, 42L).targets.isEmpty())
        val denied = assertFailsWith<AdmissionDenied> {
            CourtAdmission(mock(DispatchPrecheckService::class.java), reader = reader)
                .canonicalArguments(actor.id, 42, CaptiveReleaseInput.INPUT_ID, """{"targetGeneralId":8}""")
        }
        assertEquals(PeopleFailure.TARGET_NOT_CAPTIVE.name, denied.code)
    }
}
