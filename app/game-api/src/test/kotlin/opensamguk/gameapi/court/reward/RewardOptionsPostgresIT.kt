package opensamguk.gameapi.court.reward

import javax.sql.DataSource
import kotlin.test.*
import opensamguk.gameapi.config.GameApiProcessWorld
import opensamguk.gameapi.config.GameApiProcessWorldIdConfiguration
import opensamguk.gameapi.owner.GeneralOwnershipReadSource
import opensamguk.gameapi.owner.GeneralOwnershipSnapshot
import opensamguk.gameapi.owner.JdbcGeneralOwnershipReadSource
import opensamguk.gameapi.read.*
import opensamguk.infra.seed.ResolvedWorldArtifacts
import opensamguk.logic.world.StrategicRouteProjection
import org.junit.jupiter.api.BeforeEach
import org.mockito.Mockito.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(GameApiProcessWorldIdConfiguration::class, GeneralReadRepository::class, RetainerReadRepository::class,
    CityReadRepository::class, NationReadRepository::class, RewardOptionsReader::class, RewardOptionsQuery::class,
    RewardOptionsPostgresIT.Config::class)
class RewardOptionsPostgresIT {
    @TestConfiguration
    open class Config {
        @Bean open fun artifacts() = mock(ActiveWorldArtifactResolver::class.java)
        @Bean open fun ownership(source: DataSource, process: GameApiProcessWorld): GeneralOwnershipReadSource =
            ObservedOwnership(source, process)
    }

    internal class ObservedOwnership(source: DataSource, process: GameApiProcessWorld) : GeneralOwnershipReadSource {
        val jdbc = JdbcTemplate(source)
        private val actual = JdbcGeneralOwnershipReadSource(NamedParameterJdbcTemplate(source), process)
        var ownershipTransaction: Map<String, Any>? = null
        override fun findPlayableByUserId(userId: String): GeneralOwnershipSnapshot? {
            assertEquals("on", jdbc.queryForObject("SHOW transaction_read_only", String::class.java))
            assertEquals("repeatable read", jdbc.queryForObject("SHOW transaction_isolation", String::class.java))
            ownershipTransaction = identity()
            return actual.findPlayableByUserId(userId)
        }
        override fun findById(id: Int) = actual.findById(id)
        fun identity(): Map<String, Any> = jdbc.queryForMap("SELECT pg_backend_pid() AS pid, txid_current_snapshot()::text AS snapshot")
    }

    @Autowired lateinit var query: RewardOptionsQuery
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var worlds: WorldStateReadRawRepository
    @Autowired lateinit var cities: CityReadRepository
    @Autowired lateinit var artifacts: ActiveWorldArtifactResolver
    @Autowired lateinit var ownership: GeneralOwnershipReadSource
    private val huge = 9_007_199_254_740_993L

    @BeforeEach fun seed() {
        jdbc.execute("TRUNCATE world_state CASCADE")
        jdbc.update("""INSERT INTO world_state(id,scenario_code,current_year,current_month,current_phase,tick_seconds,config)
            VALUES (1,'reward-options-test',200,1,2,60,'{"worldFormat":"GENERAL_RETAINER_CAMPAIGN","mapName":"han-world-v3"}'::jsonb),
                   (2,'foreign-test',200,1,2,60,'{"worldFormat":"GENERAL_RETAINER_CAMPAIGN","mapName":"han-world-v3"}'::jsonb)""")
        jdbc.update("""INSERT INTO nation(world_id,id,name,color,capital_city_id) VALUES
            (1,1,'N1','#111111',20),(2,2,'Foreign','#222222',30)""")
        for ((id, money) in listOf(10 to huge, 20 to 30L)) insertCity(1, id, 1, warehouse(id, money, huge))
        insertCity(2, 30, 2, "{}")
        jdbc.update("""INSERT INTO general(world_id,id,name,user_id,npc_state,nation_id,city_id,turn_time) VALUES
            (1,101,'Actor','42',0,1,10,'2000-01-01T00:00:00Z'),(1,201,'Recipient',null,0,1,10,'2000-01-01T00:00:00Z'),
            (1,102,'Stale body',null,3,1,10,'2000-01-01T00:00:00Z'),(2,301,'PRIVATE_FOREIGN_PERSON','42',0,2,30,'2000-01-01T00:00:00Z'),
            (2,101,'PRIVATE_FOREIGN_ACTOR','42',0,2,30,'2000-01-01T00:00:00Z')""")
        jdbc.update("""INSERT INTO general_owner(world_id,general_id,user_id,claimed_at)
            VALUES (1,102,42,'2000-01-01T00:00:00Z')""")
        jdbc.update("""INSERT INTO general_retainers(world_id,id,master_general_id,general_id,name,loyalty,origin,relation,release_policy)
            VALUES (1,5,101,201,'Card',95,'EXISTING','staff','MUTUAL'),
                   (2,6,101,301,'PRIVATE_FOREIGN_CARD',95,'EXISTING','staff','MUTUAL')""")
        reset(artifacts)
        val bundle = mock(ResolvedWorldArtifacts::class.java)
        val projection = mock(StrategicRouteProjection::class.java)
        `when`(bundle.projection).thenReturn(projection)
        `when`(projection.administrativeCountyIds).thenReturn(setOf(10, 20))
        `when`(artifacts.resolve()).thenAnswer {
            val observed = ownership as ObservedOwnership
            assertEquals("on", jdbc.queryForObject("SHOW transaction_read_only", String::class.java))
            assertEquals("repeatable read", jdbc.queryForObject("SHOW transaction_isolation", String::class.java))
            assertEquals(observed.ownershipTransaction, observed.identity())
            ActiveWorldArtifactSnapshot(worlds.findById(1).get(), cities.findAll(), bundle)
        }
    }

    private fun warehouse(id: Int, money: Long, revision: Long = 1) =
        """{"countyWarehouse":{"version":1,"countyId":$id,"revision":$revision,"stock":{"money":$money,"grain":0,"iron":0,"timber":0,"horses":0}}}"""

    private fun insertCity(worldId: Int, id: Int, nationId: Int, meta: String) {
        jdbc.update("""INSERT INTO city(world_id,id,name,level,nation_id,supply_state,pop,pop_max,agri,agri_max,
            comm,comm_max,secu,secu_max,def,def_max,wall,wall_max,region,meta)
            VALUES (?,?,'County',1,?,1,1,1,1,1,1,1,0,0,0,0,0,0,1,?::jsonb)""", worldId, id, nationId, meta)
    }

    private fun rows(): Map<String, List<Map<String, Any>>> = listOf("world_state", "general", "general_retainers",
        "city", "nation", "general_owner", "log_entry").associateWith { table ->
        jdbc.queryForList("SELECT row_to_json(t)::text AS row FROM (SELECT * FROM $table ORDER BY 1,2) t")
    }

    @Test fun `actual jsonb uses exact integers and a single readonly snapshot without changing any rows`() {
        val before = rows()
        val result = query.options(101, 42, 5, "100")
        assertEquals("READY", result.status)
        assertEquals(listOf(5), result.cards!!.map { it.retainerId })
        assertEquals((huge + 30).toString(), result.cards.single().funding.usableMoney)
        assertEquals(listOf(RewardDebitDto(20, true, "30", "30", huge.toString()),
            RewardDebitDto(10, false, "70", huge.toString(), huge.toString())), result.preview!!.debitPlan)
        assertEquals(before, rows())
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM general_owner WHERE general_id=102", Int::class.java))
    }

    @Test fun `jsonb fractional warehouse is unavailable while an absent key is distinct and rows stay unchanged`() {
        jdbc.update("UPDATE city SET meta=jsonb_set(meta,'{countyWarehouse,stock,money}','100.5'::jsonb) WHERE world_id=1 AND id=20")
        var before = rows()
        var result = query.options(101, 42, 5, "100")
        assertEquals("WAREHOUSE_MALFORMED", result.cards!!.single().funding.unavailableReason)
        assertEquals("FUNDING_UNAVAILABLE", result.preview!!.verdict)
        assertNull(result.preview.debitPlan)
        assertEquals(before, rows())
        jdbc.update("UPDATE city SET meta=meta-'countyWarehouse' WHERE world_id=1 AND id=20")
        before = rows()
        result = query.options(101, 42, 5, "100")
        assertEquals("KNOWN", result.cards!!.single().funding.status)
        assertEquals(1, result.cards.single().funding.warehouseCount)
        assertEquals(huge.toString(), result.cards.single().funding.usableMoney)
        assertEquals(before, rows())
    }

    @Test fun `actual jsonb overflow closes funding without a partial debit plan or writes`() {
        jdbc.update("UPDATE city SET meta=?::jsonb WHERE world_id=1 AND id=10", warehouse(10, Long.MAX_VALUE))
        jdbc.update("UPDATE city SET meta=?::jsonb WHERE world_id=1 AND id=20", warehouse(20, 1))
        val before = rows()
        val result = query.options(101, 42, 5, "100")
        assertEquals("TOTAL_OVERFLOW", result.cards!!.single().funding.unavailableReason)
        assertNull(result.cards.single().funding.usableMoney)
        assertNull(result.preview!!.debitPlan)
        assertEquals(before, rows())
    }

    companion object {
        @Container @JvmStatic val postgres = PostgreSQLContainer("postgres:16-alpine")
        @DynamicPropertySource @JvmStatic fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }
}
