package opensamguk.logic.v2.command

import opensamguk.common.constants.GameConst
import opensamguk.logic.util.phpRound
import opensamguk.logic.world.StrategicPathResult
import opensamguk.logic.world.TraversalMode
import opensamguk.logic.world.PathDenialCode

const val V2_GARRISON_GOLD_PER_CREW: Double = 0.09
const val V2_TRANSPORT_MAX_GOLD: Long = 50_000
const val V2_TRANSPORT_MAX_RICE: Long = 50_000
const val V2_TRANSPORT_MAX_GARRISON: Int = 50_000
const val V2_TRANSPORT_MIN_ESCORT_CREW: Int = 2_000

data class V2GarrisonRecruitContext(
    val generalCityId: Int?,
    val generalNationId: Int?,
    val leadership: Int?,
    val cityNationId: Int?,
    val cityPopulation: Int?,
    val cityTrust: Double?,
    val ledgerGold: Long,
) {
    companion object {
        fun missingGeneral(): V2GarrisonRecruitContext = V2GarrisonRecruitContext(
            generalCityId = null,
            generalNationId = null,
            leadership = null,
            cityNationId = null,
            cityPopulation = null,
            cityTrust = null,
            ledgerGold = 0,
        )
    }
}

sealed interface V2GarrisonRecruitDecision {
    data class Denied(val code: String, val reason: String) : V2GarrisonRecruitDecision

    data class Applied(
        val goldCost: Long,
        val amount: Int,
        val popAfter: Int,
        val trustAfter: Double,
    ) : V2GarrisonRecruitDecision
}

fun decideGarrisonRecruit(
    args: V2GarrisonRecruitArgs,
    context: V2GarrisonRecruitContext,
): V2GarrisonRecruitDecision {
    val generalCityId = context.generalCityId
        ?: return V2GarrisonRecruitDecision.Denied("GENERAL_NOT_FOUND", "장수를 찾을 수 없습니다.")
    val generalNationId = context.generalNationId
        ?: return V2GarrisonRecruitDecision.Denied("GENERAL_NOT_FOUND", "장수를 찾을 수 없습니다.")
    val leadership = context.leadership
        ?: return V2GarrisonRecruitDecision.Denied("GENERAL_NOT_FOUND", "장수를 찾을 수 없습니다.")
    val cityNationId = context.cityNationId
        ?: return V2GarrisonRecruitDecision.Denied("CITY_NOT_FOUND", "도시를 찾을 수 없습니다.")
    val population = context.cityPopulation
        ?: return V2GarrisonRecruitDecision.Denied("CITY_NOT_FOUND", "도시를 찾을 수 없습니다.")
    val trust = context.cityTrust
        ?: return V2GarrisonRecruitDecision.Denied("CITY_NOT_FOUND", "도시를 찾을 수 없습니다.")
    if (generalCityId != args.cityId) {
        return V2GarrisonRecruitDecision.Denied("ACTOR_CITY_MISMATCH", "다른 도시의 병사를 보충할 수 없습니다.")
    }
    if (cityNationId == 0 || cityNationId != generalNationId) {
        return V2GarrisonRecruitDecision.Denied("CITY_AUTHORITY_DENIED", "자국 도시가 아닙니다.")
    }
    if (args.amount < 100) {
        return V2GarrisonRecruitDecision.Denied("RECRUIT_AMOUNT_TOO_SMALL", "최소 100명부터 보충할 수 있습니다.")
    }
    if (args.amount > leadership * 100) {
        return V2GarrisonRecruitDecision.Denied("RECRUIT_LEADERSHIP_LIMIT", "통솔로 보충할 수 있는 한도를 넘었습니다.")
    }
    if (population - args.amount < GameConst.minAvailableRecruitPop) {
        return V2GarrisonRecruitDecision.Denied("CITY_POPULATION_INSUFFICIENT", "주민이 부족합니다.")
    }
    val goldCost = phpRound(args.amount * V2_GARRISON_GOLD_PER_CREW).toLong()
    if (context.ledgerGold < goldCost) {
        return V2GarrisonRecruitDecision.Denied("CITY_GOLD_INSUFFICIENT", "도시의 금이 부족합니다.")
    }
    return V2GarrisonRecruitDecision.Applied(
        goldCost = goldCost,
        amount = args.amount,
        popAfter = population - args.amount,
        trustAfter = (trust - (args.amount.toDouble() / population) * 100).coerceAtLeast(0.0),
    )
}

data class V2CityTransportContext(
    val generalCityId: Int?,
    val generalNationId: Int?,
    val escortCrew: Int?,
    val fromNationId: Int?,
    val toNationId: Int?,
    val hopDistance: Int?,
    val fromGold: Long,
    val fromRice: Long,
    val fromGarrison: Int,
    val requiresStrategicRoute: Boolean = false,
    val strategicRoute: StrategicPathResult? = null,
)

sealed interface V2CityTransportDecision {
    data class Denied(val code: String, val reason: String) : V2CityTransportDecision
    data class Applied(val gold: Long, val rice: Long, val garrison: Int) : V2CityTransportDecision
}

fun decideCityTransport(args: V2CityTransportArgs, context: V2CityTransportContext): V2CityTransportDecision {
    val generalCityId = context.generalCityId
        ?: return V2CityTransportDecision.Denied("GENERAL_NOT_FOUND", "장수를 찾을 수 없습니다.")
    val generalNationId = context.generalNationId
        ?: return V2CityTransportDecision.Denied("GENERAL_NOT_FOUND", "장수를 찾을 수 없습니다.")
    val escortCrew = context.escortCrew
        ?: return V2CityTransportDecision.Denied("GENERAL_NOT_FOUND", "장수를 찾을 수 없습니다.")
    val fromNationId = context.fromNationId
        ?: return V2CityTransportDecision.Denied("FROM_CITY_NOT_FOUND", "출발 도시를 찾을 수 없습니다.")
    val toNationId = context.toNationId
        ?: return V2CityTransportDecision.Denied("TO_CITY_NOT_FOUND", "도착 도시를 찾을 수 없습니다.")
    if (generalCityId != args.fromCityId) {
        return V2CityTransportDecision.Denied("ACTOR_CITY_MISMATCH", "장수가 있는 도시에서만 수송할 수 있습니다.")
    }
    if (args.fromCityId == args.toCityId) {
        return V2CityTransportDecision.Denied("SAME_CITY", "같은 도시로는 수송할 수 없습니다.")
    }
    if (fromNationId == 0 || fromNationId != generalNationId || toNationId != generalNationId) {
        return V2CityTransportDecision.Denied("CITY_AUTHORITY_DENIED", "자국 도시끼리만 수송할 수 있습니다.")
    }
    if (args.gold < 0 || args.rice < 0 || args.garrison < 0) {
        return V2CityTransportDecision.Denied("TRANSPORT_AMOUNT_NEGATIVE", "수송량은 음수일 수 없습니다.")
    }
    if (args.gold == 0L && args.rice == 0L && args.garrison == 0) {
        return V2CityTransportDecision.Denied("TRANSPORT_AMOUNT_EMPTY", "수송할 자원을 지정해야 합니다.")
    }
    if (context.requiresStrategicRoute) {
        val path = when (val route = context.strategicRoute) {
            null -> return V2CityTransportDecision.Denied("TOPOLOGY_STATE_INVALID", "수송 지형 정보를 확인할 수 없습니다.")
            is StrategicPathResult.Denied -> return V2CityTransportDecision.Denied(route.code.name, route.code.transportReason())
            is StrategicPathResult.Resolved -> route.path
        }
        if (args.topologyRevision.isNullOrBlank()) {
            return V2CityTransportDecision.Denied("TOPOLOGY_REVISION_REQUIRED", "서버에서 수송 경로를 먼저 확인해야 합니다.")
        }
        if (args.routePathHash.isNullOrBlank()) {
            return V2CityTransportDecision.Denied("ROUTE_PATH_HASH_REQUIRED", "확인한 수송 경로 식별값이 필요합니다.")
        }
        if (args.topologyRevision != path.topologyRevision) {
            return V2CityTransportDecision.Denied("TOPOLOGY_REVISION_STALE", "지도 정보가 변경되었습니다. 수송 경로를 다시 확인하세요.")
        }
        if (args.routePathHash != path.pathHash) {
            return V2CityTransportDecision.Denied("ROUTE_PATH_HASH_STALE", "수송 경로가 변경되었습니다. 경로를 다시 확인하세요.")
        }
        if (path.modes.any { it != TraversalMode.LAND }) {
            return V2CityTransportDecision.Denied("TRANSPORT_MODE_UNSUPPORTED", "도하·수운은 다턴 수송이 구현되기 전에는 사용할 수 없습니다.")
        }
        if (path.edgeIds.size != 1 || path.modes.size != 1 || path.nodeKeys.size != 2) {
            return V2CityTransportDecision.Denied("ROUTE_REQUIRES_MULTI_TURN", "즉시 수송은 육로 한 구간만 가능합니다. 다턴 수송은 아직 지원하지 않습니다.")
        }
    } else if (context.hopDistance != 1) {
        return V2CityTransportDecision.Denied("ROUTE_NOT_ADJACENT", "인접한 도시로만 수송할 수 있습니다.")
    }
    if (escortCrew < V2_TRANSPORT_MIN_ESCORT_CREW) {
        return V2CityTransportDecision.Denied("ESCORT_INSUFFICIENT", "수송에는 병사 ${V2_TRANSPORT_MIN_ESCORT_CREW}명이 필요합니다.")
    }
    if (args.gold > V2_TRANSPORT_MAX_GOLD) {
        return V2CityTransportDecision.Denied("TRANSPORT_GOLD_LIMIT", "금은 한 번에 ${V2_TRANSPORT_MAX_GOLD}까지 수송할 수 있습니다.")
    }
    if (args.rice > V2_TRANSPORT_MAX_RICE) {
        return V2CityTransportDecision.Denied("TRANSPORT_RICE_LIMIT", "병량은 한 번에 ${V2_TRANSPORT_MAX_RICE}까지 수송할 수 있습니다.")
    }
    if (args.garrison > V2_TRANSPORT_MAX_GARRISON) {
        return V2CityTransportDecision.Denied("TRANSPORT_GARRISON_LIMIT", "도시병사는 한 번에 ${V2_TRANSPORT_MAX_GARRISON}까지 수송할 수 있습니다.")
    }
    if (context.fromGold < args.gold) return V2CityTransportDecision.Denied("CITY_GOLD_INSUFFICIENT", "도시의 금이 부족합니다.")
    if (context.fromRice < args.rice) return V2CityTransportDecision.Denied("CITY_RICE_INSUFFICIENT", "도시의 병량이 부족합니다.")
    if (context.fromGarrison < args.garrison) return V2CityTransportDecision.Denied("CITY_GARRISON_INSUFFICIENT", "도시의 병사가 부족합니다.")
    return V2CityTransportDecision.Applied(args.gold, args.rice, args.garrison)
}

private fun PathDenialCode.transportReason(): String = when (this) {
    PathDenialCode.NO_LAND_CONNECTION -> "연결된 육로가 없습니다."
    PathDenialCode.RIVER_CROSSING_REQUIRED -> "강을 건널 수 있는 검증된 통과점이 필요합니다."
    PathDenialCode.NO_EMBARK_POINT -> "승선 또는 하선할 수 있는 지점이 없습니다."
    PathDenialCode.NO_TRANSPORT_CAPACITY -> "수송 경로의 가용 용량이 부족합니다."
    PathDenialCode.WATERWAY_BLOCKED -> "물길이 봉쇄되었거나 현재 통행할 수 없습니다."
    PathDenialCode.TOPOLOGY_REVISION_STALE -> "지도 정보가 변경되었습니다. 수송 경로를 다시 확인하세요."
    PathDenialCode.TOPOLOGY_STATE_INVALID -> "수송 지형 정보 검증에 실패했습니다."
    PathDenialCode.UNKNOWN_NODE -> "출발지 또는 도착지가 수송 지도에 연결되지 않았습니다."
}
