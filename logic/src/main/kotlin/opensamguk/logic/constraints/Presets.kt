package opensamguk.logic.constraints

import opensamguk.common.josa.JosaUtil
import opensamguk.logic.domain.City
import opensamguk.logic.domain.General
import opensamguk.logic.domain.Nation

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

fun occupiedCity() = object : Constraint {
    override val name = "OccupiedCity"
    override fun requires(ctx: ConstraintContext) =
        listOf(RequirementKey.General(ctx.actorId), RequirementKey.City(ctx.cityId ?: 0))
    override fun test(ctx: ConstraintContext, view: StateView): ConstraintResult {
        val g = gen(ctx, view) ?: return ConstraintResult.Unknown(requires(ctx))
        val c = city(ctx, view) ?: return ConstraintResult.Unknown(requires(ctx))
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
