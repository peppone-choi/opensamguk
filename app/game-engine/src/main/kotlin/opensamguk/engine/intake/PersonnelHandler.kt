package opensamguk.engine.intake

import opensamguk.common.constants.GameConst
import opensamguk.common.wire.GeneralBoolResult
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.City
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.Nation
import opensamguk.engine.turn.PerTurnOverlay
import opensamguk.engine.turn.TurnGeneral
import opensamguk.logic.actions.intake.SecretPermission

class PersonnelHandler(
    private val world: InMemoryTurnWorld,
    private val recorder: ChangeRecorder,
) {
    fun handleAppoint(c: TurnDaemonCommand.Appoint): GeneralBoolResult {
        val me = world.getGeneralById(c.generalId) ?: return fail("appoint", c.generalId, "장수가 없습니다.")
        if (me.officerLevel < 5) return fail("appoint", c.generalId, "수뇌가 아닙니다.")
        if (c.officerLevel == 12) return fail("appoint", c.generalId, "군주를 대상으로 할 수 없습니다.")

        val target = if (c.destGeneralId == 0) null else world.getGeneralById(c.destGeneralId)
            ?: return fail("appoint", c.generalId, "올바르지 않은 장수입니다.")
        if (target != null && target.nationId != me.nationId) return fail("appoint", c.generalId, "아국 장수가 아닙니다")

        return when (c.officerLevel) {
            in 2..4 -> appointCity(me, target, c.destCityId, c.officerLevel, c.generalId)
            in 5..11 -> appointChief(me, target, c.officerLevel, c.generalId)
            else -> fail("appoint", c.generalId, "올바르지 않은 지정입니다.")
        }
    }

    fun handleKick(c: TurnDaemonCommand.Kick): GeneralBoolResult {
        val me = world.getGeneralById(c.generalId) ?: return fail("kick", c.generalId, "장수가 없습니다.")
        if (me.officerLevel < 5) return fail("kick", c.generalId, "수뇌가 아닙니다.")
        if (c.destGeneralId == 0) return fail("kick", c.generalId, "장수가 지정되지 않았습니다.")
        val target = world.getGeneralById(c.destGeneralId) ?: return fail("kick", c.generalId, "올바르지 않은 장수입니다.")
        if (target.nationId != me.nationId) return fail("kick", c.generalId, "아국 장수가 아닙니다")
        if (SecretPermission.check(PerTurnOverlay.toLogicGeneral(target)) == 4) {
            return fail("kick", c.generalId, "외교권자는 추방할 수 없습니다.")
        }
        val nation = world.getNationById(target.nationId) ?: return fail("kick", c.generalId, "올바르지 않은 국가입니다.")

        val surplusGold = (target.gold - GameConst.defaultGold).coerceAtLeast(0)
        val surplusRice = (target.rice - GameConst.defaultRice).coerceAtLeast(0)
        val nextMeta = LinkedHashMap(target.meta)
        nextMeta["officer_city"] = 0
        nextMeta["belong"] = 0
        nextMeta["makelimit"] = 12
        nextMeta["permission"] = "normal"
        val betray = (target.meta["betray"] as? Number)?.toInt() ?: 0
        nextMeta["betray"] = minOf(betray + 1, GameConst.maxBetrayCnt)
        applyGeneral(target.copy(
            nationId = 0,
            officerLevel = 0,
            troopId = 0,
            gold = minOf(target.gold, GameConst.defaultGold),
            rice = minOf(target.rice, GameConst.defaultRice),
            meta = nextMeta,
        ))

        if (target.troopId == target.id) {
            for (member in world.listGenerals().filter { it.troopId == target.id && it.id != target.id }) {
                applyGeneral(member.copy(troopId = 0))
            }
            world.removeTroop(target.id)
        }

        val nMeta = LinkedHashMap(nation.meta)
        val gennum = (nMeta["gennum"] as? Number)?.toInt() ?: 0
        nMeta["gennum"] = (gennum - if (target.npcState != 5) 1 else 0).coerceAtLeast(0)
        applyNation(nation.copy(gold = nation.gold + surplusGold, rice = nation.rice + surplusRice, meta = nMeta))
        return GeneralBoolResult("kick", true, c.generalId)
    }

    fun handleChangePermission(c: TurnDaemonCommand.ChangePermission): GeneralBoolResult {
        val me = world.getGeneralById(c.generalId) ?: return fail("changePermission", c.generalId, "장수가 없습니다.")
        if (me.officerLevel != 12) return fail("changePermission", c.generalId, "군주가 아닙니다")
        if (c.isAmbassador && c.targetGeneralIds.size > 2) {
            return fail("changePermission", c.generalId, "외교권자는 최대 둘까지만 설정 가능합니다.")
        }
        val targetType = if (c.isAmbassador) SecretPermission.AMBASSADOR else SecretPermission.AUDITOR
        val targetLevel = if (c.isAmbassador) 4 else 3

        for (g in world.listGenerals().filter { it.nationId == me.nationId && it.meta["permission"] == targetType }) {
            applyGeneral(g.copy(meta = LinkedHashMap(g.meta).apply { put("permission", "normal") }))
        }
        for (g in world.listGenerals().filter { it.id in c.targetGeneralIds && it.nationId == me.nationId && it.officerLevel != 12 }) {
            val currentPermission = g.meta["permission"] as? String ?: "normal"
            if (currentPermission != "normal") continue
            if (SecretPermission.checkSecretMaxPermission((g.meta["penalty"] as? Map<String, Any?>) ?: emptyMap()) < targetLevel) continue
            applyGeneral(g.copy(meta = LinkedHashMap(g.meta).apply { put("permission", targetType) }))
        }
        return GeneralBoolResult("changePermission", true, c.generalId)
    }

    private fun appointCity(me: TurnGeneral, target: TurnGeneral?, cityId: Int, officerLevel: Int, generalId: Int): GeneralBoolResult {
        if (cityId == 0) return fail("appoint", generalId, "도시가 지정되지 않았습니다.")
        val city = world.getCityById(cityId) ?: return fail("appoint", generalId, "올바르지 않은 도시입니다")
        if (city.nationId != me.nationId) return fail("appoint", generalId, "아국 도시가 아닙니다")
        if (isOfficerSet(city.officerSet, officerLevel)) return fail("appoint", generalId, "이미 다른 장수가 임명되어있습니다")
        if (target != null && officerLevel == 4 && target.stats.strength < GameConst.chiefStatMin) return fail("appoint", generalId, "무력이 부족합니다.")
        if (target != null && officerLevel == 3 && target.stats.intelligence < GameConst.chiefStatMin) return fail("appoint", generalId, "지력이 부족합니다.")

        for (old in world.listGenerals().filter { it.id != target?.id && it.officerLevel == officerLevel && officerCity(it) == cityId }) {
            applyGeneral(old.copy(officerLevel = 1, meta = LinkedHashMap(old.meta).apply { put("officer_city", 0) }))
        }
        applyCity(city.copy(officerSet = doOfficerSet(city.officerSet, officerLevel)))
        if (target != null) applyGeneral(target.copy(officerLevel = officerLevel, meta = LinkedHashMap(target.meta).apply { put("officer_city", cityId) }))
        return GeneralBoolResult("appoint", true, generalId)
    }

    private fun appointChief(me: TurnGeneral, target: TurnGeneral?, officerLevel: Int, generalId: Int): GeneralBoolResult {
        val targetGeneral = target ?: return fail("appoint", generalId, "올바르지 않은 장수입니다.")
        val nation = world.getNationById(me.nationId) ?: return fail("appoint", generalId, "올바르지 않은 국가입니다.")
        val chiefSet = (nation.meta["chief_set"] as? Number)?.toInt() ?: 0
        val minLevel = GameConst.getNationChiefLevel(nation.level)
        if (officerLevel < minLevel) return fail("appoint", generalId, "임명불가능한 관직입니다.")
        if (isOfficerSet(chiefSet, officerLevel)) return fail("appoint", generalId, "지금은 임명할 수 없습니다.")
        if (officerLevel != 11 && officerLevel % 2 == 0 && targetGeneral.stats.strength < GameConst.chiefStatMin) return fail("appoint", generalId, "무력이 부족합니다.")
        if (officerLevel != 11 && officerLevel % 2 == 1 && targetGeneral.stats.intelligence < GameConst.chiefStatMin) return fail("appoint", generalId, "지력이 부족합니다.")
        for (old in world.listGenerals().filter { it.id != targetGeneral.id && it.nationId == me.nationId && it.officerLevel == officerLevel }) {
            applyGeneral(old.copy(officerLevel = 1, meta = LinkedHashMap(old.meta).apply { put("officer_city", 0) }))
        }
        applyGeneral(targetGeneral.copy(officerLevel = officerLevel, meta = LinkedHashMap(targetGeneral.meta).apply { put("officer_city", 0) }))
        applyNation(nation.copy(meta = LinkedHashMap(nation.meta).apply { put("chief_set", doOfficerSet(chiefSet, officerLevel)) }))
        return GeneralBoolResult("appoint", true, generalId)
    }

    private fun applyGeneral(next: TurnGeneral) {
        val pre = world.getGeneralById(next.id) ?: return
        recorder.diffGeneral(PerTurnOverlay.toLogicGeneral(pre), PerTurnOverlay.toLogicGeneral(next))
        world.applyGeneralDirtyFree(next)
    }

    private fun applyCity(next: City) {
        val pre = world.getCityById(next.id) ?: return
        recorder.diffCity(PerTurnOverlay.toLogicCity(pre), PerTurnOverlay.toLogicCity(next))
        world.applyCityDirtyFree(next)
    }

    private fun applyNation(next: Nation) {
        val pre = world.getNationById(next.id) ?: return
        recorder.diffNation(PerTurnOverlay.toLogicNation(pre), PerTurnOverlay.toLogicNation(next))
        world.applyNationDirtyFree(next)
    }

    private fun officerCity(g: TurnGeneral): Int = (g.meta["officer_city"] as? Number)?.toInt() ?: 0
    private fun isOfficerSet(officerSet: Int, reqOfficerLevel: Int): Boolean = (officerSet and (1 shl reqOfficerLevel)) != 0
    private fun doOfficerSet(officerSet: Int, reqOfficerLevel: Int): Int = officerSet or (1 shl reqOfficerLevel)
    private fun fail(type: String, generalId: Int, reason: String) = GeneralBoolResult(type, false, generalId, reason)
}
