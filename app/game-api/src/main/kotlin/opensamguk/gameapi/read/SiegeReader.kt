package opensamguk.gameapi.read

import opensamguk.gameapi.dto.*
import opensamguk.logic.economy.CountyWarehouse
import opensamguk.logic.input.DeploymentState
import opensamguk.logic.input.CorpsEncounter
import opensamguk.logic.input.CorpsMarchState
import opensamguk.logic.input.DeployedCorps
import opensamguk.logic.input.CityMilitaryState
import opensamguk.logic.input.MarchState
import opensamguk.logic.war.SiegeRules
import opensamguk.logic.world.StrategicNodeRef
import opensamguk.logic.world.LandMarchStop
import opensamguk.infra.seed.UnitProfilesJson
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

/**
 * 공성 조회(읽기 전용). 인증은 휘하 화면 조회([CampReader])와 같다: `?generalId=` 장수의 `userId` 가
 * principal 과 같아야 하고 아니면 [CampForbidden](403). 보이는 포위는 **관여한 것**이다 — 조회 장수가
 * 포위 지휘관이거나, 그 장수의 세력이 포위·수비 세력인 행. 시야 규칙이 생기기 전에는 남의 포위를 보여 주지 않는다.
 * 판정 값(항복 권고 문턱·급식)은 엔진과 같은 [SiegeRules] 를 부른다.
 */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
class SiegeReader(
    private val generals: GeneralReadRepository,
    private val worlds: WorldStateReadRepository,
    private val nations: NationReadRepository,
    private val cities: CityReadRepository,
    private val retainers: RetainerReadRepository,
    private val sieges: SiegeReadRepository,
    private val artifacts: ActiveWorldArtifactResolver,
    private val spatial: SpatialStateReadRepository,
    private val diplomacy: DiplomacyReadRepository,
) {
    fun sieges(generalId: Int, userId: Long): SiegesResponse {
        val actor = generals.findById(generalId).orElse(null) ?: throw CampForbidden()
        if (userId <= 0 || userId > Int.MAX_VALUE || actor.userId?.toLongOrNull() != userId) throw CampForbidden()
        val world = worlds.findProcessWorld() ?: return SiegesResponse("UNAVAILABLE")
        if (actor.worldId != world.id) return SiegesResponse("UNAVAILABLE")
        if (runCatching { opensamguk.logic.world.WorldFormat.require(world.config, world.meta) }.isFailure) return SiegesResponse("UNSUPPORTED_WORLD_FORMAT")
        val nationNames = nations.findAll().associate { it.id to it.name }
        val visible = sieges.involving(actor.id, actor.nationId)
        val activeForActor = visible.count { it.status == "ACTIVE" && it.besiegerGeneralId == actor.id }
        val rows = visible.map { row ->
            val city = cities.findById(row.countyId).orElse(null)
            val besieger = generals.findById(row.besiegerGeneralId).orElse(null)
            val grain = city?.let { runCatching { CountyWarehouse.read(it.meta, it.id)?.stock?.grain }.getOrNull() }
            val corps = corpsOf(row)
            val troops = corps?.units?.sumOf { it.troops }
            val fed = corps?.units?.all { SiegeRules.besiegerFed(it.troops, it.provisions) }
            val active = row.status == "ACTIVE"
            val trust = city?.trust ?: 0.0
            val assaultBlock = if (active && row.besiegerGeneralId == actor.id && activeForActor != 1)
                SiegeRules.AssaultBlock.STATE_UNAVAILABLE.let { it.name to it.message }
                else if (active && row.besiegerGeneralId == actor.id)
                assaultBlock(actor, world, row, city, corps) else null
            SiegeDto(
                countyId = row.countyId, countyName = city?.name, status = row.status, endReason = row.endReason,
                besieger = SiegePartyDto(row.besiegerGeneralId, besieger?.name, row.besiegerNationId, nationNames[row.besiegerNationId]),
                defenderNationId = row.defenderNationId, defenderNationName = nationNames[row.defenderNationId],
                startedAt = SiegePhaseDto(row.startedYear, row.startedMonth, row.startedPhase), turns = row.turns,
                grain = grain, morale = row.morale, garrison = row.garrison, trust = trust,
                countySupplied = (city?.supplyState ?: 0) != 0, besiegerTroops = troops, besiegerFed = fed,
                canAct = active && row.besiegerGeneralId == actor.id,
                canAssault = active && row.besiegerGeneralId == actor.id && assaultBlock == null,
                assaultCode = assaultBlock?.first,
                assaultReason = assaultBlock?.second,
                surrenderDemandAccepted = active && SiegeRules.surrenderDemandAccepted(row.morale, trust),
                timeline = row.timeline,
            )
        }
        return SiegesResponse("READY", rows)
    }

    /** 포위 군단(주인의 출전 기록 → 부곡)의 병력과 급식 판정. 읽을 수 없으면 (null, null). */
    private data class SiegeCorps(val corps: DeployedCorps, val units: List<GeneralBugokReadEntity>)

    private fun corpsOf(row: SiegeReadRow): SiegeCorps? {
        val owner = generals.findById(row.besiegerOwnerGeneralId).orElse(null) ?: return null
        val corps = runCatching { DeploymentState.read(owner.meta) }.getOrNull()?.corps
            ?.singleOrNull { it.orderId == row.besiegerOrderId } ?: return null
        val units = retainers.bugoksOf(owner.id).filter { it.id in corps.bugokIds }
        if (units.size != corps.bugokIds.size) return null
        return SiegeCorps(corps, units)
    }

    private fun assaultBlock(actor: GeneralReadEntity, world: WorldStateReadEntity, row: SiegeReadRow,
        city: CityReadEntity?, siegeCorps: SiegeCorps?): Pair<String, String>? {
        fun block(code: String, reason: String) = code to reason
        val unavailable = block("STATE_UNAVAILABLE", SiegeRules.AssaultBlock.STATE_UNAVAILABLE.message)
        val selected = runCatching { artifacts.resolve() }.getOrNull() ?: return unavailable
        if (selected.world.id != world.id) return unavailable
        val bundle = selected.artifacts ?: return unavailable
        val projection = bundle.projection
        val provinceId = projection.bindingsByCityId[row.countyId]?.landProvinceId ?: return unavailable
        val position = runCatching { spatial.readSnapshot(world.id, projection.topology)
            .generalPositionSnapshot.stateFor(actor.id) }.getOrNull() ?: return unavailable
        val inBattle = runCatching {
            val metrics = bundle.landMarchMetrics
            position.battlefield != null || CorpsEncounter.read(actor.meta, projection.topology) != null ||
                MarchState.read(actor.meta, projection.topology, metrics)?.stop == LandMarchStop.ENCOUNTER ||
                CorpsMarchState.read(actor.meta, projection.topology, metrics)?.checkpoint?.stop == LandMarchStop.ENCOUNTER
        }.getOrElse { return unavailable }
        val corps = siegeCorps?.corps
        val corpsMatches = corps != null && corps.ownerGeneralId == row.besiegerOwnerGeneralId &&
            corps.commanderGeneralId == actor.id && corps.orderId == row.besiegerOrderId &&
            corps.nationId == row.besiegerNationId && actor.nationId == corps.nationId
        val atTarget = position.node == StrategicNodeRef.LandProvince(provinceId)
        val hostile = if (city == null || corps == null) null else {
            val wars = runCatching { diplomacy.findAll() }.getOrNull() ?: return unavailable
            val atWar = city.nationId == 0 || wars.any { it.stateCode == 0 &&
                ((it.srcNationId == corps.nationId && it.destNationId == city.nationId) ||
                    (it.destNationId == corps.nationId && it.srcNationId == city.nationId)) }
            city.nationId == row.defenderNationId && corps.nationId > 0 &&
                corps.nationId != city.nationId && atWar && atTarget
        }
        SiegeRules.assaultReadiness(row.countyId, row.countyId, row.turns, inBattle, corpsMatches, hostile)
            ?.let { return block(it.name, it.message) }
        val selectedCorps = siegeCorps ?: return unavailable
        val cells = runCatching { bundle.provinceCells }.getOrNull() ?: return unavailable
        val layout = SiegeRules.assaultLayout(cells, provinceId, row.approachProvinceId)
            ?: return block("BATTLEFIELD_UNAVAILABLE", "이 縣의 전장을 만들 수 없어 강공할 수 없습니다.")
        val profiles = runCatching { UnitProfilesJson.loadDefault() }.getOrNull() ?: return unavailable
        if (selectedCorps.units.any { profiles.find(it.crewTypeId) == null })
            return block("UNIT_UNAVAILABLE", "강공에 쓸 수 있는 병종이 아닌 부대가 있습니다.")
        if (selectedCorps.units.isEmpty()) return unavailable
        val military = runCatching { CityMilitaryState.read(requireNotNull(city).meta,
            city.defense.coerceAtLeast(0)) }.getOrNull() ?: return unavailable
        SiegeRules.assaultApproachReadiness(layout,
            selectedCorps.units.associate { it.id to requireNotNull(profiles.find(it.crewTypeId)) }, military.troops)
            ?.let { return block(it.name, it.message) }
        return null
    }
}
