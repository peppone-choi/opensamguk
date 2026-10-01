package opensamguk.gameapi.web

import java.sql.Connection
import java.time.Instant
import javax.sql.DataSource
import opensamguk.gameapi.read.WorldStateReadEntity
import opensamguk.gameapi.read.WorldStateReadRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.data.redis.connection.RedisConnection
import org.springframework.data.redis.connection.RedisConnectionFactory

class HealthCheckControllerTest {
    private val dataSource = mock(DataSource::class.java)
    private val database = mock(Connection::class.java)
    private val redisFactory = mock(RedisConnectionFactory::class.java)
    private val redis = mock(RedisConnection::class.java)
    private val world = mock(WorldStateReadRepository::class.java)
    private val controller = HealthCheckController(dataSource, redisFactory, world)

    private fun healthyDependencies() {
        `when`(dataSource.connection).thenReturn(database)
        `when`(database.isValid(3)).thenReturn(true)
        `when`(redisFactory.connection).thenReturn(redis)
        `when`(redis.ping()).thenReturn("PONG")
    }

    @Test
    fun `stopped world returns HTTP 200 degraded and cannot be cached`() {
        healthyDependencies()
        `when`(world.findProcessWorld()).thenReturn(WorldStateReadEntity(
            status = "OPEN", tickSeconds = 300,
            meta = mapOf("lastTurnTime" to Instant.now().minusSeconds(20 * 3600).toString(),
                         "lastTickExecutedAt" to Instant.now().minusSeconds(25 * 3600 + 1).toString()),
        ))
        val response = controller.health()
        assertEquals(200, response.statusCode.value())
        assertEquals("no-store", response.headers.getFirst("Cache-Control"))
        assertEquals("degraded", response.body?.status)
        assertTrue(response.body!!.world.stale)
    }

    @Test
    fun `database rejection is degraded even with a recent tick`() {
        healthyDependencies()
        `when`(database.isValid(3)).thenReturn(false)
        `when`(world.findProcessWorld()).thenReturn(WorldStateReadEntity(
            tickSeconds = 300, meta = mapOf("lastTickExecutedAt" to Instant.now().toString()),
        ))
        assertEquals("degraded", controller.health().body?.status)
    }

    @Test
    fun `paused lifecycle is HTTP 200 degraded rather than a healthy recovery`() {
        healthyDependencies()
        `when`(world.findProcessWorld()).thenReturn(WorldStateReadEntity(status = "PRE_OPEN", tickSeconds = 300,
            meta = mapOf("lastTickExecutedAt" to Instant.now().toString())))
        val response = controller.health()
        assertEquals(200, response.statusCode.value())
        assertEquals("degraded", response.body?.status)
        assertEquals(opensamguk.gameapi.read.TurnLoopHealth.State.PAUSED, response.body?.world?.turnLoop?.state)
    }

    @Test
    fun `disabled engine observation keeps recent OPEN world degraded`() {
        healthyDependencies()
        `when`(world.findProcessWorld()).thenReturn(WorldStateReadEntity(status = "OPEN", tickSeconds = 300,
            meta = mapOf("lastTickExecutedAt" to Instant.now().minusSeconds(5).toString())))
        val result = controller.health()
        assertEquals(200, result.statusCode.value())
        assertEquals("degraded", result.body?.status)
        assertEquals(opensamguk.gameapi.read.TurnLoopHealth.State.UNKNOWN, result.body?.world?.turnLoop?.state)
    }

}
