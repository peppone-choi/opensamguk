package opensamguk.engine.campaign

import kotlin.test.*
import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.Retainer
import opensamguk.logic.input.*
import opensamguk.logic.record.AudienceTarget
import opensamguk.logic.record.EventFact
import opensamguk.logic.record.EventKind
import opensamguk.logic.record.EventRef
import opensamguk.logic.record.FactRole
import opensamguk.logic.record.RefRole
import opensamguk.logic.renown.RenownEventSource

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
        val searchEvents = world.consumeDirtyState().gameEvents
        assertEquals(listOf(EventKind.RENOWN_EVENT, EventKind.PEOPLE_SEARCHED), searchEvents.map { it.kind })
        val searched = searchEvents.single { it.kind == EventKind.PEOPLE_SEARCHED }
        assertEquals(AudienceTarget.Self(actor.id), searched.audience)
        assertFalse(RefRole.PERSON in searched.refs, "hidden candidate is not stored in the event")
        assertEquals(EventFact.RenownSource(RenownEventSource.DIRECT_PEOPLE_ACTION),
            searchEvents.first().facts[FactRole.SOURCE])
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
        val joinedEvents = world.consumeDirtyState().gameEvents
        assertEquals(listOf(EventKind.RENOWN_EVENT, EventKind.PEOPLE_JOINED, EventKind.RETAINER_JOINED),
            joinedEvents.map { it.kind })
        val joined = joinedEvents.single { it.kind == EventKind.PEOPLE_JOINED }
        assertEquals(AudienceTarget.Self(actor.id), joined.audience)
        assertEquals(AudienceTarget.Self(free.id), joinedEvents.single { it.kind == EventKind.RETAINER_JOINED }.audience)
        assertEquals(EventRef.General(free.id), joined.refs[RefRole.PERSON])
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

    @Test fun `unaffiliated player discovers and employs a free retinue without changing nation`() {
        val route = fixture.route()
        val actor = fixture.person(861, 0, route.startCity, userId = "42", lord = false)
        val target = fixture.person(862, 0, route.startCity, lord = false)
        val child = fixture.person(863, 0, route.startCity, lord = false)
        val childCard = Retainer(864, target.id, "EXISTING", child.id, child.name, "guest")
        val world = fixture.world(listOf(actor to route.start, target to route.start, child to route.start),
            retainers = listOf(childCard))
        val handler = PeopleHandler(world, ChangeRecorder(), DomesticContext(), "test",
            ready.copy(searchDiscoverCount = 1)) { seed ->
            object : RandUtil(LiteHashDrbg(seed)) {
                override fun nextInt(minInclusive: Int, maxExclusive: Int) = minInclusive
            }
        }
        assertEquals(PeopleFailure.TARGET_NOT_DISCOVERED.name,
            assertIs<TurnOutcome.Rejected>(handler.handle(PeopleInput.EMPLOY, actor.id,
                """{"targetGeneralId":862}""", "unseen-861", 42)).code)
        assertEquals(actor, world.getGeneralById(actor.id))
        assertEquals(listOf(childCard), world.listRetainers())
        val search = assertIs<TurnOutcome.Applied>(handler.handle(PeopleInput.SEARCH, actor.id,
            "{}", "search-861", 42))
        assertTrue(search.effects.contains("discoveredGeneralId:862"))
        assertEquals(setOf(target.id), TalentDiscovery.read(world.getGeneralById(actor.id)!!.meta))
        val afterSearch = world.getGeneralById(actor.id)!!
        world.applyGeneralDirtyFree(afterSearch.copy(turnTime = afterSearch.turnTime.plusSeconds(3600)))

        val args = """{"targetGeneralId":862}"""
        val employed = assertIs<TurnOutcome.Applied>(handler.handle(PeopleInput.EMPLOY, actor.id,
            args, "employ-861", 42))
        assertTrue(employed.effects.contains("joinedGeneralId:862"))
        assertEquals(employed, handler.handle(PeopleInput.EMPLOY, actor.id, args, "employ-861", 42))
        assertEquals(0, world.getGeneralById(target.id)!!.nationId)
        assertEquals(0, world.getGeneralById(child.id)!!.nationId)
        assertEquals(1, world.listRetainers().count { it.masterGeneralId == actor.id && it.generalId == target.id })
        assertEquals(1, world.listRetainers().count { it.masterGeneralId == target.id && it.generalId == child.id })
        assertEquals(2, world.listRetainers().size)
    }

    @Test fun `unaffiliated player's resisted offer still consumes one personal turn without a card`() {
        val route = fixture.route()
        val actor = fixture.person(871, 0, route.startCity, userId = "42", lord = false)
            .let { it.copy(meta = TalentDiscovery.add(it.meta, 872)) }
        val target = fixture.person(872, 0, route.startCity, lord = false)
        val world = fixture.world(listOf(actor to route.start, target to route.start))
        val handler = PeopleHandler(world, ChangeRecorder(), DomesticContext(), "test", ready) { seed ->
            object : RandUtil(LiteHashDrbg(seed)) {
                override fun nextInt(minInclusive: Int, maxExclusive: Int) = maxExclusive - 1
            }
        }
        val args = """{"targetGeneralId":872}"""
        val resisted = assertIs<TurnOutcome.Applied>(handler.handle(PeopleInput.EMPLOY, actor.id,
            args, "resist-871", 42))
        assertTrue(resisted.effects.contains("resistedGeneralId:872"))
        assertEquals(0, world.getGeneralById(target.id)!!.nationId)
        assertTrue(world.listRetainers().isEmpty())
        assertEquals(resisted, handler.handle(PeopleInput.EMPLOY, actor.id, args, "resist-871", 42))
        assertEquals(PeopleFailure.ALREADY_PROCESSED.name, assertIs<TurnOutcome.Rejected>(
            handler.handle(PeopleInput.EMPLOY, actor.id, args, "resist-again-871", 42)).code)
    }

    @Test fun `failed captive persuasion consumes the personal turn and retains custody`() {
        val route = fixture.route()
        val actor = fixture.person(821, 1, route.startCity, userId = "42")
        val captive = fixture.person(822, 2, route.startCity, lord = false).copy(meta =
            fixture.person(822, 2, route.startCity, lord = false).meta +
                (CaptiveState.META_KEY to CaptiveState(actor.id, route.start.id,
                    Phase(200, 1, 1), "battle-821").toMetaValue()))
        val world = fixture.world(listOf(actor to route.start, captive to route.start))
        val handler = PeopleHandler(world, ChangeRecorder(), DomesticContext(), "test", ready) { seed ->
            object : RandUtil(LiteHashDrbg(seed)) {
                override fun nextInt(minInclusive: Int, maxExclusive: Int) = maxExclusive - 1
            }
        }
        val args = """{"targetGeneralId":822}"""
        assertIs<TurnOutcome.Applied>(handler.handle(PeopleInput.PERSUADE_CAPTIVE,
            actor.id, args, "persuade-821", 42))
        assertEquals(2, world.getGeneralById(captive.id)!!.nationId)
        assertNotNull(CaptiveState.read(world.getGeneralById(captive.id)!!.meta))
        assertTrue(world.listRetainers().isEmpty())
        assertEquals(10, world.getGeneralById(actor.id)!!.experience)
    }

    @Test fun `captive persuasion joins at the actual place and clears custody`() {
        assertEquals("action.persuadeCaptive", PeopleInput.PERSUADE_CAPTIVE)
        val route = fixture.route()
        val actor = fixture.person(851, 1, route.startCity, userId = "42")
        val captive = fixture.person(852, 2, route.startCity, lord = false).let { it.copy(meta = it.meta +
            (CaptiveState.META_KEY to CaptiveState(actor.id, route.start.id,
                Phase(200, 1, 1), "battle-851").toMetaValue())) }
        val world = fixture.world(listOf(actor to route.start, captive to route.start))
        val handler = PeopleHandler(world, ChangeRecorder(), DomesticContext(), "test", ready) { seed ->
            object : RandUtil(LiteHashDrbg(seed)) {
                override fun nextInt(minInclusive: Int, maxExclusive: Int) = minInclusive
            }
        }
        assertIs<TurnOutcome.Applied>(handler.handle(PeopleInput.PERSUADE_CAPTIVE,
            actor.id, """{"targetGeneralId":852}""", "persuade-851", 42))
        assertEquals(1, world.getGeneralById(captive.id)!!.nationId)
        assertNull(CaptiveState.read(world.getGeneralById(captive.id)!!.meta))
        assertEquals(route.start, world.positionOf(captive.id))
        assertEquals(actor.id, world.listRetainers().single().masterGeneralId)
    }

    @Test fun `foreign lord captive is rejected before consent roll or transfer`() {
        val route = fixture.route()
        val actor = fixture.person(831, 1, route.startCity, userId = "42")
        val lord = fixture.person(832, 2, route.startCity, lord = true).copy(meta =
            fixture.person(832, 2, route.startCity, lord = true).meta +
                (CaptiveState.META_KEY to CaptiveState(actor.id, route.start.id,
                    Phase(200, 1, 1), "battle-831").toMetaValue()))
        val world = fixture.world(listOf(actor to route.start, lord to route.start))
        val handler = PeopleHandler(world, ChangeRecorder(), DomesticContext(), "test", ready) {
            error("foreign lord gate must run before RNG")
        }
        val result = assertIs<TurnOutcome.Rejected>(handler.handle(PeopleInput.PERSUADE_CAPTIVE,
            actor.id, """{"targetGeneralId":832}""", "persuade-831", 42))
        assertEquals(PeopleFailure.TARGET_IS_LORD.name, result.code)
        assertEquals(lord, world.getGeneralById(lord.id))
        assertTrue(world.listRetainers().isEmpty())
    }
}
