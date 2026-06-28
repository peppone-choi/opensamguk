package opensamguk.logic.constraints

import opensamguk.common.constants.GameConst
import opensamguk.common.josa.JosaUtil
import opensamguk.logic.domain.City
import opensamguk.logic.domain.Diplomacy
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
    "level" -> c.level                  // PHP city.level('규모') — ReqDestCityValue('level',...) in che_감축/che_증축
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

// ============================================================================
// CP2 — Req*Value comparator family + parsePercent. compareValues / parsePercent live in Comparators.kt.
// The final reason is `{keyNick}{josa-이} {fragment}` UNLESS a non-empty errMsg overrides it
// (reqEnvValue / reqNationAuxValue ALWAYS use errMsg — boolean comparator, no derived text).
// ============================================================================

/** Numeric field resolver for the general Req*Value path. */
internal fun generalNumField(g: General, key: String): Number = when (key) {
    "leadership" -> g.leadership
    "strength" -> g.strength
    "intel" -> g.intel
    "injury" -> g.injury
    "crew" -> g.crew
    "train" -> g.train
    "atmos" -> g.atmos
    "gold" -> g.gold
    "rice" -> g.rice
    "experience" -> g.experience
    "dedication" -> g.dedication
    "officer_level" -> g.officerLevel
    else -> error("unknown general num field $key")
}

/** Numeric field resolver for the nation Req*Value path. */
internal fun nationNumField(n: Nation, key: String): Number = when (key) {
    // PHP nation 테이블의 PK 컬럼명은 'nation'(국가 번호) — ReqDestNationValue('nation',...)가
    // 제의 장수 소속 검증에 쓴다(che_불가침수락/종전수락/불가침파기수락.php). 도메인에서는 Nation.id가 동일.
    "nation" -> n.id
    "gold" -> n.gold
    "rice" -> n.rice
    "tech" -> n.tech
    "gennum" -> n.gennum
    "level" -> n.level
    "capset" -> n.capset
    // V1 keeps these PHP nation scalar columns in Nation.meta; ReqNationValue still addresses them
    // by their PHP field names, so the generic comparator must resolve them here.
    "rate", "bill", "surlimit", "secretlimit", "strategic_cmd_limit", "war", "scout" -> metaInt(n.meta, key)
    else -> error("unknown nation num field $key")
}

/** Assemble the final Req*Value reason from a derived fragment, applying the errMsg override + josa-이. */
private fun assembleReason(keyNick: String, errMsg: String?, fragment: String): String {
    if (!errMsg.isNullOrEmpty()) return errMsg
    val josaYi = JosaUtil.pick(keyNick, "이")
    return "$keyNick$josaYi $fragment"
}

/**
 * ReqGeneralValue.php — generic general-field comparator. `reqVal` is numeric (no percent path for
 * generals). On fail, errMsg overrides; else `{keyNick}{josa} {derived}`.
 */
fun reqGeneralValue(key: String, keyNick: String, comp: String, reqVal: Number, errMsg: String? = null) = object : Constraint {
    override val name = "ReqGeneralValue"
    override fun requires(ctx: ConstraintContext) = listOf(RequirementKey.General(ctx.actorId))
    override fun test(ctx: ConstraintContext, view: StateView): ConstraintResult {
        val g = gen(ctx, view) ?: return ConstraintResult.Unknown(requires(ctx))
        val result = compareValues(generalNumField(g, key), reqVal, comp, keyNick)
        if (result == COMPARE_PASS) return ConstraintResult.Allow
        return ConstraintResult.Deny(assembleReason(keyNick, errMsg, result as String))
    }
}

/**
 * ReqCityValue.php — generic city-field comparator with percent support: a `NN%` reqVal compares
 * city[key] against city[key_max] * fraction. On fail, errMsg overrides; else `{keyNick}{josa} {derived}`.
 */
fun reqCityValue(key: String, keyNick: String, comp: String, reqVal: Any, errMsg: String? = null) = object : Constraint {
    override val name = "ReqCityValue"
    override fun requires(ctx: ConstraintContext) = listOf(RequirementKey.City(ctx.cityId ?: 0))
    override fun test(ctx: ConstraintContext, view: StateView): ConstraintResult {
        val c = city(ctx, view) ?: return ConstraintResult.Unknown(requires(ctx))
        val target = cityIntField(c, key)
        val src: Number = resolveReqVal(reqVal) { cityIntField(c, "${key}_max") }
        val result = compareValues(target, src, comp, keyNick)
        if (result == COMPARE_PASS) return ConstraintResult.Allow
        return ConstraintResult.Deny(assembleReason(keyNick, errMsg, result as String))
    }
}

/**
 * ReqNationValue.php — generic nation-field comparator (percent compares nation[key] vs nation[key_max]
 * * fraction; the nation fields used in P2 are non-percent, but the path is ported for parity).
 */
fun reqNationValue(key: String, keyNick: String, comp: String, reqVal: Any, errMsg: String? = null) = object : Constraint {
    override val name = "ReqNationValue"
    override fun requires(ctx: ConstraintContext) = listOf(RequirementKey.Nation(ctx.nationId ?: 0))
    override fun test(ctx: ConstraintContext, view: StateView): ConstraintResult {
        val n = nation(ctx, view) ?: return ConstraintResult.Unknown(requires(ctx))
        val target = nationNumField(n, key)
        val src: Number = resolveReqVal(reqVal) { nationNumField(n, "${key}_max") }
        val result = compareValues(target, src, comp, keyNick)
        if (result == COMPARE_PASS) return ConstraintResult.Allow
        return ConstraintResult.Deny(assembleReason(keyNick, errMsg, result as String))
    }
}

/**
 * ReqDestCityValue.php — same comparator core, target is the DEST city's field. The DestCity
 * RequirementKey is C-DEST's (CD1); until that lands, the target (and the percent `key_max`) are
 * supplied by the caller as lambdas — the comparator/reason logic is identical to ReqCityValue.
 */
fun reqDestCityValue(
    key: String,
    keyNick: String,
    comp: String,
    reqVal: Any,
    errMsg: String? = null,
    max: ((ConstraintContext, StateView) -> Number)? = null,
    target: (ConstraintContext, StateView) -> Number,
) = object : Constraint {
    override val name = "ReqDestCityValue"
    override fun requires(ctx: ConstraintContext) = emptyList<RequirementKey>()
    override fun test(ctx: ConstraintContext, view: StateView): ConstraintResult {
        val t = target(ctx, view)
        val src: Number = resolveReqVal(reqVal) { (max ?: error("percent reqVal needs a max supplier"))(ctx, view) }
        val result = compareValues(t, src, comp, keyNick)
        if (result == COMPARE_PASS) return ConstraintResult.Allow
        return ConstraintResult.Deny(assembleReason(keyNick, errMsg, result as String))
    }
}

/** ReqDestNationValue.php — dest-nation comparator (caller supplies target + optional percent max). */
fun reqDestNationValue(
    key: String,
    keyNick: String,
    comp: String,
    reqVal: Any,
    errMsg: String? = null,
    max: ((ConstraintContext, StateView) -> Number)? = null,
    target: (ConstraintContext, StateView) -> Number,
) = object : Constraint {
    override val name = "ReqDestNationValue"
    override fun requires(ctx: ConstraintContext) = emptyList<RequirementKey>()
    override fun test(ctx: ConstraintContext, view: StateView): ConstraintResult {
        val t = target(ctx, view)
        val src: Number = resolveReqVal(reqVal) { (max ?: error("percent reqVal needs a max supplier"))(ctx, view) }
        val result = compareValues(t, src, comp, keyNick)
        if (result == COMPARE_PASS) return ConstraintResult.Allow
        return ConstraintResult.Deny(assembleReason(keyNick, errMsg, result as String))
    }
}

/**
 * ReqNationAuxValue.php — reads nation aux[key] (rides nation.meta['aux']), defaulting to `defaultValue`
 * when absent. STRIPPED comparator: boolean-only, the reason is ALWAYS the caller errMsg (no derived text).
 */
fun reqNationAuxValue(key: String, defaultValue: Any?, comp: String, reqVal: Any?, errMsg: String) = object : Constraint {
    override val name = "ReqNationAuxValue"
    override fun requires(ctx: ConstraintContext) = listOf(RequirementKey.Nation(ctx.nationId ?: 0))
    override fun test(ctx: ConstraintContext, view: StateView): ConstraintResult {
        val n = nation(ctx, view) ?: return ConstraintResult.Unknown(requires(ctx))
        @Suppress("UNCHECKED_CAST")
        val aux = (n.meta["aux"] as? Map<String, Any?>) ?: emptyMap()
        val target = if (aux.containsKey(key)) aux[key] else defaultValue
        return if (compareBool(target, reqVal, comp)) ConstraintResult.Allow else ConstraintResult.Deny(errMsg)
    }
}

/**
 * ReqEnvValue.php — reads env[key]; STRIPPED comparator (boolean-only), reason ALWAYS the caller errMsg.
 */
fun reqEnvValue(key: String, comp: String, reqVal: Any?, errMsg: String) = object : Constraint {
    override val name = "ReqEnvValue"
    override fun requires(ctx: ConstraintContext) = listOf(RequirementKey.Env(key))
    override fun test(ctx: ConstraintContext, view: StateView): ConstraintResult {
        if (!view.has(RequirementKey.Env(key))) return ConstraintResult.Unknown(requires(ctx))
        val target = view.get(RequirementKey.Env(key))
        return if (compareBool(target, reqVal, comp)) ConstraintResult.Allow else ConstraintResult.Deny(errMsg)
    }
}

/**
 * ReqCityCapacity.php percent path — `reqVal` is a `NN%` string; pass when city[key] >= city[key_max]
 * * fraction. DENY josa `이`: `{keyNick}{이/가} 부족합니다.` (the numeric path is [reqCityCapacity]).
 */
fun reqCityCapacityPercent(cityKey: String, keyNick: String, percent: String) = object : Constraint {
    override val name = "ReqCityCapacity"
    override fun requires(ctx: ConstraintContext) = listOf(RequirementKey.City(ctx.cityId ?: 0))
    override fun test(ctx: ConstraintContext, view: StateView): ConstraintResult {
        val c = city(ctx, view) ?: return ConstraintResult.Unknown(requires(ctx))
        val fraction = parsePercent(percent) ?: error("invalid percent string $percent")
        val cur = cityIntField(c, cityKey)
        val threshold = cityIntField(c, "${cityKey}_max") * fraction
        if (cur >= threshold) return ConstraintResult.Allow
        val josaYi = JosaUtil.pick(keyNick, "이")
        return ConstraintResult.Deny("$keyNick$josaYi 부족합니다.")
    }
}

/** Resolve a Req*Value `reqVal`: numeric → itself; `NN%` string → fraction * max(). */
private inline fun resolveReqVal(reqVal: Any, max: () -> Number): Number = when (reqVal) {
    is Number -> reqVal
    is String -> {
        val fraction = parsePercent(reqVal) ?: error("invalid reqVal (percentStr|numeric): $reqVal")
        max().toDouble() * fraction
    }
    else -> error("invalid reqVal type: $reqVal")
}

// ============================================================================
// CD2 — dest-* / diplomacy / name-dup presets. Faithful port of PHP Constraint/[Name].php
// (reason strings + test order). The dest rows resolve through the CD1 RequirementKey.Dest*
// keys (the two adapters preload them — DB/pathfinding-backed constraints NEVER touch the DB or
// searchDistance inside test(): NearCity takes a preloaded reachable-set predicate, diplomacy reads
// the preloaded directional row, CheckNationNameDuplicate scans the preloaded NationList).
// ============================================================================

private fun destGeneral(ctx: ConstraintContext, view: StateView) =
    ctx.destGeneralId?.let { view.get(RequirementKey.DestGeneral(it)) as? General }
private fun destCity(ctx: ConstraintContext, view: StateView) =
    ctx.destCityId?.let { view.get(RequirementKey.DestCity(it)) as? City }
private fun destNation(ctx: ConstraintContext, view: StateView) =
    ctx.destNationId?.let { view.get(RequirementKey.DestNation(it)) as? Nation }

// --- Dest general ---

/** FriendlyDestGeneral.php — pass when general.nation == destGeneral.nation; else `아국 장수가 아닙니다.`. */
fun friendlyDestGeneral() = object : Constraint {
    override val name = "FriendlyDestGeneral"
    override fun requires(ctx: ConstraintContext) =
        listOf(RequirementKey.General(ctx.actorId), RequirementKey.DestGeneral(ctx.destGeneralId ?: 0))
    override fun test(ctx: ConstraintContext, view: StateView): ConstraintResult {
        val g = gen(ctx, view) ?: return ConstraintResult.Unknown(requires(ctx))
        val d = destGeneral(ctx, view) ?: return ConstraintResult.Unknown(requires(ctx))
        return if (g.nationId == d.nationId) ConstraintResult.Allow else ConstraintResult.Deny("아국 장수가 아닙니다.")
    }
}

/** ExistsDestGeneral.php — pass when destGeneral['no'] is truthy (id != 0); else `없는 장수입니다.`. */
fun existsDestGeneral() = object : Constraint {
    override val name = "ExistsDestGeneral"
    override fun requires(ctx: ConstraintContext) = listOf(RequirementKey.DestGeneral(ctx.destGeneralId ?: 0))
    override fun test(ctx: ConstraintContext, view: StateView): ConstraintResult {
        val d = destGeneral(ctx, view) ?: return ConstraintResult.Unknown(requires(ctx))
        return if (d.id != 0) ConstraintResult.Allow else ConstraintResult.Deny("없는 장수입니다.")
    }
}

/** DifferentNationDestGeneral.php — pass when destGeneral.nation != general.nation; else `같은 국가의 장수입니다.`. */
fun differentNationDestGeneral() = object : Constraint {
    override val name = "DifferentNationDestGeneral"
    override fun requires(ctx: ConstraintContext) =
        listOf(RequirementKey.General(ctx.actorId), RequirementKey.DestGeneral(ctx.destGeneralId ?: 0))
    override fun test(ctx: ConstraintContext, view: StateView): ConstraintResult {
        val g = gen(ctx, view) ?: return ConstraintResult.Unknown(requires(ctx))
        val d = destGeneral(ctx, view) ?: return ConstraintResult.Unknown(requires(ctx))
        return if (d.nationId != g.nationId) ConstraintResult.Allow else ConstraintResult.Deny("같은 국가의 장수입니다.")
    }
}

// --- Dest nation ---

/** DifferentDestNation.php — pass when destNation.nation != general.nation; else `이미 같은 국가입니다.`. */
fun differentDestNation() = object : Constraint {
    override val name = "DifferentDestNation"
    override fun requires(ctx: ConstraintContext) =
        listOf(RequirementKey.General(ctx.actorId), RequirementKey.DestNation(ctx.destNationId ?: 0))
    override fun test(ctx: ConstraintContext, view: StateView): ConstraintResult {
        val g = gen(ctx, view) ?: return ConstraintResult.Unknown(requires(ctx))
        val d = destNation(ctx, view) ?: return ConstraintResult.Unknown(requires(ctx))
        return if (d.id != g.nationId) ConstraintResult.Allow else ConstraintResult.Deny("이미 같은 국가입니다.")
    }
}

// --- Dest city ---

/** NotNeutralDestCity.php — pass when destCity.nation != 0; else `공백지입니다.`. */
fun notNeutralDestCity() = object : Constraint {
    override val name = "NotNeutralDestCity"
    override fun requires(ctx: ConstraintContext) = listOf(RequirementKey.DestCity(ctx.destCityId ?: 0))
    override fun test(ctx: ConstraintContext, view: StateView): ConstraintResult {
        val c = destCity(ctx, view) ?: return ConstraintResult.Unknown(requires(ctx))
        return if (c.nationId != 0) ConstraintResult.Allow else ConstraintResult.Deny("공백지입니다.")
    }
}

/** NotSameDestCity.php — pass when destCity.city != general.city; else `같은 도시입니다.`. */
fun notSameDestCity() = object : Constraint {
    override val name = "NotSameDestCity"
    override fun requires(ctx: ConstraintContext) =
        listOf(RequirementKey.General(ctx.actorId), RequirementKey.DestCity(ctx.destCityId ?: 0))
    override fun test(ctx: ConstraintContext, view: StateView): ConstraintResult {
        val g = gen(ctx, view) ?: return ConstraintResult.Unknown(requires(ctx))
        val c = destCity(ctx, view) ?: return ConstraintResult.Unknown(requires(ctx))
        return if (c.id != g.cityId) ConstraintResult.Allow else ConstraintResult.Deny("같은 도시입니다.")
    }
}

/** NotOccupiedDestCity.php — pass when destCity.nation != general.nation; else `아국입니다.`. */
fun notOccupiedDestCity() = object : Constraint {
    override val name = "NotOccupiedDestCity"
    override fun requires(ctx: ConstraintContext) =
        listOf(RequirementKey.General(ctx.actorId), RequirementKey.DestCity(ctx.destCityId ?: 0))
    override fun test(ctx: ConstraintContext, view: StateView): ConstraintResult {
        val g = gen(ctx, view) ?: return ConstraintResult.Unknown(requires(ctx))
        val c = destCity(ctx, view) ?: return ConstraintResult.Unknown(requires(ctx))
        return if (c.nationId != g.nationId) ConstraintResult.Allow else ConstraintResult.Deny("아국입니다.")
    }
}

/** OccupiedDestCity.php — pass when destCity.nation == general.nation; else `대상 도시가 아국이 아닙니다.`. */
fun occupiedDestCity() = object : Constraint {
    override val name = "OccupiedDestCity"
    override fun requires(ctx: ConstraintContext) =
        listOf(RequirementKey.General(ctx.actorId), RequirementKey.DestCity(ctx.destCityId ?: 0))
    override fun test(ctx: ConstraintContext, view: StateView): ConstraintResult {
        val g = gen(ctx, view) ?: return ConstraintResult.Unknown(requires(ctx))
        val c = destCity(ctx, view) ?: return ConstraintResult.Unknown(requires(ctx))
        return if (c.nationId == g.nationId) ConstraintResult.Allow else ConstraintResult.Deny("대상 도시가 아국이 아닙니다.")
    }
}

/** SuppliedDestCity.php — pass when destCity.supply is truthy; else `고립된 도시입니다.`. */
fun suppliedDestCity() = object : Constraint {
    override val name = "SuppliedDestCity"
    override fun requires(ctx: ConstraintContext) = listOf(RequirementKey.DestCity(ctx.destCityId ?: 0))
    override fun test(ctx: ConstraintContext, view: StateView): ConstraintResult {
        val c = destCity(ctx, view) ?: return ConstraintResult.Unknown(requires(ctx))
        return if (c.supplyState != 0) ConstraintResult.Allow else ConstraintResult.Deny("고립된 도시입니다.")
    }
}

/**
 * NearCity.php — pass when destCity.city is in `searchDistance(general.city, arg, false)`. The
 * pathfinding is the map module's (Q9); the reachable-city-id set within `arg` steps is preloaded as
 * a predicate (NO searchDistance inside test()). Reason fork: arg==1 → `인접도시가 아닙니다.`; else
 * `거리가 너무 멉니다.`.
 */
fun nearCity(arg: Int, reachableWithin: (ConstraintContext, StateView) -> Set<Int>) = object : Constraint {
    override val name = "NearCity"
    override fun requires(ctx: ConstraintContext) =
        listOf(RequirementKey.General(ctx.actorId), RequirementKey.DestCity(ctx.destCityId ?: 0))
    override fun test(ctx: ConstraintContext, view: StateView): ConstraintResult {
        gen(ctx, view) ?: return ConstraintResult.Unknown(requires(ctx))
        val c = destCity(ctx, view) ?: return ConstraintResult.Unknown(requires(ctx))
        if (c.id in reachableWithin(ctx, view)) return ConstraintResult.Allow
        return ConstraintResult.Deny(if (arg == 1) "인접도시가 아닙니다." else "거리가 너무 멉니다.")
    }
}

// --- Nation name duplicate ---

/**
 * CheckNationNameDuplicate.php — DENY when another nation (id != ctx.nationId) already carries the
 * requested name (`args['name']`); else pass. PHP queries `nation WHERE name=? AND nation!=me`; the
 * full nation collection is preloaded (RequirementKey.NationList). Reason `존재하는 국가명입니다.`.
 */
fun checkNationNameDuplicate(nameArg: String = "name") = object : Constraint {
    override val name = "CheckNationNameDuplicate"
    override fun requires(ctx: ConstraintContext) =
        listOf(RequirementKey.Nation(ctx.nationId ?: 0), RequirementKey.NationList)
    override fun test(ctx: ConstraintContext, view: StateView): ConstraintResult {
        val wantName = ctx.args[nameArg] as? String ?: return ConstraintResult.Unknown(requires(ctx))
        @Suppress("UNCHECKED_CAST")
        val nations = (view.get(RequirementKey.NationList) as? Collection<Nation>)
            ?: return ConstraintResult.Unknown(requires(ctx))
        val exists = nations.any { it.name == wantName && it.id != ctx.nationId }
        return if (!exists) ConstraintResult.Allow else ConstraintResult.Deny("존재하는 국가명입니다.")
    }
}

// --- Join dest nation (4-branch order) ---

/**
 * AllowJoinDestNation.php — 4-branch order (FIRST failing branch wins):
 *  1. relYear < openingPartYear && destNation.gennum >= initialNationGenLimit → `임관이 제한되고 있습니다.`
 *  2. destNation.scout == 1 → `임관이 금지되어 있습니다.`
 *  3. general.npc < 2 (default 2) && destNation.name starts with `ⓤ` → `유저장은 태수국에 임관할 수 없습니다.`
 *  4. general.npc != 9 && destNation.name starts with `ⓞ` → `이민족 국가에 임관할 수 없습니다.`
 * `relYear` is supplied via `ctx.args['relYear']` (PHP `arg`); destNation.scout rides nation.meta['scout'].
 * The actor npcType comes from a preloaded lambda (PHP `general['npc']??2`).
 */
fun allowJoinDestNation(npcType: (ConstraintContext, StateView) -> Int) = object : Constraint {
    override val name = "AllowJoinDestNation"
    override fun requires(ctx: ConstraintContext) =
        listOf(RequirementKey.General(ctx.actorId), RequirementKey.DestNation(ctx.destNationId ?: 0))
    override fun test(ctx: ConstraintContext, view: StateView): ConstraintResult {
        gen(ctx, view) ?: return ConstraintResult.Unknown(requires(ctx))
        val d = destNation(ctx, view) ?: return ConstraintResult.Unknown(requires(ctx))
        val relYear = (ctx.args["relYear"] as? Number)?.toInt() ?: return ConstraintResult.Unknown(requires(ctx))
        val scout = metaInt(d.meta, "scout")
        val npc = npcType(ctx, view)

        if (relYear < GameConst.openingPartYear && d.gennum >= GameConst.initialNationGenLimit) {
            return ConstraintResult.Deny("임관이 제한되고 있습니다.")
        }
        if (scout == 1) {
            return ConstraintResult.Deny("임관이 금지되어 있습니다.")
        }
        if (npc < 2 && d.name.startsWith("ⓤ")) {
            return ConstraintResult.Deny("유저장은 태수국에 임관할 수 없습니다.")
        }
        if (npc != 9 && d.name.startsWith("ⓞ")) {
            return ConstraintResult.Deny("이민족 국가에 임관할 수 없습니다.")
        }
        return ConstraintResult.Allow
    }
}

// --- Diplomacy ---

/**
 * AllowDiplomacyStatus.php — pass when a diplomacy row `me == nationID && state IN allowStatus`
 * exists; else `errMsg`. PHP queries `diplomacy WHERE me=? AND state IN(?) LIMIT 1` — a per-`me`
 * existence scan that the `Diplomacy(me,you)` per-pair key cannot answer alone (the `you` set is
 * unknown). The matching-row existence is therefore preloaded as a predicate (the same idiom CP1
 * `battleGroundCity` uses for its diplomacy at-war check); the daemon/precheck adapters wire it from
 * the directional diplomacy rows they already load.
 */
fun allowDiplomacyStatus(
    errMsg: String,
    hasAllowedState: (ConstraintContext, StateView) -> Boolean,
) = object : Constraint {
    override val name = "AllowDiplomacyStatus"
    override fun requires(ctx: ConstraintContext) = emptyList<RequirementKey>()
    override fun test(ctx: ConstraintContext, view: StateView): ConstraintResult =
        if (hasAllowedState(ctx, view)) ConstraintResult.Allow else ConstraintResult.Deny(errMsg)
}

// ============================================================================
// FR1 — the 5 NEW diplomacy/pathfinding presets the AI-emitted FULL packs need
// (existsDestNation / nearNation / hasRoute / hasRouteWithEnemy +
//  disallowDiplomacyBetweenStatus / allowDiplomacyBetweenStatus / allowDiplomacyWithTerm).
// Faithful port of PHP Constraint/{ExistsDestNation,NearNation,HasRoute,HasRouteWithEnemy,
// DisallowDiplomacyBetweenStatus,AllowDiplomacyBetweenStatus,AllowDiplomacyWithTerm}.php.
// Reason strings are PHP grand truth. DB/isNeighbor/searchDistance-backed predicates are PRELOADED
// as lambdas (the CD2 idiom — NO DB / searchDistance / isNeighbor inside test()); the F-BFS-backed
// reachability + the directional diplomacy state are wired in by the daemon/precheck adapters.
// ============================================================================

/**
 * ExistsDestNation.php — pass when `destNation['nation']` is truthy (id != 0); else `멸망한 국가입니다.`.
 * (PHP reads the dest-nation row's `nation` field; a destroyed/absent nation has id 0.)
 */
fun existsDestNation() = object : Constraint {
    override val name = "ExistsDestNation"
    override fun requires(ctx: ConstraintContext) = listOf(RequirementKey.DestNation(ctx.destNationId ?: 0))
    override fun test(ctx: ConstraintContext, view: StateView): ConstraintResult {
        val d = destNation(ctx, view) ?: return ConstraintResult.Unknown(requires(ctx))
        return if (d.id != 0) ConstraintResult.Allow else ConstraintResult.Deny("멸망한 국가입니다.")
    }
}

/**
 * NearNation.php — pass when `isNeighbor(srcNation, destNation, false)`; else `인접 국가가 아닙니다.`.
 * PHP calls `\sammo\isNeighbor` over the map adjacency; that predicate is preloaded (F-BFS-backed,
 * NO adjacency walk inside test()).
 */
fun nearNation(isNeighbor: (ConstraintContext, StateView) -> Boolean) = object : Constraint {
    override val name = "NearNation"
    override fun requires(ctx: ConstraintContext) =
        listOf(RequirementKey.Nation(ctx.nationId ?: 0), RequirementKey.DestNation(ctx.destNationId ?: 0))
    override fun test(ctx: ConstraintContext, view: StateView): ConstraintResult {
        return if (isNeighbor(ctx, view)) ConstraintResult.Allow else ConstraintResult.Deny("인접 국가가 아닙니다.")
    }
}

/**
 * HasRoute.php — pass when `searchDistanceListToDest(general.city, destCity.city, [general.nation])`
 * is non-empty; else `경로에 도달할 방법이 없습니다.`. The BFS reachability is preloaded as a predicate
 * (F-BFS-backed, NO searchDistance inside test()).
 */
fun hasRoute(routeExists: (ConstraintContext, StateView) -> Boolean) = object : Constraint {
    override val name = "HasRoute"
    override fun requires(ctx: ConstraintContext) =
        listOf(RequirementKey.General(ctx.actorId), RequirementKey.DestCity(ctx.destCityId ?: 0))
    override fun test(ctx: ConstraintContext, view: StateView): ConstraintResult {
        return if (routeExists(ctx, view)) ConstraintResult.Allow else ConstraintResult.Deny("경로에 도달할 방법이 없습니다.")
    }
}

/**
 * HasRouteWithEnemy.php — two-stage (FIRST failing branch wins, PHP test order):
 *  1. the dest city must be neutral / mine / an at-war-allowed nation; else `교전중인 국가가 아닙니다.`;
 *  2. `searchDistanceListToDest(general.city, destCity.city, allowedNationList∪enemies∪{0})` must be
 *     non-empty; else `경로에 도달할 방법이 없습니다.`.
 * Both the at-war-eligibility of the dest city and the enemy-inclusive BFS reachability are preloaded
 * predicates (F-BFS / diplomacy-backed, NO DB / searchDistance inside test()).
 */
fun hasRouteWithEnemy(
    isAtWarDestCity: (ConstraintContext, StateView) -> Boolean,
    routeExists: (ConstraintContext, StateView) -> Boolean,
) = object : Constraint {
    override val name = "HasRouteWithEnemy"
    override fun requires(ctx: ConstraintContext) =
        listOf(RequirementKey.General(ctx.actorId), RequirementKey.DestCity(ctx.destCityId ?: 0))
    override fun test(ctx: ConstraintContext, view: StateView): ConstraintResult {
        if (!isAtWarDestCity(ctx, view)) return ConstraintResult.Deny("교전중인 국가가 아닙니다.")
        return if (routeExists(ctx, view)) ConstraintResult.Allow else ConstraintResult.Deny("경로에 도달할 방법이 없습니다.")
    }
}

/**
 * DisallowDiplomacyBetweenStatus.php — read the directional diplomacy row (me=nation, you=destNation);
 * if its `state` is a key of `disallowStatus`, DENY with that key's errMsg; else (state not in the map,
 * OR no row) pass. PHP queries `diplomacy WHERE me=? AND you=? AND state IN(map keys) LIMIT 1` and on a
 * hit returns `disallowStatus[state]`. The directional row is preloaded via RequirementKey.Diplomacy.
 */
fun disallowDiplomacyBetweenStatus(disallowStatus: Map<Int, String>) = object : Constraint {
    override val name = "DisallowDiplomacyBetweenStatus"
    override fun requires(ctx: ConstraintContext) =
        listOf(RequirementKey.Diplomacy(ctx.nationId ?: 0, ctx.destNationId ?: 0))
    override fun test(ctx: ConstraintContext, view: StateView): ConstraintResult {
        val row = view.get(RequirementKey.Diplomacy(ctx.nationId ?: 0, ctx.destNationId ?: 0)) as? Diplomacy
            ?: return ConstraintResult.Allow
        val errMsg = disallowStatus[row.state] ?: return ConstraintResult.Allow
        return ConstraintResult.Deny(errMsg)
    }
}

/**
 * AllowDiplomacyBetweenStatus.php — read the directional diplomacy row; pass when its `state` is in
 * `allowDipCodeList`; else (state not allowed, OR no row) DENY with `errMsg`. PHP queries
 * `diplomacy WHERE me=? AND you=? AND state IN(allowList) LIMIT 1` and passes on a hit.
 */
fun allowDiplomacyBetweenStatus(allowDipCodeList: List<Int>, errMsg: String) = object : Constraint {
    override val name = "AllowDiplomacyBetweenStatus"
    override fun requires(ctx: ConstraintContext) =
        listOf(RequirementKey.Diplomacy(ctx.nationId ?: 0, ctx.destNationId ?: 0))
    override fun test(ctx: ConstraintContext, view: StateView): ConstraintResult {
        val row = view.get(RequirementKey.Diplomacy(ctx.nationId ?: 0, ctx.destNationId ?: 0)) as? Diplomacy
            ?: return ConstraintResult.Deny(errMsg)
        return if (row.state in allowDipCodeList) ConstraintResult.Allow else ConstraintResult.Deny(errMsg)
    }
}

/**
 * AllowDiplomacyWithTerm.php — read the directional diplomacy row; pass when `state == allowDipCode &&
 * term >= allowMinTerm`; else DENY with `errMsg`. PHP queries
 * `diplomacy WHERE me=? AND you=? AND state=? AND term>=?` and passes on a hit.
 */
fun allowDiplomacyWithTerm(allowDipCode: Int, allowMinTerm: Int, errMsg: String) = object : Constraint {
    override val name = "AllowDiplomacyWithTerm"
    override fun requires(ctx: ConstraintContext) =
        listOf(RequirementKey.Diplomacy(ctx.nationId ?: 0, ctx.destNationId ?: 0))
    override fun test(ctx: ConstraintContext, view: StateView): ConstraintResult {
        val row = view.get(RequirementKey.Diplomacy(ctx.nationId ?: 0, ctx.destNationId ?: 0)) as? Diplomacy
            ?: return ConstraintResult.Deny(errMsg)
        return if (row.state == allowDipCode && row.term >= allowMinTerm) ConstraintResult.Allow
        else ConstraintResult.Deny(errMsg)
    }
}

// --- Dest comparator forms (key-backed) — resolve target from the preloaded Dest* row ---

/**
 * ReqDestCityValue.php — same comparator core as ReqCityValue; target is the DEST city's field
 * (resolved via RequirementKey.DestCity). Percent reqVal compares destCity[key] vs destCity[key_max]
 * * fraction. On fail, errMsg overrides; else `{keyNick}{josa-이} {derived}`.
 */
fun reqDestCityValue(key: String, keyNick: String, comp: String, reqVal: Any, errMsg: String? = null) = object : Constraint {
    override val name = "ReqDestCityValue"
    override fun requires(ctx: ConstraintContext) = listOf(RequirementKey.DestCity(ctx.destCityId ?: 0))
    override fun test(ctx: ConstraintContext, view: StateView): ConstraintResult {
        val c = destCity(ctx, view) ?: return ConstraintResult.Unknown(requires(ctx))
        val target = cityIntField(c, key)
        val src: Number = resolveReqVal(reqVal) { cityIntField(c, "${key}_max") }
        val result = compareValues(target, src, comp, keyNick)
        if (result == COMPARE_PASS) return ConstraintResult.Allow
        return ConstraintResult.Deny(assembleReasonDest(keyNick, errMsg, result as String))
    }
}

/**
 * ReqDestNationValue.php — same comparator core; target is the DEST nation's field (RequirementKey.
 * DestNation). On fail, errMsg overrides; else `{keyNick}{josa-이} {derived}`.
 */
fun reqDestNationValue(key: String, keyNick: String, comp: String, reqVal: Any, errMsg: String? = null) = object : Constraint {
    override val name = "ReqDestNationValue"
    override fun requires(ctx: ConstraintContext) = listOf(RequirementKey.DestNation(ctx.destNationId ?: 0))
    override fun test(ctx: ConstraintContext, view: StateView): ConstraintResult {
        val n = destNation(ctx, view) ?: return ConstraintResult.Unknown(requires(ctx))
        val target = nationNumField(n, key)
        val src: Number = resolveReqVal(reqVal) { nationNumField(n, "${key}_max") }
        val result = compareValues(target, src, comp, keyNick)
        if (result == COMPARE_PASS) return ConstraintResult.Allow
        return ConstraintResult.Deny(assembleReasonDest(keyNick, errMsg, result as String))
    }
}

/** Assemble the final ReqDest*Value reason (errMsg override + josa-이) — mirrors [assembleReason]. */
private fun assembleReasonDest(keyNick: String, errMsg: String?, fragment: String): String {
    if (!errMsg.isNullOrEmpty()) return errMsg
    val josaYi = JosaUtil.pick(keyNick, "이")
    return "$keyNick$josaYi $fragment"
}
