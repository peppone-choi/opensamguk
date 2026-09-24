package opensamguk.engine.hwiha

import kotlin.test.*
import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.logic.input.*

class HwihaPeopleHandlerTest {
    private val fixture = HwihaCampaignWorldFixture()
    private val ready = HwihaPeopleDesign.CANON.copy(status = HwihaPeopleDesign.CONFIRMED)

    @Test fun `search discovers an existing free person once and replays the same result`() {
        val route = fixture.route()
        val actor = fixture.person(801, 1, route.startCity, userId = "42")
        val free = fixture.person(802, 0, route.startCity, lord = false)
        val world = fixture.world(listOf(actor to route.start, free to route.start))
        val handler = HwihaPeopleHandler(world, ChangeRecorder(), HwihaDomesticContext(), "test", ready) {
            error("one search candidate must not consume RNG")
        }
        val result = assertIs<HwihaTurnOutcome.Applied>(handler.handle(HwihaPeopleInput.SEARCH, actor.id,
            "{}", "search-801", 42))
        assertTrue(result.effects.contains("discoveredGeneralId:802"))
        assertEquals(setOf(free.id), HwihaTalentDiscovery.read(world.getGeneralById(actor.id)!!.meta))
        assertEquals(result, handler.handle(HwihaPeopleInput.SEARCH, actor.id, "{}", "search-801", 42))
        assertTrue(world.listRetainers().isEmpty())
        assertEquals(ready.experience, world.getGeneralById(actor.id)!!.experience)
    }

    @Test fun `employ waits for discovery and adds only a consenting target`() {
        val route = fixture.route()
        val actor = fixture.person(811, 1, route.startCity, userId = "42")
        val free = fixture.person(812, 0, route.startCity, lord = false)
        val world = fixture.world(listOf(actor to route.start, free to route.start))
        val handler = HwihaPeopleHandler(world, ChangeRecorder(), HwihaDomesticContext(), "test", ready) { seed ->
            object : RandUtil(LiteHashDrbg(seed)) {
                override fun nextInt(minInclusive: Int, maxExclusive: Int) = minInclusive
            }
        }
        val args = """{"targetGeneralId":812}"""
        assertEquals(HwihaPeopleFailure.TARGET_NOT_DISCOVERED.name,
            assertIs<HwihaTurnOutcome.Rejected>(handler.handle(HwihaPeopleInput.EMPLOY, actor.id,
                args, "employ-811", 42)).code)
        world.applyGeneralDirtyFree(actor.copy(meta = HwihaTalentDiscovery.add(actor.meta, free.id)))
        val result = assertIs<HwihaTurnOutcome.Applied>(handler.handle(HwihaPeopleInput.EMPLOY,
            actor.id, args, "employ-811", 42))
        assertTrue(result.effects.contains("joinedGeneralId:812"))
        assertEquals(actor.nationId, world.getGeneralById(free.id)!!.nationId)
        assertEquals(actor.id, world.listRetainers().single().masterGeneralId)
        assertEquals(result, handler.handle(HwihaPeopleInput.EMPLOY, actor.id, args, "employ-811", 42))
        assertEquals(1, world.listRetainers().size)
    }

    @Test fun `captive persuasion spends the phase on resistance without transferring the captive`() {
        val route = fixture.route()
        val actor = fixture.person(821, 1, route.startCity, userId = "42")
        val captive = fixture.person(822, 2, route.startCity, lord = false).copy(meta =
            fixture.person(822, 2, route.startCity, lord = false).meta +
                ("hwihaCaptive" to mapOf("captorGeneralId" to actor.id)))
        val world = fixture.world(listOf(actor to route.start, captive to route.start))
        val handler = HwihaPeopleHandler(world, ChangeRecorder(), HwihaDomesticContext(), "test", ready) { seed ->
            object : RandUtil(LiteHashDrbg(seed)) {
                override fun nextInt(minInclusive: Int, maxExclusive: Int) = maxExclusive - 1
            }
        }
        val args = """{"targetGeneralId":822}"""
        val result = assertIs<HwihaTurnOutcome.Applied>(handler.handle(HwihaPeopleInput.PERSUADE_CAPTIVE,
            actor.id, args, "persuade-821", 42))
        assertTrue(result.effects.contains("resistedGeneralId:822"))
        assertEquals(2, world.getGeneralById(captive.id)!!.nationId)
        assertTrue(world.listRetainers().isEmpty())
        assertEquals(ready.experience, world.getGeneralById(actor.id)!!.experience)
    }
}
