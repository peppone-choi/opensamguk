package opensamguk.engine.run

import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.City
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.LogEntryDraft
import opensamguk.engine.turn.Nation
import opensamguk.engine.turn.GeneralStats
import opensamguk.engine.turn.RankColumn
import opensamguk.engine.turn.RankDelta
import opensamguk.engine.turn.TurnGeneral
import opensamguk.engine.turn.TurnWorldState
import opensamguk.engine.turn.WorldSnapshot
import opensamguk.engine.world.WorldActionContext
import opensamguk.infra.entity.AuctionBidEntity
import opensamguk.infra.entity.AuctionEntity
import opensamguk.infra.persistence.MetaJson
import opensamguk.infra.read.AuctionBidRepository
import opensamguk.infra.read.AuctionRepository
import opensamguk.logic.auction.AuctionInfoDetail
import opensamguk.logic.auction.AuctionType
import opensamguk.logic.auction.ResourceType
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.world.CityConstRegistry
import opensamguk.logic.world.checkEmperior
import java.lang.reflect.Proxy
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Q14 천하통일 탐지의 엔진 시임([WorldActionContext]) 검증 — `func_gamerule.php:696-939`.
 * PHP :725 이후 tail side effect가 닫히기 전에는 meta/log를 완료처럼 쓰지 않고 fail-fast 한다.
 */
class WorldActionCheckEmperiorContextTest {

    private fun world(
        meta: Map<String, Any?>,
        nations: List<Nation>,
        cities: List<City>,
    ) = InMemoryTurnWorld(
        WorldSnapshot(
            state = TurnWorldState(
                id = 1,
                currentYear = 200,
                currentMonth = 1,
                tickSeconds = 3600,
                lastTurnTime = Instant.EPOCH,
                meta = mapOf("map" to "miniche") + meta,
            ),
            nations = nations,
            cities = cities,
            worldId = opensamguk.common.world.WorldId((TurnWorldState(
                id = 1,
                currentYear = 200,
                currentMonth = 1,
                tickSeconds = 3600,
                lastTurnTime = Instant.EPOCH,
                meta = mapOf("map" to "miniche") + meta,
            )).id),
        ),
    )

    private fun context(w: InMemoryTurnWorld) = WorldActionContext(
        env = mutableMapOf("year" to 200, "month" to 1),
        world = w,
        recorder = ChangeRecorder(),
        pipeline = GeneralActionPipeline(),
    )

    @Test
    fun `1국 전도시 소유 → PHP 725-939 tail completion writes`() {
        val nations = listOf(Nation(id = 7, name = "촉", color = "#fff", level = 1))
        val cities = CityConstRegistry.of("miniche").all().keys.map { City(id = it, name = "c$it", nationId = 7, level = 5) }
        val w = world(mapOf("isunited" to 0, "refreshLimit" to 30000), nations, cities)
        val recorder = ChangeRecorder()

        checkEmperior(
            WorldActionContext(
                env = mutableMapOf("year" to 200, "month" to 1),
                world = w,
                recorder = recorder,
                pipeline = GeneralActionPipeline(),
            ),
        )

        assertEquals(2, (w.getState().meta["isunited"] as Number).toInt())
        assertEquals(3_000_000, (w.getState().meta["refreshLimit"] as Number).toInt())
        assertEquals(3_000_000, recorder.kvDirty().values.single())
        val dirty = w.consumeDirtyState()
        val national = dirty.logs.single { it.scope == "nation" && it.category == "history" }
        assertEquals("<C>●</>200년 1월:<D><b>촉</b></>이 전토를 통일", national.text)
        assertEquals(7, national.nationId)
        assertEquals(200, national.year)
        assertEquals(1, national.month)
        val global = dirty.logs.single { it.scope == "global" && it.category == "history" }
        assertEquals(1, Regex("200년 1월").findAll(global.text).count())
    }

    @Test
    fun `2국 잔존이면 no-op`() {
        val nations = listOf(
            Nation(id = 7, name = "촉", color = "#fff", level = 1),
            Nation(id = 8, name = "위", color = "#000", level = 1),
        )
        val cities = CityConstRegistry.of("miniche").all().keys.map { City(id = it, name = "c$it", nationId = if (it % 2 == 0) 7 else 8, level = 5) }
        val w = world(mapOf("isunited" to 0), nations, cities)

        checkEmperior(context(w))

        assertEquals(0, (w.getState().meta["isunited"] as Number).toInt())
        assertTrue(w.consumeDirtyState().logs.isEmpty())
    }

    @Test
    fun `1국이지만 공백지 잔존(전도시 미소유)이면 no-op`() {
        val nations = listOf(Nation(id = 7, name = "촉", color = "#fff", level = 1))
        val cities = CityConstRegistry.of("miniche").all().keys.map { City(id = it, name = "c$it", nationId = if (it == 1) 0 else 7, level = 5) }
        val w = world(mapOf("isunited" to 0), nations, cities)

        checkEmperior(context(w))

        assertEquals(0, (w.getState().meta["isunited"] as Number).toInt())
        assertTrue(w.consumeDirtyState().logs.isEmpty())
    }

    @Test
    fun `CheckHall emits PHP metric rows thresholds aux and order`() {
        val recorder = ChangeRecorder()
        val general = hallGeneral()
        val w = InMemoryTurnWorld(
            WorldSnapshot(
                state = TurnWorldState(
                    id = 1,
                    currentYear = 200,
                    currentMonth = 1,
                    tickSeconds = 3600,
                    lastTurnTime = Instant.parse("0200-01-01T00:00:00Z"),
                    meta = mapOf(
                        "season" to 6,
                        "scenario" to 1010,
                        "scenario_text" to "영웅집결",
                        "starttime" to "0199-01-01 00:00:00",
                        "serverName" to "테스트",
                        "serverCount" to 4,
                        "ownerNames" to mapOf("101" to "회원갑"),
                    ),
                ),
                serverId = "server-1",
                nations = listOf(Nation(id = 7, name = "촉", color = "#330000", level = 1)),
                generals = listOf(general),
                worldId = opensamguk.common.world.WorldId((TurnWorldState(
                    id = 1,
                    currentYear = 200,
                    currentMonth = 1,
                    tickSeconds = 3600,
                    lastTurnTime = Instant.parse("0200-01-01T00:00:00Z"),
                    meta = mapOf(
                        "season" to 6,
                        "scenario" to 1010,
                        "scenario_text" to "영웅집결",
                        "starttime" to "0199-01-01 00:00:00",
                        "serverName" to "테스트",
                        "serverCount" to 4,
                        "ownerNames" to mapOf("101" to "회원갑"),
                    ),
                )).id),
            ),
        )
        val context = WorldActionContext(
            env = mutableMapOf("year" to 200, "month" to 1),
            world = w,
            recorder = recorder,
            pipeline = GeneralActionPipeline(),
        )

        context.checkHallForEligibleUserGenerals()

        val rows = recorder.hallUpserts().map { it.columns }
        assertEquals(
            listOf(
                "experience", "dedication", "firenum", "warnum", "killnum", "winrate",
                "occupied", "killcrew", "killrate", "killcrew_person", "killrate_person",
                "dex1", "dex2", "dex3", "dex4", "dex5",
                "ttrate", "tlrate", "tsrate", "tirate",
                "betgold", "betwin", "betwingold", "betrate",
            ),
            rows.map { it["type"] },
        )
        assertEquals(listOf(10, 20, 2, 10, 5, 0.5), rows.take(6).map { it["value"] })
        assertEquals(listOf(1000, 2, 1500, 1.5), rows.takeLast(4).map { it["value"] })
        assertTrue(rows.all { it["server_id"] == "server-1" && it["season"] == 6 && it["scenario"] == 1010 })
        val aux = MetaJson.decode(rows.single { it["type"] == "experience" }["aux"] as String)
        assertEquals(
            listOf("name", "nationName", "bgColor", "fgColor", "picture", "imgsvr", "startTime", "unitedTime", "ownerName", "serverID", "serverIdx", "serverName", "scenarioName"),
            aux.keys.toList(),
        )
        assertEquals("갑", aux["name"])
        assertEquals("촉", aux["nationName"])
        assertEquals("#FFFFFF", aux["fgColor"])
        assertEquals("회원갑", aux["ownerName"])
        assertEquals("영웅집결", aux["scenarioName"])
        assertTrue(Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}""").matches(aux["unitedTime"].toString()))
    }

    @Test
    fun `hall capture keeps neutral generals with synthetic jaeya nation info`() {
        val recorder = ChangeRecorder()
        val w = InMemoryTurnWorld(
            WorldSnapshot(
                state = TurnWorldState(
                    id = 1,
                    currentYear = 200,
                    currentMonth = 1,
                    tickSeconds = 3600,
                    lastTurnTime = Instant.parse("0200-01-01T00:00:00Z"),
                    meta = mapOf(
                        "season" to 6,
                        "scenario" to 1010,
                        "scenario_text" to "영웅집결",
                        "serverName" to "테스트",
                        "serverCount" to 4,
                        "ownerNames" to mapOf("101" to "회원갑"),
                    ),
                ),
                serverId = "server-1",
                nations = emptyList(),
                generals = listOf(hallGeneral().copy(nationId = 0, experience = 3)),
                worldId = opensamguk.common.world.WorldId((TurnWorldState(
                    id = 1,
                    currentYear = 200,
                    currentMonth = 1,
                    tickSeconds = 3600,
                    lastTurnTime = Instant.parse("0200-01-01T00:00:00Z"),
                    meta = mapOf(
                        "season" to 6,
                        "scenario" to 1010,
                        "scenario_text" to "영웅집결",
                        "serverName" to "테스트",
                        "serverCount" to 4,
                        "ownerNames" to mapOf("101" to "회원갑"),
                    ),
                )).id),
            ),
        )
        val context = WorldActionContext(
            env = mutableMapOf("year" to 200, "month" to 1),
            world = w,
            recorder = recorder,
            pipeline = GeneralActionPipeline(),
        )

        context.checkHallForEligibleUserGenerals()

        val aux = MetaJson.decode(recorder.hallUpserts().single { it.columns["type"] == "experience" }.columns["aux"] as String)
        assertEquals("재야", aux["nationName"])
        assertEquals("#000000", aux["bgColor"])
        assertEquals("#FFFFFF", aux["fgColor"])
        assertEquals("회원갑", aux["ownerName"])
    }

    @Test
    fun `checkStatistic displays neutral nation type as dash`() {
        val recorder = ChangeRecorder()
        val w = InMemoryTurnWorld(
            WorldSnapshot(
                state = TurnWorldState(
                    id = 1,
                    currentYear = 200,
                    currentMonth = 1,
                    tickSeconds = 3600,
                    lastTurnTime = Instant.parse("0200-01-01T00:00:00Z"),
                ),
                nations = listOf(Nation(id = 1, name = "재야국", color = "#000000", level = 1, typeCode = "che_중립")),
                cities = listOf(City(id = 1, name = "도시", nationId = 1, level = 5, population = 10, populationMax = 20)),
                generals = listOf(hallGeneral().copy(nationId = 1)),
                worldId = opensamguk.common.world.WorldId((TurnWorldState(
                    id = 1,
                    currentYear = 200,
                    currentMonth = 1,
                    tickSeconds = 3600,
                    lastTurnTime = Instant.parse("0200-01-01T00:00:00Z"),
                )).id),
            ),
        )
        val context = WorldActionContext(
            env = mutableMapOf("year" to 200, "month" to 1),
            world = w,
            recorder = recorder,
            pipeline = GeneralActionPipeline(),
        )

        context.checkStatistic()

        val columns = recorder.statisticInserts().single().columns
        assertTrue(columns.getValue("nation_name").toString().contains("재야국(-)"))
    }

    @Test
    fun `unification archive ranks and invader offers preserve PHP ordering and message shape`() {
        val generals = listOf(
            general(id = 1, name = "갑", officerLevel = 12, dedication = 90, killnum = 12345, firenum = 2),
            general(id = 3, name = "재야", officerLevel = 0, dedication = 1, killnum = 0, firenum = 0).copy(nationId = 0),
            general(id = 2, name = "을", officerLevel = 11, dedication = 100, killnum = 9, firenum = 3000),
            general(id = 4, name = "타국", officerLevel = 12, dedication = 200, killnum = 50, firenum = 50).copy(nationId = 8),
        )
        val cities = CityConstRegistry.of("miniche").all().values.map {
            City(
                id = it.id,
                name = it.name,
                nationId = 7,
                level = it.level,
                population = 100,
                populationMax = 200,
            )
        }
        val recorder = ChangeRecorder()
        val w = InMemoryTurnWorld(
            WorldSnapshot(
                state = TurnWorldState(
                    id = 1,
                    currentYear = 200,
                    currentMonth = 1,
                    tickSeconds = 3600,
                    lastTurnTime = Instant.EPOCH,
                    meta = mapOf(
                        "map" to "miniche",
                        "serverName" to "테스트",
                        "serverCount" to 4,
                        "statisticRows" to listOf(
                            mapOf(
                                "nation_count" to 3,
                                "nation_name" to "삼국",
                                "nation_hist" to "삼국 기록",
                                "gen_count" to "9(4+5)",
                                "personal_hist" to "이전 개인",
                                "special_hist" to "이전 특기",
                                "aux" to "{\"turn\":1}",
                            ),
                        ),
                        "nationHistory" to mapOf(7 to listOf("최근 기록", "과거 기록")),
                    ),
                ),
                serverId = "server-1",
                nations = listOf(
                    Nation(
                        id = 7,
                        name = "촉",
                        color = "#fff",
                        level = 1,
                        gold = 300,
                        rice = 400,
                        meta = mapOf(
                            "gennum" to 42,
                            "rate" to 15,
                            "bill" to 2,
                            "spy" to mapOf("8" to 3),
                            "aux" to mapOf("gold" to 7),
                            "nation_env" to mapOf("max_power" to mapOf("gold" to 999, "rice" to 888)),
                        ),
                    ),
                    Nation(id = 8, name = "위", color = "#000", level = 0),
                ),
                cities = cities,
                generals = generals,
                worldId = opensamguk.common.world.WorldId((TurnWorldState(
                    id = 1,
                    currentYear = 200,
                    currentMonth = 1,
                    tickSeconds = 3600,
                    lastTurnTime = Instant.EPOCH,
                    meta = mapOf(
                        "map" to "miniche",
                        "serverName" to "테스트",
                        "serverCount" to 4,
                        "statisticRows" to listOf(
                            mapOf(
                                "nation_count" to 3,
                                "nation_name" to "삼국",
                                "nation_hist" to "삼국 기록",
                                "gen_count" to "9(4+5)",
                                "personal_hist" to "이전 개인",
                                "special_hist" to "이전 특기",
                                "aux" to "{\"turn\":1}",
                            ),
                        ),
                        "nationHistory" to mapOf(7 to listOf("최근 기록", "과거 기록")),
                    ),
                )).id),
            ),
        )
        val context = WorldActionContext(
            env = mutableMapOf("year" to 200, "month" to 1),
            world = w,
            recorder = recorder,
            pipeline = GeneralActionPipeline(),
        )

        recorder.recordStatisticInsert(
            mapOf(
                "nation_count" to 1,
                "nation_name" to "통일",
                "nation_hist" to "통일 기록",
                "gen_count" to "10(5+5)",
                "personal_hist" to "최종 개인",
                "special_hist" to "최종 특기",
                "aux" to "{\"turn\":2}",
            ),
        )
        val duplicateUnificationLog = "<C>●</>200년 1월:<D><b>촉</b></>이 전토를 통일"
        w.pushLog(LogEntryDraft("nation", "history", duplicateUnificationLog, nationId = 7))
        context.pushNationalHistoryLog(7, "<D><b>촉</b></>이 전토를 통일")
        w.pushLog(LogEntryDraft("nation", "history", duplicateUnificationLog, nationId = 7))
        w.pushLog(LogEntryDraft("nation", "history", "<C>●</>200년 1월:United 후속 기록", nationId = 7))
        context.persistUnificationArchive(7, "이")
        context.pushPreformattedGlobalHistoryLog("<C>●</>200년 1월:<Y><b>【통일】</b></>통일 기록")
        context.logHistory()
        context.raiseInvaderMessages()

        val emperor = recorder.emperiorInserts().single().columns
        assertEquals("테스트4기", emperor["phase"])
        assertEquals("1 / 3", emperor["nation_count"])
        assertEquals("4 / 9(4+5)", emperor["gen_count"])
        assertEquals(42, emperor["gennum"])
        assertEquals("삼국", emperor["nation_name"])
        assertEquals("삼국 기록", emperor["nation_hist"])
        assertEquals("최종 개인", emperor["personal_hist"])
        assertEquals("최종 특기", emperor["special_hist"])
        assertEquals("을, 갑", emperor["gen"])
        assertEquals(
            listOf(
                "<C>●</>200년 1월:United 후속 기록",
                duplicateUnificationLog,
                duplicateUnificationLog,
                "최근 기록",
                "과거 기록",
            ),
            MetaJson.decode("{\"history\":${emperor["history"]}}")["history"],
        )
        val nationArchive = recorder.nationArchiveSnapshots().first { it["nation"] == 7 }
        val nationData = nationArchive["data"] as Map<*, *>
        assertEquals(
            listOf(
                "<C>●</>200년 1월:United 후속 기록",
                duplicateUnificationLog,
                duplicateUnificationLog,
                "최근 기록",
                "과거 기록",
            ),
            nationData["history"],
        )
        val neutralData = recorder.nationArchiveSnapshots().first { it["nation"] == 0 }["data"] as Map<*, *>
        assertEquals(listOf("nation", "name", "generals"), neutralData.keys.toList())
        assertEquals(15, nationData["rate"])
        assertEquals(2, nationData["bill"])
        assertEquals("{\"8\":3}", nationData["spy"])
        assertEquals(mapOf("gold" to 7, "rice" to 888), nationData["aux"])
        assertEquals(listOf(1, 2), nationData["generals"], "archive preserves the source row order")
        assertEquals(listOf(3, 1, 2), recorder.oldGeneralSnapshots().map { it.id })
        assertEquals("갑【12,345】, 을【9】", emperor["tiger"])
        assertEquals("을【3,000】, 갑【2】", emperor["eagle"])
        val unificationActions = w.peekLogs().filter { it.scope == "general" && it.category == "action" }
        assertEquals(listOf(2, 1), unificationActions.map { it.generalId })
        assertTrue(unificationActions.all { it.meta?.get("_flushBeforeArchive") == true })
        val yearbook = recorder.yearbookInserts().single().columns
        val map = MetaJson.decode(yearbook["map"] as String)
        assertEquals(
            listOf("startYear", "year", "month", "cityList", "nationList", "spyList", "shownByGeneralList", "myCity", "myNation", "version", "result"),
            map.keys.toList(),
        )
        assertEquals(cities.size, (map["cityList"] as List<*>).size)
        val nationsJson = yearbook["nations"] as String
        val yearbookNations = MetaJson.decode("""{"rows":$nationsJson}""")["rows"] as List<*>
        val nationIds = yearbookNations.map { ((it as Map<*, *>)["nation"] as Number).toInt() }
        assertTrue(0 in nationIds)
        assertTrue(nationIds.indexOf(7) < nationIds.indexOf(8), "equal-power nations keep insertion order")
        val globalHistoryJson = yearbook["global_history"] as String
        assertEquals(
            listOf("<C>●</>200년 1월:<Y><b>【통일】</b></>통일 기록"),
            MetaJson.decode("""{"rows":$globalHistoryJson}""")["rows"],
        )
        assertEquals(
            listOf("<C>●</>1월: 기록 없음"),
            MetaJson.decode("""{"rows":${yearbook["global_action"]}}""")["rows"],
        )
        assertEquals(12, recorder.createdMessages().size)
        assertEquals(listOf(1, 0, 1, 0, 1, 0, 2, 0, 2, 0, 2, 0), recorder.createdMessages().map { it.mailbox })
        val receiverBodies = recorder.createdMessages().filter { it.mailbox != 0 }.map { MetaJson.decode(it.bodyJson) }
        assertEquals(
            listOf("어려움", "보통", "쉬움", "어려움", "보통", "쉬움"),
            receiverBodies.map { (it["text"] as String).substringAfter('[').substringBefore(']') },
        )
        assertTrue(receiverBodies.all { (it["option"] as Map<*, *>)["action"] == "raiseInvader" })
        assertTrue(generals.filter { it.nationId == 7 }.all { (w.getGeneralById(it.id)?.meta?.get("newmsg") as? Number)?.toInt() == 1 })
        assertEquals(null, w.getGeneralById(4)?.meta?.get("newmsg"))
    }

    @Test
    fun `unification cancels unique auction with bidder refund rank rollback and receiver-only notice`() {
        val bidder = general(id = 1, name = "갑", officerLevel = 5, dedication = 10, killnum = 0, firenum = 0)
        val w = InMemoryTurnWorld(
            WorldSnapshot(
                state = TurnWorldState(
                    id = 1,
                    currentYear = 200,
                    currentMonth = 1,
                    tickSeconds = 3600,
                    lastTurnTime = Instant.EPOCH,
                    meta = mapOf("map" to "miniche", "isunited" to 0),
                ),
                nations = listOf(Nation(id = 7, name = "촉", color = "#fff", level = 1)),
                generals = listOf(bidder),
                worldId = opensamguk.common.world.WorldId((TurnWorldState(
                    id = 1,
                    currentYear = 200,
                    currentMonth = 1,
                    tickSeconds = 3600,
                    lastTurnTime = Instant.EPOCH,
                    meta = mapOf("map" to "miniche", "isunited" to 0),
                )).id),
            ),
        )
        val auction = AuctionEntity(
            type = AuctionType.UNIQUE_ITEM,
            target = "che_무기_의천검",
            hostGeneralId = 0,
            reqResource = ResourceType.INHERITANCE_POINT,
            openDate = Instant.EPOCH,
            closeDate = Instant.EPOCH.plusSeconds(60),
            detail = AuctionInfoDetail(
                title = "의천검",
                hostName = "상인",
                amount = 1,
                startBidAmount = 1_000,
            ).toJson(),
            id = 9,
        )
        val bid = AuctionBidEntity(
            auctionId = 9,
            owner = 999,
            generalId = bidder.id,
            amount = 6_000,
            date = Instant.EPOCH,
            no = 3,
        )
        val recorder = ChangeRecorder()
        val context = WorldActionContext(
            env = mutableMapOf("year" to 200, "month" to 1),
            world = w,
            recorder = recorder,
            pipeline = GeneralActionPipeline(),
            auctionRepository = auctionRepo(listOf(auction)),
            auctionBidRepository = auctionBidRepo(mapOf(9 to bid)),
        )

        context.closeActiveUniqueAuctions()

        assertEquals(true, recorder.auctionUpserts().single().columns["finished"])
        assertEquals("inheritance_1", recorder.inheritanceKvWrites().single().namespace)
        assertEquals(listOf(6_000.0, null), recorder.inheritanceKvWrites().single().value)
        assertEquals(RankDelta.Increment(-6_000), recorder.rankPatches()[1]?.get(RankColumn.INHERIT_SPENT_DYN))
        val notice = recorder.createdMessages().single()
        assertEquals(1, notice.mailbox)
        assertEquals("9번 의천검가 취소되었습니다.", MetaJson.decode(notice.bodyJson)["text"])
        assertEquals(1, (w.getGeneralById(1)?.meta?.get("newmsg") as Number).toInt())
    }

    private fun auctionRepo(active: List<AuctionEntity>): AuctionRepository =
        Proxy.newProxyInstance(
            AuctionRepository::class.java.classLoader,
            arrayOf(AuctionRepository::class.java),
        ) { _, method, args ->
            when (method.name) {
                "findByFinishedFalseAndTypeValue" -> active.filter { it.type.value == args?.get(0) }
                else -> when (method.returnType) {
                    java.util.List::class.java -> emptyList<Any>()
                    java.lang.Boolean.TYPE -> false
                    else -> null
                }
            }
        } as AuctionRepository

    private fun auctionBidRepo(highest: Map<Int, AuctionBidEntity>): AuctionBidRepository =
        Proxy.newProxyInstance(
            AuctionBidRepository::class.java.classLoader,
            arrayOf(AuctionBidRepository::class.java),
        ) { _, method, args ->
            when (method.name) {
                "findTopByAuctionIdOrderByAmountDesc" -> highest[args?.get(0) as Int]
                else -> when (method.returnType) {
                    java.util.List::class.java -> emptyList<Any>()
                    java.lang.Boolean.TYPE -> false
                    else -> null
                }
            }
        } as AuctionBidRepository

    private fun general(
        id: Int,
        name: String,
        officerLevel: Int,
        dedication: Int,
        killnum: Int,
        firenum: Int,
    ) = TurnGeneral(
        id = id,
        userId = id.toString(),
        name = name,
        nationId = 7,
        cityId = 1,
        troopId = 0,
        stats = GeneralStats(70, 60, 50),
        experience = 0,
        dedication = dedication,
        officerLevel = officerLevel,
        age = 40,
        npcState = 0,
        turnTime = Instant.EPOCH,
        meta = mapOf("killnum" to killnum, "firenum" to firenum, "picture" to "$id.png"),
    )

    private fun hallGeneral() = TurnGeneral(
        id = 1,
        userId = "101",
        name = "갑",
        nationId = 7,
        cityId = 1,
        troopId = 0,
        stats = GeneralStats(70, 60, 50),
        experience = 10,
        dedication = 20,
        officerLevel = 5,
        age = 40,
        npcState = 0,
        turnTime = Instant.EPOCH,
        meta = mapOf(
            "owner_name" to "유저갑",
            "picture" to "1.png",
            "imgsvr" to 2,
            "firenum" to 2,
            "warnum" to 10,
            "killnum" to 5,
            "occupied" to 1,
            "killcrew" to 30,
            "deathcrew" to 10,
            "killcrew_person" to 12,
            "deathcrew_person" to 4,
            "dex1" to 1,
            "dex2" to 2,
            "dex3" to 3,
            "dex4" to 4,
            "dex5" to 5,
            "ttw" to 25,
            "ttd" to 15,
            "ttl" to 10,
            "tlw" to 20,
            "tld" to 20,
            "tll" to 10,
            "tsw" to 10,
            "tsd" to 30,
            "tsl" to 10,
            "tiw" to 5,
            "tid" to 20,
            "til" to 25,
            "betgold" to 1000,
            "betwin" to 2,
            "betwingold" to 1500,
        ),
    )
}
