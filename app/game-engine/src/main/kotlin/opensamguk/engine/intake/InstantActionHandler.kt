package opensamguk.engine.intake

import opensamguk.common.wire.GeneralBoolResult
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.common.wire.TurnDaemonCommandResult
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.LogEntryDraft
import opensamguk.engine.turn.PerTurnOverlay
import opensamguk.engine.turn.RankColumn
import opensamguk.engine.turn.TurnGeneral
import opensamguk.logic.actions.instant.DieOnPrestart
import opensamguk.logic.actions.instant.DieOnPrestartOutcome
import opensamguk.logic.actions.instant.DropItem
import opensamguk.logic.actions.instant.DropItemOutcome
import opensamguk.logic.actions.instant.inherit.CheckOwner
import opensamguk.logic.actions.instant.inherit.CheckOwnerOutcome
import opensamguk.logic.util.jsonEncode
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class InstantActionHandler(
    private val world: InMemoryTurnWorld,
    private val recorder: ChangeRecorder,
) {
    fun handleDieOnPrestart(c: TurnDaemonCommand.DieOnPrestart): TurnDaemonCommandResult {
        val me = world.getGeneralById(c.generalId)
            ?: return fail("dieOnPrestart", c.generalId, "장수가 없습니다")
        val meta = world.getState().meta
        val now = world.getState().lastTurnTime.atZone(ZoneOffset.UTC).toLocalDateTime()
        val lastRefresh = parseDate(me.meta["lastRefresh"] ?: meta["lastRefresh"]) ?: now
        val out = DieOnPrestart.resolve(
            generalExists = true,
            generalName = me.name,
            nationId = me.nationId,
            turntime = meta.string("turntime") ?: me.turnTime.toString(),
            opentime = meta.string("opentime") ?: me.turnTime.toString(),
            lastRefresh = lastRefresh,
            turnterm = (meta["turnterm"] as? Number)?.toInt() ?: 1,
            now = now,
        )
        return when (out) {
            is DieOnPrestartOutcome.Denied -> fail("dieOnPrestart", c.generalId, out.reason)
            is DieOnPrestartOutcome.Killed -> {
                recorder.markGeneralDeleted(world, me.id)
                world.pushLog(LogEntryDraft("GLOBAL", "action", out.dyingLog, generalId = me.id))
                ok("dieOnPrestart", c.generalId)
            }
        }
    }

    fun handleDropItem(c: TurnDaemonCommand.DropItem): TurnDaemonCommandResult {
        val me = world.getGeneralById(c.generalId) ?: return fail("dropItem", c.generalId, "장수가 없습니다.")
        val item = itemValue(me, c.itemType)
        val out = DropItem.drop(c.itemType, if (item == "None") "None" else item, itemName(item), itemRawName(item), true, me.name, world.getNationById(me.nationId)?.name ?: "")
        return when (out) {
            is DropItemOutcome.Denied -> fail("dropItem", c.generalId, out.reason)
            is DropItemOutcome.Applied -> {
                val pre = PerTurnOverlay.toLogicGeneral(me)
                val next = me.copy(role = me.role.copy(items = setItem(me, c.itemType, "None")))
                world.applyGeneralDirtyFree(next)
                recorder.diffGeneral(pre, PerTurnOverlay.toLogicGeneral(next))
                world.pushLog(LogEntryDraft("GENERAL", "action", out.actionLog, generalId = me.id))
                ok("dropItem", c.generalId)
            }
        }
    }

    fun handleInstantRetreat(c: TurnDaemonCommand.InstantRetreat): TurnDaemonCommandResult {
        val me = world.getGeneralById(c.generalId) ?: return fail("instantRetreat", c.generalId, "장수가 없습니다")
        val destination = world.listCities().filter { it.nationId != me.nationId }.minByOrNull { it.id }
            ?: return fail("instantRetreat", c.generalId, "가까운 아국 도시가 없습니다.")
        val pre = PerTurnOverlay.toLogicGeneral(me)
        val next = me.copy(cityId = destination.id)
        world.applyGeneralDirtyFree(next)
        recorder.diffGeneral(pre, PerTurnOverlay.toLogicGeneral(next))
        return ok("instantRetreat", c.generalId)
    }

    fun handleCheckOwner(c: TurnDaemonCommand.CheckOwner): TurnDaemonCommandResult {
        val me = world.getGeneralById(c.generalId) ?: return fail("checkOwner", c.generalId, "장수가 없습니다.")
        val target = world.getGeneralById(c.destGeneralId)
        val ownerId = (me.meta["owner"] as? Number)?.toInt() ?: me.id
        val previous = previousPoint(ownerId)
        val targetOwner = (target?.meta?.get("owner") as? Number)?.toInt() ?: 0
        val out = CheckOwner.resolve(
            actingGeneralId = me.id,
            destGeneralId = c.destGeneralId,
            actingOwnerMatches = true,
            destExists = target != null,
            destOwner = targetOwner,
            isUnited = ((world.getState().meta["isunited"] as? Number)?.toInt() ?: 0) != 0,
            previousPoint = previous,
            destGeneralName = target?.name ?: "",
            destGeneralOwnerName = target?.meta?.string("owner_name") ?: "알수없음",
            acting = targetFor(me),
            destDisplay = target?.let(::targetFor) ?: targetFor(me),
            now = world.getState().lastTurnTime.atZone(ZoneOffset.UTC).toLocalDateTime(),
        )
        return when (out) {
            is CheckOwnerOutcome.Denied -> fail("checkOwner", c.generalId, out.reason)
            is CheckOwnerOutcome.Applied -> {
                recorder.recordInheritanceLog(ownerId, out.log, "inheritPoint")
                recorder.recordInheritancePointSet(ownerId, "previous", out.remainingPrevious, null)
                recorder.recordRankIncrease(me.id, RankColumn.INHERIT_SPENT_DYN, out.spent)
                out.messages.forEach { message ->
                    val dest = message.dest
                    recorder.recordMessageInsert( dest.generalId, "private", 0, dest.generalId,
                        message.time.toString().replace('T', ' '), "9999-12-31 00:00:00",
                        jsonEncode(linkedMapOf("src" to targetArray(message.src), "dest" to targetArray(dest), "text" to message.text, "option" to linkedMapOf<String, Any?>())))
                }
                ok("checkOwner", c.generalId)
            }
        }
    }

    private fun ok(type: String, id: Int) = GeneralBoolResult(type = type, ok = true, generalId = id)
    private fun fail(type: String, id: Int, reason: String) = GeneralBoolResult(type = type, ok = false, generalId = id, reason = reason)

    private fun itemValue(g: TurnGeneral, slot: String): String = when (slot) {
        "horse" -> g.role.items.horse ?: "None"
        "weapon" -> g.role.items.weapon ?: "None"
        "book" -> g.role.items.book ?: "None"
        "item" -> g.role.items.item ?: "None"
        else -> "None"
    }
    private fun setItem(g: TurnGeneral, slot: String, value: String) = when (slot) {
        "horse" -> g.role.items.copy(horse = value)
        "weapon" -> g.role.items.copy(weapon = value)
        "book" -> g.role.items.copy(book = value)
        "item" -> g.role.items.copy(item = value)
        else -> g.role.items
    }
    private fun itemName(code: String) = code.substringAfterLast('_', code)
    private fun itemRawName(code: String) = itemName(code)
    private fun targetFor(g: TurnGeneral) = opensamguk.logic.messaging.MessageTarget(g.id, g.name, g.nationId, world.getNationById(g.nationId)?.name ?: "", world.getNationById(g.nationId)?.color ?: "#000000", "")
    private fun targetArray(t: opensamguk.logic.messaging.MessageTarget) = linkedMapOf<String, Any?>("id" to t.generalId, "name" to t.generalName, "nation_id" to t.nationId, "nation" to t.nationName, "color" to t.color, "icon" to t.icon)
    private fun parseDate(value: Any?): LocalDateTime? = value?.toString()?.let { runCatching { LocalDateTime.parse(it.take(19), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) }.getOrNull() }
    private fun previousPoint(ownerId: Int): Double =
        recorder.effectiveInheritancePoint(ownerId, "previous")?.first
            ?: (ownerScoped(world.getState().meta["inheritancePrevious"], ownerId) as? Number)?.toDouble()
            ?: 0.0
    private fun ownerScoped(value: Any?, owner: Int): Any? = (value as? Map<*, *>)?.get(owner) ?: (value as? Map<*, *>)?.get(owner.toString())
    private fun Map<String, Any?>.string(key: String): String? = this[key]?.toString()
}
