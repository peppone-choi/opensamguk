package opensamguk.logic.v2.command

import opensamguk.logic.world.HanStrategicRouteProjection
import opensamguk.logic.world.PathDenialCode
import opensamguk.logic.world.StrategicEdgeStateSnapshot
import opensamguk.logic.world.StrategicPathResult

/**
 * Wave3 has no water/crossing execution or mutable edge scheduler. Resolve one convoy request
 * against the validated immutable snapshot; the decision still rejects everything but one LAND edge.
 * API preview, precheck and daemon execution must all use this same projection and state policy.
 */
fun resolveImmediateCityTransportRoute(
    args: V2CityTransportArgs,
    loadTopology: () -> HanStrategicRouteProjection,
): StrategicPathResult = try {
    val projection = loadTopology()
    projection.resolve(
        args.fromCityId, args.toCityId, requiredCapacity = 1,
        state = StrategicEdgeStateSnapshot(
            projection.topology.topologyRevision, projection.topology.contentHash, emptyMap(),
        ),
    )
} catch (_: Exception) {
    // No CityConst or ordinal fallback when artifacts are missing, malformed or mismatched.
    StrategicPathResult.Denied(PathDenialCode.TOPOLOGY_STATE_INVALID)
}
