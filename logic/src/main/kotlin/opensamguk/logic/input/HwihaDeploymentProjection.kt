package opensamguk.logic.input

import opensamguk.logic.world.*

/** Source identity and metadata; ownership classification is supplied by the server adapter. */
data class DeploymentPersonSource(val id: Int, val nationId: Int, val isUnownedNpc: Boolean,
    val meta: Map<String, Any?>)

/** Shared API/engine projection. Corruption stays unavailable, never an empty battlefield. */
object HwihaDeploymentProjection {
    fun build(profile: RuleProfile, people: List<DeploymentPersonSource>, units: List<DeploymentUnit>,
        retainers: List<DeploymentRetainer>, positions: GeneralPositionSnapshot?,
        topology: StrategicTopologySnapshot, metrics: LandMarchMetricSnapshot): DeploymentProjection? {
        if (positions == null) return null
        return try {
            require(positions.topologyRevision == topology.topologyRevision && positions.topologyHash == topology.contentHash)
            require(positions.knownLandProvinceIds == topology.landProvinceIds &&
                positions.knownWaterZoneIds == topology.waterZones.map { it.id }.toSet())
            val orderedPeople = people.sortedBy { it.id }
            val corps = orderedPeople.flatMap { person ->
                HwihaDeploymentState.read(person.meta)?.corps.orEmpty().also { rows ->
                    require(rows.all { it.ownerGeneralId == person.id })
                }
            }
            require(corps.map { it.orderId }.distinct().size == corps.size)
            require(corps.map { it.commanderGeneralId }.distinct().size == corps.size)
            require(corps.flatMap { it.bugokIds }.distinct().size == corps.sumOf { it.bugokIds.size })
            DeploymentProjection(profile, orderedPeople.map { person ->
                val position = positions.stateFor(person.id)
                val march = HwihaMarchState.read(person.meta, topology, metrics)
                require(march == null || march.path.nodeKeys[march.cursor.edgeIndex] == position?.node?.canonicalKey)
                val corpsMarch = HwihaCorpsMarchState.read(person.meta, topology, metrics)
                val order = HwihaCorpsOrder.read(person.meta, topology)
                if (order != null) {
                    val deployed = corps.singleOrNull { it.commanderGeneralId == person.id }
                        ?: throw IllegalArgumentException("Corps destination has no deployment")
                    order.requireBinding(deployed, person.id)
                    require(corpsMarch == null || corpsMarch.checkpoint.path.nodeKeys.last() == order.destination.canonicalKey) {
                        "Corps path destination differs from durable order"
                    }
                }
                if (corpsMarch != null) {
                    val deployed = corps.singleOrNull { it.commanderGeneralId == person.id }
                        ?: throw IllegalArgumentException("Corps march has no deployment")
                    corpsMarch.requireBinding(deployed, person.id)
                    val checkpoint = corpsMarch.checkpoint
                    require(checkpoint.path.nodeKeys[checkpoint.cursor.edgeIndex] == position?.node?.canonicalKey)
                    require(march == null) { "Assignment and corps marches cannot own the same position" }
                }
                DeploymentPerson(person.id, person.nationId, person.isUnownedNpc,
                    position?.node, position?.battlefield != null || march?.stop == LandMarchStop.ENCOUNTER ||
                        corpsMarch?.checkpoint?.stop == LandMarchStop.ENCOUNTER)
            }, units, retainers, corps)
        } catch (_: IllegalArgumentException) { null }
    }
}
