package opensamguk.logic.input

import opensamguk.logic.world.StrategicEdgeStateSnapshot
import opensamguk.logic.world.StrategicNodeRef
import opensamguk.logic.world.StrategicRoadGate
import opensamguk.logic.world.StrategicTopologySnapshot

/** The same road/fort site test is used at reservation and immediately before execution. */
data class HwihaInfrastructureSiteState(
    val topology: StrategicTopologySnapshot?,
    val roadGates: List<StrategicRoadGate>,
    val passage: StrategicEdgeStateSnapshot?,
    val forts: List<HwihaRoadFort>,
)

object HwihaInfrastructureSiteRules {
    fun error(request: WorkRequest, state: HwihaDomesticProjection,
        infrastructure: HwihaInfrastructureSiteState): String? {
        if (request.work !in setOf(DomesticWork.ROAD, DomesticWork.FORTIFICATION)) return null
        if (request.work == DomesticWork.FORTIFICATION && request.edgeId == null &&
            request.row == null && request.col == null) return null // County wall.
        if (infrastructure.roadGates.isEmpty()) {
            // Older maps use ROAD as county commerce work and have no strategic road gates.
            if (request.work == DomesticWork.ROAD && request.edgeId == null &&
                request.row == null && request.col == null) return null
            return "이 지도에는 도로 공사 자리가 없습니다."
        }
        val gate = infrastructure.roadGates.singleOrNull { it.edgeId == request.edgeId }
            ?: return "지도에 등록된 도로 접경을 골라 주세요."
        val countyProvinces = state.provinceIdsByCounty[request.countyId].orEmpty() +
            listOfNotNull(state.county(request.countyId)?.provinceId)
        if (countyProvinces.isEmpty()) return "공사할 현의 지도 구역을 찾을 수 없습니다."
        val topology = infrastructure.topology ?: return "도로 위상 자료를 읽을 수 없습니다."
        val edge = topology.traversalEdges.singleOrNull { it.id == gate.edgeId }
            ?: return "도로 접경의 위상 자료를 읽을 수 없습니다."
        if (listOf(edge.from, edge.to).none { it is StrategicNodeRef.LandProvince && it.id in countyProvinces })
            return "해당 현에 닿는 도로만 공사할 수 있습니다."
        val active = infrastructure.passage?.edgeStates?.get(gate.edgeId)?.active
            ?: return "도로 통행 상태가 비어 있습니다."
        if (request.work == DomesticWork.ROAD) {
            if (!gate.buildable) return "성 자리와 이어지지 않는 접경입니다. 나루나 별도 도하가 필요합니다."
            if (request.row != null || request.col != null) return "도로 개척에는 접경만 지정해 주세요."
            if (active) return "이미 열린 도로입니다."
        } else {
            if (!active) return "보루는 개통된 도로에만 세울 수 있습니다."
            if (request.row == null || request.col == null || gate.fortCells.none {
                    it.provinceId in countyProvinces && it.row == request.row && it.col == request.col
                }) return "보루는 자기 현의 도로 칸 또는 인접한 마른땅 칸에 세워야 합니다."
            if (infrastructure.forts.any { it.row == request.row && it.col == request.col })
                return "이미 보루가 있는 칸입니다."
        }
        return null
    }
}
