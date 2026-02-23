package com.opensam.command.constraint

import com.opensam.entity.City
import java.util.ArrayDeque

fun NotBeNeutral() = object : Constraint {
    override val name = "NotBeNeutral"
    override fun test(ctx: ConstraintContext): ConstraintResult {
        return if (ctx.general.nationId == 0L) ConstraintResult.Fail("소속 국가가 없습니다.")
        else ConstraintResult.Pass
    }
}

fun SuppliedCity() = object : Constraint {
    override val name = "SuppliedCity"
    override fun test(ctx: ConstraintContext): ConstraintResult {
        val city = ctx.city ?: return ConstraintResult.Fail("도시 정보가 없습니다.")
        return if (city.supplyState > 0) ConstraintResult.Pass
        else ConstraintResult.Fail("보급이 끊긴 도시입니다.")
    }
}

fun NotWanderingNation() = object : Constraint {
    override val name = "NotWanderingNation"
    override fun test(ctx: ConstraintContext): ConstraintResult {
        return if (ctx.general.nationId == 0L) ConstraintResult.Fail("방랑 중입니다.")
        else ConstraintResult.Pass
    }
}

fun ReqGeneralGold(amount: Int) = object : Constraint {
    override val name = "ReqGeneralGold"
    override fun test(ctx: ConstraintContext): ConstraintResult {
        return if (ctx.general.gold >= amount) ConstraintResult.Pass
        else ConstraintResult.Fail("자금이 부족합니다. (필요: $amount, 보유: ${ctx.general.gold})")
    }
}

fun ReqGeneralRice(amount: Int) = object : Constraint {
    override val name = "ReqGeneralRice"
    override fun test(ctx: ConstraintContext): ConstraintResult {
        return if (ctx.general.rice >= amount) ConstraintResult.Pass
        else ConstraintResult.Fail("군량이 부족합니다. (필요: $amount, 보유: ${ctx.general.rice})")
    }
}

fun ReqGeneralCrew(minCrew: Int = 1) = object : Constraint {
    override val name = "ReqGeneralCrew"
    override fun test(ctx: ConstraintContext): ConstraintResult {
        return if (ctx.general.crew >= minCrew) ConstraintResult.Pass
        else ConstraintResult.Fail("병사가 부족합니다. (필요: $minCrew)")
    }
}

fun RemainCityCapacity(cityKey: String, actionName: String) = object : Constraint {
    override val name = "RemainCityCapacity"
    override fun test(ctx: ConstraintContext): ConstraintResult {
        val city = ctx.city ?: return ConstraintResult.Fail("도시 정보가 없습니다.")
        val current = when (cityKey) {
            "agri" -> city.agri
            "comm" -> city.comm
            "secu" -> city.secu
            "def" -> city.def
            "wall" -> city.wall
            "pop" -> city.pop
            else -> return ConstraintResult.Pass
        }
        val max = when (cityKey) {
            "agri" -> city.agriMax
            "comm" -> city.commMax
            "secu" -> city.secuMax
            "def" -> city.defMax
            "wall" -> city.wallMax
            "pop" -> city.popMax
            else -> Int.MAX_VALUE
        }
        return if (current < max) ConstraintResult.Pass
        else ConstraintResult.Fail("${actionName}이(가) 최대치에 도달했습니다.")
    }
}

fun BeChief() = object : Constraint {
    override val name = "BeChief"
    override fun test(ctx: ConstraintContext): ConstraintResult {
        return if (ctx.general.officerLevel >= 12.toShort()) ConstraintResult.Pass
        else ConstraintResult.Fail("군주만 사용할 수 있습니다.")
    }
}

fun ReqOfficerLevel(minLevel: Int) = object : Constraint {
    override val name = "ReqOfficerLevel"
    override fun test(ctx: ConstraintContext): ConstraintResult {
        return if (ctx.general.officerLevel >= minLevel.toShort()) ConstraintResult.Pass
        else ConstraintResult.Fail("관직 레벨이 부족합니다. (필요: $minLevel)")
    }
}

fun NotSameDestCity() = object : Constraint {
    override val name = "NotSameDestCity"
    override fun test(ctx: ConstraintContext): ConstraintResult {
        val destCity = ctx.destCity ?: return ConstraintResult.Fail("목적지 도시 정보가 없습니다.")
        return if (ctx.general.cityId != destCity.id) ConstraintResult.Pass
        else ConstraintResult.Fail("현재 도시와 같은 도시입니다.")
    }
}

fun NearCity(maxDistance: Int) = object : Constraint {
    override val name = "NearCity"
    override fun test(ctx: ConstraintContext): ConstraintResult {
        val destCity = ctx.destCity ?: return ConstraintResult.Fail("목적지 도시 정보가 없습니다.")
        val fromCityId = ctx.general.cityId
        val distance = shortestDistance(ctx, fromCityId, destCity.id)
        if (distance >= 0 && distance <= maxDistance) {
            return ConstraintResult.Pass
        }
        return if (maxDistance == 1) ConstraintResult.Fail("인접도시가 아닙니다.")
        else ConstraintResult.Fail("거리가 너무 멉니다.")
    }
}

fun NotOccupiedDestCity() = object : Constraint {
    override val name = "NotOccupiedDestCity"
    override fun test(ctx: ConstraintContext): ConstraintResult {
        val destCity = ctx.destCity ?: return ConstraintResult.Fail("목적지 도시 정보가 없습니다.")
        return if (destCity.nationId != ctx.general.nationId) ConstraintResult.Pass
        else ConstraintResult.Fail("아군 도시에는 사용할 수 없습니다.")
    }
}

fun NotNeutralDestCity() = object : Constraint {
    override val name = "NotNeutralDestCity"
    override fun test(ctx: ConstraintContext): ConstraintResult {
        val destCity = ctx.destCity ?: return ConstraintResult.Fail("목적지 도시 정보가 없습니다.")
        return if (destCity.nationId != 0L) ConstraintResult.Pass
        else ConstraintResult.Fail("공백지에는 사용할 수 없습니다.")
    }
}

fun BeNeutral() = object : Constraint {
    override val name = "BeNeutral"
    override fun test(ctx: ConstraintContext): ConstraintResult {
        return if (ctx.general.nationId == 0L) ConstraintResult.Pass
        else ConstraintResult.Fail("재야 상태여야 합니다.")
    }
}

fun NotOccupiedCity() = object : Constraint {
    override val name = "NotOccupiedCity"
    override fun test(ctx: ConstraintContext): ConstraintResult {
        val city = ctx.city ?: return ConstraintResult.Fail("도시 정보가 없습니다.")
        return if (city.nationId != ctx.general.nationId) ConstraintResult.Pass
        else ConstraintResult.Fail("아군 도시에서는 사용할 수 없습니다.")
    }
}

fun MustBeTroopLeader() = object : Constraint {
    override val name = "MustBeTroopLeader"
    override fun test(ctx: ConstraintContext): ConstraintResult {
        val general = ctx.general
        return if (general.troopId == general.id || general.troopId == 0L) ConstraintResult.Pass
        else ConstraintResult.Fail("부대장만 사용할 수 있습니다.")
    }
}

fun ReqTroopMembers() = object : Constraint {
    override val name = "ReqTroopMembers"
    override fun test(ctx: ConstraintContext): ConstraintResult {
        val troopId = ctx.general.troopId
        if (troopId <= 0L) {
            return ConstraintResult.Fail("집합 가능한 부대원이 없습니다.")
        }
        val memberExists = readBooleanMap(ctx.env["troopMemberExistsByTroopId"])[troopId] == true
        return if (memberExists) ConstraintResult.Pass
        else ConstraintResult.Fail("집합 가능한 부대원이 없습니다.")
    }
}

fun ReqGeneralTrainMargin(maxTrain: Int) = object : Constraint {
    override val name = "ReqGeneralTrainMargin"
    override fun test(ctx: ConstraintContext): ConstraintResult {
        return if (ctx.general.train < maxTrain) ConstraintResult.Pass
        else ConstraintResult.Fail("훈련이 이미 충분합니다.")
    }
}

fun ReqGeneralAtmosMargin(maxAtmos: Int) = object : Constraint {
    override val name = "ReqGeneralAtmosMargin"
    override fun test(ctx: ConstraintContext): ConstraintResult {
        return if (ctx.general.atmos < maxAtmos) ConstraintResult.Pass
        else ConstraintResult.Fail("사기가 이미 충분합니다.")
    }
}

fun BeLord() = object : Constraint {
    override val name = "BeLord"
    override fun test(ctx: ConstraintContext): ConstraintResult {
        return if (ctx.general.officerLevel >= 12.toShort()) ConstraintResult.Pass
        else ConstraintResult.Fail("군주만 사용할 수 있습니다.")
    }
}

fun AllowWar() = object : Constraint {
    override val name = "AllowWar"
    override fun test(ctx: ConstraintContext): ConstraintResult {
        val nation = ctx.nation ?: return ConstraintResult.Fail("국가 정보가 없습니다.")
        return if (nation.warState == 0.toShort()) ConstraintResult.Pass
        else ConstraintResult.Fail("현재 전쟁 금지입니다.")
    }
}

fun HasRouteWithEnemy() = object : Constraint {
    override val name = "HasRouteWithEnemy"
    override fun test(ctx: ConstraintContext): ConstraintResult {
        val destCity = ctx.destCity ?: return ConstraintResult.Fail("목적지 도시 정보가 없습니다.")
        val generalNationId = ctx.general.nationId
        val destNationId = destCity.nationId
        val atWarNationIds = readLongSet(ctx.env["atWarNationIds"])

        val allowedNationIds = buildSet {
            add(generalNationId)
            add(0L)
            addAll(atWarNationIds)
        }

        if (destNationId != 0L && destNationId != generalNationId && destNationId !in atWarNationIds) {
            return ConstraintResult.Fail("교전중인 국가가 아닙니다.")
        }

        val pathDistance = shortestDistance(
            ctx = ctx,
            fromCityId = ctx.general.cityId,
            toCityId = destCity.id,
            allowedNationIds = allowedNationIds,
        )
        if (pathDistance < 0) {
            return ConstraintResult.Fail("경로에 도달할 방법이 없습니다.")
        }
        return ConstraintResult.Pass
    }
}

fun NotOpeningPart(relYear: Int) = object : Constraint {
    override val name = "NotOpeningPart"
    override fun test(ctx: ConstraintContext): ConstraintResult {
        return if (relYear >= 1) ConstraintResult.Pass
        else ConstraintResult.Fail("오프닝 기간에는 사용할 수 없습니다.")
    }
}

fun BeOpeningPart(relYear: Int) = object : Constraint {
    override val name = "BeOpeningPart"
    override fun test(ctx: ConstraintContext): ConstraintResult {
        return if (relYear < 1) ConstraintResult.Pass
        else ConstraintResult.Fail("오프닝 기간에만 사용할 수 있습니다.")
    }
}

fun AllowJoinAction() = object : Constraint {
    override val name = "AllowJoinAction"
    override fun test(ctx: ConstraintContext): ConstraintResult {
        if (ctx.general.makeLimit <= 0) {
            return ConstraintResult.Pass
        }
        val joinActionLimit = (ctx.env["joinActionLimit"] as? Number)?.toInt() ?: 12
        return ConstraintResult.Fail("재야가 된지 ${joinActionLimit}턴이 지나야 합니다.")
    }
}

fun AlwaysFail(reason: String) = object : Constraint {
    override val name = "AlwaysFail"
    override fun test(ctx: ConstraintContext): ConstraintResult = ConstraintResult.Fail(reason)
}

fun ReqNationGold(amount: Int) = object : Constraint {
    override val name = "ReqNationGold"
    override fun test(ctx: ConstraintContext): ConstraintResult {
        val nation = ctx.nation ?: return ConstraintResult.Fail("국가 정보가 없습니다.")
        return if (nation.gold >= amount) ConstraintResult.Pass
        else ConstraintResult.Fail("국고가 부족합니다. (필요: $amount, 보유: ${nation.gold})")
    }
}

fun ReqNationRice(amount: Int) = object : Constraint {
    override val name = "ReqNationRice"
    override fun test(ctx: ConstraintContext): ConstraintResult {
        val nation = ctx.nation ?: return ConstraintResult.Fail("국가 정보가 없습니다.")
        return if (nation.rice >= amount) ConstraintResult.Pass
        else ConstraintResult.Fail("병량이 부족합니다. (필요: $amount, 보유: ${nation.rice})")
    }
}

fun ExistsDestGeneral() = object : Constraint {
    override val name = "ExistsDestGeneral"
    override fun test(ctx: ConstraintContext): ConstraintResult {
        return if (ctx.destGeneral != null) ConstraintResult.Pass
        else ConstraintResult.Fail("대상 장수를 찾을 수 없습니다.")
    }
}

fun FriendlyDestGeneral() = object : Constraint {
    override val name = "FriendlyDestGeneral"
    override fun test(ctx: ConstraintContext): ConstraintResult {
        val destGeneral = ctx.destGeneral ?: return ConstraintResult.Fail("대상 장수를 찾을 수 없습니다.")
        return if (destGeneral.nationId == ctx.general.nationId) ConstraintResult.Pass
        else ConstraintResult.Fail("아군 장수가 아닙니다.")
    }
}

fun ExistsDestNation() = object : Constraint {
    override val name = "ExistsDestNation"
    override fun test(ctx: ConstraintContext): ConstraintResult {
        return if (ctx.destNation != null) ConstraintResult.Pass
        else ConstraintResult.Fail("대상 국가를 찾을 수 없습니다.")
    }
}

fun DifferentDestNation() = object : Constraint {
    override val name = "DifferentDestNation"
    override fun test(ctx: ConstraintContext): ConstraintResult {
        val destNation = ctx.destNation ?: return ConstraintResult.Fail("대상 국가를 찾을 수 없습니다.")
        return if (destNation.id != ctx.general.nationId) ConstraintResult.Pass
        else ConstraintResult.Fail("자국에는 사용할 수 없습니다.")
    }
}

fun OccupiedDestCity() = object : Constraint {
    override val name = "OccupiedDestCity"
    override fun test(ctx: ConstraintContext): ConstraintResult {
        val destCity = ctx.destCity ?: return ConstraintResult.Fail("목적지 도시 정보가 없습니다.")
        return if (destCity.nationId == ctx.general.nationId) ConstraintResult.Pass
        else ConstraintResult.Fail("아군 도시가 아닙니다.")
    }
}

fun SuppliedDestCity() = object : Constraint {
    override val name = "SuppliedDestCity"
    override fun test(ctx: ConstraintContext): ConstraintResult {
        val destCity = ctx.destCity ?: return ConstraintResult.Fail("목적지 도시 정보가 없습니다.")
        return if (destCity.supplyState > 0) ConstraintResult.Pass
        else ConstraintResult.Fail("보급이 끊긴 도시입니다.")
    }
}

fun AvailableStrategicCommand() = object : Constraint {
    override val name = "AvailableStrategicCommand"
    override fun test(ctx: ConstraintContext): ConstraintResult {
        val nation = ctx.nation ?: return ConstraintResult.Fail("국가 정보가 없습니다.")
        return if (nation.strategicCmdLimit <= 0) ConstraintResult.Pass
        else ConstraintResult.Fail("전략 명령 대기중입니다. (잔여: ${nation.strategicCmdLimit}턴)")
    }
}

fun BattleGroundCity() = object : Constraint {
    override val name = "BattleGroundCity"
    override fun test(ctx: ConstraintContext): ConstraintResult {
        val destCity = ctx.destCity ?: return ConstraintResult.Fail("목적지 도시 정보가 없습니다.")
        val destNationId = destCity.nationId
        if (destNationId == 0L) {
            return ConstraintResult.Pass
        }
        val atWarNationIds = readLongSet(ctx.env["atWarNationIds"])
        return if (destNationId in atWarNationIds) ConstraintResult.Pass
        else ConstraintResult.Fail("교전중인 국가의 도시가 아닙니다.")
    }
}

fun RemainCityTrust(maxTrust: Int = 100) = object : Constraint {
    override val name = "RemainCityTrust"
    override fun test(ctx: ConstraintContext): ConstraintResult {
        val city = ctx.city ?: return ConstraintResult.Fail("도시 정보가 없습니다.")
        return if (city.trust < maxTrust) ConstraintResult.Pass
        else ConstraintResult.Fail("민심이 이미 최대입니다.")
    }
}

fun ReqGeneralStatValue(statGetter: (com.opensam.entity.General) -> Number, displayName: String, minValue: Int) = object : Constraint {
    override val name = "ReqGeneralStatValue_$displayName"
    override fun test(ctx: ConstraintContext): ConstraintResult {
        val value = statGetter(ctx.general).toInt()
        return if (value >= minValue) ConstraintResult.Pass
        else ConstraintResult.Fail("${displayName}이(가) ${minValue} 이상이어야 합니다.")
    }
}

fun NotLord() = object : Constraint {
    override val name = "NotLord"
    override fun test(ctx: ConstraintContext): ConstraintResult {
        return if (ctx.general.officerLevel < 12.toShort()) ConstraintResult.Pass
        else ConstraintResult.Fail("군주는 사용할 수 없습니다.")
    }
}

fun DifferentNationDestGeneral() = object : Constraint {
    override val name = "DifferentNationDestGeneral"
    override fun test(ctx: ConstraintContext): ConstraintResult {
        val dest = ctx.destGeneral ?: return ConstraintResult.Fail("대상 장수를 찾을 수 없습니다.")
        return if (dest.nationId != ctx.general.nationId) ConstraintResult.Pass
        else ConstraintResult.Fail("같은 국가 소속 장수입니다.")
    }
}

fun MustBeNPC() = object : Constraint {
    override val name = "MustBeNPC"
    override fun test(ctx: ConstraintContext): ConstraintResult {
        return if (ctx.general.npcState > 0.toShort()) ConstraintResult.Pass
        else ConstraintResult.Fail("NPC 전용 명령입니다.")
    }
}

fun WanderingNation() = object : Constraint {
    override val name = "WanderingNation"
    override fun test(ctx: ConstraintContext): ConstraintResult {
        val nation = ctx.nation ?: return ConstraintResult.Pass
        return if (nation.level <= 0.toShort()) ConstraintResult.Pass
        else ConstraintResult.Fail("방랑군 상태여야 합니다.")
    }
}

fun ReqGeneralAge(minAge: Int) = object : Constraint {
    override val name = "ReqGeneralAge"
    override fun test(ctx: ConstraintContext): ConstraintResult {
        return if (ctx.general.age >= minAge.toShort()) ConstraintResult.Pass
        else ConstraintResult.Fail("나이가 ${minAge}세 이상이어야 합니다.")
    }
}

fun NeutralCity() = object : Constraint {
    override val name = "NeutralCity"
    override fun test(ctx: ConstraintContext): ConstraintResult {
        val city = ctx.city ?: return ConstraintResult.Fail("도시 정보가 없습니다.")
        return if (city.nationId == 0L) ConstraintResult.Pass
        else ConstraintResult.Fail("공백지가 아닙니다.")
    }
}

fun HasRoute() = object : Constraint {
    override val name = "HasRoute"
    override fun test(ctx: ConstraintContext): ConstraintResult {
        val destCity = ctx.destCity ?: return ConstraintResult.Fail("목적지 도시 정보가 없습니다.")
        val allowedNationIds = setOf(ctx.general.nationId)
        val pathDistance = shortestDistance(
            ctx = ctx,
            fromCityId = ctx.general.cityId,
            toCityId = destCity.id,
            allowedNationIds = allowedNationIds,
        )
        if (pathDistance < 0) {
            return ConstraintResult.Fail("경로에 도달할 방법이 없습니다.")
        }
        return ConstraintResult.Pass
    }
}

fun AllowDiplomacy(minOfficerLevel: Int = 5) = object : Constraint {
    override val name = "AllowDiplomacy"
    override fun test(ctx: ConstraintContext): ConstraintResult {
        return if (ctx.general.officerLevel >= minOfficerLevel.toShort()) ConstraintResult.Pass
        else ConstraintResult.Fail("외교 권한이 없습니다. (관직 레벨 ${minOfficerLevel} 이상 필요)")
    }
}

fun NotInjured(maxInjury: Int = 0) = object : Constraint {
    override val name = "NotInjured"
    override fun test(ctx: ConstraintContext): ConstraintResult {
        return if (ctx.general.injury <= maxInjury.toShort()) ConstraintResult.Pass
        else ConstraintResult.Fail("부상 상태입니다. (부상: ${ctx.general.injury}, 허용: $maxInjury)")
    }
}

private fun shortestDistance(
    ctx: ConstraintContext,
    fromCityId: Long,
    toCityId: Long,
    allowedNationIds: Set<Long>? = null,
): Int {
    if (fromCityId == toCityId) return 0
    val adjacency = readAdjacency(ctx.env["mapAdjacency"])
    if (adjacency.isEmpty()) return -1

    val cityNationById = readLongMap(ctx.env["cityNationById"])
    val visited = mutableSetOf(fromCityId)
    val queue = ArrayDeque<Pair<Long, Int>>()
    queue.addLast(fromCityId to 0)

    while (queue.isNotEmpty()) {
        val (current, distance) = queue.removeFirst()
        for (next in adjacency[current].orEmpty()) {
            if (next in visited) continue
            if (next == toCityId) return distance + 1

            if (allowedNationIds != null) {
                val nationId = cityNationById[next] ?: return -1
                if (nationId !in allowedNationIds) continue
            }

            visited.add(next)
            queue.addLast(next to (distance + 1))
        }
    }

    return -1
}

private fun readAdjacency(raw: Any?): Map<Long, List<Long>> {
    if (raw !is Map<*, *>) return emptyMap()
    val result = mutableMapOf<Long, List<Long>>()
    raw.forEach { (k, v) ->
        val key = asLong(k) ?: return@forEach
        val values = when (v) {
            is Iterable<*> -> v.mapNotNull { asLong(it) }
            else -> emptyList()
        }
        result[key] = values
    }
    return result
}

private fun readLongMap(raw: Any?): Map<Long, Long> {
    if (raw !is Map<*, *>) return emptyMap()
    val result = mutableMapOf<Long, Long>()
    raw.forEach { (k, v) ->
        val key = asLong(k) ?: return@forEach
        val value = asLong(v) ?: return@forEach
        result[key] = value
    }
    return result
}

private fun readLongSet(raw: Any?): Set<Long> {
    return when (raw) {
        is Set<*> -> raw.mapNotNull { asLong(it) }.toSet()
        is Iterable<*> -> raw.mapNotNull { asLong(it) }.toSet()
        else -> emptySet()
    }
}

private fun readBooleanMap(raw: Any?): Map<Long, Boolean> {
    if (raw !is Map<*, *>) return emptyMap()
    val result = mutableMapOf<Long, Boolean>()
    raw.forEach { (k, v) ->
        val key = asLong(k) ?: return@forEach
        val value = v as? Boolean ?: return@forEach
        result[key] = value
    }
    return result
}

fun ReqEnvValue(key: String, op: String, expected: String, reason: String) = object : Constraint {
    override val name = "ReqEnvValue_$key"
    override fun test(ctx: ConstraintContext): ConstraintResult {
        val actual = ctx.env[key]?.toString() ?: ""
        val matches = when (op) {
            "!=" -> actual != expected
            "==" -> actual == expected
            else -> true
        }
        return if (matches) ConstraintResult.Pass
        else ConstraintResult.Fail(reason)
    }
}

fun NoPenalty(penaltyKey: String) = object : Constraint {
    override val name = "NoPenalty_$penaltyKey"
    override fun test(ctx: ConstraintContext): ConstraintResult {
        val penalty = ctx.general.meta["penalty"]
        if (penalty is Map<*, *> && penalty.containsKey(penaltyKey)) {
            return ConstraintResult.Fail("페널티로 인해 사용할 수 없습니다.")
        }
        return ConstraintResult.Pass
    }
}

fun AllowJoinDestNation(relYear: Int) = object : Constraint {
    override val name = "AllowJoinDestNation"
    override fun test(ctx: ConstraintContext): ConstraintResult {
        val destNation = ctx.destNation ?: return ConstraintResult.Fail("대상 국가를 찾을 수 없습니다.")
        val scout = (destNation.meta["scout"] as? Number)?.toInt() ?: 0
        // Legacy PHP: checks if scout level allows joining based on relYear
        if (scout <= 0 && relYear >= 3) {
            return ConstraintResult.Fail("등용 설정이 되어 있지 않습니다.")
        }
        return ConstraintResult.Pass
    }
}

fun CheckNationNameDuplicate(nationName: String) = object : Constraint {
    override val name = "CheckNationNameDuplicate"
    override fun test(ctx: ConstraintContext): ConstraintResult {
        val existingNames = readStringSet(ctx.env["existingNationNames"])
        return if (nationName !in existingNames) ConstraintResult.Pass
        else ConstraintResult.Fail("이미 같은 이름의 국가가 존재합니다.")
    }
}

fun ConstructableCity() = object : Constraint {
    override val name = "ConstructableCity"
    override fun test(ctx: ConstraintContext): ConstraintResult {
        val city = ctx.city ?: return ConstraintResult.Fail("도시 정보가 없습니다.")
        // Legacy: city must be neutral or owned by this wandering nation
        return if (city.nationId == 0L || city.nationId == ctx.general.nationId) ConstraintResult.Pass
        else ConstraintResult.Fail("건국할 수 없는 도시입니다.")
    }
}

fun ReqNationGenCount(minCount: Int) = object : Constraint {
    override val name = "ReqNationGenCount"
    override fun test(ctx: ConstraintContext): ConstraintResult {
        val nation = ctx.nation ?: return ConstraintResult.Fail("국가 정보가 없습니다.")
        val genNum = (nation.meta["gennum"] as? Number)?.toInt() ?: 0
        return if (genNum >= minCount) ConstraintResult.Pass
        else ConstraintResult.Fail("수하 장수가 ${minCount}명 이상이어야 합니다. (현재: $genNum)")
    }
}

fun ReqNationValue(key: String, displayName: String, op: String, expected: Int, failMessage: String? = null) = object : Constraint {
    override val name = "ReqNationValue_$key"
    override fun test(ctx: ConstraintContext): ConstraintResult {
        val nation = ctx.nation ?: return ConstraintResult.Fail("국가 정보가 없습니다.")
        val actual = when (key) {
            "level" -> nation.level.toInt()
            "gold" -> nation.gold
            "rice" -> nation.rice
            else -> (nation.meta[key] as? Number)?.toInt() ?: 0
        }
        val matches = when (op) {
            "==" -> actual == expected
            "!=" -> actual != expected
            ">=" -> actual >= expected
            "<=" -> actual <= expected
            ">" -> actual > expected
            "<" -> actual < expected
            else -> true
        }
        return if (matches) ConstraintResult.Pass
        else ConstraintResult.Fail(failMessage ?: "${displayName}이(가) 조건을 만족하지 않습니다.")
    }
}

fun AllowDiplomacyBetweenStatus(allowedStatuses: List<Int>, failMessage: String) = object : Constraint {
    override val name = "AllowDiplomacyBetweenStatus"
    override fun test(ctx: ConstraintContext): ConstraintResult {
        val destNation = ctx.destNation ?: return ConstraintResult.Fail("대상 국가를 찾을 수 없습니다.")
        val nationId = ctx.general.nationId
        val destNationId = destNation.id
        // Check diplomacy status between nations from env
        @Suppress("UNCHECKED_CAST")
        val diplomacyMap = ctx.env["diplomacyStatus"] as? Map<String, Int> ?: emptyMap()
        val key = "${minOf(nationId, destNationId)}_${maxOf(nationId, destNationId)}"
        val status = diplomacyMap[key] ?: 7 // default: no relation (peacetime)
        return if (status in allowedStatuses) ConstraintResult.Pass
        else ConstraintResult.Fail(failMessage)
    }
}

fun AllowDiplomacyStatus(allowedStatuses: List<Int>, failMessage: String) = object : Constraint {
    override val name = "AllowDiplomacyStatus"
    override fun test(ctx: ConstraintContext): ConstraintResult {
        val nation = ctx.nation ?: return ConstraintResult.Fail("국가 정보가 없습니다.")
        // Check if nation is in any of the allowed war statuses
        val warState = nation.warState.toInt()
        return if (warState in allowedStatuses) ConstraintResult.Pass
        else ConstraintResult.Fail(failMessage)
    }
}

fun DisallowDiplomacyStatus(disallowedStatuses: Map<Int, String>) = object : Constraint {
    override val name = "DisallowDiplomacyStatus"
    override fun test(ctx: ConstraintContext): ConstraintResult {
        val nation = ctx.nation ?: return ConstraintResult.Fail("국가 정보가 없습니다.")
        val warState = nation.warState.toInt()
        val failMsg = disallowedStatuses[warState]
        return if (failMsg != null) ConstraintResult.Fail(failMsg)
        else ConstraintResult.Pass
    }
}

fun ReqCityCapacity(cityKey: String, displayName: String, minValue: Int) = object : Constraint {
    override val name = "ReqCityCapacity_$cityKey"
    override fun test(ctx: ConstraintContext): ConstraintResult {
        val city = ctx.city ?: return ConstraintResult.Fail("도시 정보가 없습니다.")
        val current = when (cityKey) {
            "pop", "population" -> city.pop
            "agri", "agriculture" -> city.agri
            "comm", "commerce" -> city.comm
            "secu", "security" -> city.secu
            "def", "defence" -> city.def
            "wall" -> city.wall
            else -> 0
        }
        return if (current >= minValue) ConstraintResult.Pass
        else ConstraintResult.Fail("${displayName}이(가) 부족합니다. (필요: $minValue, 현재: $current)")
    }
}

fun ReqCityTrader() = object : Constraint {
    override val name = "ReqCityTrader"
    override fun test(ctx: ConstraintContext): ConstraintResult {
        val city = ctx.city ?: return ConstraintResult.Fail("도시 정보가 없습니다.")
        // Legacy: city must have trade value, or general is NPC (npcType >= 2)
        val hasTrade = city.trade > 0
        val isNPC = ctx.general.npcState >= 2
        return if (hasTrade || isNPC) ConstraintResult.Pass
        else ConstraintResult.Fail("상인이 없는 도시입니다.")
    }
}

fun OccupiedCity(allowNeutral: Boolean = false) = object : Constraint {
    override val name = "OccupiedCity"
    override fun test(ctx: ConstraintContext): ConstraintResult {
        val city = ctx.city ?: return ConstraintResult.Fail("도시 정보가 없습니다.")
        if (city.nationId == ctx.general.nationId) return ConstraintResult.Pass
        if (allowNeutral && city.nationId == 0L) return ConstraintResult.Pass
        return ConstraintResult.Fail("아군 도시가 아닙니다.")
    }
}

fun NotCapital(checkCurrentCity: Boolean = false) = object : Constraint {
    override val name = "NotCapital"
    override fun test(ctx: ConstraintContext): ConstraintResult {
        val nation = ctx.nation ?: return ConstraintResult.Fail("국가 정보가 없습니다.")
        val capitalCityId = nation.capitalCityId ?: return ConstraintResult.Pass
        return if (ctx.general.cityId != capitalCityId) ConstraintResult.Pass
        else ConstraintResult.Fail("이미 수도에 있습니다.")
    }
}

private fun readStringSet(raw: Any?): Set<String> {
    return when (raw) {
        is Set<*> -> raw.mapNotNull { it as? String }.toSet()
        is Iterable<*> -> raw.mapNotNull { it as? String }.toSet()
        else -> emptySet()
    }
}

// --- Diplomacy Constraints ---

fun AllowDiplomacyStatus(allowList: List<Int>, reason: String) = object : Constraint {
    override val name = "AllowDiplomacyStatus"
    override fun test(ctx: ConstraintContext): ConstraintResult {
        val nationId = ctx.general.nationId
        if (nationId == 0L) return ConstraintResult.Fail("국가 정보가 없습니다.")
        val diplomacyList = readDiplomacyList(ctx.env["diplomacyList"])
        val matched = diplomacyList.any { it.fromNationId == nationId && it.state in allowList }
        return if (matched) ConstraintResult.Pass
        else ConstraintResult.Fail(reason)
    }
}

fun AllowDiplomacyBetweenStatus(allowList: List<Int>, reason: String) = object : Constraint {
    override val name = "AllowDiplomacyBetweenStatus"
    override fun test(ctx: ConstraintContext): ConstraintResult {
        val nationId = ctx.general.nationId
        val destNationId = ctx.destNation?.id ?: ctx.destCity?.nationId
            ?: return ConstraintResult.Fail("상대 국가 정보가 없습니다.")
        val state = readDiplomacyState(ctx.env, nationId, destNationId)
            ?: return ConstraintResult.Fail("외교 정보가 없습니다.")
        return if (state in allowList) ConstraintResult.Pass
        else ConstraintResult.Fail(reason)
    }
}

fun AllowDiplomacyWithTerm(requiredState: Int, minTerm: Int, reason: String) = object : Constraint {
    override val name = "AllowDiplomacyWithTerm"
    override fun test(ctx: ConstraintContext): ConstraintResult {
        val nationId = ctx.general.nationId
        val destNationId = ctx.destNation?.id ?: ctx.destCity?.nationId
            ?: return ConstraintResult.Fail("상대 국가 정보가 없습니다.")
        val entry = readDiplomacyEntry(ctx.env, nationId, destNationId)
            ?: return ConstraintResult.Fail("외교 정보가 없습니다.")
        return if (entry.state == requiredState && entry.term >= minTerm) ConstraintResult.Pass
        else ConstraintResult.Fail(reason)
    }
}

fun DisallowDiplomacyBetweenStatus(disallowList: Map<Int, String>) = object : Constraint {
    override val name = "DisallowDiplomacyBetweenStatus"
    override fun test(ctx: ConstraintContext): ConstraintResult {
        val nationId = ctx.general.nationId
        val destNationId = ctx.destNation?.id ?: ctx.destCity?.nationId
            ?: return ConstraintResult.Fail("상대 국가 정보가 없습니다.")
        val state = readDiplomacyState(ctx.env, nationId, destNationId)
            ?: return ConstraintResult.Fail("외교 정보가 없습니다.")
        val reason = disallowList[state]
        return if (reason != null) ConstraintResult.Fail(reason)
        else ConstraintResult.Pass
    }
}

fun DisallowDiplomacyStatus(disallowList: Map<Int, String>) = object : Constraint {
    override val name = "DisallowDiplomacyStatus"
    override fun test(ctx: ConstraintContext): ConstraintResult {
        // Same logic as DisallowDiplomacyBetweenStatus (legacy alias)
        val nationId = ctx.general.nationId
        val destNationId = ctx.destNation?.id ?: ctx.destCity?.nationId
            ?: return ConstraintResult.Fail("상대 국가 정보가 없습니다.")
        val state = readDiplomacyState(ctx.env, nationId, destNationId)
            ?: return ConstraintResult.Fail("외교 정보가 없습니다.")
        val reason = disallowList[state]
        return if (reason != null) ConstraintResult.Fail(reason)
        else ConstraintResult.Pass
    }
}

// --- General Constraints ---

fun AllowRebellion() = object : Constraint {
    override val name = "AllowRebellion"
    override fun test(ctx: ConstraintContext): ConstraintResult {
        val general = ctx.general
        if (general.nationId == 0L) return ConstraintResult.Fail("재야입니다.")
        if (general.officerLevel >= 12.toShort()) return ConstraintResult.Fail("이미 군주입니다.")

        val killturn = (ctx.env["killturn"] as? Number)?.toInt()
            ?: return ConstraintResult.Fail("턴 정보가 없습니다.")

        val lordKillturn = (ctx.env["lordKillturn"] as? Number)?.toInt()
            ?: return ConstraintResult.Fail("군주 정보가 없습니다.")

        if (lordKillturn >= killturn) {
            return ConstraintResult.Fail("군주가 활동중입니다.")
        }

        val lordNpcState = (ctx.env["lordNpcState"] as? Number)?.toInt() ?: 0
        if (lordNpcState in listOf(2, 3, 6, 9)) {
            return ConstraintResult.Fail("군주가 NPC입니다.")
        }

        return ConstraintResult.Pass
    }
}

fun NotChief() = object : Constraint {
    override val name = "NotChief"
    override fun test(ctx: ConstraintContext): ConstraintResult {
        return if (ctx.general.officerLevel < 12.toShort()) ConstraintResult.Pass
        else ConstraintResult.Fail("군주는 사용할 수 없습니다.")
    }
}

fun ReqGeneralValue(key: String, displayName: String, op: String, expected: Any, failMessage: String? = null) = object : Constraint {
    override val name = "ReqGeneralValue_$key"
    override fun test(ctx: ConstraintContext): ConstraintResult {
        val general = ctx.general
        val actual = general.meta[key]
        val matches = compareGeneric(actual, op, expected)
        return if (matches) ConstraintResult.Pass
        else ConstraintResult.Fail(failMessage ?: "${displayName} 조건을 만족하지 않습니다.")
    }
}

fun ReqGeneralCrewMargin(crewTypeId: Int) = object : Constraint {
    override val name = "ReqGeneralCrewMargin"
    override fun test(ctx: ConstraintContext): ConstraintResult {
        val general = ctx.general
        if (crewTypeId != general.crewTypeId) return ConstraintResult.Pass
        val maxCrew = general.leadership * 100
        return if (maxCrew > general.crew) ConstraintResult.Pass
        else ConstraintResult.Fail("이미 많은 병력을 보유하고 있습니다.")
    }
}

fun AvailableRecruitCrewType(crewTypeId: Int) = object : Constraint {
    override val name = "AvailableRecruitCrewType"
    override fun test(ctx: ConstraintContext): ConstraintResult {
        val availableCrewTypes = readIntSet(ctx.env["availableCrewTypes"])
        return if (crewTypeId in availableCrewTypes) ConstraintResult.Pass
        else ConstraintResult.Fail("해당 병종을 모집할 수 없습니다.")
    }
}

fun ExistsAllowJoinNation(relYear: Int, excludeNationIds: Set<Long>) = object : Constraint {
    override val name = "ExistsAllowJoinNation"
    override fun test(ctx: ConstraintContext): ConstraintResult {
        val nations = readNationList(ctx.env["nationList"])
        val exists = nations.any { nation ->
            nation.id !in excludeNationIds && nation.level > 0
        }
        return if (exists) ConstraintResult.Pass
        else ConstraintResult.Fail("임관 가능한 국가가 없습니다.")
    }
}

// --- City Constraints ---

fun ReqCityValue(key: String, displayName: String, op: String, expected: Any, failMessage: String? = null) = object : Constraint {
    override val name = "ReqCityValue_$key"
    override fun test(ctx: ConstraintContext): ConstraintResult {
        val city = ctx.city ?: return ConstraintResult.Fail("도시 정보가 없습니다.")
        val actual = city.meta[key]
        val matches = compareGeneric(actual, op, expected)
        return if (matches) ConstraintResult.Pass
        else ConstraintResult.Fail(failMessage ?: "${displayName} 조건을 만족하지 않습니다.")
    }
}

fun ReqDestCityValue(key: String, displayName: String, op: String, expected: Any, failMessage: String? = null) = object : Constraint {
    override val name = "ReqDestCityValue_$key"
    override fun test(ctx: ConstraintContext): ConstraintResult {
        val city = ctx.destCity ?: return ConstraintResult.Fail("목적지 도시 정보가 없습니다.")
        val actual = city.meta[key]
        val matches = compareGeneric(actual, op, expected)
        return if (matches) ConstraintResult.Pass
        else ConstraintResult.Fail(failMessage ?: "${displayName} 조건을 만족하지 않습니다.")
    }
}

fun ReqCityCapacity(key: String, displayName: String, required: Int) = object : Constraint {
    override val name = "ReqCityCapacity_$key"
    override fun test(ctx: ConstraintContext): ConstraintResult {
        val city = ctx.city ?: return ConstraintResult.Fail("도시 정보가 없습니다.")
        val current = getCityNumericField(city, key) ?: 0
        return if (current >= required) ConstraintResult.Pass
        else ConstraintResult.Fail("${displayName}이(가) 부족합니다.")
    }
}

fun ReqCityTrust(minTrust: Float) = object : Constraint {
    override val name = "ReqCityTrust"
    override fun test(ctx: ConstraintContext): ConstraintResult {
        val city = ctx.city ?: return ConstraintResult.Fail("도시 정보가 없습니다.")
        return if (city.trust >= minTrust) ConstraintResult.Pass
        else ConstraintResult.Fail("민심이 부족합니다. (필요: $minTrust)")
    }
}

fun ReqCityLevel(levels: List<Int>) = object : Constraint {
    override val name = "ReqCityLevel"
    override fun test(ctx: ConstraintContext): ConstraintResult {
        val city = ctx.city ?: return ConstraintResult.Fail("도시 정보가 없습니다.")
        return if (city.level in levels) ConstraintResult.Pass
        else ConstraintResult.Fail("해당 도시 등급에서는 사용할 수 없습니다.")
    }
}

fun ExistsDestCity() = object : Constraint {
    override val name = "ExistsDestCity"
    override fun test(ctx: ConstraintContext): ConstraintResult {
        return if (ctx.destCity != null) ConstraintResult.Pass
        else ConstraintResult.Fail("목적지 도시를 찾을 수 없습니다.")
    }
}

// --- Nation Constraints ---

fun ReqDestNationValue(key: String, displayName: String, op: String, expected: Int, failMessage: String? = null) = object : Constraint {
    override val name = "ReqDestNationValue_$key"
    override fun test(ctx: ConstraintContext): ConstraintResult {
        val nation = ctx.destNation ?: return ConstraintResult.Fail("대상 국가 정보가 없습니다.")
        val actual = when (key) {
            "level" -> nation.level.toInt()
            "gold" -> nation.gold
            "rice" -> nation.rice
            else -> (nation.meta[key] as? Number)?.toInt() ?: 0
        }
        val matches = when (op) {
            "==" -> actual == expected
            "!=" -> actual != expected
            ">=" -> actual >= expected
            "<=" -> actual <= expected
            ">" -> actual > expected
            "<" -> actual < expected
            else -> true
        }
        return if (matches) ConstraintResult.Pass
        else ConstraintResult.Fail(failMessage ?: "${displayName} 조건을 만족하지 않습니다.")
    }
}

fun ReqNationAuxValue(key: String, defaultValue: Int, op: String, expected: Int, failMessage: String) = object : Constraint {
    override val name = "ReqNationAuxValue_$key"
    override fun test(ctx: ConstraintContext): ConstraintResult {
        val nation = ctx.nation ?: return ConstraintResult.Fail("국가 정보가 없습니다.")
        val actual = (nation.meta[key] as? Number)?.toInt() ?: defaultValue
        val matches = when (op) {
            "==" -> actual == expected
            "!=" -> actual != expected
            ">=" -> actual >= expected
            "<=" -> actual <= expected
            ">" -> actual > expected
            "<" -> actual < expected
            else -> true
        }
        return if (matches) ConstraintResult.Pass
        else ConstraintResult.Fail(failMessage)
    }
}

fun NearNation() = object : Constraint {
    override val name = "NearNation"
    override fun test(ctx: ConstraintContext): ConstraintResult {
        val nationId = ctx.general.nationId
        val destNationId = ctx.destNation?.id
            ?: return ConstraintResult.Fail("상대 국가 정보가 없습니다.")

        val cityNationById = readLongMap(ctx.env["cityNationById"])
        val adjacency = readAdjacency(ctx.env["mapAdjacency"])

        // Find cities belonging to our nation
        val myCityIds = cityNationById.filter { it.value == nationId }.keys
        // Find cities belonging to dest nation
        val destCityIds = cityNationById.filter { it.value == destNationId }.keys

        // Check if any of our cities are adjacent to any dest cities
        for (myCityId in myCityIds) {
            val neighbors = adjacency[myCityId].orEmpty()
            if (neighbors.any { it in destCityIds }) {
                return ConstraintResult.Pass
            }
        }
        return ConstraintResult.Fail("인접한 국가가 아닙니다.")
    }
}

fun DestGeneralInDestNation() = object : Constraint {
    override val name = "DestGeneralInDestNation"
    override fun test(ctx: ConstraintContext): ConstraintResult {
        val destGeneral = ctx.destGeneral ?: return ConstraintResult.Fail("대상 장수를 찾을 수 없습니다.")
        val destNation = ctx.destNation ?: return ConstraintResult.Fail("대상 국가를 찾을 수 없습니다.")
        return if (destGeneral.nationId == destNation.id) ConstraintResult.Pass
        else ConstraintResult.Fail("대상 장수가 해당 국가 소속이 아닙니다.")
    }
}

// --- Helper data classes and functions ---

private data class DiplomacyEntry(val state: Int, val term: Int)

private data class DiplomacyListEntry(val fromNationId: Long, val state: Int)

private fun readDiplomacyState(env: Map<String, Any>, srcNationId: Long, destNationId: Long): Int? {
    val key = "diplomacy_${srcNationId}_${destNationId}"
    val raw = env[key]
    if (raw is Number) return raw.toInt()
    if (raw is Map<*, *>) {
        return (raw["state"] as? Number)?.toInt() ?: (raw["stateCode"] as? Number)?.toInt()
    }
    // Try diplomacyMap
    val diplomacyMap = env["diplomacyMap"]
    if (diplomacyMap is Map<*, *>) {
        val entry = diplomacyMap["${srcNationId}_${destNationId}"]
        if (entry is Number) return entry.toInt()
        if (entry is Map<*, *>) {
            return (entry["state"] as? Number)?.toInt() ?: (entry["stateCode"] as? Number)?.toInt()
        }
    }
    return null
}

private fun readDiplomacyEntry(env: Map<String, Any>, srcNationId: Long, destNationId: Long): DiplomacyEntry? {
    val key = "diplomacy_${srcNationId}_${destNationId}"
    val raw = env[key]
    if (raw is Map<*, *>) {
        val state = (raw["state"] as? Number)?.toInt() ?: (raw["stateCode"] as? Number)?.toInt() ?: return null
        val term = (raw["term"] as? Number)?.toInt() ?: 0
        return DiplomacyEntry(state, term)
    }
    val diplomacyMap = env["diplomacyMap"]
    if (diplomacyMap is Map<*, *>) {
        val entry = diplomacyMap["${srcNationId}_${destNationId}"]
        if (entry is Map<*, *>) {
            val state = (entry["state"] as? Number)?.toInt() ?: (entry["stateCode"] as? Number)?.toInt() ?: return null
            val term = (entry["term"] as? Number)?.toInt() ?: 0
            return DiplomacyEntry(state, term)
        }
    }
    return null
}

private fun readDiplomacyList(raw: Any?): List<DiplomacyListEntry> {
    if (raw !is Iterable<*>) return emptyList()
    return raw.mapNotNull { item ->
        if (item !is Map<*, *>) return@mapNotNull null
        val fromNationId = asLong(item["fromNationId"]) ?: return@mapNotNull null
        val state = (item["state"] as? Number)?.toInt() ?: return@mapNotNull null
        DiplomacyListEntry(fromNationId, state)
    }
}

private fun readNationList(raw: Any?): List<NationListEntry> {
    if (raw !is Iterable<*>) return emptyList()
    return raw.mapNotNull { item ->
        if (item !is Map<*, *>) return@mapNotNull null
        val id = asLong(item["id"]) ?: return@mapNotNull null
        val level = (item["level"] as? Number)?.toInt() ?: 0
        NationListEntry(id, level)
    }
}

private data class NationListEntry(val id: Long, val level: Int)

private fun readIntSet(raw: Any?): Set<Int> {
    return when (raw) {
        is Set<*> -> raw.mapNotNull { (it as? Number)?.toInt() }.toSet()
        is Iterable<*> -> raw.mapNotNull { (it as? Number)?.toInt() }.toSet()
        else -> emptySet()
    }
}

private fun compareGeneric(actual: Any?, op: String, expected: Any): Boolean {
    val a = (actual as? Number)?.toDouble() ?: return false
    val b = (expected as? Number)?.toDouble() ?: return false
    return when (op) {
        "==" -> a == b
        "!=" -> a != b
        ">=" -> a >= b
        "<=" -> a <= b
        ">" -> a > b
        "<" -> a < b
        else -> true
    }
}

private fun getCityNumericField(city: City, key: String): Int? {
    return when (key) {
        "agri" -> city.agri
        "comm" -> city.comm
        "secu" -> city.secu
        "def" -> city.def
        "wall" -> city.wall
        "pop" -> city.pop
        "trust" -> city.trust.toInt()
        else -> (city.meta[key] as? Number)?.toInt()
    }
}

private fun asLong(raw: Any?): Long? {
    return when (raw) {
        is Number -> raw.toLong()
        is String -> raw.toLongOrNull()
        else -> null
    }
}

fun ReqNationAuxValue(key: String, default: Int, op: String, expected: Int, failMessage: String) = object : Constraint {
    override val name = "ReqNationAuxValue_$key"
    override fun test(ctx: ConstraintContext): ConstraintResult {
        val nation = ctx.nation ?: return ConstraintResult.Fail("국가 정보가 없습니다.")
        val actual = (nation.meta[key] as? Number)?.toInt() ?: default
        val matches = when (op) {
            "==" -> actual == expected
            "!=" -> actual != expected
            ">=" -> actual >= expected
            "<=" -> actual <= expected
            ">" -> actual > expected
            "<" -> actual < expected
            else -> true
        }
        return if (matches) ConstraintResult.Pass
        else ConstraintResult.Fail(failMessage)
    }
}

fun ReqDestNationValue(key: String, displayName: String, op: String, expected: Int, failMessage: String) = object : Constraint {
    override val name = "ReqDestNationValue_$key"
    override fun test(ctx: ConstraintContext): ConstraintResult {
        val destNation = ctx.destNation ?: return ConstraintResult.Fail("대상 국가 정보가 없습니다.")
        val actual = when (key) {
            "level" -> destNation.level.toInt()
            "gold" -> destNation.gold
            "rice" -> destNation.rice
            "surlimit" -> (destNation.meta["surlimit"] as? Number)?.toInt() ?: 0
            else -> (destNation.meta[key] as? Number)?.toInt() ?: 0
        }
        val matches = when (op) {
            "==" -> actual == expected
            "!=" -> actual != expected
            ">=" -> actual >= expected
            "<=" -> actual <= expected
            ">" -> actual > expected
            "<" -> actual < expected
            else -> true
        }
        return if (matches) ConstraintResult.Pass
        else ConstraintResult.Fail(failMessage)
    }
}

fun AllowDiplomacyWithTerm(minState: Int, minTerm: Int, failMessage: String) = object : Constraint {
    override val name = "AllowDiplomacyWithTerm"
    override fun test(ctx: ConstraintContext): ConstraintResult {
        val diplomacyInfo = ctx.env["diplomacyTerm"] as? Number
        val term = diplomacyInfo?.toInt() ?: 0
        return if (term >= minTerm) ConstraintResult.Pass
        else ConstraintResult.Fail(failMessage)
    }
}

fun ReqDestCityValue(field: String, displayName: String, op: String, expected: Int, failMessage: String) = object : Constraint {
    override val name = "ReqDestCityValue_$field"
    override fun test(ctx: ConstraintContext): ConstraintResult {
        val destCity = ctx.destCity ?: return ConstraintResult.Fail("목적지 도시 정보가 없습니다.")
        val actual = when (field) {
            "level" -> destCity.level.toInt()
            "pop" -> destCity.pop
            else -> 0
        }
        val matches = when (op) {
            ">" -> actual > expected
            "<" -> actual < expected
            ">=" -> actual >= expected
            "<=" -> actual <= expected
            "==" -> actual == expected
            else -> true
        }
        return if (matches) ConstraintResult.Pass
        else ConstraintResult.Fail(failMessage)
    }
}
