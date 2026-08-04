package opensamguk.engine.turn

import opensamguk.engine.config.DaemonLoopConfig
import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.actions.nation.NationActionResolverRegistry
import opensamguk.logic.ai.ChosenCommand
import opensamguk.logic.diplomacy.DiplomacyState
import opensamguk.logic.domain.LastTurn
import opensamguk.logic.message.Mailbox
import opensamguk.logic.message.Message
import opensamguk.logic.message.MessageTarget
import opensamguk.logic.message.MessageType
import opensamguk.logic.stats.GeneralActionPipeline
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * T0.6 — the registry-backed nation-command dispatch in [ProcessNationCommand]. A registered resolver
 * mutates the draft + buffers side effects; the engine routes them through the recorder
 * single-dirty-source (diplomacy delta, logs, message, KV) — never an inline write.
 */
class NationCommandDispatchTest {

    private val t0 = Instant.parse("0200-01-01T00:00:00Z")

    @AfterTest fun reset() = NationActionResolverRegistry.clear()

    private fun world(
        diplomacyState: Int = DiplomacyState.TRADE,
        diplomacyTerm: Int = 0,
        nationMeta: Map<String, Any?> = emptyMap(),
    ): InMemoryTurnWorld = InMemoryTurnWorld(
        WorldSnapshot(
            state = TurnWorldState(id = 1, currentYear = 200, currentMonth = 3, tickSeconds = 3600, lastTurnTime = t0),
            generals = listOf(
                TurnGeneral(id = 10, name = "유비", nationId = 1, cityId = 5, troopId = 0,
                    stats = GeneralStats(80, 70, 60), experience = 0, dedication = 0, officerLevel = 12, gold = 100, turnTime = t0),
            ),
            cities = listOf(
                City(id = 5, name = "업", nationId = 1, level = 6, supplyState = 1),
                City(id = 8, name = "허창", nationId = 2, level = 6, supplyState = 1),
            ),
            nations = listOf(
                Nation(id = 1, name = "촉", color = "#0f0", meta = nationMeta),
                Nation(id = 2, name = "위", color = "#00f"),
            ),
            diplomacy = listOf(
                TurnDiplomacy(1, 2, state = diplomacyState, term = diplomacyTerm),
                TurnDiplomacy(2, 1, state = diplomacyState, term = diplomacyTerm),
            ),
            worldId = opensamguk.common.world.WorldId((TurnWorldState(id = 1, currentYear = 200, currentMonth = 3, tickSeconds = 3600, lastTurnTime = t0)).id),
        ),
    )

    private fun installDaemonResolvers() {
        NationActionResolverRegistry.clear()
        val method = DaemonLoopConfig::class.java.getDeclaredMethod(
            "installNationActionResolvers",
            opensamguk.logic.stats.GeneralActionPipeline::class.java,
        )
        method.isAccessible = true
        method.invoke(DaemonLoopConfig(), opensamguk.logic.stats.GeneralActionPipeline())
    }

    @Test
    fun `installed declaration resolver accepts an actually adjacent nation in the execution gate`() {
        installDaemonResolvers()
        val world = InMemoryTurnWorld(
            WorldSnapshot(
                state = TurnWorldState(id = 1, currentYear = 200, currentMonth = 3, tickSeconds = 3600, lastTurnTime = t0),
                generals = listOf(
                    TurnGeneral(
                        id = 10, name = "유비", nationId = 1, cityId = 1, troopId = 0,
                        stats = GeneralStats(80, 70, 60), experience = 0, dedication = 0,
                        officerLevel = 12, gold = 100, turnTime = t0,
                    ),
                ),
                cities = listOf(
                    City(id = 1, name = "업", nationId = 1, level = 6, supplyState = 1),
                    City(id = 9, name = "남피", nationId = 2, level = 6, supplyState = 1),
                ),
                nations = listOf(
                    Nation(id = 1, name = "촉", color = "#0f0"),
                    Nation(id = 2, name = "위", color = "#00f"),
                ),
                diplomacy = listOf(
                    TurnDiplomacy(1, 2, state = DiplomacyState.TRADE, term = 0),
                    TurnDiplomacy(2, 1, state = DiplomacyState.TRADE, term = 0),
                ),
                worldId = opensamguk.common.world.WorldId((TurnWorldState(id = 1, currentYear = 200, currentMonth = 3, tickSeconds = 3600, lastTurnTime = t0)).id),
            ),
        )
        val recorder = ChangeRecorder()
        val proc = ProcessNationCommand(
            world = world,
            recorder = recorder,
            hiddenSeed = "seed",
            registry = CommandRegistry(GeneralActionPipeline()),
            startYear = 184,
        )

        proc.process(
            generalId = 10,
            officerLevel = 12,
            nationCommand = ChosenCommand("che_선전포고", linkedMapOf("destNationID" to 2)),
            lastTurn = LastTurn(),
            year = 200,
            month = 3,
            date = "12:00",
        )

        assertEquals(DiplomacyState.DECLARATION, world.getDiplomacy(1, 2)?.state)
        assertEquals(DiplomacyState.DECLARATION, world.getDiplomacy(2, 1)?.state)
    }

    @Test
    fun `installed declaration resolver rejects adjacency through an unsupplied destination city`() {
        installDaemonResolvers()
        val world = InMemoryTurnWorld(
            WorldSnapshot(
                state = TurnWorldState(id = 1, currentYear = 200, currentMonth = 3, tickSeconds = 3600, lastTurnTime = t0),
                generals = listOf(
                    TurnGeneral(
                        id = 10, name = "유비", nationId = 1, cityId = 1, troopId = 0,
                        stats = GeneralStats(80, 70, 60), experience = 0, dedication = 0,
                        officerLevel = 12, gold = 100, turnTime = t0,
                    ),
                ),
                cities = listOf(
                    City(id = 1, name = "업", nationId = 1, level = 6, supplyState = 1),
                    City(id = 9, name = "남피", nationId = 2, level = 6, supplyState = 0),
                ),
                nations = listOf(
                    Nation(id = 1, name = "촉", color = "#0f0"),
                    Nation(id = 2, name = "위", color = "#00f"),
                ),
                diplomacy = listOf(
                    TurnDiplomacy(1, 2, state = DiplomacyState.TRADE, term = 0),
                    TurnDiplomacy(2, 1, state = DiplomacyState.TRADE, term = 0),
                ),
                worldId = opensamguk.common.world.WorldId((TurnWorldState(id = 1, currentYear = 200, currentMonth = 3, tickSeconds = 3600, lastTurnTime = t0)).id),
            ),
        )
        val proc = ProcessNationCommand(
            world = world,
            recorder = ChangeRecorder(),
            hiddenSeed = "seed",
            registry = CommandRegistry(GeneralActionPipeline()),
            startYear = 184,
        )

        proc.process(
            generalId = 10,
            officerLevel = 12,
            nationCommand = ChosenCommand("che_선전포고", linkedMapOf("destNationID" to 2)),
            lastTurn = LastTurn(),
            year = 200,
            month = 3,
            date = "12:00",
        )

        assertEquals(DiplomacyState.TRADE, world.getDiplomacy(1, 2)?.state)
        assertEquals(DiplomacyState.TRADE, world.getDiplomacy(2, 1)?.state)
    }

    @Test
    fun `a registered diplomacy resolver routes the bidirectional delta + logs + message + kv through the recorder`() {
        // a fake 선전포고-like resolver: state 2->1/term 24 both dirs, an action log, a national message, a KV write.
        NationActionResolverRegistry.register("che_선전포고") { ctx ->
            ctx.setDiplomacyBidirectional(1, 2, state = 1, term = 24)
            ctx.addActionLog("선전포고 완료")
            ctx.addGlobalHistoryLog("【선포】 촉이 위에 선전포고")
            ctx.recordKv("nation_env", "1", "lastWar", 200)
            val src = MessageTarget(10, "유비", 1, "촉", "#0f0")
            val dest = MessageTarget(0, "", 2, "위", "#00f")
            ctx.sendMessage(Message(MessageType.NATIONAL, src, dest, "선전포고", "2026-05-31 00:00:00", "9999-12-31 23:59:59", linkedMapOf("k" to 1)))
        }

        val world = world()
        val recorder = ChangeRecorder()
        val proc = ProcessNationCommand(world, recorder, hiddenSeed = "seed")

        proc.process(
            generalId = 10, officerLevel = 12,
            nationCommand = ChosenCommand("che_선전포고", linkedMapOf("destNationID" to 2)),
            lastTurn = LastTurn(), year = 200, month = 3, date = "12:00",
        )

        // diplomacy delta reached the recorder (both directions).
        val dip = recorder.diplomacyUpdateDirty()
        assertEquals(2, dip.size)
        assertEquals(listOf(1 to 2, 2 to 1), dip.map { it.fromNationId to it.toNationId })
        assertTrue(dip.all { it.state == 1 && it.term == 24 })
        // world rows updated (dirty-free apply).
        assertEquals(1, world.getDiplomacy(1, 2)!!.state)
        assertEquals(24, world.getDiplomacy(2, 1)!!.term)

        // KV delta reached the recorder.
        assertEquals(1, recorder.kvDirty().filterKeys { it.key == "lastWar" }.size)

        // message reached the mailbox channel (national: receiver + sender row = 2 rows).
        val msgs = recorder.createdMessages()
        assertEquals(2, msgs.size)
        assertEquals(2 + Mailbox.NATIONAL_BASE, msgs[0].mailbox) // receiver = dest nation mailbox
        assertEquals(1 + Mailbox.NATIONAL_BASE, msgs[1].mailbox) // sender = src nation mailbox

        // logs reached the world.
        val dirty = world.consumeDirtyState()
        assertTrue(dirty.logs.any { it.text == "선전포고 완료" })
        assertTrue(dirty.logs.any { it.text.contains("【선포】") })
    }

    @Test
    fun `an unregistered code falls back to the no-op pass-through (returns lastTurn)`() {
        val world = world()
        val recorder = ChangeRecorder()
        val proc = ProcessNationCommand(world, recorder, hiddenSeed = "seed")
        val lt = LastTurn(command = "휴식")
        val result = proc.process(
            generalId = 10, officerLevel = 12,
            nationCommand = ChosenCommand("che_없는명령", emptyMap()),
            lastTurn = lt, year = 200, month = 3, date = "12:00",
        )
        assertEquals(lt, result)
        // only the turn_last KV nation-meta diff is recorded (the legacy seam), no diplomacy/message.
        assertTrue(recorder.diplomacyUpdateDirty().isEmpty())
        assertTrue(recorder.createdMessages().isEmpty())
    }

    @Test
    fun `literal nation rest replaces the previous turn result`() {
        val previous = LastTurn(
            command = "포상",
            arg = linkedMapOf("destGeneralID" to 60, "isGold" to true, "amount" to 1000),
            term = 0,
        )
        val world = world(nationMeta = linkedMapOf("turn_last_12" to previous.toRaw()))
        val proc = ProcessNationCommand(
            world = world,
            recorder = ChangeRecorder(),
            hiddenSeed = "seed",
            registry = CommandRegistry(GeneralActionPipeline()),
        )

        val result = proc.process(
            generalId = 10,
            officerLevel = 12,
            nationCommand = ChosenCommand("휴식", emptyMap()),
            lastTurn = previous,
            year = 200,
            month = 3,
            date = "12:00",
        )

        assertEquals(LastTurn(), result)
        assertEquals(LastTurn().toRaw(), world.getNationById(1)!!.meta["turn_last_12"])
    }

    @Test
    fun `stale strategic nation command is denied before resolver and does not mutate state`() {
        installDaemonResolvers()
        val last = LastTurn()
        val world = world(
            diplomacyState = DiplomacyState.DECLARATION,
            diplomacyTerm = 15,
            nationMeta = linkedMapOf(
                "strategic_cmd_limit" to 9,
                "turn_last_12" to last.toRaw(),
            ),
        )
        val recorder = ChangeRecorder()
        val beforeGeneral = world.getGeneralById(10)!!
        val beforeNation = world.getNationById(1)!!
        val beforeDiplomacy = world.getDiplomacy(1, 2)!!
        val proc = ProcessNationCommand(
            world,
            recorder,
            hiddenSeed = "seed",
            registry = CommandRegistry(GeneralActionPipeline()),
        )

        val result = proc.process(
            generalId = 10,
            officerLevel = 12,
            nationCommand = ChosenCommand("che_급습", linkedMapOf("destNationID" to 2)),
            lastTurn = last,
            year = 200,
            month = 3,
            date = "12:00",
        )

        assertEquals(last, result)
        assertEquals(beforeGeneral, world.getGeneralById(10))
        assertEquals(beforeNation, world.getNationById(1))
        assertEquals(beforeDiplomacy, world.getDiplomacy(1, 2))
        assertTrue(recorder.diplomacyUpdateDirty().isEmpty())
        assertTrue(recorder.createdMessages().isEmpty())
    }

    @Test
    fun `research commands keep their multi-turn stack before one resolver success`() {
        installDaemonResolvers()
        val cases = listOf(
            Triple("event_상병연구", "상병 연구", 23),
            Triple("event_대검병연구", "대검병 연구", 11),
        )

        for ((code, name, preReqTurn) in cases) {
            val world = world(
                nationMeta = linkedMapOf(
                    "turn_last_12" to LastTurn().toRaw(),
                    "aux" to linkedMapOf<String, Any?>(),
                ),
            ).also { it.applyNationDirtyFree(it.getNationById(1)!!.copy(gold = 500_000, rice = 500_000)) }
            val proc = ProcessNationCommand(
                world,
                ChangeRecorder(),
                hiddenSeed = "seed",
                registry = CommandRegistry(GeneralActionPipeline()),
            )
            var last = LastTurn()

            repeat(preReqTurn) {
                last = proc.process(
                    generalId = 10,
                    officerLevel = 12,
                    nationCommand = ChosenCommand(code, emptyMap()),
                    lastTurn = last,
                    year = 200,
                    month = 3,
                    date = "12:00",
                )
            }

            assertEquals(name, last.command)
            assertEquals(preReqTurn, last.term)
            assertEquals(500_000, world.getNationById(1)!!.gold)
            assertEquals(0, world.getGeneralById(10)!!.experience)

            last = proc.process(
                generalId = 10,
                officerLevel = 12,
                nationCommand = ChosenCommand(code, emptyMap()),
                lastTurn = last,
                year = 200,
                month = 3,
                date = "12:00",
            )

            assertEquals(name, last.command)
            assertEquals(0, last.term)
            assertEquals(500_000 - if (preReqTurn == 23) 100_000 else 50_000, world.getNationById(1)!!.gold)
            assertEquals(5 * (preReqTurn + 1), world.getGeneralById(10)!!.experience)
        }
    }

    @Test
    fun `DaemonLoopConfig installs diplomacy accept resolvers into the nation registry`() {
        installDaemonResolvers()

        assertNotNull(NationActionResolverRegistry.resolve("che_선전포고"))
        assertNotNull(NationActionResolverRegistry.resolve("che_불가침수락"))
        assertNotNull(NationActionResolverRegistry.resolve("che_종전수락"))
        assertNotNull(NationActionResolverRegistry.resolve("che_불가침파기수락"))
        assertNotNull(NationActionResolverRegistry.resolve("che_급습"))
        assertNotNull(NationActionResolverRegistry.resolve("che_이호경식"))
        assertNotNull(NationActionResolverRegistry.resolve("che_물자원조"))
        assertNotNull(NationActionResolverRegistry.resolve("event_상병연구"))
        assertNotNull(NationActionResolverRegistry.resolve("event_대검병연구"))
    }

    @Test
    fun `installed diplomacy accept resolvers produce real ProcessNationCommand diplomacy deltas`() {
        installDaemonResolvers()

        assertAcceptCommandDelta(
            actionCode = "che_불가침수락",
            startState = DiplomacyState.TRADE,
            args = linkedMapOf("destNationID" to 2, "destGeneralID" to 20, "year" to 201, "month" to 3),
            expectedState = DiplomacyState.NON_AGGRESSION,
            expectedTerm = 13,
            assertKv = true,
        )
        assertAcceptCommandDelta(
            actionCode = "che_종전수락",
            startState = DiplomacyState.WAR,
            args = linkedMapOf("destNationID" to 2, "destGeneralID" to 20),
            expectedState = DiplomacyState.TRADE,
            expectedTerm = 0,
        )
        assertAcceptCommandDelta(
            actionCode = "che_불가침파기수락",
            startState = DiplomacyState.NON_AGGRESSION,
            args = linkedMapOf("destNationID" to 2, "destGeneralID" to 20),
            expectedState = DiplomacyState.TRADE,
            expectedTerm = 0,
        )
    }

    @Test
    fun `installed 급습 resolver subtracts 3 from current diplomacy term (PHP term-3)`() {
        installDaemonResolvers()
        // pre declaration term=15 → after 12 (che_급습.php:192-194)
        assertAcceptCommandDelta(
            actionCode = "che_급습",
            startState = DiplomacyState.DECLARATION,
            startTerm = 15,
            args = linkedMapOf("destNationID" to 2),
            expectedState = DiplomacyState.DECLARATION,
            expectedTerm = 12,
        )
    }

    @Test
    fun `installed 이호경식 resolver applies IF state0 then 3 else term plus 3`() {
        installDaemonResolvers()
        // war → declaration term=3
        assertAcceptCommandDelta(
            actionCode = "che_이호경식",
            startState = DiplomacyState.WAR,
            startTerm = 6,
            args = linkedMapOf("destNationID" to 2),
            expectedState = DiplomacyState.DECLARATION,
            expectedTerm = 3,
        )
        // declaration term=12 → 15
        assertAcceptCommandDelta(
            actionCode = "che_이호경식",
            startState = DiplomacyState.DECLARATION,
            startTerm = 12,
            args = linkedMapOf("destNationID" to 2),
            expectedState = DiplomacyState.DECLARATION,
            expectedTerm = 15,
        )
    }

    @Test
    fun `급습 resolver also applies exp ded and action log through general channel`() {
        installDaemonResolvers()
        val world = world(diplomacyState = DiplomacyState.DECLARATION, diplomacyTerm = 15)
        val recorder = ChangeRecorder()
        val proc = ProcessNationCommand(world, recorder, hiddenSeed = "seed")

        proc.process(
            generalId = 10, officerLevel = 12,
            nationCommand = ChosenCommand("che_급습", linkedMapOf("destNationID" to 2)),
            lastTurn = LastTurn(), year = 200, month = 3, date = "12:00",
        )

        // term-3
        assertEquals(12, world.getDiplomacy(1, 2)!!.term)
        // exp/ded +5 via general draft → world apply
        val g = world.getGeneralById(10)!!
        assertEquals(5, g.experience)
        assertEquals(5, g.dedication)
        // action log present
        val dirty = world.consumeDirtyState()
        assertTrue(dirty.logs.any { it.text.contains("급습 발동") })
    }

    @Test
    fun `물자원조 resolver transfers gold rice and raises surlimit`() {
        installDaemonResolvers()
        val world = world(diplomacyState = DiplomacyState.TRADE, diplomacyTerm = 0).also {
            // enrich nations with treasury
            it.applyNationDirtyFree(it.getNationById(1)!!.copy(gold = 10_000, rice = 10_000))
            it.applyNationDirtyFree(it.getNationById(2)!!.copy(gold = 100, rice = 100))
        }
        val recorder = ChangeRecorder()
        val proc = ProcessNationCommand(world, recorder, hiddenSeed = "seed")

        proc.process(
            generalId = 10, officerLevel = 12,
            nationCommand = ChosenCommand(
                "che_물자원조",
                linkedMapOf("destNationID" to 2, "amountList" to listOf(500, 300)),
            ),
            lastTurn = LastTurn(), year = 200, month = 3, date = "12:00",
        )

        val me = world.getNationById(1)!!
        val you = world.getNationById(2)!!
        assertEquals(10_000 - 500, me.gold)
        assertEquals(10_000 - 300, me.rice)
        assertEquals(100 + 500, you.gold)
        assertEquals(100 + 300, you.rice)
        assertEquals(12, me.meta["surlimit"])
        assertEquals(5, world.getGeneralById(10)!!.experience)
    }

    @Test
    fun `logic bridge runs 몰수 without NationActionResolverRegistry entry`() {
        // 몰수 is NOT in installNationActionResolvers — must still mutate via CommandRegistry bridge.
        NationActionResolverRegistry.clear()
        val world = world().also {
            it.createGeneral(
                TurnGeneral(
                    id = 20, name = "장비", nationId = 1, cityId = 5, troopId = 0,
                    stats = GeneralStats(70, 90, 40), experience = 0, dedication = 0,
                    officerLevel = 1, gold = 5000, rice = 3000, turnTime = t0,
                ),
            )
            it.applyNationDirtyFree(it.getNationById(1)!!.copy(gold = 1000, rice = 1000))
        }
        val recorder = ChangeRecorder()
        val reg = CommandRegistry(GeneralActionPipeline())
        val proc = ProcessNationCommand(world, recorder, hiddenSeed = "seed", registry = reg, startYear = 184)

        proc.process(
            generalId = 10, officerLevel = 12,
            nationCommand = ChosenCommand(
                "che_몰수",
                linkedMapOf("isGold" to true, "amount" to 1000, "destGeneralID" to 20),
            ),
            lastTurn = LastTurn(), year = 200, month = 3, date = "12:00",
        )

        assertEquals(4000, world.getGeneralById(20)!!.gold, "dest gold seized by 1000")
        assertEquals(2000, world.getNationById(1)!!.gold, "nation treasury +1000")
    }

    @Test
    fun `logic bridge runs 피장파장 exp ded without registry entry`() {
        NationActionResolverRegistry.clear()
        val world = world(
            diplomacyState = DiplomacyState.WAR,
            nationMeta = linkedMapOf("strategic_cmd_limit" to 0),
        )
        val recorder = ChangeRecorder()
        val reg = CommandRegistry(GeneralActionPipeline())
        val proc = ProcessNationCommand(world, recorder, hiddenSeed = "seed", registry = reg, startYear = 184)

        proc.process(
            generalId = 10, officerLevel = 12,
            nationCommand = ChosenCommand(
                "che_피장파장",
                linkedMapOf("commandType" to "che_급습", "destNationID" to 2),
            ),
            lastTurn = LastTurn(
                command = "피장파장",
                arg = linkedMapOf("destNationID" to 2, "commandType" to "che_급습"),
                term = 1,
                seq = 0,
            ),
            year = 200, month = 3, date = "12:00",
        )

        // preReqTurn for 피장파장 = 1 → exp/ded = 5*(1+1)=10
        assertEquals(10, world.getGeneralById(10)!!.experience)
        assertEquals(10, world.getGeneralById(10)!!.dedication)
        val dirty = world.consumeDirtyState()
        assertTrue(dirty.logs.any { it.text.contains("피장파장") || it.text.contains("급습") })
    }

    @Test
    fun `installed 피장파장 writes delay KV on both nations`() {
        installDaemonResolvers()
        val world = world(diplomacyState = DiplomacyState.DECLARATION, diplomacyTerm = 12)
        val recorder = ChangeRecorder()
        val proc = ProcessNationCommand(world, recorder, hiddenSeed = "seed")

        proc.process(
            generalId = 10, officerLevel = 12,
            nationCommand = ChosenCommand(
                "che_피장파장",
                linkedMapOf("commandType" to "che_급습", "destNationID" to 2),
            ),
            lastTurn = LastTurn(), year = 200, month = 3, date = "12:00",
        )

        assertEquals(10, world.getGeneralById(10)!!.experience)
        val kv = recorder.kvDirty()
        assertTrue(kv.any { it.key.key == "next_execute_급습" && it.key.namespace == "1" })
        assertTrue(kv.any { it.key.key == "next_execute_급습" && it.key.namespace == "2" })
    }

    @Test
    fun `logic bridge 허보 moves enemy generals in dest city`() {
        NationActionResolverRegistry.clear()
        val world = InMemoryTurnWorld(
            WorldSnapshot(
                state = TurnWorldState(id = 1, currentYear = 200, currentMonth = 3, tickSeconds = 3600, lastTurnTime = t0),
                generals = listOf(
                    TurnGeneral(
                        id = 10, name = "유비", nationId = 1, cityId = 5, troopId = 0,
                        stats = GeneralStats(80, 70, 60), experience = 0, dedication = 0,
                        officerLevel = 12, gold = 100, turnTime = t0,
                    ),
                    TurnGeneral(
                        id = 30, name = "조운", nationId = 2, cityId = 8, troopId = 0,
                        stats = GeneralStats(70, 80, 50), experience = 0, dedication = 0,
                        officerLevel = 1, gold = 100, turnTime = t0,
                    ),
                ),
                cities = listOf(
                    City(id = 5, name = "업", nationId = 1, level = 6, supplyState = 1),
                    City(id = 8, name = "허창", nationId = 2, level = 6, supplyState = 1),
                    City(id = 9, name = "낙양", nationId = 2, level = 5, supplyState = 1),
                ),
                nations = listOf(
                    Nation(id = 1, name = "촉", color = "#0f0", meta = linkedMapOf("strategic_cmd_limit" to 0)),
                    Nation(id = 2, name = "위", color = "#00f"),
                ),
                diplomacy = listOf(
                    TurnDiplomacy(1, 2, state = DiplomacyState.WAR, term = 3),
                    TurnDiplomacy(2, 1, state = DiplomacyState.WAR, term = 3),
                ),
                worldId = opensamguk.common.world.WorldId((TurnWorldState(id = 1, currentYear = 200, currentMonth = 3, tickSeconds = 3600, lastTurnTime = t0)).id),
            ),
        )
        val recorder = ChangeRecorder()
        val proc = ProcessNationCommand(
            world, recorder, hiddenSeed = "seed",
            registry = CommandRegistry(GeneralActionPipeline()), startYear = 184,
        )

        proc.process(
            generalId = 10, officerLevel = 12,
            nationCommand = ChosenCommand("che_허보", linkedMapOf("destCityID" to 8)),
            lastTurn = LastTurn(
                command = "허보",
                arg = linkedMapOf("destCityID" to 8),
                term = 1,
                seq = 0,
            ),
            year = 200, month = 3, date = "12:00",
        )

        // enemy general in city 8 must move to a supplied enemy city (8 or 9)
        val moved = world.getGeneralById(30)!!
        assertTrue(moved.cityId == 8 || moved.cityId == 9, "moved city=${moved.cityId}")
        // preReqTurn=1 → exp/ded += 5*(1+1)=10
        assertEquals(10, world.getGeneralById(10)!!.experience)
    }

    @Test
    fun `logic bridge scorches city on 초토화`() {
        NationActionResolverRegistry.clear()
        val world = InMemoryTurnWorld(
            WorldSnapshot(
                state = TurnWorldState(id = 1, currentYear = 200, currentMonth = 3, tickSeconds = 3600, lastTurnTime = t0),
                generals = listOf(
                    TurnGeneral(
                        id = 10, name = "유비", nationId = 1, cityId = 5, troopId = 0,
                        stats = GeneralStats(80, 70, 60), experience = 100, dedication = 100,
                        officerLevel = 12, gold = 100, turnTime = t0,
                    ),
                ),
                cities = listOf(
                    City(
                        id = 5, name = "업", nationId = 1, level = 6,
                        supplyState = 1,
                        population = 10000, populationMax = 20000,
                        agriculture = 1000, agricultureMax = 2000,
                        commerce = 1000, commerceMax = 2000,
                        security = 500, securityMax = 1000,
                        defence = 500, defenceMax = 1000,
                        wall = 800, wallMax = 1000,
                    ),
                ),
                nations = listOf(Nation(id = 1, name = "촉", color = "#0f0", gold = 5000, rice = 5000)),
                diplomacy = emptyList(),
                worldId = opensamguk.common.world.WorldId((TurnWorldState(id = 1, currentYear = 200, currentMonth = 3, tickSeconds = 3600, lastTurnTime = t0)).id),
            ),
        )
        val recorder = ChangeRecorder()
        val proc = ProcessNationCommand(
            world, recorder, hiddenSeed = "seed",
            registry = CommandRegistry(GeneralActionPipeline()), startYear = 184,
        )

        proc.process(
            generalId = 10, officerLevel = 12,
            nationCommand = ChosenCommand("che_초토화", linkedMapOf("destCityID" to 5)),
            lastTurn = LastTurn(
                command = "초토화",
                arg = linkedMapOf("destCityID" to 5),
                term = 2,
                seq = 0,
            ),
            year = 200, month = 3, date = "12:00",
        )

        val city = world.getCityById(5)!!
        assertEquals(0, city.nationId, "scorched city becomes neutral")
        assertEquals(0, city.frontState)
        assertTrue(city.population < 10000, "population reduced")
        assertTrue(world.getNationById(1)!!.gold >= 5000, "treasury gains return amount")
    }

    @Test
    fun `event 상병연구 resolver spends gold rice and sets aux unlock`() {
        installDaemonResolvers()
        val world = world().also {
            it.applyNationDirtyFree(it.getNationById(1)!!.copy(gold = 200_000, rice = 200_000))
        }
        val recorder = ChangeRecorder()
        val proc = ProcessNationCommand(world, recorder, hiddenSeed = "seed")

        proc.process(
            generalId = 10, officerLevel = 12,
            nationCommand = ChosenCommand("event_상병연구", emptyMap()),
            lastTurn = LastTurn(), year = 200, month = 3, date = "12:00",
        )

        val n = world.getNationById(1)!!
        assertEquals(100_000, n.gold)
        assertEquals(100_000, n.rice)
        @Suppress("UNCHECKED_CAST")
        val aux = n.meta["aux"] as? Map<String, Any?>
        assertEquals(1, aux?.get("can_상병사용"))
        // exp/ded = 5*(23+1)=120
        assertEquals(120, world.getGeneralById(10)!!.experience)
    }

    private fun assertAcceptCommandDelta(
        actionCode: String,
        startState: Int,
        args: LinkedHashMap<String, Any?>,
        expectedState: Int,
        expectedTerm: Int,
        assertKv: Boolean = false,
        startTerm: Int = 9,
    ) {
        val world = world(diplomacyState = startState, diplomacyTerm = startTerm)
        val recorder = ChangeRecorder()
        val proc = ProcessNationCommand(world, recorder, hiddenSeed = "seed")

        proc.process(
            generalId = 10, officerLevel = 12,
            nationCommand = ChosenCommand(actionCode, args),
            lastTurn = LastTurn(), year = 200, month = 3, date = "12:00",
        )

        val dip = recorder.diplomacyUpdateDirty()
        assertEquals(2, dip.size, "$actionCode must record both directional diplomacy rows")
        assertEquals(listOf(1 to 2, 2 to 1), dip.map { it.fromNationId to it.toNationId })
        assertTrue(dip.all { it.state == expectedState && it.term == expectedTerm },
            "$actionCode expected state=$expectedState term=$expectedTerm, got ${dip.map { it.state to it.term }}")
        assertEquals(expectedState, world.getDiplomacy(1, 2)!!.state)
        assertEquals(expectedTerm, world.getDiplomacy(2, 1)!!.term)

        if (assertKv) {
            val kv = recorder.kvDirty().entries.single { it.key.table == "nation_env" && it.key.key == "resp_assist" }
            assertEquals("2", kv.key.namespace)
            @Suppress("UNCHECKED_CAST")
            val respAssist = kv.value as Map<String, Any?>
            assertEquals(listOf(1, 0), respAssist["n1"])
        }
    }
}
