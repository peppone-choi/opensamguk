package opensamguk.engine.siege

import opensamguk.common.world.WorldId
import opensamguk.engine.boot.SeedBootstrap
import opensamguk.engine.boot.WorldSnapshotLoader
import opensamguk.infra.persistence.MetaJson
import opensamguk.infra.seed.UnitProfilesJson
import opensamguk.logic.input.CityMilitaryState
import opensamguk.logic.war.SiegeRules
import opensamguk.logic.world.*
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import kotlin.test.*

/** Real PG/production loader, synthetic small grid. No live world, auth, full-map validator or HTTP claim. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SiegeApproachColdReloadIT {
    private val postgres = PostgreSQLContainer("postgres:16-alpine")
    private lateinit var jdbc: JdbcTemplate

    @BeforeAll fun start() {
        postgres.start() // Docker is required: unavailable Docker fails this specific acceptance probe, never skips it.
        val source = DriverManagerDataSource().apply {
            setDriverClassName("org.postgresql.Driver")
            url = postgres.jdbcUrl; username = postgres.username; password = postgres.password
        }
        Flyway.configure().dataSource(source).locations("classpath:db/migration")
            .configuration(mapOf("flyway.postgresql.transactional.lock" to "false")).load().migrate()
        jdbc = JdbcTemplate(source)
        jdbc.update("""INSERT INTO world_state(id,scenario_code,current_year,current_month,tick_seconds,config)
            VALUES(160,'synthetic_siege_reload',193,7,1800,'{"worldFormat":"GENERAL_RETAINER_CAMPAIGN"}'::jsonb)""")
        jdbc.update("""INSERT INTO city(world_id,id,name,level,nation_id,pop,pop_max,agri,agri_max,comm,comm_max,
            secu,secu_max,def,def_max,wall,wall_max,region,meta)
            VALUES(160,204,'synthetic county',1,0,1000,10000,0,1000,0,1000,0,100,900,1000,100,1000,0,?::jsonb)""",
            MetaJson.encode(mapOf(CityMilitaryState.META_KEY to
                CityMilitaryState(training = 50, morale = 100, troops = 900).toMetaValue())))
        jdbc.update("""INSERT INTO general(world_id,id,name,nation_id,user_id,city_id,turn_time)
            VALUES(160,1394,'synthetic attacker',3,'42',204,TIMESTAMPTZ '0193-07-01 00:00:00+00')""")
        jdbc.update("""INSERT INTO general_bugok(world_id,id,master_general_id,name,troops,crew_type_id,training,morale,fatigue,provisions)
            VALUES(160,48,1394,'synthetic unit',3000,1100,70,60,0,9000)""")
        jdbc.update("""INSERT INTO siege(world_id,county_id,status,besieger_general_id,besieger_owner_general_id,
            besieger_order_id,besieger_nation_id,defender_nation_id,approach_province_id,
            started_year,started_month,started_phase,turns,morale,garrison,timeline)
            VALUES(160,204,'ACTIVE',1394,1394,'synthetic-order',3,0,'approach',193,6,3,3,10000,900,'[]'::jsonb)""")
    }

    @AfterAll fun stop() { if (postgres.isRunning) postgres.stop() }

    @Test fun `cold loader restores source and revalidates current unit profile without mutating the database`() {
        val cells = ProvinceCellIndex("synthetic", "a".repeat(64), "b".repeat(64), 49, 1,
            mapOf('1' to "PLAIN"), mapOf("approach" to listOf(ProvinceCell(0, 0, '1')),
                "battle" to (1..48).map { ProvinceCell(it, 0, '1') }))
        val profiles = UnitProfilesJson.loadDefault()
        fun read() = WorldSnapshotLoader(jdbc, SeedBootstrap(seedEnabled = false, worldId = WorldId(160)),
            WorldId(160), snapshotValidator = {}).buildSnapshot()
        fun checkReload() {
            val cold = read()
            assertEquals(WorldId(160), cold.worldId)
            val siege = cold.sieges.single()
            assertEquals(3, siege.turns); assertEquals("ACTIVE", siege.status)
            assertEquals("approach", siege.approachProvinceId)
            val unit = cold.bugoks.single()
            assertEquals(48, unit.id); assertEquals(1394, unit.masterGeneralId)
            assertTrue(SiegeRules.besiegerFed(unit.troops, unit.provisions))
            val city = cold.cities.single()
            val layout = requireNotNull(SiegeRules.assaultLayout(cells, "battle", siege.approachProvinceId))
            assertEquals(SiegeRules.AssaultBlock.ASSAULT_APPROACH_UNREACHABLE,
                SiegeRules.assaultApproachReadiness(layout, mapOf(unit.id to requireNotNull(profiles.find(unit.crewTypeId))),
                    CityMilitaryState.read(city.meta, city.defence).troops))
            assertEquals(3000, unit.troops); assertEquals(60, unit.morale); assertEquals(0, unit.fatigue)
            assertEquals(900, CityMilitaryState.read(city.meta).troops)
            assertTrue(siege.timeline.isEmpty())
        }
        checkReload(); checkReload() // new JDBC reads and new loader objects, not a cached in-memory snapshot.
        jdbc.update("UPDATE general_bugok SET crew_type_id=1300 WHERE world_id=160 AND id=48")
        val changed = read()
        val layout = requireNotNull(SiegeRules.assaultLayout(cells, "battle", changed.sieges.single().approachProvinceId))
        assertNull(SiegeRules.assaultApproachReadiness(layout,
            mapOf(48 to requireNotNull(profiles.find(changed.bugoks.single().crewTypeId))), 900),
            "current faster profile is reloaded; previous impossibility is not cached")
        jdbc.update("UPDATE general_bugok SET crew_type_id=1100 WHERE world_id=160 AND id=48")
        checkReload()
    }
}
