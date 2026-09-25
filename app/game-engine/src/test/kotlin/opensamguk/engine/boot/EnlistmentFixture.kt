package opensamguk.engine.boot

import java.nio.file.Path
import opensamguk.common.world.WorldId
import opensamguk.engine.turn.*
import opensamguk.infra.persistence.JdbcFlushExecutor
import opensamguk.infra.seed.HanWorldArtifactsResolver
import opensamguk.logic.world.HanWorldVariant
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

/** Synthetic people on the actual archived map; shared only by database boundary tests. */
internal class HwihaEnlistmentFixture(private val jdbc: JdbcTemplate, private val flush: JdbcFlushExecutor) {
    private val artifacts = HanWorldArtifactsResolver(Path.of("../.."))
    private val bundle by lazy { artifacts.artifacts(HanWorldVariant.V3_1133) }
    fun seed(id: Int) {
        jdbc.update("""INSERT INTO world_state(id,scenario_code,current_year,current_month,tick_seconds,config,meta)
            VALUES (?, 'enlistment-storage-test',200,1,3600,
            '{"mapName":"han-world-v3","ruleProfile":"HWIHA"}'::jsonb,
            '{"lastTurnTime":"0200-01-01T00:00:00Z"}'::jsonb)""", id)
        jdbc.batchUpdate("""INSERT INTO city(world_id,id,name,level,nation_id,pop,pop_max,agri,agri_max,comm,comm_max,
            secu,secu_max,def,def_max,wall,wall_max,region) VALUES (?,?,?,1,0,100,1000,10,1000,10,1000,10,1000,10,1000,10,1000,1)""",
            bundle.cityConst.all().keys.map { arrayOf<Any>(id, it, "fixture-$it") })
        val binding = bundle.projection.bindingsByCityId.entries.sortedBy { it.key }.first { it.value.landProvinceId != null }
        jdbc.update("INSERT INTO nation(world_id,id,name,color,gold,capital_city_id,meta) VALUES (?,1,'주공국','#000000',500,?,'{\"gennum\":1,\"keep\":42}'::jsonb)", id, binding.key)
        for ((generalId, nation, npc) in listOf(Triple(1, 0, 0), Triple(2, 0, 2), Triple(10, 1, 2))) {
            jdbc.update("""INSERT INTO general(world_id,id,name,nation_id,city_id,npc_state,officer_level,gold,rice,crew,leadership,strength,intel,politics,charm,turn_time,last_turn,meta)
                VALUES (?,?,?,?,?,?,?,1000,2000,300,70,70,70,70,70,'0200-01-01T00:00:00Z','{"command":"휴식"}'::jsonb,?::jsonb)""",
                id, generalId, "G$generalId", nation, binding.key, npc, if (generalId == 10) 12 else 0,
                """{"hwihaLord":${generalId != 2},"keep":"unchanged","hwihaPersonPolicy":{
                    "renownCapacity":30,"acceptsEnlistment":true,"statSourceId":"synthetic-storage-fixture",
                    "statSourceRevision":"fixture-v1","officerId":$generalId}}""")
            val topology = bundle.projection.topology
            jdbc.update("""INSERT INTO general_spatial_position(world_id,general_id,topology_revision,topology_hash,node_kind,node_id,revision)
                VALUES (?,?,?,?,'LAND_PROVINCE',?,1)""", id, generalId, topology.topologyRevision, topology.contentHash, binding.value.landProvinceId)
        }
        jdbc.update("""INSERT INTO general_retainers(world_id,id,master_general_id,origin,general_id,name,relation,has_own_bugok,release_policy)
            VALUES (?,4,1,'EXISTING',2,'G2','lieutenant',true,'MUTUAL')""", id)
        jdbc.update("""INSERT INTO general_bugok(world_id,id,master_general_id,name,troops,crew_type_id,training,morale,provisions,commander_retainer_id)
            VALUES (?,7,1,'personal',100,1,50,50,200,4)""", id)
    }
    fun load(id: Int) = WorldSnapshotLoader(jdbc, SeedBootstrap(seedEnabled = false, worldId = WorldId(id)), WorldId(id),
        waterTopologyLoader = { artifacts.artifacts(it).projection.topology },
        hanVariantSelector = { ids, pins -> artifacts.resolve(ids, pins).variant },
        administrativeCountyIdsLoader = { artifacts.artifacts(it).projection.administrativeCountyIds },
        cityLandProvinceLoader = { variant -> artifacts.artifacts(variant).projection.bindingsByCityId
            .mapNotNull { (city, binding) -> binding.landProvinceId?.let { city to it } }.toMap() }).buildSnapshot()
    fun service(id: WorldId, active: InMemoryTurnWorld, published: MutableList<String>, intake: Boolean = false, movement: Boolean = false): opensamguk.engine.run.TurnRunService {
        val reservations = opensamguk.infra.persistence.ReservedTurnRepository(NamedParameterJdbcTemplate(jdbc))
        val redis = org.mockito.Mockito.mock(org.springframework.data.redis.core.StringRedisTemplate::class.java)
        val recorder = ChangeRecorder()
        val deploymentContext = if (movement) bundle.projection.topology to bundle.landMarchMetrics else null
        val handler = ReservedTurnHandler(active,
            opensamguk.logic.actions.CommandRegistry(opensamguk.logic.stats.GeneralActionPipeline()), "00", 200,
            recorder=recorder,hwihaDeploymentContext=deploymentContext)
        val lifecycle = TurnDaemonLifecycle(active, handler,
            pullGeneralTurnOf = { handler.recorder.recordGeneralTurnPull(it) },
            hwihaMovementOf = if (movement) opensamguk.engine.hwiha.HwihaAssignmentMarchTurn(active, handler.recorder,
                bundle.projection.topology, bundle.landMarchMetrics, bundle.provinceCells)::onTurn else { _, _, _ -> },
            reservedActionOf = { reservations.readReserved(id, it, 0) })
        val stream = object : opensamguk.engine.redis.RedisCommandStream(redis, "fixture", id, startId = "0") {
            override fun readEnvelopes(blockMs: Long) = emptyList<opensamguk.common.wire.TurnDaemonCommandEnvelope>()
        }
        val publisher = object : opensamguk.engine.redis.RealtimePublisher(redis, "fixture", id) {
            override fun publishCommandResultPayload(requestId: String, payloadJson: String) { published += requestId }
        }
        return opensamguk.engine.run.TurnRunService(active, stream, lifecycle, handler, flush, publisher,
            auctionRepository = if (intake) org.mockito.Mockito.mock(opensamguk.infra.read.AuctionRepository::class.java) else null,
            auctionBidRepository = if (intake) org.mockito.Mockito.mock(opensamguk.infra.read.AuctionBidRepository::class.java) else null,
            boardPostRepository = if (intake) org.mockito.Mockito.mock(opensamguk.infra.read.BoardPostRepository::class.java) else null,
            commandInboxRepository = if (intake) opensamguk.infra.persistence.CommandInboxRepository(NamedParameterJdbcTemplate(jdbc)) else null,
            commandOutboxRelay = opensamguk.engine.redis.CommandOutboxRelay(
                opensamguk.infra.persistence.CommandResultRepository(NamedParameterJdbcTemplate(jdbc)), publisher, id))
    }

}
