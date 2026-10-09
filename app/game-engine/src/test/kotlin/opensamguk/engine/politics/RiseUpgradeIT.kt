package opensamguk.engine.politics

import kotlin.test.*
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer

/** Upgrade populated synthetic V77 worlds without replacing their rows or relaxing positive-nation FKs. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RiseUpgradeIT {
    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var source: DriverManagerDataSource
    private lateinit var jdbc: JdbcTemplate

    @BeforeAll fun setup() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable,
            "Docker unavailable: populated V77 to V78 upgrade NOT verified")
        postgres = PostgreSQLContainer("postgres:16-alpine")
        postgres.start()
        source = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        jdbc = JdbcTemplate(source)
    }

    @AfterAll fun cleanup() { if (this::postgres.isInitialized) postgres.stop() }

    private fun migrations(target: String) = Flyway.configure().dataSource(source)
        .locations("classpath:db/migration").target(target)
        .configuration(mapOf("flyway.postgresql.transactional.lock" to "false")).load()

    private fun rows() = listOf("world_state", "nation", "city", "general", "troop").associateWith { table ->
        jdbc.queryForList("SELECT (to_jsonb(t) - 'nation_ref')::text FROM $table t ORDER BY 1", String::class.java)
    }

    @Test fun `populated V77 worlds upgrade preserving all old columns and same world references`() {
        migrations("77").migrate()
        for (world in listOf(7491, 7492)) {
            jdbc.update("""INSERT INTO world_state(id,scenario_code,current_year,current_month,tick_seconds)
                VALUES (?,'synthetic-rise-upgrade',200,1,3600)""", world)
            jdbc.update("INSERT INTO nation(world_id,id,name,color) VALUES (?,7,'existing nation','#123456')", world)
            jdbc.update("""INSERT INTO city(world_id,id,name,level,nation_id,pop,pop_max,agri,agri_max,comm,comm_max,
                secu,secu_max,def,def_max,wall,wall_max,region)
                VALUES (?,1,'existing county',1,7,100,1000,10,1000,10,1000,10,1000,10,1000,10,1000,1)""", world)
            jdbc.update("""INSERT INTO general(world_id,id,name,nation_id,city_id,gold,rice,crew,turn_time,meta)
                VALUES (?,1,'existing leader',7,1,123,456,789,'0200-01-01T00:00:00Z',
                    '{"keep":"old world asset"}'::jsonb)""", world)
            jdbc.update("INSERT INTO troop(world_id,troop_leader,nation,name) VALUES (?,1,7,'existing troop')", world)
        }
        val before = rows()
        val sequences = jdbc.queryForList("SELECT sequencename,last_value FROM pg_sequences ORDER BY sequencename")
        val upgrade = migrations("78")
        assertEquals(1, upgrade.migrate().migrationsExecuted)
        assertEquals("78", upgrade.info().current().version.version)
        assertEquals(before, rows())
        assertEquals(sequences, jdbc.queryForList("SELECT sequencename,last_value FROM pg_sequences ORDER BY sequencename"))
        assertEquals(listOf(7, 7), jdbc.queryForList("SELECT nation_ref FROM troop ORDER BY world_id", Int::class.java))
        jdbc.update("""INSERT INTO general(world_id,id,name,nation_id,city_id,turn_time)
            VALUES (7491,2,'free leader',0,1,'0200-01-01T00:00:00Z')""")
        jdbc.update("INSERT INTO troop(world_id,troop_leader,nation,name) VALUES (7491,2,0,'free troop')")
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM nation WHERE id=0", Int::class.java))
        assertNull(jdbc.queryForObject("SELECT nation_ref FROM troop WHERE world_id=7491 AND troop_leader=2", Int::class.java))
        jdbc.update("INSERT INTO nation(world_id,id,name,color) VALUES (7492,9,'other world only','#999999')")
        for (nation in listOf(-1, 9, 999)) assertFailsWith<DataIntegrityViolationException> {
            jdbc.update("UPDATE troop SET nation=? WHERE world_id=7491 AND troop_leader=2", nation)
        }
        assertFailsWith<DataIntegrityViolationException> {
            jdbc.update("INSERT INTO troop(world_id,troop_leader,nation,name) VALUES (7491,999,0,'missing leader')")
        }
        assertEquals(before.getValue("troop"), rows().getValue("troop").filterNot { it.contains("free troop") })
        assertEquals(0, upgrade.migrate().migrationsExecuted)
    }
}
