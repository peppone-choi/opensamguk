package opensamguk.gameapi.read

import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.gameapi.dto.HwihaRoadFortDto
import opensamguk.gameapi.dto.HwihaRoadGateDto
import opensamguk.gameapi.dto.HwihaRoadFortsResponse
import opensamguk.logic.input.HwihaLandPassageState
import opensamguk.logic.input.HwihaRoadFortState
import opensamguk.logic.world.StrategicNodeRef
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

/** A player sees their own forts and forts on the road touching the selected general's position. */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
class HwihaRoadFortReader(
    private val generals: GeneralReadRepository,
    private val worlds: WorldStateReadRepository,
    private val artifacts: ActiveWorldArtifactResolver,
    private val spatial: SpatialStateReadRepository,
    private val gameKv: GameKvReadRepository,
    private val diplomacy: DiplomacyReadRepository,
    private val mapper: ObjectMapper,
) {
    fun forts(generalId: Int, userId: Long): HwihaRoadFortsResponse {
        val actor = generals.findById(generalId).orElse(null) ?: throw HwihaCampForbidden()
        if (userId <= 0 || userId > Int.MAX_VALUE || actor.userId?.toLongOrNull() != userId) throw HwihaCampForbidden()
        val world = worlds.findProcessWorld() ?: return HwihaRoadFortsResponse("UNAVAILABLE")
        if (actor.worldId != world.id) return HwihaRoadFortsResponse("UNAVAILABLE")
        if (world.config["ruleProfile"] != "HWIHA") return HwihaRoadFortsResponse("WRONG_RULE_PROFILE")
        val selected = artifacts.resolve() ?: return HwihaRoadFortsResponse("UNAVAILABLE")
        val bundle = selected.artifacts ?: return HwihaRoadFortsResponse("UNAVAILABLE")
        val topology = bundle.projection.topology
        val position = (spatial.readSnapshot(world.id, topology).generalPositionSnapshot.stateFor(generalId)?.node
            as? StrategicNodeRef.LandProvince) ?: return HwihaRoadFortsResponse("UNAVAILABLE")
        val raw = gameKv.findByTableAndNamespaceAndKey("game_env", "game_env", HwihaRoadFortState.META_KEY)?.value
        val forts = try { if (raw == null) emptyList() else HwihaRoadFortState.read(mapOf(
            HwihaRoadFortState.META_KEY to mapper.readValue(raw, Map::class.java))) }
            catch (_: RuntimeException) { return HwihaRoadFortsResponse("UNAVAILABLE") }
        val edges = topology.traversalEdges.associateBy { it.id }
        val enemies = diplomacy.findAll().filter { it.stateCode == 0 }.mapNotNull { relation ->
            when (actor.nationId) {
                relation.srcNationId -> relation.destNationId
                relation.destNationId -> relation.srcNationId
                else -> null
            }
        }.toSet()
        val geography = opensamguk.infra.seed.HwihaCountyGeographyJson.load(bundle)
        val ownedProvinces = selected.cities.filter { it.nationId == actor.nationId }
            .flatMap { city -> geography.provincesOfCounty(city.id).ifEmpty {
                setOfNotNull(bundle.projection.bindingsByCityId[city.id]?.landProvinceId)
            } }.toSet()
        val passageRaw = gameKv.findByTableAndNamespaceAndKey("game_env", "game_env", HwihaLandPassageState.META_KEY)?.value
        val passage = try {
            passageRaw?.let { serialized ->
                val decoded = mapper.readValue(serialized, Map::class.java)
                HwihaLandPassageState.read(mapOf(HwihaLandPassageState.META_KEY to decoded), topology)
            }
        } catch (_: RuntimeException) { return HwihaRoadFortsResponse("UNAVAILABLE") }
        val gates = bundle.projection.presentation?.roadGates.orEmpty().mapNotNull { gate ->
            val edge = edges[gate.edgeId] ?: return@mapNotNull null
            val from = edge.from as? StrategicNodeRef.LandProvince ?: return@mapNotNull null
            val to = edge.to as? StrategicNodeRef.LandProvince ?: return@mapNotNull null
            if (from.id !in ownedProvinces && to.id !in ownedProvinces) return@mapNotNull null
            HwihaRoadGateDto(gate.edgeId, from.id, to.id,
                passage?.edgeStates?.get(gate.edgeId)?.active ?: gate.initiallyBuilt,
                gate.buildable, gate.historicalRouteIds, gate.fortCells)
        }
        val shown = forts.mapNotNull { fort ->
            val edge = edges[fort.edgeId] ?: return@mapNotNull null
            val near = position == edge.from || position == edge.to
            if (fort.ownerNationId != actor.nationId && !near && fort.besiegerGeneralId != generalId) return@mapNotNull null
            HwihaRoadFortDto(fort.id, fort.edgeId, fort.provinceId, fort.row, fort.col, fort.ownerNationId,
                fort.wall, fort.garrison, fort.besiegerGeneralId, fort.siegeProgress,
                near && fort.ownerNationId in enemies && fort.besiegerGeneralId == null)
        }
        return HwihaRoadFortsResponse("READY", shown, gates,
            bundle.projection.presentation?.roadGates?.isNotEmpty() == true)
    }
}
