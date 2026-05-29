package opensamguk.logic.constraints

import opensamguk.common.constants.GameConst
import opensamguk.common.josa.JosaUtil
import opensamguk.logic.domain.City
import opensamguk.logic.domain.General
import opensamguk.logic.domain.Nation
import opensamguk.logic.domain.metaInt

private fun gen(ctx: ConstraintContext, view: StateView) = view.get(RequirementKey.General(ctx.actorId)) as? General
private fun city(ctx: ConstraintContext, view: StateView) =
    (ctx.cityId ?: (gen(ctx, view)?.cityId))?.let { view.get(RequirementKey.City(it)) as? City }
private fun nation(ctx: ConstraintContext, view: StateView) =
    (ctx.nationId ?: (gen(ctx, view)?.nationId))?.let { view.get(RequirementKey.Nation(it)) as? Nation }

fun notBeNeutral() = object : Constraint {
    override val name = "NotBeNeutral"
    override fun requires(ctx: ConstraintContext) = listOf(RequirementKey.General(ctx.actorId))
    override fun test(ctx: ConstraintContext, view: StateView): ConstraintResult {
        val g = gen(ctx, view) ?: return ConstraintResult.Unknown(requires(ctx))
        return if (g.nationId != 0) ConstraintResult.Allow else ConstraintResult.Deny("재야입니다.")
    }
}

fun notWanderingNation() = object : Constraint {
    override val name = "NotWanderingNation"
    override fun requires(ctx: ConstraintContext) =
        listOf(RequirementKey.Nation(ctx.nationId ?: 0))
    override fun test(ctx: ConstraintContext, view: StateView): ConstraintResult {
        val n = nation(ctx, view) ?: return ConstraintResult.Unknown(requires(ctx))
        return if (n.level != 0) ConstraintResult.Allow else ConstraintResult.Deny("방랑군은 불가능합니다.")
    }
}

// CP1: ADD the allowNeutral branch the P1 preset omitted (OccupiedCity.php:33-37 — `if(arg && nation==0) pass`).
fun occupiedCity(allowNeutral: Boolean = false) = object : Constraint {
    override val name = "OccupiedCity"
    override fun requires(ctx: ConstraintContext) =
        listOf(RequirementKey.General(ctx.actorId), RequirementKey.City(ctx.cityId ?: 0))
    override fun test(ctx: ConstraintContext, view: StateView): ConstraintResult {
        val g = gen(ctx, view) ?: return ConstraintResult.Unknown(requires(ctx))
        val c = city(ctx, view) ?: return ConstraintResult.Unknown(requires(ctx))
        if (allowNeutral && g.nationId == 0) return ConstraintResult.Allow
        return if (c.nationId == g.nationId) ConstraintResult.Allow else ConstraintResult.Deny("아국이 아닙니다.")
    }
}

fun suppliedCity() = object : Constraint {
    override val name = "SuppliedCity"
    override fun requires(ctx: ConstraintContext) = listOf(RequirementKey.City(ctx.cityId ?: 0))
    override fun test(ctx: ConstraintContext, view: StateView): ConstraintResult {
        val c = city(ctx, view) ?: return ConstraintResult.Unknown(requires(ctx))
        return if (c.supplyState != 0) ConstraintResult.Allow else ConstraintResult.Deny("고립된 도시입니다.")
    }
}

fun reqGeneralGold(cost: (ConstraintContext, StateView) -> Int) = object : Constraint {
    override val name = "ReqGeneralGold"
    override fun requires(ctx: ConstraintContext) = listOf(RequirementKey.General(ctx.actorId))
    override fun test(ctx: ConstraintContext, view: StateView): ConstraintResult {
        val g = gen(ctx, view) ?: return ConstraintResult.Unknown(requires(ctx))
        return if (g.gold >= cost(ctx, view)) ConstraintResult.Allow else ConstraintResult.Deny("자금이 모자랍니다.")
    }
}

fun reqGeneralRice(cost: (ConstraintContext, StateView) -> Int) = object : Constraint {
    override val name = "ReqGeneralRice"
    override fun requires(ctx: ConstraintContext) = listOf(RequirementKey.General(ctx.actorId))
    override fun test(ctx: ConstraintContext, view: StateView): ConstraintResult {
        val g = gen(ctx, view) ?: return ConstraintResult.Unknown(requires(ctx))
        return if (g.rice >= cost(ctx, view)) ConstraintResult.Allow else ConstraintResult.Deny("군량이 모자랍니다.")
    }
}

fun remainCityCapacity(cityKey: String, keyNick: String) = object : Constraint {
    override val name = "RemainCityCapacity"
    override fun requires(ctx: ConstraintContext) = listOf(RequirementKey.City(ctx.cityId ?: 0))
    override fun test(ctx: ConstraintContext, view: StateView): ConstraintResult {
        val c = city(ctx, view) ?: return ConstraintResult.Unknown(requires(ctx))
        val (cur, max) = when (cityKey) {
            "comm" -> c.commerce to c.commerceMax
            "agri" -> c.agriculture to c.agricultureMax
            else -> error("unknown cityKey $cityKey")
        }
        if (cur < max) return ConstraintResult.Allow
        val josaUn = JosaUtil.pick(keyNick, "은")          // PHP RemainCityCapacity.php uses 은/는
        return ConstraintResult.Deny("$keyNick$josaUn 충분합니다.")
    }
}

// ============================================================================
// CP1 — pure general/city/nation-state presets (faithful port of PHP Constraint/[Name].php).
// Reason strings are PHP grand truth (NOT TS). RequirementKey resolution stays inside the existing
// General/City/Nation keys (NO DB inside test() — DB/diplomacy/unit-isValid forms take a preloaded
// predicate, mirroring the P1 reqGeneralGold cost-lambda idiom).
// ============================================================================

// --- General state ---

/** BeNeutral.php — pass when nation==0; reason `재야가 아닙니다.` (TS-divergent: NOT `재야입니다.`). */
fun beNeutral() = object : Constraint {
    override val name = "BeNeutral"
    override fun requires(ctx: ConstraintContext) = listOf(RequirementKey.General(ctx.actorId))
    override fun test(ctx: ConstraintContext, view: StateView): ConstraintResult {
        val g = gen(ctx, view) ?: return ConstraintResult.Unknown(requires(ctx))
        return if (g.nationId == 0) ConstraintResult.Allow else ConstraintResult.Deny("재야가 아닙니다.")
    }
}

/** BeChief.php — pass when officer_level > 4. */
fun beChief() = object : Constraint {
    override val name = "BeChief"
    override fun requires(ctx: ConstraintContext) = listOf(RequirementKey.General(ctx.actorId))
    override fun test(ctx: ConstraintContext, view: StateView): ConstraintResult {
        val g = gen(ctx, view) ?: return ConstraintResult.Unknown(requires(ctx))
        return if (g.officerLevel > 4) ConstraintResult.Allow else ConstraintResult.Deny("수뇌가 아닙니다.")
    }
}

/** NotChief.php — pass when officer_level <= 4. */
fun notChief() = object : Constraint {
    override val name = "NotChief"
    override fun requires(ctx: ConstraintContext) = listOf(RequirementKey.General(ctx.actorId))
    override fun test(ctx: ConstraintContext, view: StateView): ConstraintResult {
        val g = gen(ctx, view) ?: return ConstraintResult.Unknown(requires(ctx))
        return if (g.officerLevel <= 4) ConstraintResult.Allow else ConstraintResult.Deny("수뇌입니다.")
    }
}

/** BeLord.php — pass when officer_level == 12. */
fun beLord() = object : Constraint {
    override val name = "BeLord"
    override fun requires(ctx: ConstraintContext) = listOf(RequirementKey.General(ctx.actorId))
    override fun test(ctx: ConstraintContext, view: StateView): ConstraintResult {
        val g = gen(ctx, view) ?: return ConstraintResult.Unknown(requires(ctx))
        return if (g.officerLevel == 12) ConstraintResult.Allow else ConstraintResult.Deny("군주가 아닙니다.")
    }
}

/** NotLord.php — pass when officer_level != 12. */
fun notLord() = object : Constraint {
    override val name = "NotLord"
    override fun requires(ctx: ConstraintContext) = listOf(RequirementKey.General(ctx.actorId))
    override fun test(ctx: ConstraintContext, view: StateView): ConstraintResult {
        val g = gen(ctx, view) ?: return ConstraintResult.Unknown(requires(ctx))
        return if (g.officerLevel != 12) ConstraintResult.Allow else ConstraintResult.Deny("군주입니다.")
    }
}

/** MustBeNPC.php — DENY when npc<2 with `NPC여야 합니다.` (TS-divergent reason). */
fun mustBeNPC() = object : Constraint {
    override val name = "MustBeNPC"
    override fun requires(ctx: ConstraintContext) = listOf(RequirementKey.General(ctx.actorId))
    override fun test(ctx: ConstraintContext, view: StateView): ConstraintResult {
        val g = gen(ctx, view) ?: return ConstraintResult.Unknown(requires(ctx))
        return if (g.npcType < 2) ConstraintResult.Deny("NPC여야 합니다.") else ConstraintResult.Allow
    }
}

/** MustBeTroopLeader.php — pass when general.no == general.troop (NO DB; the membership self-check). */
fun mustBeTroopLeader() = object : Constraint {
    override val name = "MustBeTroopLeader"
    override fun requires(ctx: ConstraintContext) = listOf(RequirementKey.General(ctx.actorId))
    override fun test(ctx: ConstraintContext, view: StateView): ConstraintResult {
        val g = gen(ctx, view) ?: return ConstraintResult.Unknown(requires(ctx))
        return if (g.id == g.troop) ConstraintResult.Allow else ConstraintResult.Deny("부대장이 아닙니다.")
    }
}

/**
 * ReqTroopMembers.php — PHP queries `general WHERE troop=? AND no!=?` for a fellow member; pass when one
 * exists. The caller preloads that existence as a predicate (DB must NOT be touched inside test()).
 */
fun reqTroopMembers(hasMember: (ConstraintContext, StateView) -> Boolean) = object : Constraint {
    override val name = "ReqTroopMembers"
    override fun requires(ctx: ConstraintContext) = listOf(RequirementKey.General(ctx.actorId))
    override fun test(ctx: ConstraintContext, view: StateView): ConstraintResult {
        gen(ctx, view) ?: return ConstraintResult.Unknown(requires(ctx))
        return if (hasMember(ctx, view)) ConstraintResult.Allow else ConstraintResult.Deny("집합 가능한 부대원이 없습니다.")
    }
}

/** ReqGeneralCrew.php — pass when crew > 0. */
fun reqGeneralCrew() = object : Constraint {
    override val name = "ReqGeneralCrew"
    override fun requires(ctx: ConstraintContext) = listOf(RequirementKey.General(ctx.actorId))
    override fun test(ctx: ConstraintContext, view: StateView): ConstraintResult {
        val g = gen(ctx, view) ?: return ConstraintResult.Unknown(requires(ctx))
        return if (g.crew > 0) ConstraintResult.Allow else ConstraintResult.Deny("병사가 모자랍니다.")
    }
}

/** ReqGeneralAtmosMargin.php — pass when atmos < maxAtmos(arg). */
fun reqGeneralAtmosMargin(maxAtmos: (ConstraintContext, StateView) -> Int) = object : Constraint {
    override val name = "ReqGeneralAtmosMargin"
    override fun requires(ctx: ConstraintContext) = listOf(RequirementKey.General(ctx.actorId))
    override fun test(ctx: ConstraintContext, view: StateView): ConstraintResult {
        val g = gen(ctx, view) ?: return ConstraintResult.Unknown(requires(ctx))
        return if (g.atmos < maxAtmos(ctx, view)) ConstraintResult.Allow else ConstraintResult.Deny("이미 사기는 하늘을 찌를듯 합니다.")
    }
}

/** ReqGeneralTrainMargin.php — pass when train < maxTrain(arg). */
fun reqGeneralTrainMargin(maxTrain: (ConstraintContext, StateView) -> Int) = object : Constraint {
    override val name = "ReqGeneralTrainMargin"
    override fun requires(ctx: ConstraintContext) = listOf(RequirementKey.General(ctx.actorId))
    override fun test(ctx: ConstraintContext, view: StateView): ConstraintResult {
        val g = gen(ctx, view) ?: return ConstraintResult.Unknown(requires(ctx))
        return if (g.train < maxTrain(ctx, view)) ConstraintResult.Allow else ConstraintResult.Deny("병사들은 이미 정예병사들입니다.")
    }
}

/**
 * ReqGeneralCrewMargin.php — pass when the requested crewType differs from the general's effective
 * crewType, OR effective leadership*100 > crew. The effective leadership / current crewType (both
 * RankVar/city-adjusted in PHP — getLeadership()/getCrewTypeObj()) are supplied as preloaded lambdas
 * (the stat-stack derivation is F-PIPELINE's, not C-PURE's).
 */
fun reqGeneralCrewMargin(
    reqCrewTypeId: Int,
    curCrewTypeId: (ConstraintContext, StateView) -> Int,
    leadership: (ConstraintContext, StateView) -> Int,
) = object : Constraint {
    override val name = "ReqGeneralCrewMargin"
    override fun requires(ctx: ConstraintContext) = listOf(RequirementKey.General(ctx.actorId))
    override fun test(ctx: ConstraintContext, view: StateView): ConstraintResult {
        val g = gen(ctx, view) ?: return ConstraintResult.Unknown(requires(ctx))
        if (reqCrewTypeId != curCrewTypeId(ctx, view)) return ConstraintResult.Allow
        return if (leadership(ctx, view) * 100 > g.crew) ConstraintResult.Allow
        else ConstraintResult.Deny("이미 많은 병력을 보유하고 있습니다.")
    }
}

/** AllowJoinAction.php — pass when general.makelimit (meta) == 0; reason carries GameConst.joinActionLimit. */
fun allowJoinAction() = object : Constraint {
    override val name = "AllowJoinAction"
    override fun requires(ctx: ConstraintContext) = listOf(RequirementKey.General(ctx.actorId))
    override fun test(ctx: ConstraintContext, view: StateView): ConstraintResult {
        val g = gen(ctx, view) ?: return ConstraintResult.Unknown(requires(ctx))
        if (metaInt(g.meta, "makelimit") == 0) return ConstraintResult.Allow
        return ConstraintResult.Deny("재야가 된지 ${GameConst.joinActionLimit}턴이 지나야 합니다.")
    }
}

/**
 * NoPenalty.php — pass when penaltyKey absent from the general's `penalty` map (rides meta['penalty']);
 * else DENY with `징계 사유: {penaltyList[key]}`.
 */
fun noPenalty(penaltyKey: String) = object : Constraint {
    override val name = "NoPenalty"
    override fun requires(ctx: ConstraintContext) = listOf(RequirementKey.General(ctx.actorId))
    override fun test(ctx: ConstraintContext, view: StateView): ConstraintResult {
        val g = gen(ctx, view) ?: return ConstraintResult.Unknown(requires(ctx))
        @Suppress("UNCHECKED_CAST")
        val penalty = (g.meta["penalty"] as? Map<String, Any?>) ?: emptyMap()
        if (!penalty.containsKey(penaltyKey)) return ConstraintResult.Allow
        return ConstraintResult.Deny("징계 사유: ${penalty[penaltyKey]}")
    }
}

// --- Nation state ---

/** WanderingNation.php — pass when level==0; reason `방랑군이어야 합니다` (TS-divergent: NO trailing period). */
fun wanderingNation() = object : Constraint {
    override val name = "WanderingNation"
    override fun requires(ctx: ConstraintContext) = listOf(RequirementKey.Nation(ctx.nationId ?: 0))
    override fun test(ctx: ConstraintContext, view: StateView): ConstraintResult {
        val n = nation(ctx, view) ?: return ConstraintResult.Unknown(requires(ctx))
        return if (n.level == 0) ConstraintResult.Allow else ConstraintResult.Deny("방랑군이어야 합니다")
    }
}

/**
 * NotCapital.php — pass when general.city != nation.capital; the `ignoreOfficer` (PHP `arg`) branch also
 * passes a capital city for officer_level in 2..4. Else `이미 수도입니다.`.
 */
fun notCapital(ignoreOfficer: Boolean = false) = object : Constraint {
    override val name = "NotCapital"
    override fun requires(ctx: ConstraintContext) =
        listOf(RequirementKey.General(ctx.actorId), RequirementKey.Nation(ctx.nationId ?: 0))
    override fun test(ctx: ConstraintContext, view: StateView): ConstraintResult {
        val g = gen(ctx, view) ?: return ConstraintResult.Unknown(requires(ctx))
        val n = nation(ctx, view) ?: return ConstraintResult.Unknown(requires(ctx))
        if (n.capitalCityId != g.cityId) return ConstraintResult.Allow
        if (ignoreOfficer && g.officerLevel in 2..4) return ConstraintResult.Allow
        return ConstraintResult.Deny("이미 수도입니다.")
    }
}

/** AllowWar.php — pass when nation.war (meta) == 0. */
fun allowWar() = object : Constraint {
    override val name = "AllowWar"
    override fun requires(ctx: ConstraintContext) = listOf(RequirementKey.Nation(ctx.nationId ?: 0))
    override fun test(ctx: ConstraintContext, view: StateView): ConstraintResult {
        val n = nation(ctx, view) ?: return ConstraintResult.Unknown(requires(ctx))
        return if (metaInt(n.meta, "war") == 0) ConstraintResult.Allow else ConstraintResult.Deny("현재 전쟁 금지입니다.")
    }
}

/** AllowStrategicCommand.php — identical pure form to AllowWar (pass when nation.war (meta) == 0). */
fun allowStrategicCommand() = object : Constraint {
    override val name = "AllowStrategicCommand"
    override fun requires(ctx: ConstraintContext) = listOf(RequirementKey.Nation(ctx.nationId ?: 0))
    override fun test(ctx: ConstraintContext, view: StateView): ConstraintResult {
        val n = nation(ctx, view) ?: return ConstraintResult.Unknown(requires(ctx))
        return if (metaInt(n.meta, "war") == 0) ConstraintResult.Allow else ConstraintResult.Deny("현재 전쟁 금지입니다.")
    }
}

/** ReqNationGold.php — pass when nation.gold >= req(arg). */
fun reqNationGold(req: (ConstraintContext, StateView) -> Int) = object : Constraint {
    override val name = "ReqNationGold"
    override fun requires(ctx: ConstraintContext) = listOf(RequirementKey.Nation(ctx.nationId ?: 0))
    override fun test(ctx: ConstraintContext, view: StateView): ConstraintResult {
        val n = nation(ctx, view) ?: return ConstraintResult.Unknown(requires(ctx))
        return if (n.gold >= req(ctx, view)) ConstraintResult.Allow else ConstraintResult.Deny("국고가 부족합니다.")
    }
}

/** ReqNationRice.php — pass when nation.rice >= req(arg). */
fun reqNationRice(req: (ConstraintContext, StateView) -> Int) = object : Constraint {
    override val name = "ReqNationRice"
    override fun requires(ctx: ConstraintContext) = listOf(RequirementKey.Nation(ctx.nationId ?: 0))
    override fun test(ctx: ConstraintContext, view: StateView): ConstraintResult {
        val n = nation(ctx, view) ?: return ConstraintResult.Unknown(requires(ctx))
        return if (n.rice >= req(ctx, view)) ConstraintResult.Allow else ConstraintResult.Deny("병량이 부족합니다.")
    }
}

// --- City state ---

/** NotOccupiedCity.php — pass when city.nation != general.nation; else `아국입니다.`. */
fun notOccupiedCity() = object : Constraint {
    override val name = "NotOccupiedCity"
    override fun requires(ctx: ConstraintContext) =
        listOf(RequirementKey.General(ctx.actorId), RequirementKey.City(ctx.cityId ?: 0))
    override fun test(ctx: ConstraintContext, view: StateView): ConstraintResult {
        val g = gen(ctx, view) ?: return ConstraintResult.Unknown(requires(ctx))
        val c = city(ctx, view) ?: return ConstraintResult.Unknown(requires(ctx))
        return if (c.nationId != g.nationId) ConstraintResult.Allow else ConstraintResult.Deny("아국입니다.")
    }
}

/** NeutralCity.php — pass when city.nation == 0; else `공백지가 아닙니다.`. */
fun neutralCity() = object : Constraint {
    override val name = "NeutralCity"
    override fun requires(ctx: ConstraintContext) = listOf(RequirementKey.City(ctx.cityId ?: 0))
    override fun test(ctx: ConstraintContext, view: StateView): ConstraintResult {
        val c = city(ctx, view) ?: return ConstraintResult.Unknown(requires(ctx))
        return if (c.nationId == 0) ConstraintResult.Allow else ConstraintResult.Deny("공백지가 아닙니다.")
    }
}

/**
 * ConstructableCity.php — DENY occupied (`공백지가 아닙니다.`); DENY level not in {5,6}
 * (`중, 소 도시에만 가능합니다.`); else pass. PHP checks nation first, then level.
 */
fun constructableCity() = object : Constraint {
    override val name = "ConstructableCity"
    override fun requires(ctx: ConstraintContext) = listOf(RequirementKey.City(ctx.cityId ?: 0))
    override fun test(ctx: ConstraintContext, view: StateView): ConstraintResult {
        val c = city(ctx, view) ?: return ConstraintResult.Unknown(requires(ctx))
        if (c.nationId != 0) return ConstraintResult.Deny("공백지가 아닙니다.")
        if (c.level !in listOf(5, 6)) return ConstraintResult.Deny("중, 소 도시에만 가능합니다.")
        return ConstraintResult.Allow
    }
}

/** ReqCityTrust.php — pass when city.trust >= minTrust(arg); else `민심이 낮아 주민들이 도망갑니다.`. */
fun reqCityTrust(minTrust: (ConstraintContext, StateView) -> Double) = object : Constraint {
    override val name = "ReqCityTrust"
    override fun requires(ctx: ConstraintContext) = listOf(RequirementKey.City(ctx.cityId ?: 0))
    override fun test(ctx: ConstraintContext, view: StateView): ConstraintResult {
        val c = city(ctx, view) ?: return ConstraintResult.Unknown(requires(ctx))
        return if (c.trust >= minTrust(ctx, view)) ConstraintResult.Allow else ConstraintResult.Deny("민심이 낮아 주민들이 도망갑니다.")
    }
}

/** ReqCityTrader.php:25-30 — pass when city.trade != null OR npcType(arg) >= 2; else `도시에 상인이 없습니다.`. */
fun reqCityTrader(npcType: (ConstraintContext, StateView) -> Int) = object : Constraint {
    override val name = "ReqCityTrader"
    override fun requires(ctx: ConstraintContext) = listOf(RequirementKey.City(ctx.cityId ?: 0))
    override fun test(ctx: ConstraintContext, view: StateView): ConstraintResult {
        val c = city(ctx, view) ?: return ConstraintResult.Unknown(requires(ctx))
        return if (c.trade != null || npcType(ctx, view) >= 2) ConstraintResult.Allow else ConstraintResult.Deny("도시에 상인이 없습니다.")
    }
}

/** RemainCityTrust.php — pass when city.trust < 100; else `{actionName}{은/는} 충분합니다.` (josa 은). */
fun remainCityTrust(actionName: String) = object : Constraint {
    override val name = "RemainCityTrust"
    override fun requires(ctx: ConstraintContext) = listOf(RequirementKey.City(ctx.cityId ?: 0))
    override fun test(ctx: ConstraintContext, view: StateView): ConstraintResult {
        val c = city(ctx, view) ?: return ConstraintResult.Unknown(requires(ctx))
        if (c.trust < 100) return ConstraintResult.Allow
        val josaUn = JosaUtil.pick(actionName, "은")
        return ConstraintResult.Deny("$actionName$josaUn 충분합니다.")
    }
}

/**
 * BattleGroundCity.php — pass when dest city is neutral (nation==0) OR my nation is at war with the
 * dest city's nation (diplomacy.state==0). PHP queries diplomacy; the at-war existence is preloaded
 * as a predicate. Reason `교전중인 국가의 도시가 아닙니다.`. (Operates on ctx.cityId as the dest city.)
 */
fun battleGroundCity(atWarWithDest: (ConstraintContext, StateView) -> Boolean) = object : Constraint {
    override val name = "BattleGroundCity"
    override fun requires(ctx: ConstraintContext) =
        listOf(RequirementKey.General(ctx.actorId), RequirementKey.City(ctx.cityId ?: 0))
    override fun test(ctx: ConstraintContext, view: StateView): ConstraintResult {
        val c = city(ctx, view) ?: return ConstraintResult.Unknown(requires(ctx))
        if (c.nationId == 0) return ConstraintResult.Allow
        return if (atWarWithDest(ctx, view)) ConstraintResult.Allow else ConstraintResult.Deny("교전중인 국가의 도시가 아닙니다.")
    }
}

// --- Env / relYear state (BeOpeningPart/NotOpeningPart compare a relYear arg to GameConst.openingPartYear) ---

/** BeOpeningPart.php — pass when relYear(arg) < GameConst.openingPartYear; else `초반이 지났습니다.`. */
fun beOpeningPart(relYear: (ConstraintContext, StateView) -> Int) = object : Constraint {
    override val name = "BeOpeningPart"
    override fun requires(ctx: ConstraintContext) = emptyList<RequirementKey>()
    override fun test(ctx: ConstraintContext, view: StateView): ConstraintResult =
        if (relYear(ctx, view) < GameConst.openingPartYear) ConstraintResult.Allow else ConstraintResult.Deny("초반이 지났습니다.")
}

/** NotOpeningPart.php — pass when relYear(arg) >= GameConst.openingPartYear; else `초반 제한 중에는 불가능합니다.`. */
fun notOpeningPart(relYear: (ConstraintContext, StateView) -> Int) = object : Constraint {
    override val name = "NotOpeningPart"
    override fun requires(ctx: ConstraintContext) = emptyList<RequirementKey>()
    override fun test(ctx: ConstraintContext, view: StateView): ConstraintResult =
        if (relYear(ctx, view) >= GameConst.openingPartYear) ConstraintResult.Allow else ConstraintResult.Deny("초반 제한 중에는 불가능합니다.")
}

// --- Military presets the Wave-3 protocol moves into C-PURE ---

/**
 * AvailableRecruitCrewType.php — pass when the crewType is recruitable for this general/nation. PHP runs
 * `crewType.isValid(general, ownCities, ownRegions, relYear, tech, aux)` against DB-loaded owned-city
 * state; that whole predicate is preloaded by the caller (no DB inside test()). Reason
 * `현재 선택할 수 없는 병종입니다.`.
 */
fun availableRecruitCrewType(crewTypeId: Int, recruitable: (ConstraintContext, StateView) -> Boolean) = object : Constraint {
    override val name = "AvailableRecruitCrewType"
    override fun requires(ctx: ConstraintContext) =
        listOf(RequirementKey.General(ctx.actorId), RequirementKey.Nation(ctx.nationId ?: 0))
    override fun test(ctx: ConstraintContext, view: StateView): ConstraintResult {
        gen(ctx, view) ?: return ConstraintResult.Unknown(requires(ctx))
        nation(ctx, view) ?: return ConstraintResult.Unknown(requires(ctx))
        return if (recruitable(ctx, view)) ConstraintResult.Allow else ConstraintResult.Deny("현재 선택할 수 없는 병종입니다.")
    }
}

/**
 * ReqCityCapacity.php — numeric path: pass when city[key] >= reqVal. Percent path (reqVal a `NN%` string)
 * lives in Comparators.kt (CP2) — this CP1 form is the plain-numeric one. DENY josa `이`:
 * `{keyNick}{이/가} 부족합니다.`.
 */
fun reqCityCapacity(cityKey: String, keyNick: String, reqVal: Int) = object : Constraint {
    override val name = "ReqCityCapacity"
    override fun requires(ctx: ConstraintContext) = listOf(RequirementKey.City(ctx.cityId ?: 0))
    override fun test(ctx: ConstraintContext, view: StateView): ConstraintResult {
        val c = city(ctx, view) ?: return ConstraintResult.Unknown(requires(ctx))
        val cur = cityIntField(c, cityKey)
        if (cur >= reqVal) return ConstraintResult.Allow
        val josaYi = JosaUtil.pick(keyNick, "이")
        return ConstraintResult.Deny("$keyNick$josaYi 부족합니다.")
    }
}

/** Shared city int-field resolver (the V1 city columns the capacity/value constraints read by name). */
internal fun cityIntField(c: City, key: String): Int = when (key) {
    "pop" -> c.population
    "pop_max" -> c.populationMax
    "agri" -> c.agriculture
    "agri_max" -> c.agricultureMax
    "comm" -> c.commerce
    "comm_max" -> c.commerceMax
    "secu" -> c.security
    "secu_max" -> c.securityMax
    "def" -> c.defense
    "def_max" -> c.defenseMax
    "wall" -> c.wall
    "wall_max" -> c.wallMax
    else -> error("unknown city int field $key")
}
