package opensamguk.engine.run

import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.City
import opensamguk.engine.turn.GeneralStats
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.KvKey
import opensamguk.engine.turn.Nation
import opensamguk.engine.turn.TurnDiplomacy
import opensamguk.engine.turn.TurnGeneral
import opensamguk.engine.turn.TurnWorldState
import opensamguk.engine.turn.WorldSnapshot
import opensamguk.infra.entity.AuctionEntity
import opensamguk.infra.read.AuctionRepository
import opensamguk.logic.auction.AuctionType
import opensamguk.logic.event.EventAction
import opensamguk.logic.event.EventActionContext
import opensamguk.logic.event.EventActionFactory
import opensamguk.logic.event.EventCondition
import opensamguk.logic.event.EventDispatcher
import opensamguk.logic.event.EventStore
import opensamguk.logic.event.LightActionWorld
import opensamguk.logic.event.RawAction
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.util.jsonDecode
import java.lang.reflect.Proxy
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MonthlyPostUpdateHookTailWiringTest {

    private val t0 = Instant.parse("0200-04-01T00:00:00Z")

    private class ScriptedRng(
        private val bools: ArrayDeque<Boolean> = ArrayDeque(),
        private val ints: ArrayDeque<Int> = ArrayDeque(),
    ) : RandUtil(LiteHashDrbg("monthly-post-tail-test")) {
        var shuffleCalls: Int = 0

        override fun nextRange(min: Double, max: Double): Double = 1.0
        override fun nextBool(prob: Double): Boolean = bools.removeFirst()
        override fun nextRangeInt(minInclusive: Int, maxInclusive: Int): Int = ints.removeFirst()
        override fun <T> shuffle(srcArray: List<T>): List<T> {
            shuffleCalls++
            return srcArray
        }
    }

    @Test
    fun `Q11 checkWander auto-disbands wandering rulers after opening period`() {
        val world = world(
            nations = listOf(nation(7, "방랑", level = 0, meta = mapOf("gennum" to 1))),
            generals = listOf(general(70, name = "방랑군", nationId = 7, officerLevel = 12, npc = 0)),
            cities = listOf(city(1, "낙양", nationId = 0)),
        )
        val recorder = ChangeRecorder()

        MonthlyPostUpdateHook(world, recorder, GeneralActionPipeline(), auctionRepository = auctionRepo())
            .run(ScriptedRng(bools = ArrayDeque(listOf(false, false))))

        assertEquals(null, world.getNationById(7))
        assertEquals(0, world.getGeneralById(70)!!.nationId)
        assertTrue(recorder.deletedNationIds().contains(7))
    }

    @Test
    fun `Q11 auto-disband drains ordered PHP logs before one OccupyCity dispatch`() {
        val world = world(
            nations = listOf(nation(7, "방랑", level = 0, meta = mapOf("gennum" to 2))),
            generals = listOf(
                general(70, name = "방랑군", nationId = 7, officerLevel = 12, npc = 0),
                general(71, name = "부장", nationId = 7, officerLevel = 1, npc = 0),
            ),
            cities = listOf(city(1, "낙양", nationId = 7, front = 3)),
        )
        val recorder = ChangeRecorder()
        val dispatchObservations = mutableListOf<String>()
        val dispatcher = occupyCityDispatcher(world, dispatchObservations)

        MonthlyPostUpdateHook(
            world,
            recorder,
            GeneralActionPipeline(),
            auctionRepository = auctionRepo(),
            eventDispatcher = dispatcher,
        ).run(ScriptedRng(bools = ArrayDeque(listOf(false, false))))

        assertEquals(
            listOf("nation=null,generals=0/0,cities=0/0"),
            dispatchObservations,
            "OCCUPY_CITY runs once after deletion state has been applied",
        )
        val logs = world.consumeDirtyState().logs.map { it.scope to it.category to it.text }
        assertEquals(
            listOf(
                "general" to "action" to "<C>●</>초반 제한후 방랑군은 자동 해산됩니다.",
                "general" to "action" to "<C>●</>4월:세력을 해산했습니다. <1>00:00</>",
                "global" to "action" to "<C>●</>4월:<Y>방랑군</>이 세력을 해산했습니다.",
                "general" to "history" to "<C>●</>200년 4월:<D><b>방랑</b></>을 해산",
                "global" to "history" to "<C>●</>200년 4월:<R><b>【멸망】</b></><D><b>방랑</b></>은 <R>멸망</>했습니다.",
                "general" to "action" to "<C>●</><D><b>방랑</b></>이 <R>멸망</>했습니다.",
                "general" to "history" to "<C>●</>200년 4월:<D><b>방랑</b></>이 <R>멸망</>",
                "general" to "action" to "<C>●</><D><b>방랑</b></>이 <R>멸망</>했습니다.",
                "general" to "history" to "<C>●</>200년 4월:<D><b>방랑</b></>이 <R>멸망</>",
                "global" to "action" to "<C>●</>4월:OCCUPY marker",
            ),
            logs,
        )
    }

    @Test
    fun `Q11 auto-disband uses game env sameMonth guard and skips delete event on failed run`() {
        val world = world(
            meta = mapOf("init_year" to 200, "init_month" to 4),
            nations = listOf(nation(7, "방랑", level = 0, meta = mapOf("gennum" to 1))),
            generals = listOf(general(70, name = "방랑군", nationId = 7, officerLevel = 12, npc = 0)),
            cities = listOf(city(1, "낙양", nationId = 7, front = 3)),
        )
        val recorder = ChangeRecorder()
        val dispatchObservations = mutableListOf<String>()

        MonthlyPostUpdateHook(
            world,
            recorder,
            GeneralActionPipeline(),
            auctionRepository = auctionRepo(),
            eventDispatcher = occupyCityDispatcher(world, dispatchObservations),
        ).run(ScriptedRng(bools = ArrayDeque(listOf(false, false))))

        assertEquals(emptyList(), dispatchObservations)
        assertEquals(null, recorder.deletedNationIds().singleOrNull())
        assertEquals(7, world.getNationById(7)!!.id)
        assertEquals(7, world.getGeneralById(70)!!.nationId)
        assertEquals(
            listOf(
                "<C>●</>초반 제한후 방랑군은 자동 해산됩니다.",
                "<C>●</>4월:다음 턴부터 해산할 수 있습니다. <1>00:00</>",
            ),
            world.consumeDirtyState().logs.map { it.text },
        )
    }

    @Test
    fun `Q12 recomputes gennum and Q17 applies SetNationFront to the live world`() {
        val world = world(
            nations = listOf(
                nation(1, "후한", level = 7, meta = mapOf("gennum" to 0)),
                nation(2, "위", level = 1, meta = mapOf("gennum" to 9)),
                nation(3, "촉", level = 1, meta = mapOf("gennum" to 4)),
            ),
            generals = listOf(
                general(10, nationId = 1, officerLevel = 12, npc = 0),
                general(11, nationId = 1, officerLevel = 1, npc = 5),
                general(20, nationId = 2, officerLevel = 12, npc = 0),
            ),
            cities = listOf(
                city(1, "낙양", nationId = 1, front = 0),
                city(32, "하내", nationId = 2, front = 0),
            ),
            diplomacy = listOf(
                TurnDiplomacy(1, 2, state = 0, term = 6),
                TurnDiplomacy(2, 1, state = 0, term = 6),
            ),
        )
        val recorder = ChangeRecorder()

        MonthlyPostUpdateHook(world, recorder, GeneralActionPipeline(), auctionRepository = auctionRepo())
            .run(ScriptedRng(bools = ArrayDeque(listOf(false, false))))

        assertEquals(1, (world.getNationById(1)!!.meta["gennum"] as Number).toInt())
        assertEquals(1, (world.getNationById(2)!!.meta["gennum"] as Number).toInt())
        assertEquals(4, (world.getNationById(3)!!.meta["gennum"] as Number).toInt())
        assertEquals(3, world.getCityById(1)!!.frontState)
        assertTrue(recorder.nationPatches().any { it.id == 1 && it.meta["gennum"] == 1 })
        assertFalse(recorder.nationPatches().any { it.id == 3 && it.meta["gennum"] == 0 })
        assertTrue(recorder.cityPatches().any { it.id == 1 && it.columns["frontState"] == 3 })
    }

    @Test
    fun `Q4 and Q10 preserve existing nation_env max power and war setting counters`() {
        val world = world(
            nations = listOf(
                nation(
                    1,
                    "후한",
                    level = 7,
                    meta = linkedMapOf(
                        "gennum" to 1,
                        "nation_env" to linkedMapOf(
                            "max_power" to linkedMapOf(
                                "maxPower" to 999_999,
                                "maxCrew" to 50,
                                "maxCities" to listOf("이전1", "이전2"),
                            ),
                            "available_war_setting_cnt" to 9,
                        ),
                    ),
                ),
            ),
            generals = listOf(general(10, nationId = 1, officerLevel = 12, npc = 0, gold = 100, rice = 100)),
            cities = listOf(city(1, "낙양", nationId = 1)),
        )
        val recorder = ChangeRecorder()

        MonthlyPostUpdateHook(world, recorder, GeneralActionPipeline(), auctionRepository = auctionRepo())
            .run(ScriptedRng(bools = ArrayDeque(listOf(false, false))))

        val kv = recorder.kvDirty()
        @Suppress("UNCHECKED_CAST")
        val maxPower = kv.getValue(KvKey("nation_env", "1", "max_power")) as Map<String, Any?>
        assertEquals(999_999, maxPower["maxPower"])
        assertEquals(50, maxPower["maxCrew"])
        assertEquals(listOf("이전1", "이전2"), maxPower["maxCities"])
        assertEquals(10, kv.getValue(KvKey("nation_env", "1", "available_war_setting_cnt")))
    }

    @Test
    fun `Q15 triggerTournament writes game env and resets participant flags when monthly gate hits`() {
        val world = world(
            meta = mapOf(
                "tournament" to 0,
                "tnmt_trig" to true,
                "tnmt_pattern" to listOf(0, 1),
                "turnterm" to 60,
            ),
            nations = listOf(nation(1, "후한", level = 7, meta = mapOf("gennum" to 1))),
            generals = listOf(
                general(
                    10,
                    name = "유비",
                    nationId = 1,
                    officerLevel = 12,
                    npc = 0,
                    tournament = 7,
                    politics = 77,
                    charm = 66,
                ),
            ),
            cities = listOf(city(1, "낙양", nationId = 1)),
        )
        val recorder = ChangeRecorder()

        MonthlyPostUpdateHook(world, recorder, GeneralActionPipeline(), auctionRepository = auctionRepo())
            .run(ScriptedRng(bools = ArrayDeque(listOf(true, false, false))))

        val kv = recorder.kvDirty()
        assertEquals(listOf(0), kv.getValue(opensamguk.engine.turn.KvKey("game_env", "game_env", "tnmt_pattern")))
        assertEquals(true, kv.getValue(opensamguk.engine.turn.KvKey("game_env", "game_env", "tnmt_auto")))
        assertEquals(1, kv.getValue(opensamguk.engine.turn.KvKey("game_env", "game_env", "tournament")))
        assertEquals(1, kv.getValue(opensamguk.engine.turn.KvKey("game_env", "game_env", "tnmt_type")))
        assertEquals(0, kv.getValue(opensamguk.engine.turn.KvKey("game_env", "game_env", "last_tournament_betting_id")))
        assertEquals(0, kv.getValue(opensamguk.engine.turn.KvKey("game_env", "game_env", "phase")))
        assertEquals(0, (world.getGeneralById(10)!!.meta["tournament"] as Number).toInt())
        assertEquals(77, world.getGeneralById(10)!!.stats.politics)
        assertEquals(66, world.getGeneralById(10)!!.stats.charm)
    }

    @Test
    fun `Q15 ambient tournament pattern does not consume the monthly RNG shuffle cursor`() {
        val world = world(
            meta = mapOf(
                "tournament" to 0,
                "tnmt_trig" to true,
                "tnmt_pattern" to emptyList<Int>(),
            ),
            nations = listOf(nation(1, "후한", level = 7, meta = mapOf("gennum" to 1))),
            generals = listOf(general(10, name = "유비", nationId = 1, officerLevel = 12, npc = 0)),
            cities = listOf(city(1, "낙양", nationId = 1)),
        )
        val recorder = ChangeRecorder()
        val rng = ScriptedRng(bools = ArrayDeque(listOf(true, false, false)))

        MonthlyPostUpdateHook(world, recorder, GeneralActionPipeline(), auctionRepository = auctionRepo())
            .run(rng)

        assertEquals(0, rng.shuffleCalls)
        val log = world.consumeDirtyState().logs.single { it.scope == "global" }.text
        assertTrue(log.contains("황제 <Y>유비</>의 명으로 "))
        assertTrue(log.contains("대회가 개최됩니다! 천하의 "))
        assertTrue(log.contains("</span>들을 모집하고 있습니다!"))
    }

    @Test
    fun `Q15 sanctioned tournament divergence is repeatable without claiming PHP shuffle parity`() {
        fun runPattern(): List<Int> {
            val world = world(
                meta = mapOf(
                    "hiddenSeed" to "0123456789abcdef0123456789abcdef",
                    "tournament" to 0,
                    "tnmt_trig" to true,
                    "tnmt_pattern" to emptyList<Int>(),
                ),
                nations = listOf(nation(1, "후한", level = 7, meta = mapOf("gennum" to 1))),
                generals = listOf(general(10, name = "유비", nationId = 1, officerLevel = 12, npc = 0)),
                cities = listOf(city(1, "낙양", nationId = 1)),
            )
            val recorder = ChangeRecorder()
            val rng = ScriptedRng(bools = ArrayDeque(listOf(true, false, false)))

            MonthlyPostUpdateHook(world, recorder, GeneralActionPipeline(), auctionRepository = auctionRepo())
                .run(rng)

            assertEquals(0, rng.shuffleCalls)
            val kv = recorder.kvDirty()
            @Suppress("UNCHECKED_CAST")
            val remaining = kv.getValue(KvKey("game_env", "game_env", "tnmt_pattern")) as List<Int>
            val selected = kv.getValue(KvKey("game_env", "game_env", "tnmt_type")) as Int
            return remaining + selected
        }

        assertEquals(1, List(12) { runPattern() }.toSet().size)
    }

    @Test
    fun `Q16 registerAuction records neutral buyRice and sellRice auction inserts`() {
        val world = world(
            nations = listOf(nation(1, "후한", level = 7, meta = mapOf("gennum" to 2))),
            generals = listOf(
                general(10, nationId = 1, officerLevel = 12, npc = 0, gold = 30_000, rice = 8_000),
                general(11, nationId = 1, officerLevel = 1, npc = 1, gold = 10_000, rice = 12_000),
            ),
            cities = listOf(city(1, "낙양", nationId = 1)),
        )
        val recorder = ChangeRecorder()

        MonthlyPostUpdateHook(world, recorder, GeneralActionPipeline(), auctionRepository = auctionRepo())
            .run(ScriptedRng(
                bools = ArrayDeque(listOf(true, true)),
                ints = ArrayDeque(listOf(1, 3, 1, 3)),
            ))

        val upserts = recorder.auctionUpserts()
        assertEquals(2, upserts.size)
        assertEquals("buyRice", upserts[0].columns["type"])
        assertEquals(0, upserts[0].columns["host_general_id"])
        assertEquals("gold", upserts[0].columns["req_resource"])
        assertEquals("sellRice", upserts[1].columns["type"])
        assertEquals("rice", upserts[1].columns["req_resource"])
        val buyDetail = jsonDecode(upserts[0].columns["detail"] as String)
        val sellDetail = jsonDecode(upserts[1].columns["detail"] as String)
        assertEquals("쌀 500 경매", buyDetail["title"])
        assertEquals("금 1000 경매", sellDetail["title"])
    }

    private fun world(
        meta: Map<String, Any?> = emptyMap(),
        nations: List<Nation>,
        generals: List<TurnGeneral>,
        cities: List<City>,
        diplomacy: List<TurnDiplomacy> = emptyList(),
    ): InMemoryTurnWorld = InMemoryTurnWorld(
        WorldSnapshot(
            state = TurnWorldState(
                id = 1,
                currentYear = 200,
                currentMonth = 4,
                tickSeconds = 3600,
                lastTurnTime = t0,
                meta = mapOf("startYear" to 184, "map" to "miniche", "turnterm" to 60, "tnmt_trig" to false) + meta,
            ),
            generals = generals,
            cities = cities,
            nations = nations,
            diplomacy = diplomacy,
        ),
    )

    private fun nation(id: Int, name: String, level: Int, meta: Map<String, Any?> = emptyMap()) =
        Nation(id = id, name = name, color = "#000", level = level, gold = 10_000, rice = 10_000, tech = 100.0, meta = meta)

    private fun general(
        id: Int,
        name: String = "장수$id",
        nationId: Int,
        officerLevel: Int,
        npc: Int,
        gold: Int = 1_000,
        rice: Int = 1_000,
        tournament: Int = 0,
        politics: Int = 50,
        charm: Int = 50,
    ) = TurnGeneral(
        id = id,
        name = name,
        nationId = nationId,
        cityId = 1,
        troopId = 0,
        stats = GeneralStats(leadership = 80, strength = 70, intelligence = 60, politics = politics, charm = charm),
        experience = 0,
        dedication = 0,
        officerLevel = officerLevel,
        gold = gold,
        rice = rice,
        npcState = npc,
        turnTime = t0,
        meta = mapOf("tournament" to tournament),
    )

    private fun city(id: Int, name: String, nationId: Int, front: Int = 0) =
        City(id = id, name = name, nationId = nationId, level = 5, frontState = front, supplyState = 1)

    private fun auctionRepo(active: List<AuctionEntity> = emptyList()): AuctionRepository =
        Proxy.newProxyInstance(
            AuctionRepository::class.java.classLoader,
            arrayOf(AuctionRepository::class.java),
        ) { _, method, args ->
            when (method.name) {
                "findByFinishedFalse" -> active
                "findByFinishedFalseAndTypeValue" -> active.filter { it.type.value == args?.get(0) }
                else -> when (method.returnType) {
                    java.util.List::class.java -> emptyList<Any>()
                    java.lang.Boolean.TYPE -> false
                    else -> null
                }
            }
        } as AuctionRepository

    private fun occupyCityDispatcher(
        world: InMemoryTurnWorld,
        observations: MutableList<String>,
    ): EventDispatcher {
        val store = EventStore()
        store.insert("occupy_city", 1, EventCondition.ConstBool(true), listOf(RawAction("Probe", emptyList())))
        val factory = EventActionFactory().register("Probe") {
            object : EventAction {
                override fun run(ctx: EventActionContext) {
                    observations += "nation=${world.getNationById(7)},generals=${world.getGeneralById(70)?.nationId}/${world.getGeneralById(71)?.nationId},cities=${world.getCityById(1)?.nationId}/${world.getCityById(1)?.frontState}"
                    (ctx as LightActionWorld).pushGlobalActionLog("OCCUPY marker")
                }
            }
        }
        return EventDispatcher(store, factory)
    }
}
