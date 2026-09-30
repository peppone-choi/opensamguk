package opensamguk.engine.invariance

import java.time.Instant
import opensamguk.common.turn.TurnCatchUp
import opensamguk.common.world.WorldId
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.TurnWorldState
import opensamguk.engine.turn.WorldSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals

class TurnCatchUpWorldHashTest {
    @Test
    fun `operational pacing state does not alter the pinned gameplay projection`() {
        val now = Instant.parse("2026-09-27T00:00:00Z")
        val state = TurnWorldState(
            id = 1, currentYear = 200, currentMonth = 1,
            tickSeconds = 300, lastTurnTime = now.minusSeconds(72300),
        )
        val world = InMemoryTurnWorld(WorldSnapshot(state = state, worldId = WorldId(1)))
        val baseline = WorldStateBaseline.sha256(world)
        world.setCatchUp(TurnCatchUp.start(now.minusSeconds(72000), now))
        assertEquals(baseline, WorldStateBaseline.sha256(world))
        world.setCatchUp(world.getState().catchUp?.copy(active = false))
        assertEquals(baseline, WorldStateBaseline.sha256(world))
        world.setGameEnvValue("lastTickExecutedAt", now.toString())
        assertEquals(baseline, WorldStateBaseline.sha256(world),
                     "성공 턴 벽시계는 기존 게임 상태 정규화에서 제외된다")
    }
}
