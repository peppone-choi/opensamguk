package opensamguk.gameapi.reserve

import opensamguk.common.world.WorldId
import opensamguk.gameapi.config.GameApiProcessWorld
import opensamguk.infra.persistence.ReservedTurnRepository
import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.stats.GeneralActionPipeline
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.data.redis.core.StreamOperations
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

class CommandReserveServiceTest {
    private class RecordingReservedTurns :
        ReservedTurnRepository(mock(NamedParameterJdbcTemplate::class.java)) {
        data class ReserveCall(
            val worldId: WorldId,
            val generalId: Int,
            val turnIdx: Int,
            val actionCode: String?,
            val argJson: String?,
            val brief: String,
        )

        val reserves = mutableListOf<ReserveCall>()

        override fun reserve(
            worldId: WorldId,
            generalId: Int,
            turnIdx: Int,
            actionCode: String?,
            argJson: String?,
            brief: String,
        ) {
            reserves += ReserveCall(worldId, generalId, turnIdx, actionCode, argJson, brief)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun redis(): StringRedisTemplate {
        val redis = mock(StringRedisTemplate::class.java)
        val streamOps = mock(StreamOperations::class.java) as StreamOperations<String, Any, Any>
        `when`(redis.opsForStream<Any, Any>()).thenReturn(streamOps)
        return redis
    }

    @Test
    fun `turn-reserved commands store action definition name as brief for the reserved table`() {
        val reservedTurns = RecordingReservedTurns()
        val service = CommandReserveService(
            reservedTurns = reservedTurns,
            redis = redis(),
            registry = CommandRegistry(GeneralActionPipeline()),
            processWorld = GameApiProcessWorld(1),
            profile = "che:scenario_2",
            clock = Clock.fixed(Instant.parse("0200-01-01T00:00:00Z"), ZoneOffset.UTC),
            requestIds = { "req-brief" },
        )

        service.reserve(generalId = 10, actionCode = "che_견문", turnIdx = 0, argJson = null)

        assertEquals(1, reservedTurns.reserves.size)
        assertEquals(WorldId(1), reservedTurns.reserves.single().worldId)
        assertEquals("che_견문", reservedTurns.reserves.single().actionCode)
        assertEquals("견문", reservedTurns.reserves.single().brief)
    }
}
