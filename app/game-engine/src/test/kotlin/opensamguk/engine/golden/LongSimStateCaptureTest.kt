package opensamguk.engine.golden

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import opensamguk.common.world.WorldId
import opensamguk.engine.turn.City
import opensamguk.engine.turn.GeneralStats
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.KvKey
import opensamguk.engine.turn.Nation
import opensamguk.engine.turn.TurnWorldState
import opensamguk.engine.turn.TurnGeneral
import opensamguk.engine.turn.TurnDiplomacy
import opensamguk.engine.turn.WorldSnapshot
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals

class LongSimStateCaptureTest {

    @Test
    fun `candidate replay scope accepts twelve months and thirty six phase drains`() {
        val manifest = buildJsonObject {
            put("maxTurns", 12)
            put("totalMonths", 12)
            put("reachedMaxTurns", true)
            put("points", buildJsonArray {
                add(buildJsonObject { put("gameMonths", 12) })
            })
            put("phaseDrains", buildJsonArray {
                repeat(36) { add(buildJsonObject {}) }
            })
        }

        validateLongSimReplayScope(monthsMax = 12, manifest = manifest)
    }

    @Test
    fun `replay pre-update snapshots live nation rate through production world and recorder path`() {
        val nation = Nation(
            id = 1,
            name = "후한",
            color = "#800000",
            meta = linkedMapOf(
                "rate" to 15,
                "rate_tmp" to 0,
                "strategic_cmd_limit" to 24,
                "surlimit" to 72,
                "spy" to emptyMap<String, Int>(),
            ),
        )
        val world = world(nations = listOf(nation))
        val recorder = longSimReplayRecorder(world)

        val succeeded = longSimReplayPreUpdate(world, recorder).run()

        assertEquals(true, succeeded)
        assertEquals(15.0, (world.getNationById(1)!!.meta["rate_tmp"] as Number).toDouble())
        assertEquals(15.0, (recorder.nationPatches().single().meta["rate_tmp"] as Number).toDouble())
        assertEquals(1, recorder.yearbookInserts().size)
        assertEquals(20, world.getState().meta["develcost"])
        assertEquals(20, recorder.kvDirty()[KvKey("game_env", "game_env", "develcost")])
    }

    @Test
    fun `replay recorder applies game env writes to live world meta`() {
        val world = world()
        val recorder = longSimReplayRecorder(world)

        recorder.recordKv("game_env", "game_env", "lastNPCTroopLeaderID", 12)

        assertEquals(12, recorder.kvDirty()[KvKey("game_env", "game_env", "lastNPCTroopLeaderID")])
        assertEquals(12, world.getState().meta["lastNPCTroopLeaderID"])
    }

    @Test
    fun `replay capture uses capture point schema for newly created game env keys`() {
        val world = world(meta = mapOf("lastNPCTroopLeaderID" to 12))
        val expectedState = stateShape(buildJsonObject {
            put("block_general_create", 0)
            put("lastNPCTroopLeaderID", 12)
            put("year", 181)
            put("month", 2)
        })

        val gameEnv = captureLongSimReplayState(world, expectedState)["game_env"]!!.jsonObject

        assertEquals(
            listOf("block_general_create", "lastNPCTroopLeaderID", "year", "month"),
            gameEnv.keys.toList(),
        )
        assertEquals(12, gameEnv["lastNPCTroopLeaderID"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `game env preserves baseline policy keys and insertion order while overriding live clock`() {
        val world = world(
            meta = linkedMapOf(
                "block_general_create" to 0,
                "startYear" to 181,
                "startTime" to "2026-07-27T00:00:00Z",
                "turnterm" to 120,
                "develcost" to 20,
                "isunited" to 0,
                "scenario" to 1010,
                "map" to "che",
            ),
        )
        val baselineState = stateShape(buildJsonObject {
            put("block_general_create", 0)
            put("year", 999)
            put("month", 12)
            put("turnterm", 120)
        })

        val gameEnv = LongSimStateCapture.captureState(world, baselineState)["game_env"]!!.jsonObject

        assertEquals(listOf("block_general_create", "year", "month", "turnterm"), gameEnv.keys.toList())
        assertEquals(0, gameEnv["block_general_create"]!!.jsonPrimitive.content.toInt())
        assertEquals(181, gameEnv["year"]!!.jsonPrimitive.content.toInt())
        assertEquals(2, gameEnv["month"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `city officer set is captured from the typed world row`() {
        val world = world(
            cities = listOf(
                City(id = 1, name = "낙양", nationId = 1, level = 7, term = 12, conflict = """{"2":3.5}"""),
            ),
        )
        val expectedState = stateShape(
            buildJsonObject {},
            cities = kotlinx.serialization.json.JsonArray(
                listOf(buildJsonObject {
                    put("city", 1)
                    put("officer_set", 0)
                    put("term", 12)
                    put("conflict", """{"2":3.5}""")
                }),
            ),
        )

        val city = LongSimStateCapture.captureState(world, expectedState)["city"]!!
            .jsonArray.single().jsonObject

        assertEquals(0, city["officer_set"]!!.jsonPrimitive.content.toInt())
        assertEquals(12, city["term"]!!.jsonPrimitive.content.toInt())
        assertEquals("""{"2":3.5}""", city["conflict"]!!.jsonPrimitive.content)
    }

    @Test
    fun `general last turn is captured as its JSON storage string`() {
        val general = TurnGeneral(
            id = 1,
            name = "우길",
            nationId = 0,
            cityId = 76,
            troopId = 0,
            stats = GeneralStats(1, 1, 1),
            experience = 0,
            dedication = 0,
            officerLevel = 0,
            turnTime = Instant.parse("2026-07-27T00:00:00Z"),
            meta = mapOf(
                "last_turn" to linkedMapOf("command" to "견문"),
                "aux" to """{"last발령":2184}""",
                "max_domestic_critical" to 164.88666035902668,
                "penalty" to emptyMap<String, Any?>(),
            ),
        )
        val movingGeneral = general.copy(
            id = 2,
            meta = mapOf(
                "last_turn" to "{}",
                "aux" to linkedMapOf("movingTargetCityID" to 26),
            ),
        )
        val expectedState = stateShape(
            buildJsonObject {},
            generals = kotlinx.serialization.json.JsonArray(
                listOf(
                    buildJsonObject {
                        put("no", 1)
                        put("last_turn", """{"command":"견문"}""")
                        put("aux", """{"max_domestic_critical":164.88666035902668,"last발령":2184}""")
                        put("penalty", "{}")
                    },
                    buildJsonObject {
                        put("no", 2)
                        put("last_turn", "{}")
                        put("aux", """{"movingTargetCityID":26}""")
                    },
                ),
            ),
        )

        val captured = LongSimStateCapture.captureState(
            world(generals = listOf(general, movingGeneral)),
            expectedState,
        )["general"]!!.jsonArray.map { it.jsonObject }
        val lastTurn = captured[0]["last_turn"]!!.jsonPrimitive

        assertEquals(true, lastTurn.isString)
        assertEquals("""{"command":"견문"}""", lastTurn.content)
        assertEquals(
            """{"max_domestic_critical":164.88666035902668,"last발령":2184}""",
            captured[0]["aux"]!!.jsonPrimitive.content,
        )
        assertEquals("""{"movingTargetCityID":26}""", captured[1]["aux"]!!.jsonPrimitive.content)
        assertEquals("{}", captured[0]["penalty"]!!.jsonPrimitive.content)
    }

    @Test
    fun `new nation aux is captured as its PHP JSON storage string`() {
        val nation = Nation(
            id = 3,
            name = "㉿번주",
            color = "#123456",
            meta = linkedMapOf("aux" to linkedMapOf("can_국기변경" to 1)),
        )
        val expectedState = stateShape(
            buildJsonObject {},
            nations = JsonArray(
                listOf(buildJsonObject {
                    put("nation", 3)
                    put("aux", """{"can_국기변경":1}""")
                }),
            ),
        )

        val captured = LongSimStateCapture.captureState(
            world(nations = listOf(nation)),
            expectedState,
        )["nation"]!!.jsonArray.single().jsonObject

        assertEquals(true, captured["aux"]!!.jsonPrimitive.isString)
        assertEquals("""{"can_국기변경":1}""", captured["aux"]!!.jsonPrimitive.content)
    }

    @Test
    fun `monthly replay exposes the crossed phase boundary before builders run`() {
        val world = world()
        val crossedBoundary = Instant.parse("2026-07-27T05:00:00Z")

        advanceLongSimReplayMonthBoundary(world, crossedBoundary)

        assertEquals(crossedBoundary, world.getState().lastTurnTime)
        assertEquals(
            Instant.parse("2026-07-27T05:40:57.981060Z"),
            world.getState().lastTurnTime.plusSeconds(40 * 60 + 57).plusNanos(981_060_000),
        )
    }

    @Test
    fun `diplomacy capture orders persisted rows by MariaDB identity without changing world order`() {
        val world = world(
            diplomacy = listOf(
                TurnDiplomacy(2, 1, state = 2, term = 0, meta = mapOf("no" to 2)),
                TurnDiplomacy(1, 2, state = 2, term = 0, meta = mapOf("no" to 1)),
                TurnDiplomacy(1, 3, state = 2, term = 0),
            ),
        )
        assignLongSimReplayDiplomacyIdentity(world)
        val expectedState = stateShape(
            gameEnv = buildJsonObject {},
            diplomacy = JsonArray(
                listOf(buildJsonObject {
                    put("no", 1)
                    put("me", 2)
                    put("you", 1)
                }),
            ),
        )

        val rows = LongSimStateCapture.captureState(world, expectedState)["diplomacy"]!!
            .jsonArray.map { it.jsonObject }

        assertEquals(listOf(1, 2, 3), rows.map { it["no"]!!.jsonPrimitive.content.toInt() })
        assertEquals(listOf(1 to 2, 2 to 1, 1 to 3), rows.map {
            it["me"]!!.jsonPrimitive.content.toInt() to it["you"]!!.jsonPrimitive.content.toInt()
        })
        assertEquals(listOf(2 to 1, 1 to 2, 1 to 3), world.listDiplomacy().map {
            it.fromNationId to it.toNationId
        })
    }

    @Test
    fun `legacy diplomacy identity high water survives deleted rows`() {
        val world = world(
            nations = listOf(
                Nation(1, "n1", "#111"),
                Nation(6, "n6", "#666"),
                Nation(8, "n8", "#888"),
            ),
            diplomacy = listOf(
                TurnDiplomacy(2, 1, state = 2, term = 0, meta = mapOf("no" to 1)),
                TurnDiplomacy(1, 2, state = 2, term = 0, meta = mapOf("no" to 2)),
            ),
        )

        assertEquals(3, world.createDiplomacy(TurnDiplomacy(1, 6, state = 2, term = 0)).meta["no"])
        assertEquals(4, world.createDiplomacy(TurnDiplomacy(6, 1, state = 2, term = 0)).meta["no"])
        world.removeNation(6)
        assertEquals(5, world.createDiplomacy(TurnDiplomacy(1, 8, state = 2, term = 0)).meta["no"])
    }

    @Test
    fun `nation turn last metadata is mirrored into PHP nation env storage`() {
        val nation = Nation(
            id = 1,
            name = "후한",
            color = "#800000",
            meta = linkedMapOf(
                "nation_env" to linkedMapOf("scout_msg" to "후한왕조"),
                "turn_last_12" to linkedMapOf("command" to "휴식"),
            ),
        )
        val world = world(nations = listOf(nation))

        syncLongSimReplayNationEnv(world)

        @Suppress("UNCHECKED_CAST")
        val nationEnv = world.getNationById(1)!!.meta["nation_env"] as Map<String, Any?>
        assertEquals(linkedMapOf("command" to "휴식"), nationEnv["turn_last_12"])
    }

    @Test
    fun `nation env capture restores PHP JSON storage strings`() {
        val nation = Nation(
            id = 1,
            name = "후한",
            color = "#800000",
            meta = mapOf(
                "nation_env" to linkedMapOf(
                    "scout_msg" to "후한왕조",
                    "turn_last_12" to linkedMapOf("command" to "휴식"),
                ),
            ),
        )

        val rows = LongSimStateCapture.captureState(
            world(nations = listOf(nation)),
            stateShape(buildJsonObject {}),
        )["nation_env"]!!.jsonArray.map { it.jsonObject }

        assertEquals("\"후한왕조\"", rows[0]["value"]!!.jsonPrimitive.content)
        assertEquals("{\"command\":\"휴식\"}", rows[1]["value"]!!.jsonPrimitive.content)
    }

    private fun world(
        meta: Map<String, Any?> = emptyMap(),
        nations: List<Nation> = emptyList(),
        cities: List<City> = emptyList(),
        generals: List<TurnGeneral> = emptyList(),
        diplomacy: List<TurnDiplomacy> = emptyList(),
    ): InMemoryTurnWorld {
        val state = TurnWorldState(
            id = 1,
            currentYear = 181,
            currentMonth = 2,
            tickSeconds = 7_200,
            lastTurnTime = Instant.parse("2026-07-27T01:00:00Z"),
            meta = meta,
        )
        return InMemoryTurnWorld(
            WorldSnapshot(
                state = state,
                generals = generals,
                nations = nations,
                cities = cities,
                diplomacy = diplomacy,
                worldId = WorldId(state.id),
            ),
        )
    }

    private fun stateShape(
        gameEnv: kotlinx.serialization.json.JsonObject,
        nations: kotlinx.serialization.json.JsonArray = kotlinx.serialization.json.JsonArray(emptyList()),
        cities: kotlinx.serialization.json.JsonArray = kotlinx.serialization.json.JsonArray(emptyList()),
        generals: kotlinx.serialization.json.JsonArray = kotlinx.serialization.json.JsonArray(emptyList()),
        diplomacy: kotlinx.serialization.json.JsonArray = kotlinx.serialization.json.JsonArray(emptyList()),
    ) = buildJsonObject {
        put("game_env", gameEnv)
        put("nation", nations)
        put("city", cities)
        put("general", generals)
        put("diplomacy", diplomacy)
        put("nation_env", kotlinx.serialization.json.JsonArray(emptyList()))
    }
}
