package opensamguk.infra.persistence

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.FlywayException
import org.flywaydb.core.api.MigrationVersion
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import kotlin.test.*

class PersonCardMigrationTest {
    @Test fun `a database recording V61 before V60 needs explicit out of order recovery`() {
        assumeTrue(runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false))
        PostgreSQLContainer("postgres:16-alpine").use { pg ->
            pg.start()
            fun configure() = Flyway.configure().dataSource(pg.jdbcUrl, pg.username, pg.password)
                .locations("classpath:db/migration")
                .configuration(mapOf("flyway.postgresql.transactional.lock" to "false"))
            configure().target(MigrationVersion.fromVersion("59")).load().migrate()
            val jdbc = JdbcTemplate(DriverManagerDataSource(pg.jdbcUrl, pg.username, pg.password))
            val version61 = configure().load().info().all().single { it.version?.version == "61" }
            val rank = jdbc.queryForObject("SELECT max(installed_rank)+1 FROM flyway_schema_history", Int::class.java)!!
            // Reproduce the version ordering in Flyway history; schema contents do not affect this check.
            jdbc.update("INSERT INTO flyway_schema_history (installed_rank,version,description,type,script,checksum,installed_by,execution_time,success) " +
                "VALUES (?, '61', ?, 'SQL', ?, ?, current_user, 0, true)", rank,
                version61.description, version61.script, version61.checksum)
            assertFailsWith<FlywayException> { configure().target(MigrationVersion.fromVersion("63")).load().migrate() }
            configure().outOfOrder(true).target(MigrationVersion.fromVersion("63")).load().migrate()
            assertEquals(true, jdbc.queryForObject(
                "SELECT success FROM flyway_schema_history WHERE version='60'", Boolean::class.java))
        }
    }

    @Test fun `V55 ownership and V58 unique holder project into person cards without copying rows`() {
        assumeTrue(runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false))
        PostgreSQLContainer("postgres:16-alpine").use { pg ->
            pg.start()
            fun flyway(target: String) = Flyway.configure().dataSource(pg.jdbcUrl, pg.username, pg.password)
                .locations("classpath:db/migration")
                .configuration(mapOf("flyway.postgresql.transactional.lock" to "false"))
                .target(MigrationVersion.fromVersion(target)).load()
            flyway("58").migrate()
            val jdbc = JdbcTemplate(DriverManagerDataSource(pg.jdbcUrl, pg.username, pg.password))
            jdbc.execute("INSERT INTO world_state (id, scenario_code, current_year, current_month, tick_seconds) VALUES (1, 'sc', 200, 1, 3600)")
            jdbc.execute("INSERT INTO general (world_id,id,name,nation_id,city_id,turn_time) VALUES (1,10,'주공',1,1,now()),(1,20,'부장',1,1,now()),(1,30,'재야',0,1,now())")
            jdbc.execute("UPDATE general SET meta='{" +
                "\"personPolicy\":{\"renownCapacity\":30,\"acceptsEnlistment\":true,\"statSourceId\":\"verified\",\"statSourceRevision\":\"v1\",\"officerId\":20}}'::jsonb " +
                "WHERE world_id=1 AND id=20")
            jdbc.execute("""UPDATE general SET meta = meta || '{"personBonds":{"version":1,"bonds":[]},
                "personContribution":{"version":1,"stratagemCardIds":["stratagem-insight"]}}'::jsonb
                WHERE world_id=1 AND id=20""".trimIndent())
            jdbc.execute("INSERT INTO general_retainers (world_id,id,master_general_id,origin,general_id,name,relation,release_policy) " +
                "VALUES (1,1,10,'EXISTING',20,'부장','lieutenant','MUTUAL')," +
                "(1,2,10,'RECRUITED',NULL,'무명','guest','MASTER_ONLY')")

            flyway("64").migrate()
            assertEquals(false, jdbc.queryForObject("SELECT to_regclass('campaign_siege') IS NOT NULL", Boolean::class.java))
            assertEquals(true, jdbc.queryForObject("SELECT to_regclass('siege') IS NOT NULL", Boolean::class.java))
            assertEquals(false, jdbc.queryForObject("SELECT to_regclass('campaign_person_card') IS NOT NULL", Boolean::class.java))
            assertEquals(true, jdbc.queryForObject("SELECT to_regclass('person_card') IS NOT NULL", Boolean::class.java))
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM pg_constraint WHERE conrelid = 'siege'::regclass AND conname LIKE 'campaign_siege_%'", Int::class.java))
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE tablename = 'siege' AND indexname LIKE 'campaign_siege_%'", Int::class.java))
            val cards = jdbc.queryForList("SELECT card_id,holder_general_id,availability,renown_cost FROM person_card WHERE world_id=1 ORDER BY card_id")
            assertEquals(listOf("general:10", "general:20", "general:30", "recruited:2"), cards.map { it["card_id"] })
            assertEquals(10, cards[1]["holder_general_id"])
            assertEquals("UNIQUE", cards[1]["availability"])
            assertEquals(5, cards[1]["renown_cost"])
            assertTrue(jdbc.queryForObject("SELECT bond_state::text FROM person_card WHERE world_id=1 AND card_id='general:20'", String::class.java)!!.contains("\"bonds\""))
            assertTrue(jdbc.queryForObject("SELECT contribution_state::text FROM person_card WHERE world_id=1 AND card_id='general:20'", String::class.java)!!.contains("stratagem-insight"))
            assertNull(cards[0]["renown_cost"])
            assertEquals("COMMON", cards[3]["availability"])
            assertNull(cards[3]["renown_cost"])
            assertFailsWith<DataIntegrityViolationException> {
                jdbc.execute("INSERT INTO general_retainers (world_id,id,master_general_id,origin,general_id,name,relation,release_policy) " +
                    "VALUES (1,3,30,'EXISTING',20,'부장','guest','MUTUAL')")
            }
            jdbc.execute("DELETE FROM general_retainers WHERE world_id=1 AND id=1")
            assertNull(jdbc.queryForObject("SELECT holder_general_id FROM person_card WHERE world_id=1 AND card_id='general:20'", Int::class.java))
        }
    }
}
