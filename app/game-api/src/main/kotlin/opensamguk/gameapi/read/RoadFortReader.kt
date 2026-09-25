package opensamguk.gameapi.read

import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.gameapi.dto.RoadFortDto
import opensamguk.gameapi.dto.RoadGateDto
import opensamguk.gameapi.dto.RoadFortsResponse
import opensamguk.logic.input.LandPassageState
import opensamguk.logic.input.RoadFortState
import opensamguk.logic.world.StrategicNodeRef
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

/** A player sees their own forts and forts on the road touching the selected general's position. */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
class RoadFortReader(
    private val generals: GeneralReadRepository,
    private val worlds: WorldStateReadRepository,
    private val artifacts: ActiveWorldArtifactResolver,
    private val spatial: SpatialStateReadRepository,
    private val gameKv: GameKvReadRepository,
    private val diplomacy: DiplomacyReadRepository,
    private val mapper: ObjectMapper,
) {
    fun forts(generalId: Int, userId: Long): RoadFortsResponse {
        val actor = generals.findById(generalId).orElse(null) ?: throw CampForbidden()
        if (userId <= 0 || userId > Int.MAX_VALUE || actor.userId?.toLongOrNull() != userId) throw CampForbidden()
        val world = worlds.findProcessWorld() ?: return RoadFortsResponse("UNAVAILABLE")
        if (actor.worldId != world.id) return RoadFortsResponse("UNAVAILABLE")
        if (world.config["ruleProfile"] != "HWIHA") return RoadFortsResponse("WRONG_RULE_PROFILE")
        val selected = artifacts.resolve() ?: return RoadFortsResponse("UNAVAILABLE")
        val bundle = selected.artifacts ?: return RoadFortsResponse("UNAVAILABLE")
        val topology = bundle.projection.topology
        val position = (spatial.readSnapshot(world.id, topology).generalPositionSnapshot.stateFor(generalId)?.node
            as? StrategicNodeRef.LandProvince) ?: return RoadFortsResponse("UNAVAILABLE")
        val forts = try { RoadFortState.read(GameEnvStateMeta.overlay(emptyMap(), gameKv, mapper,
            RoadFortState.META_KEY)) }
            catch (_: RuntimeException) { return RoadFortsResponse("UNAVAILABLE") }
        val edges = topology.traversalEdges.associateBy { it.id }
        val enemies = diplomacy.findAll().filter { it.stateCode == 0 }.mapNotNull { relation ->
            when (actor.nationId) {
                relation.srcNationId -> relation.destNationId
                relation.destNationId -> relation.srcNationId
                else -> null
            }
        }.toSet()
        val geography = opensamguk.infra.seed.CountyGeographyJson.load(bundle)
        val ownedProvinces = selected.cities.filter { it.nationId == actor.nationId }
            .flatMap { city -> geography.provincesOfCounty(city.id).ifEmpty {
                setOfNotNull(bundle.projection.bindingsByCityId[city.id]?.landProvinceId)
            } }.toSet()
        val passage = try {
            LandPassageState.read(GameEnvStateMeta.overlay(emptyMap(), gameKv, mapper,
                LandPassageState.META_KEY), topology)
        } catch (_: RuntimeException) { return RoadFortsResponse("UNAVAILABLE") }
        val gates = bundle.projection.presentation?.roadGates.orEmpty().mapNotNull { gate ->
            val edge = edges[gate.edgeId] ?: return@mapNotNull null
            val from = edge.from as? StrategicNodeRef.LandProvince ?: return@mapNotNull null
            val to = edge.to as? StrategicNodeRef.LandProvince ?: return@mapNotNull null
            if (from.id !in ownedProvinces && to.id !in ownedProvinces) return@mapNotNull null
            RoadGateDto(gate.edgeId, from.id, to.id,
                passage?.edgeStates?.get(gate.edgeId)?.active ?: gate.initiallyBuilt,
                gate.buildable, gate.historicalRouteIds, gate.fortCells)
        }
        val shown = forts.mapNotNull { fort ->
            val edge = edges[fort.edgeId] ?: return@mapNotNull null
            val near = position == edge.from || position == edge.to
            if (fort.ownerNationId != actor.nationId && !near && fort.besiegerGeneralId != generalId) return@mapNotNull null
            RoadFortDto(fort.id, fort.edgeId, fort.provinceId, fort.row, fort.col, fort.ownerNationId,
                fort.wall, fort.garrison, fort.besiegerGeneralId, fort.siegeProgress,
                near && fort.ownerNationId in enemies && fort.besiegerGeneralId == null)
        }
        return RoadFortsResponse("READY", shown, gates,
            bundle.projection.presentation?.roadGates?.isNotEmpty() == true)
    }
}
