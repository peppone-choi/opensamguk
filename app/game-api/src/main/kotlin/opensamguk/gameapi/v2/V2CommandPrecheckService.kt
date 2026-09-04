package opensamguk.gameapi.v2

import opensamguk.gameapi.config.GameApiProcessWorld
import opensamguk.gameapi.precheck.PrecheckStateViewFactory
import opensamguk.infra.v2.V2SandboxGate
import opensamguk.infra.seed.HanStrategicTopologyJson
import opensamguk.logic.constraints.RequirementKey
import opensamguk.logic.domain.City
import opensamguk.logic.v2.command.V2CityTransportArgs
import opensamguk.logic.v2.command.V2CityTransportContext
import opensamguk.logic.v2.command.V2CityTransportDecision
import opensamguk.logic.v2.command.V2CommandAvailability
import opensamguk.logic.v2.command.V2GarrisonRecruitArgs
import opensamguk.logic.v2.command.V2GarrisonRecruitContext
import opensamguk.logic.v2.command.V2GarrisonRecruitDecision
import opensamguk.logic.v2.command.decideCityTransport
import opensamguk.logic.v2.command.decideGarrisonRecruit
import opensamguk.logic.v2.command.resolveImmediateCityTransportRoute
import opensamguk.logic.world.HAN_WORLD_V3_MAP_NAME
import opensamguk.logic.world.HanStrategicRouteProjection
import opensamguk.logic.world.ResolvedStrategicPath
import opensamguk.logic.world.StrategicPathResult
import opensamguk.logic.world.CalcCityDistance
import opensamguk.logic.world.CityConstRegistry
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.beans.factory.annotation.Autowired

@Service
@Profile(V2SandboxGate.PROFILE)
@ConditionalOnProperty(name = [V2SandboxGate.PROPERTY], havingValue = "true", matchIfMissing = false)
class V2CommandPrecheckService(
    private val states: PrecheckStateViewFactory,
    private val jdbc: NamedParameterJdbcTemplate,
    processWorld: GameApiProcessWorld,
    private val loadTopology: () -> HanStrategicRouteProjection,
) {
    @Autowired
    constructor(states: PrecheckStateViewFactory, jdbc: NamedParameterJdbcTemplate, processWorld: GameApiProcessWorld) :
        this(states, jdbc, processWorld, HanStrategicTopologyJson::loadDefault)

    private val worldId = processWorld.worldId

    fun precheck(
        generalId: Int,
        available: V2CommandAvailability.Available,
    ): V2CommandAvailability = when (val args = available.args) {
        is V2GarrisonRecruitArgs -> precheckRecruit(generalId, available, args)
        is V2CityTransportArgs -> precheckTransport(generalId, available, args)
    }

    private fun precheckRecruit(
        generalId: Int,
        available: V2CommandAvailability.Available,
        args: V2GarrisonRecruitArgs,
    ): V2CommandAvailability {
        val state = states.build(generalId, args = mapOf("destCityID" to args.cityId))
        val city = state?.view?.get(RequirementKey.City(args.cityId)) as? City
        val decision = decideGarrisonRecruit(
            args,
            V2GarrisonRecruitContext(
                generalCityId = state?.actor?.cityId,
                generalNationId = state?.actor?.nationId,
                leadership = state?.actor?.leadership,
                cityNationId = city?.nationId,
                cityPopulation = city?.population,
                cityTrust = city?.trust,
                ledgerGold = ledger(args.cityId).gold,
            ),
        )
        return when (decision) {
            is V2GarrisonRecruitDecision.Applied -> available
            is V2GarrisonRecruitDecision.Denied -> V2CommandAvailability.Blocked(decision.code, decision.reason)
        }
    }

    private fun precheckTransport(
        generalId: Int,
        available: V2CommandAvailability.Available,
        args: V2CityTransportArgs,
    ): V2CommandAvailability {
        return when (val decision = evaluateTransport(generalId, args).first) {
            is V2CityTransportDecision.Applied -> available
            is V2CityTransportDecision.Denied -> V2CommandAvailability.Blocked(decision.code, decision.reason)
        }
    }

    fun previewTransport(generalId: Int, args: V2CityTransportArgs): V2CityTransportRoutePreview {
        val (decision, path) = evaluateTransport(generalId, args, preview = true)
        return when (decision) {
            is V2CityTransportDecision.Denied -> V2CityTransportRoutePreview(
                status = "BLOCKED", code = decision.code, reason = decision.reason,
            )
            is V2CityTransportDecision.Applied -> V2CityTransportRoutePreview(
                status = "AVAILABLE", route = path?.let(V2CityTransportRoute::from),
            )
        }
    }

    private fun evaluateTransport(
        generalId: Int,
        args: V2CityTransportArgs,
        preview: Boolean = false,
    ): Pair<V2CityTransportDecision, ResolvedStrategicPath?> {
        val state = states.build(
            generalId,
            args = mapOf("sourceCityID" to args.fromCityId, "destCityID" to args.toCityId),
            requireActiveMap = false,
        )
        val from = state?.view?.get(RequirementKey.City(args.fromCityId)) as? City
        val to = state?.view?.get(RequirementKey.City(args.toCityId)) as? City
        val mapName = state?.env?.get("mapName") as? String
        val strategic = mapName == HAN_WORLD_V3_MAP_NAME
        val route = if (strategic) resolveImmediateCityTransportRoute(args, loadTopology) else null
        val path = (route as? StrategicPathResult.Resolved)?.path
        val decisionArgs = if (preview && path != null) args.copy(
            topologyRevision = path.topologyRevision, routePathHash = path.pathHash,
        ) else args
        val distance = mapName?.takeUnless { strategic }?.let(CityConstRegistry::find)?.let {
            CalcCityDistance.calcCityDistance(args.fromCityId, args.toCityId, cityConst = it)
        }
        val ledger = ledger(args.fromCityId)
        val decision = decideCityTransport(
            decisionArgs,
            V2CityTransportContext(
                generalCityId = state?.actor?.cityId,
                generalNationId = state?.actor?.nationId,
                escortCrew = state?.actor?.crew,
                fromNationId = from?.nationId,
                toNationId = to?.nationId,
                hopDistance = distance,
                fromGold = ledger.gold,
                fromRice = ledger.rice,
                fromGarrison = ledger.garrison,
                requiresStrategicRoute = strategic,
                strategicRoute = route,
            ),
        )
        return decision to path
    }

    private fun ledger(cityId: Int): Ledger = jdbc.query(
        "SELECT gold, rice, garrison FROM v2_city_ledger WHERE world_id = :world_id AND city_id = :city_id",
        MapSqlParameterSource("world_id", worldId.value).addValue("city_id", cityId),
    ) { rs, _ -> Ledger(rs.getLong("gold"), rs.getLong("rice"), rs.getInt("garrison")) }
        .firstOrNull() ?: Ledger(0, 0, 0)

    private data class Ledger(val gold: Long, val rice: Long, val garrison: Int)
}
