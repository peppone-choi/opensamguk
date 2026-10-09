package opensamguk.gameapi.politics

import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.gameapi.precheck.PoliticalOption
import opensamguk.gameapi.precheck.PoliticalOptionsService
import opensamguk.gameapi.read.DomesticForbidden
import opensamguk.gameapi.read.DomesticReader
import opensamguk.gameapi.read.DomesticSnapshot
import opensamguk.gameapi.reserve.AdmissionDenied
import opensamguk.gameapi.reserve.PoliticalAdmission
import opensamguk.logic.domestic.DomesticCounty
import opensamguk.logic.domestic.DomesticNation
import opensamguk.logic.domestic.DomesticPerson
import opensamguk.logic.domestic.DomesticProjection
import opensamguk.logic.input.*
import org.mockito.Mockito.`when`
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.never
import kotlin.test.*

class AbdicationOptionsTest {
    private val actor = DomesticPerson(7, "주공", 1, true, 0, 12, 60, 60, 60, 60, 60,
        "province-a", false, mapOf(LordStatus.META_KEY to true))
    private val reader = mock(DomesticReader::class.java)
    private val catalog = InputCatalog.load()

    private fun candidate(id: Int, nationId: Int = 1, accepted: Boolean? = null): DomesticPerson {
        val consent = accepted?.let { mapOf(PoliticalConsent.META_KEY to
            PoliticalConsent(actor.id, PoliticalInput.ABDICATE, it).toMetaValue()) }.orEmpty()
        return actor.copy(id = id, name = "후보$id", nationId = nationId, officerLevel = 1,
            meta = mapOf(LordStatus.META_KEY to false) + consent)
    }

    private fun state(people: List<DomesticPerson> = listOf(actor)) =
        DomesticProjection(RuleProfile.HWIHA, Phase(200, 1, 1), people, emptyList(),
            listOf(DomesticCounty(11, "縣", 1, "province-a", "郡", emptyMap())),
            listOf(DomesticNation(1, "세력", 11, emptyMap(), chiefGeneralId = actor.id)), setOf("province-a"))

    private fun options(state: DomesticProjection?, actorId: Int = actor.id,
        inputCatalog: InputCatalog = catalog): List<PoliticalOption> {
        `when`(reader.snapshot()).thenReturn(DomesticSnapshot(state = state))
        return PoliticalOptionsService(reader, inputCatalog).options(actorId, 42L)
    }

    private fun abdication(state: DomesticProjection?, actorId: Int = actor.id) =
        options(state, actorId).single { it.inputId == PoliticalInput.ABDICATE }

    private fun assertNeutral(option: PoliticalOption) {
        assertFalse(option.available)
        assertNull(option.code)
        assertNull(option.reason)
    }

    private fun assertTargetParity(option: PoliticalOption, state: DomesticProjection) {
        val admission = PoliticalAdmission(reader, catalog)
        for (target in option.targets) {
            val request = PoliticalRequest(actor.id, PoliticalInput.ABDICATE, target.generalId)
            val failure = (PoliticalRules.assess(request, state) as? PoliticalAssessment.Rejected)?.reason
            assertEquals(failure == null, target.available)
            assertEquals(failure?.name, target.code)
            assertEquals(failure?.message, target.reason)
            val args = PoliticalInput.canonicalJson(request)
            if (failure == null) {
                assertEquals(args, admission.canonicalArguments(PoliticalInput.ABDICATE, actor.id, 42, 0, args))
            } else {
                val denied = assertFailsWith<AdmissionDenied> {
                    admission.canonicalArguments(PoliticalInput.ABDICATE, actor.id, 42, 0, args)
                }
                assertEquals(failure.name, denied.code)
            }
        }
    }

    @Test fun `mixed blocked candidates keep their reasons without a global candidate failure`() {
        val foreign = candidate(1, nationId = 2)
        val waiting = candidate(8)
        val declined = candidate(9, accepted = false)
        val fighting = candidate(10, accepted = true).copy(inBattle = true)
        val malformed = candidate(11).copy(meta = mapOf(PoliticalConsent.META_KEY to "invalid"))
        val npc = candidate(12, accepted = true).copy(userOwned = false)
        val snapshot = state(listOf(malformed, npc, fighting, actor, declined, waiting, foreign))
        val option = abdication(snapshot)

        assertNeutral(option)
        assertEquals(listOf(1, 8, 9, 10, 11), option.targets.map { it.generalId })
        assertEquals(listOf(PoliticalFailure.SAME_NATION_REQUIRED, PoliticalFailure.CONSENT_REQUIRED,
            PoliticalFailure.CONSENT_DECLINED, PoliticalFailure.BATTLE_PENDING, PoliticalFailure.STATE_UNAVAILABLE)
            .map { it.name }, option.targets.map { it.code })
        assertTargetParity(option, snapshot)
    }

    @Test fun `global reason is independent of which blocked candidate sorts first`() {
        for ((foreignId, waitingId) in listOf(1 to 8, 8 to 1)) {
            val snapshot = state(listOf(candidate(waitingId), actor, candidate(foreignId, nationId = 2)))
            val option = abdication(snapshot)
            assertNeutral(option)
            assertTargetParity(option, snapshot)
        }
    }

    @Test fun `a valid acceptance enables abdication and a later refusal disables it`() {
        val successor = candidate(8, accepted = true)
        val snapshot = state(listOf(actor, candidate(1, nationId = 2), successor))
        val available = abdication(snapshot)
        assertTrue(available.available)
        assertNull(available.code)
        assertNull(available.reason)
        assertTargetParity(available, snapshot)

        val refused = snapshot.copy(people = snapshot.people.map {
            if (it.id == successor.id) candidate(successor.id, accepted = false) else it
        })
        val unavailable = abdication(refused)
        assertNeutral(unavailable)
        assertEquals(PoliticalFailure.CONSENT_DECLINED.name, unavailable.targets.single { it.generalId == 8 }.code)
        assertTargetParity(unavailable, refused)
    }

    @Test fun `an actor with no human candidates has a neutral unavailable option`() {
        for (people in listOf(listOf(actor), listOf(actor, candidate(8).copy(userOwned = false)))) {
            val option = abdication(state(people))
            assertNeutral(option)
            assertTrue(option.targets.isEmpty())
        }
    }

    @Test fun `actor failures remain concrete even with no candidates`() {
        val base = state()
        val actorCases = listOf(
            actor.copy(inBattle = true) to PoliticalFailure.BATTLE_PENDING,
            actor.copy(node = null) to PoliticalFailure.POSITION_UNAVAILABLE,
            actor.copy(node = "unknown") to PoliticalFailure.STATE_UNAVAILABLE,
            actor.copy(meta = mapOf(LordStatus.META_KEY to "invalid")) to PoliticalFailure.STATE_UNAVAILABLE,
            actor.copy(meta = actor.meta + (PersonPolicyState.META_KEY to "invalid")) to PoliticalFailure.STATE_UNAVAILABLE,
            actor.copy(nationId = 0) to PoliticalFailure.NOT_LORD,
            actor.copy(meta = emptyMap()) to PoliticalFailure.NOT_LORD,
            actor.copy(officerLevel = 1) to PoliticalFailure.STATE_UNAVAILABLE,
        ).map { (changed, failure) -> base.copy(people = listOf(changed)) to failure }
        val stateCases = listOf(
            base.copy(profile = RuleProfile.entries.first { it != RuleProfile.HWIHA }) to PoliticalFailure.WRONG_RULE_PROFILE,
            base.copy(people = emptyList()) to PoliticalFailure.ACTOR_NOT_FOUND,
            base.copy(landProvinceIds = null) to PoliticalFailure.STATE_UNAVAILABLE,
            base.copy(counties = base.counties + base.counties.single().copy(id = 12)) to PoliticalFailure.STATE_UNAVAILABLE,
            base.copy(nations = base.nations.map { it.copy(chiefGeneralId = 99) }) to PoliticalFailure.STATE_UNAVAILABLE,
            base.copy(people = listOf(actor, candidate(20).copy(officerLevel = 12, userOwned = false))) to
                PoliticalFailure.STATE_UNAVAILABLE,
        )
        for ((snapshot, failure) in actorCases + stateCases) {
            val option = abdication(snapshot)
            assertFalse(option.available)
            assertEquals(failure.name, option.code)
            assertEquals(failure.message, option.reason)
            assertTrue(option.targets.isEmpty())
            // A real same-nation candidate verifies the actor-only result against the unchanged domain rules.
            val withTarget = snapshot.copy(people = snapshot.people +
                listOf(candidate(1, nationId = 2), candidate(8, accepted = true)))
            assertEquals(failure, assertIs<PoliticalAssessment.Rejected>(PoliticalRules.assess(
                PoliticalRequest(actor.id, PoliticalInput.ABDICATE, 8), withTarget)).reason)
            val withCandidates = abdication(withTarget)
            assertFalse(withCandidates.available)
            assertEquals(failure.name, withCandidates.code)
            assertEquals(failure.message, withCandidates.reason)
            assertTargetParity(withCandidates, withTarget)
        }
    }

    @Test fun `actor and candidate battle failures are distinguished`() {
        val snapshot = state(listOf(actor, candidate(8).copy(inBattle = true)))
        val candidateBlocked = abdication(snapshot)
        assertNeutral(candidateBlocked)
        assertEquals(PoliticalFailure.BATTLE_PENDING.name, candidateBlocked.targets.single().code)

        val actorBlocked = snapshot.copy(people = listOf(actor.copy(inBattle = true), candidate(8)))
        val option = abdication(actorBlocked)
        assertFalse(option.available)
        assertEquals(PoliticalFailure.BATTLE_PENDING.name, option.code)
        assertTargetParity(option, actorBlocked)
    }

    @Test fun `actor display checks add no county renown or chief requirement to abdication`() {
        val snapshot = state(listOf(actor, candidate(8, accepted = true)))
        for (changed in listOf(snapshot.copy(counties = emptyList()),
            snapshot.copy(nations = snapshot.nations.map { it.copy(chiefGeneralId = null) }))) {
            val option = abdication(changed)
            assertTrue(option.available)
            assertNull(option.code)
            assertNull(option.reason)
            assertTargetParity(option, changed)
        }
        val invalidActor = abdication(state(), actorId = 0)
        assertFalse(invalidActor.available)
        assertEquals(PoliticalFailure.INVALID_INPUT.name, invalidActor.code)
    }

    @Test fun `state absence and catalog gate keep their precedence`() {
        val unavailable = abdication(null)
        assertFalse(unavailable.available)
        assertEquals(PoliticalFailure.STATE_UNAVAILABLE.name, unavailable.code)
        val original = checkNotNull(javaClass.classLoader.getResource("command-catalog/input-catalog.json")).readText()
        val row = Regex("""("inputId":\s*"action\.abdicate"[\s\S]*?"deliveryState":\s*")(PLANNED|DOMAIN_READY|HANDLER_READY|UI_READY)(")""")
        assertTrue(row.containsMatchIn(original))
        val planned = InputCatalog.parse(row.replace(original, "${'$'}1PLANNED${'$'}3"))
        val option = options(null, inputCatalog = planned).single { it.inputId == PoliticalInput.ABDICATE }
        assertFalse(option.available)
        assertEquals(InputRejection.NOT_DELIVERED.name, option.code)
        assertEquals(InputRejection.NOT_DELIVERED.message, option.reason)
        assertTrue(option.targets.isEmpty())
    }

    @Test fun `owner guard rejects before reading state`() {
        doThrow(DomesticForbidden()).`when`(reader).requireOwner(actor.id, 42L)
        assertFailsWith<DomesticForbidden> { PoliticalOptionsService(reader, catalog).options(actor.id, 42L) }
        verify(reader, never()).snapshot()
    }

    @Test fun `oath and untargeted political options retain their existing assessments`() {
        val snapshot = state(listOf(actor, candidate(1, nationId = 2).copy(node = "province-b")))
        val result = options(snapshot)
        val oath = result.single { it.inputId == PoliticalInput.OATH }
        assertFalse(oath.available)
        assertEquals(PoliticalFailure.SAME_PROVINCE_REQUIRED.name, oath.code)
        assertEquals(oath.targets.single().reason, oath.reason)
        for (option in result.filter { it.inputId in PoliticalInput.NO_ARGUMENT_IDS }) {
            if (!checkNotNull(catalog[option.inputId]).deliveryState.hasHandler) {
                assertEquals(InputRejection.NOT_DELIVERED.name, option.code)
                continue
            }
            val failure = (PoliticalRules.assess(PoliticalRequest(actor.id, option.inputId), snapshot)
                as? PoliticalAssessment.Rejected)?.reason
            assertEquals(failure == null, option.available)
            assertEquals(failure?.name, option.code)
            assertEquals(failure?.message, option.reason)
        }
    }

    @Test fun `unavailable abdication keeps the existing JSON contract`() {
        val option = abdication(state(listOf(actor, candidate(8))))
        val json = ObjectMapper().valueToTree<com.fasterxml.jackson.databind.JsonNode>(option)
        assertEquals(setOf("inputId", "available", "code", "reason", "targets"), json.fieldNames().asSequence().toSet())
        assertFalse(json["available"].booleanValue())
        assertTrue(json["code"].isNull)
        assertTrue(json["reason"].isNull)
        val target = json["targets"][0]
        assertEquals(setOf("generalId", "name", "available", "code", "reason"), target.fieldNames().asSequence().toSet())
        assertEquals(PoliticalFailure.CONSENT_REQUIRED.name, target["code"].textValue())
    }
}
