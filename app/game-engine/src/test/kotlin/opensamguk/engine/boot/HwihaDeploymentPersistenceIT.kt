package opensamguk.engine.boot

import java.nio.file.Path
import kotlin.test.*
import opensamguk.engine.flush.DatabaseHooks
import opensamguk.engine.hwiha.*
import opensamguk.engine.turn.*
import opensamguk.infra.persistence.JdbcFlushExecutor
import opensamguk.infra.seed.HanWorldArtifactsResolver
import opensamguk.logic.input.*
import opensamguk.logic.world.*
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer

/** Real database boundary; input scheduling and combat are verified separately. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HwihaDeploymentPersistenceIT {
    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var jdbc: JdbcTemplate
    private lateinit var flush: JdbcFlushExecutor
    private lateinit var fixture: HwihaEnlistmentFixture
    private val bundle by lazy { HanWorldArtifactsResolver(Path.of("../..")).artifacts(HanWorldVariant.V3_1133) }
    private fun executor(world: InMemoryTurnWorld, recorder: ChangeRecorder) =
        HwihaDeploymentExecutor(world,recorder,bundle.projection.topology,bundle.landMarchMetrics)

    @BeforeAll fun setup() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable)
        postgres=PostgreSQLContainer("postgres:16-alpine").apply { start() }
        val source=DriverManagerDataSource(postgres.jdbcUrl,postgres.username,postgres.password)
        Flyway.configure().dataSource(source).locations("classpath:db/migration")
            .configuration(mapOf("flyway.postgresql.transactional.lock" to "false")).load().migrate()
        jdbc=JdbcTemplate(source)
        flush=JdbcFlushExecutor(NamedParameterJdbcTemplate(source),TransactionTemplate(DataSourceTransactionManager(source)))
        fixture=HwihaEnlistmentFixture(jdbc,flush)
    }
    @AfterAll fun teardown() { if(this::postgres.isInitialized) postgres.stop() }
    private fun save(world: InMemoryTurnWorld, recorder: ChangeRecorder) =
        flush.flush(DatabaseHooks.toFlushPayload(world,recorder,world.consumeDirtyState()))
    private fun cold(id: Int) = InMemoryTurnWorld(fixture.load(id))

    @Test fun `multiple deputy led cards persist without moving troops or resources twice`() {
        val id=611;fixture.seed(id)
        jdbc.update("INSERT INTO general_bugok(world_id,id,master_general_id,name,troops,crew_type_id,training,morale,provisions,commander_retainer_id) VALUES (?,8,1,'second',150,1,50,50,300,4)",id)
        var world=cold(id);val recorder=ChangeRecorder()
        val beforeGeneral=world.getGeneralById(1)!!;val beforeUnits=world.listBugoks();val beforePosition=world.positionOf(2)
        val request=DeploymentRequest(1,4,listOf(8,7))
        val first=assertIs<DeploymentExecution.Applied>(executor(world,recorder).deploy("deploy-$id",request))
        assertEquals(listOf(7,8),first.corps.bugokIds)
        assertEquals(2,first.corps.commanderGeneralId)
        save(world,recorder);world=cold(id)
        assertEquals(listOf(first.corps),HwihaDeploymentState.read(world.getGeneralById(1)!!.meta)!!.corps)
        assertEquals(beforeUnits,world.listBugoks());assertEquals(beforePosition,world.positionOf(2))
        val after=world.getGeneralById(1)!!
        assertEquals(beforeGeneral.copy(meta=after.meta),after)
        val duplicate=executor(world,ChangeRecorder()).deploy("deploy-$id",request)
        assertEquals(DeploymentFailure.ALREADY_DEPLOYED,assertIs<DeploymentExecution.Rejected>(duplicate).reason)
        assertEquals(after,world.getGeneralById(1));assertEquals(beforeUnits,world.listBugoks())
        val live=executor(world,ChangeRecorder()).projection()!!
        assertIs<DeploymentAssessment.Eligible>(HwihaDeploymentRules.assessActive(first.corps,live))
        jdbc.update("UPDATE general_bugok SET commander_retainer_id=NULL WHERE world_id=? AND id=7",id)
        world=cold(id)
        val changed=executor(world,ChangeRecorder()).projection()!!
        assertEquals(DeploymentFailure.COMMANDER_CHANGED,
            assertIs<DeploymentAssessment.Rejected>(HwihaDeploymentRules.assessActive(first.corps,changed)).reason)
    }

    @Test fun `owner led deployment uses explicit neutral corps and absence remains distinct`() {
        val id=612;fixture.seed(id)
        jdbc.update("UPDATE general_bugok SET commander_retainer_id=NULL WHERE world_id=? AND id=7",id)
        var world=cold(id);val recorder=ChangeRecorder()
        assertTrue(executor(world,recorder).projection()!!.deployed.isEmpty())
        val result=assertIs<DeploymentExecution.Applied>(executor(world,recorder)
            .deploy("neutral-$id",DeploymentRequest(1,null,listOf(7))))
        assertEquals(0,result.corps.nationId);assertEquals(1,result.corps.commanderGeneralId)
        save(world,recorder);world=cold(id)
        val state=executor(world,ChangeRecorder()).projection()!!
        assertIs<DeploymentAssessment.Eligible>(HwihaDeploymentRules.assessActive(result.corps,state))
        jdbc.update("UPDATE general SET meta=jsonb_set(meta,'{hwihaDeployment}','null'::jsonb) WHERE world_id=? AND id=1",id)
        world=cold(id)
        assertNull(executor(world,ChangeRecorder()).projection())
        assertEquals(DeploymentFailure.STATE_UNAVAILABLE,assertIs<DeploymentExecution.Rejected>(
            executor(world,ChangeRecorder()).deploy("other",DeploymentRequest(1,null,listOf(7)))).reason)
    }

    @Test fun `unresolved march encounter blocks both owner and deputy deployment after cold reload`() {
        for ((id, actorId) in listOf(614 to 1,615 to 2)) {
            fixture.seed(id);var world=cold(id);val recorder=ChangeRecorder()
            val topology=bundle.projection.topology;val metrics=bundle.landMarchMetrics
            val source=world.positionOf(actorId)!!
            val edges=StrategicEdgeStateSnapshot(topology.topologyRevision,topology.contentHash,emptyMap())
            val path=topology.landProvinceIds.sorted().asSequence().mapNotNull { province ->
                if(StrategicNodeRef.LandProvince(province)==source) null else
                    (StrategicPathResolver.resolveLandMarch(topology,StrategicPathRequest(source,StrategicNodeRef.LandProvince(province),1),edges,metrics)
                        as? LandMarchPathResult.Resolved)?.path
            }.first()
            val destination=StrategicNodeRef.LandProvince(path.nodeKeys.last().removePrefix("land:"))
            // Position and encounter are explicit fixture preconditions, not a combat simulation.
            assertIs<GeneralPositionChangeResult.Changed>(recorder.moveGeneral(world,actorId,destination))
            val before=world.getGeneralById(actorId)!!
            val march=HwihaMarchState(HwihaCountyAssignment("fixture",10,1,1),path,
                LandMarchCursor(path.pathHash,path.edgeIds.size,0),HwihaPhase(200,1,1),LandMarchStop.ENCOUNTER)
            val after=before.copy(meta=before.meta+(HwihaMarchState.META_KEY to march.toMetaValue()))
            recorder.diffGeneral(PerTurnOverlay.toLogicGeneral(before),PerTurnOverlay.toLogicGeneral(after));world.applyGeneralDirtyFree(after)
            save(world,recorder);world=cold(id)
            assertEquals(DeploymentFailure.BATTLE_PENDING,assertIs<DeploymentExecution.Rejected>(
                executor(world,ChangeRecorder()).deploy("blocked-$id",DeploymentRequest(1,4,listOf(7)))).reason)
        }
    }

    @Test fun `reservation assessment is rechecked before deployment after troop ownership changes`() {
        val id=613;fixture.seed(id)
        var world=cold(id);val request=DeploymentRequest(1,4,listOf(7))
        assertIs<DeploymentAssessment.Eligible>(executor(world,ChangeRecorder()).assess(request))
        jdbc.update("UPDATE general_bugok SET master_general_id=2,commander_retainer_id=NULL WHERE world_id=? AND id=7",id)
        world=cold(id);val before=world.getGeneralById(1)
        assertEquals(DeploymentFailure.UNIT_UNAVAILABLE,assertIs<DeploymentExecution.Rejected>(
            executor(world,ChangeRecorder()).deploy("expired",request)).reason)
        assertEquals(before,world.getGeneralById(1))
    }
}
