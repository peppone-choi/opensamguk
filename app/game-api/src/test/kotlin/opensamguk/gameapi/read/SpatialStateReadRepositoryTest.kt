package opensamguk.gameapi.read

import opensamguk.gameapi.config.GameApiProcessWorld
import opensamguk.logic.world.StrategicTopologySnapshot
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.AbstractDataSource
import java.sql.Connection
import kotlin.test.*

class SpatialStateReadRepositoryTest {
    @Test fun `requested world mismatch fails before any SQL connection`() {
        var connections = 0
        val source = object : AbstractDataSource() {
            override fun getConnection(): Connection { connections++; error("Unexpected SQL") }
            override fun getConnection(username: String, password: String): Connection = getConnection()
        }
        val topology = StrategicTopologySnapshot("r1", emptySet(), emptyList(), emptyList(), emptyList(), mapOf("fixture" to "sha"))
        val repository = SpatialStateReadRepository(NamedParameterJdbcTemplate(source), GameApiProcessWorld(1))
        assertFailsWith<IllegalArgumentException> { repository.readSnapshot(2, topology) }
        assertEquals(0, connections)
    }
}
