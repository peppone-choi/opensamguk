package opensamguk.engine.golden

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import opensamguk.common.world.WorldId
import opensamguk.engine.turn.GeneralStats
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.TurnGeneral
import opensamguk.engine.turn.TurnWorldState
import opensamguk.engine.turn.WorldSnapshot
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LongSimKillturnOracleTest {

    @Test
    fun `baseline npc killturn materializes in phase units and captures raw PHP units`() {
        val baseline = LongSimWorldMaterializer.parseBaseline(
            buildJsonObject {
                put("hiddenSeed", "0123456789abcdef0123456789abcdef")
                put("startYear", 181)
                put("turnterm", 120)
                put("maxTurns", 1)
                put("state", state(generalJson(id = 7, npc = 2, killturn = 10)))
            },
        )

        val world = LongSimWorldMaterializer.materializeWorld(baseline)
        val general = world.getGeneralById(7)!!

        assertEquals(30, general.meta["killturn"])
        assertEquals("phase", general.meta["killturn_unit"])
        assertEquals(20, general.meta[LongSimKillturnOracle.OFFSET_META_KEY])
        val captured = LongSimStateCapture.captureState(world, baseline.state)
        assertEquals(
            10,
            captured["general"]!!.jsonArray.single().jsonObject["killturn"]!!.jsonPrimitive.content.toInt(),
        )
    }

    @Test
    fun `new month-derived general receives two-K offset while ordinary decrement preserves it`() {
        val oracle = LongSimKillturnOracle.fromManifest(
            manifest(
                transition(
                    generalId = 9,
                    from = null,
                    to = 10,
                    provenance = "GeneralBuilder",
                    family = "month-derived",
                ),
            ),
        )
        val world = world(general(id = 9, npc = 2, killturn = 30))

        oracle.observeWorld(world)
        assertEquals(20, world.getGeneralById(9)!!.meta[LongSimKillturnOracle.OFFSET_META_KEY])

        world.applyGeneralDirtyFree(
            world.getGeneralById(9)!!.copy(
                meta = world.getGeneralById(9)!!.meta + ("killturn" to 29),
            ),
        )
        oracle.observeWorld(world)

        assertEquals(20, world.getGeneralById(9)!!.meta[LongSimKillturnOracle.OFFSET_META_KEY])
        oracle.assertComplete()
    }

    @Test
    fun `schema three sidecar manifest preserves the killturn oracle`() {
        val oracle = LongSimKillturnOracle.fromManifest(
            manifest(
                transition(
                    generalId = 175,
                    from = null,
                    to = 289,
                    provenance = "GeneralBuilder",
                    family = "month-derived",
                ),
                schemaVersion = 3,
            ),
        )
        val world = world(general(id = 175, npc = 3, killturn = 867))

        oracle.observeWorld(world)

        assertEquals(578, world.getGeneralById(175)!!.meta[LongSimKillturnOracle.OFFSET_META_KEY])
        oracle.assertComplete()
    }

    @Test
    fun `known absolute provenance selects month or execution offset`() {
        val oracle = LongSimKillturnOracle.fromManifest(
            manifest(
                transition(
                    generalId = 3,
                    from = 12,
                    to = 40,
                    provenance = "human-reset",
                    family = "execution-constant",
                ),
                transition(4, 12, 6, "ClaimNpc", "execution-constant"),
                transition(5, 12, 70, "ai-gather-reroll", "execution-constant"),
                transition(6, 12, 1, "ai-npc-death", "execution-constant"),
                transition(7, 0, 12, "possession-release", "month-derived"),
            ),
        )
        val world = world(
            general(id = 3, npc = 0, killturn = 12, offset = 0),
            general(id = 4, npc = 0, killturn = 12, offset = 0),
            general(id = 5, npc = 5, killturn = 12, offset = 0),
            general(id = 6, npc = 5, killturn = 12, offset = 0),
            general(id = 7, npc = 1, killturn = 0, offset = 0),
        )
        oracle.observeWorld(world)
        for ((generalId, killturn) in mapOf(3 to 40, 4 to 6, 5 to 70, 6 to 1, 7 to 36)) {
            val general = world.getGeneralById(generalId)!!
            world.applyGeneralDirtyFree(general.copy(meta = general.meta + ("killturn" to killturn)))
        }

        oracle.observeWorld(world)

        for (generalId in 3..6) {
            assertEquals(0, world.getGeneralById(generalId)!!.meta[LongSimKillturnOracle.OFFSET_META_KEY])
        }
        assertEquals(24, world.getGeneralById(7)!!.meta[LongSimKillturnOracle.OFFSET_META_KEY])
        oracle.assertComplete()
    }

    @Test
    fun `unknown absolute transition provenance hard fails`() {
        val error = assertFailsWith<IllegalArgumentException> {
            LongSimKillturnOracle.fromManifest(
                manifest(
                    transition(
                        generalId = 1,
                        from = 4,
                        to = 99,
                        provenance = "mystery-writer",
                        family = "unknown",
                    ),
                ),
            )
        }

        assertEquals(true, error.message!!.contains("mystery-writer"))
    }

    private fun manifest(
        vararg transitions: kotlinx.serialization.json.JsonObject,
        schemaVersion: Int = 2,
    ) =
        buildJsonObject {
            put("schemaVersion", schemaVersion)
            put("killturnTransitions", JsonArray(transitions.toList()))
        }

    private fun transition(
        generalId: Int,
        from: Int?,
        to: Int,
        provenance: String,
        family: String,
    ) = buildJsonObject {
        put("ordinal", 0)
        put("generalId", generalId)
        if (from == null) put("from", kotlinx.serialization.json.JsonNull) else put("from", from)
        put("to", to)
        put("provenance", provenance)
        put("family", family)
    }

    private fun state(general: kotlinx.serialization.json.JsonObject) = buildJsonObject {
        put("game_env", buildJsonObject {
            put("year", 181)
            put("month", 1)
            put("startyear", 181)
            put("turnterm", 120)
            put("scenario", 1010)
            put("isunited", 0)
            put("killturn", 40)
        })
        put("nation", JsonArray(emptyList()))
        put("city", JsonArray(emptyList()))
        put("general", JsonArray(listOf(general)))
        put("diplomacy", JsonArray(emptyList()))
        put("nation_env", JsonArray(emptyList()))
    }

    private fun generalJson(id: Int, npc: Int, killturn: Int) = buildJsonObject {
        put("no", id)
        put("name", "테스트")
        put("npc", npc)
        put("killturn", killturn)
        put("turntime", "2026-07-27 00:00:00.000000")
    }

    private fun general(
        id: Int,
        npc: Int,
        killturn: Int,
        offset: Int? = null,
    ): TurnGeneral {
        val meta = linkedMapOf<String, Any?>("killturn" to killturn)
        if (offset != null) meta[LongSimKillturnOracle.OFFSET_META_KEY] = offset
        return TurnGeneral(
            id = id,
            name = "테스트",
            nationId = 0,
            cityId = 0,
            troopId = 0,
            stats = GeneralStats(1, 1, 1),
            experience = 0,
            dedication = 0,
            officerLevel = 0,
            npcState = npc,
            turnTime = Instant.parse("2026-07-27T00:00:00Z"),
            meta = meta,
        )
    }

    private fun world(vararg generals: TurnGeneral): InMemoryTurnWorld {
        val state = TurnWorldState(
            id = 1,
            currentYear = 181,
            currentMonth = 1,
            tickSeconds = 7_200,
            lastTurnTime = Instant.parse("2026-07-27T00:00:00Z"),
        )
        return InMemoryTurnWorld(
            WorldSnapshot(
                state = state,
                generals = generals.toList(),
                worldId = WorldId(state.id),
            ),
        )
    }
}
