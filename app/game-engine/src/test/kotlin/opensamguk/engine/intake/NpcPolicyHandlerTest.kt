package opensamguk.engine.intake

import kotlinx.serialization.json.Json
import opensamguk.common.wire.NationSettingResult
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.GeneralStats
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.KvKey
import opensamguk.engine.turn.Nation
import opensamguk.engine.turn.TurnGeneral
import opensamguk.engine.turn.TurnWorldState
import opensamguk.engine.turn.WorldSnapshot
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NpcPolicyHandlerTest {
    private val t0 = Instant.parse("0200-01-01T00:00:00Z")
    private val clock = Clock.fixed(Instant.parse("2026-07-10T01:02:03Z"), ZoneOffset.UTC)

    private fun general(officerLevel: Int = 12, meta: Map<String, Any?> = emptyMap()) = TurnGeneral(
        id = 10,
        name = "순욱",
        nationId = 1,
        cityId = 1,
        troopId = 0,
        stats = GeneralStats(80, 70, 90),
        experience = 0,
        dedication = 0,
        officerLevel = officerLevel,
        turnTime = t0,
        meta = meta,
    )

    private fun world(general: TurnGeneral = general(), nation: Nation = Nation(id = 1, name = "위", color = "#fff")) =
        InMemoryTurnWorld(
            WorldSnapshot(
                state = TurnWorldState(id = 1, currentYear = 200, currentMonth = 1, tickSeconds = 60, lastTurnTime = t0),
                generals = listOf(general),
                nations = listOf(nation),
            ),
        )

    @Test
    fun `nation policy save clamps negative integer and records nation env kv`() {
        val world = world()
        val recorder = ChangeRecorder()
        val result = NpcPolicyHandler(world, recorder, clock).handle(
            TurnDaemonCommand.NpcPolicyUpdate(
                generalId = 10,
                policyType = "nationPolicy",
                data = Json.parseToJsonElement("""{"reqNationGold":-5,"safeRecruitCityPopulationRatio":0.75}"""),
            ),
        ) as NationSettingResult

        assertTrue(result.ok)
        val write = recorder.kvDirty()[KvKey("nation_env", "1", "npc_nation_policy")] as Map<*, *>
        val values = write["values"] as Map<*, *>
        assertEquals(0, values["reqNationGold"])
        assertEquals(0.75, values["safeRecruitCityPopulationRatio"])
        assertEquals("순욱", write["valueSetter"])
        assertEquals("2026-07-10T01:02:03Z", write["valueSetTime"])
    }

    @Test
    fun `permission below npc policy save threshold is denied without kv write`() {
        val world = world(general(officerLevel = 5))
        val recorder = ChangeRecorder()
        val result = NpcPolicyHandler(world, recorder, clock).handle(
            TurnDaemonCommand.NpcPolicyUpdate(
                generalId = 10,
                policyType = "nationPriority",
                data = Json.parseToJsonElement("""["천도"]"""),
            ),
        ) as NationSettingResult

        assertFalse(result.ok)
        assertEquals("권한이 부족합니다. 군주, 외교권자, 조언자가 아닙니다.", result.reason)
        assertTrue(recorder.kvDirty().isEmpty())
    }

    @Test
    fun `general priority requires sortie before domestic and always-present actions`() {
        val handler = NpcPolicyHandler(world(), ChangeRecorder(), clock)

        val wrongOrder = handler.handle(
            TurnDaemonCommand.NpcPolicyUpdate(
                generalId = 10,
                policyType = "generalPriority",
                data = Json.parseToJsonElement("""["일반내정","출병"]"""),
            ),
        ) as NationSettingResult
        assertFalse(wrongOrder.ok)
        assertEquals("출병 명령은 일반내정 명령보다 먼저여야 합니다.", wrongOrder.reason)

        val missing = handler.handle(
            TurnDaemonCommand.NpcPolicyUpdate(
                generalId = 10,
                policyType = "generalPriority",
                data = Json.parseToJsonElement("""["출병"]"""),
            ),
        ) as NationSettingResult
        assertFalse(missing.ok)
        assertEquals("일반내정은 항상 사용해야 합니다.", missing.reason)
    }

    @Test
    fun `dispatcher routes npc policy update`() {
        val world = world()
        val recorder = ChangeRecorder()
        val dispatcher = opensamguk.engine.run.TurnDaemonCommandDispatcher(
            world,
            recorder,
            noopRepo(),
            noopRepo(),
            noopRepo(),
        )

        val result = dispatcher.dispatch(
            TurnDaemonCommand.NpcPolicyUpdate(
                generalId = 10,
                policyType = "nationPriority",
                data = Json.parseToJsonElement("""["천도","선전포고"]"""),
            ),
        ) as NationSettingResult

        assertTrue(result.ok)
        val write = recorder.kvDirty()[KvKey("nation_env", "1", "npc_nation_policy")] as Map<*, *>
        assertEquals(listOf("천도", "선전포고"), write["priority"])
    }

    private inline fun <reified T> noopRepo(): T = java.lang.reflect.Proxy.newProxyInstance(
        T::class.java.classLoader,
        arrayOf(T::class.java),
    ) { _, method, _ ->
        when (method.returnType) {
            java.util.List::class.java -> emptyList<Any>()
            java.lang.Boolean.TYPE -> false
            else -> null
        }
    } as T
}
