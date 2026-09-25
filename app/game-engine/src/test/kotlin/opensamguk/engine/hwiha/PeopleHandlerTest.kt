package opensamguk.engine.hwiha

import kotlin.test.*
import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.Retainer
import opensamguk.logic.input.*

class PeopleHandlerTest {
    private val fixture = CampaignWorldFixture()
    private val ready = PeopleDesign.CANON.copy(status = PeopleDesign.CONFIRMED)

    @Test fun `search discovers an existing free person once and replays the same result`() {
        val route = fixture.route()
        val actor = fixture.person(801, 1, route.startCity, userId = "42")
        val free = fixture.person(802, 0, route.startCity, lord = false)
        val world = fixture.world(listOf(actor to route.start, free to route.start))
        val handler = PeopleHandler(world, ChangeRecorder(), DomesticContext(), "test", ready) {
            error("one search candidate must not consume RNG")
        }
        val result = assertIs<TurnOutcome.Applied>(handler.handle(PeopleInput.SEARCH, actor.id,
            "{}", "search-801", 42))
        assertTrue(result.effects.contains("discoveredGeneralId:802"))
        assertEquals(setOf(free.id), TalentDiscovery.read(world.getGeneralById(actor.id)!!.meta))
        assertEquals(result, handler.handle(PeopleInput.SEARCH, actor.id, "{}", "search-801", 42))
        assertTrue(world.listRetainers().isEmpty())
        assertEquals(ready.experience, world.getGeneralById(actor.id)!!.experience)
    }

    @Test fun `employ waits for discovery and adds only a consenting target`() {
        val route = fixture.route()
        val actor = fixture.person(811, 1, route.startCity, userId = "42")
        val free = fixture.person(812, 0, route.startCity, lord = false)
        val world = fixture.world(listOf(actor to route.start, free to route.start))
        val handler = PeopleHandler(world, ChangeRecorder(), DomesticContext(), "test", ready) { seed ->
            object : RandUtil(LiteHashDrbg(seed)) {
                override fun nextInt(minInclusive: Int, maxExclusive: Int) = minInclusive
            }
        }
        val args = """{"targetGeneralId":812}"""
        assertEquals(PeopleFailure.TARGET_NOT_DISCOVERED.name,
            assertIs<TurnOutcome.Rejected>(handler.handle(PeopleInput.EMPLOY, actor.id,
                args, "employ-811", 42)).code)
        world.applyGeneralDirtyFree(actor.copy(meta = TalentDiscovery.add(actor.meta, free.id)))
        val result = assertIs<TurnOutcome.Applied>(handler.handle(PeopleInput.EMPLOY,
            actor.id, args, "employ-811", 42))
        assertTrue(result.effects.contains("joinedGeneralId:812"))
        assertEquals(actor.nationId, world.getGeneralById(free.id)!!.nationId)
        assertEquals(actor.id, world.listRetainers().single().masterGeneralId)
        assertEquals(result, handler.handle(PeopleInput.EMPLOY, actor.id, args, "employ-811", 42))
        assertEquals(1, world.listRetainers().size)
    }

    @Test fun `employ transfers the candidate and their direct retinue together`() {
        val route = fixture.route()
        val actor = fixture.person(841, 1, route.startCity, userId = "42")
            .let { it.copy(meta = TalentDiscovery.add(it.meta, 842)) }
        val target = fixture.person(842, 0, route.startCity, lord = false)
        val child = fixture.person(843, 0, route.startCity, lord = false)
        val card = Retainer(844, target.id, "EXISTING", child.id, child.name, "guest")
        val world = fixture.world(listOf(actor to route.start, target to route.start, child to route.start),
            retainers = listOf(card))
        val handler = PeopleHandler(world, ChangeRecorder(), DomesticContext(), "test", ready) { seed ->
            object : RandUtil(LiteHashDrbg(seed)) {
                override fun nextInt(minInclusive: Int, maxExclusive: Int) = minInclusive
            }
        }
        assertIs<TurnOutcome.Applied>(handler.handle(PeopleInput.EMPLOY, actor.id,
            """{"targetGeneralId":842}""", "employ-841", 42))
        assertEquals(1, world.getGeneralById(target.id)!!.nationId)
        assertEquals(1, world.getGeneralById(child.id)!!.nationId)
        assertEquals(0, world.getGeneralById(target.id)!!.officerLevel)
        assertEquals(2, world.listRetainers().size)
    }

    @Test fun `captive persuasion remains unavailable without a confinement lifecycle`() {
        val route = fixture.route()
        val actor = fixture.person(821, 1, route.startCity, userId = "42")
        val captive = fixture.person(822, 2, route.startCity, lord = false).copy(meta =
            fixture.person(822, 2, route.startCity, lord = false).meta +
                ("hwihaCaptive" to mapOf("captorGeneralId" to actor.id)))
        val world = fixture.world(listOf(actor to route.start, captive to route.start))
        val handler = PeopleHandler(world, ChangeRecorder(), DomesticContext(), "test", ready) { seed ->
            object : RandUtil(LiteHashDrbg(seed)) {
                override fun nextInt(minInclusive: Int, maxExclusive: Int) = maxExclusive - 1
            }
        }
        val args = """{"targetGeneralId":822}"""
        val result = assertIs<TurnOutcome.Rejected>(handler.handle(PeopleInput.PERSUADE_CAPTIVE,
            actor.id, args, "persuade-821", 42))
        assertEquals("NOT_DELIVERED", result.code)
        assertEquals(2, world.getGeneralById(captive.id)!!.nationId)
        assertTrue(world.listRetainers().isEmpty())
        assertEquals(0, world.getGeneralById(actor.id)!!.experience)
    }

    @Test fun `foreign lord captive is rejected before consent roll or transfer`() {
        val route = fixture.route()
        val actor = fixture.person(831, 1, route.startCity, userId = "42")
        val lord = fixture.person(832, 2, route.startCity, lord = true).copy(meta =
            fixture.person(832, 2, route.startCity, lord = true).meta +
                ("hwihaCaptive" to mapOf("captorGeneralId" to actor.id)))
        val world = fixture.world(listOf(actor to route.start, lord to route.start))
        val handler = PeopleHandler(world, ChangeRecorder(), DomesticContext(), "test", ready) {
            error("foreign lord gate must run before RNG")
        }
        val result = assertIs<TurnOutcome.Rejected>(handler.handle(PeopleInput.PERSUADE_CAPTIVE,
            actor.id, """{"targetGeneralId":832}""", "persuade-831", 42))
        assertEquals("NOT_DELIVERED", result.code)
        assertEquals(lord, world.getGeneralById(lord.id))
        assertTrue(world.listRetainers().isEmpty())
    }
}
