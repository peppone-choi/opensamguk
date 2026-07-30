package opensamguk.engine.intake

import opensamguk.common.wire.SelectPoolActionResult
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.common.wire.TurnDaemonCommandResult
import opensamguk.common.constants.GameConst
import opensamguk.common.josa.JosaUtil
import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.common.rng.serializeSeed
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.GeneralRole
import opensamguk.engine.turn.GeneralStats
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.LogEntryDraft
import opensamguk.engine.turn.PerTurnOverlay
import opensamguk.infra.read.SelectPoolRepository
import opensamguk.infra.persistence.SelectPoolCandidate
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class SelectPoolHandler(
    private val world: InMemoryTurnWorld,
    private val recorder: ChangeRecorder,
    private val selectPoolRepository: SelectPoolRepository? = null,
) {
    fun handleRefresh(c: TurnDaemonCommand.SelectPoolRefresh): TurnDaemonCommandResult {
        val repo = selectPoolRepository ?: return fail("selectPoolRefresh", 0, "유효한 장수 목록이 없습니다.")
        val now = runCatching { Instant.parse(c.requestedAt) }.getOrElse { world.getState().lastTurnTime }
        if ((world.getState().meta["npcmode"] as? Number)?.toInt() != 2) {
            return fail("selectPoolRefresh", 0, "선택 가능한 서버가 아닙니다")
        }
        if (repo.listForUser(c.ownerUserId, now).isNotEmpty()) {
            return SelectPoolActionResult(type = "selectPoolRefresh", ok = true, generalId = 0)
        }
        if (repo.targetGeneralPool() != "RandomNameGeneral") {
            return fail("selectPoolRefresh", 0, "유효한 장수 목록이 없습니다.")
        }
        val seedTime = SELECT_POOL_SEED_TIME.format(now)
        val hiddenSeed = world.getState().meta["hiddenSeed"]?.toString().orEmpty()
        val rng = RandUtil(LiteHashDrbg(serializeSeed(hiddenSeed, "selectPool", c.ownerUserId, seedTime)))
        val used = LinkedHashSet(repo.listUniqueNames())
        used += world.listGenerals().map { it.name }
        val candidates = ArrayList<SelectPoolCandidate>(PICK_COUNT)
        var attempts = 0
        while (candidates.size < PICK_COUNT && attempts < MAX_NAME_ATTEMPTS) {
            attempts += 1
            val base = rng.choice(GameConst.randGenFirstName) +
                rng.choice(GameConst.randGenMiddleName) +
                rng.choice(GameConst.randGenLastName)
            val duplicateCount = used.count { it == base || it.matches(Regex("^${Regex.escape(base)}\\d+$")) }
            val name = when {
                duplicateCount == 0 -> base
                duplicateCount < 2 -> "$base${duplicateCount + 1}"
                else -> continue
            }
            if (!used.add(name)) continue
            candidates += SelectPoolCandidate(
                uniqueName = name,
                info = linkedMapOf(
                    "uniqueName" to name,
                    "generalName" to name,
                    "imgsvr" to 0,
                    "picture" to null,
                ),
            )
        }
        if (candidates.size != PICK_COUNT) {
            return fail("selectPoolRefresh", 0, "유효한 장수 목록이 없습니다.")
        }
        recorder.recordSelectPoolRefresh(c.ownerUserId, now, now.plusSeconds(30), candidates)
        return SelectPoolActionResult(type = "selectPoolRefresh", ok = true, generalId = 0)
    }

    fun handlePick(c: TurnDaemonCommand.SelectPoolPick): TurnDaemonCommandResult =
        SelectPoolActionResult(type = "selectPoolPick", ok = false, generalId = c.generalId)

    fun handleUpdate(c: TurnDaemonCommand.SelectPoolUpdate): TurnDaemonCommandResult {
        val me = world.getGeneralById(c.generalId)
            ?: return fail("selectPoolUpdate", c.generalId, "장수가 생성하지 않았습니다. 이미 사망하지 않았는지 확인해보세요.")
        if ((world.getState().meta["npcmode"] as? Number)?.toInt() != 2) {
            return fail("selectPoolUpdate", c.generalId, "선택 가능한 서버가 아닙니다")
        }
        val now = world.getState().lastTurnTime
        val ownerId = me.userId?.toIntOrNull()
            ?: c.ownerUserId
            ?: return fail("selectPoolUpdate", c.generalId, "멤버 정보를 가져오지 못했습니다.")
        if (c.ownerUserId != null && c.ownerUserId != ownerId) {
            return fail("selectPoolUpdate", c.generalId, "유효한 장수 목록이 없습니다.")
        }
        val repo = selectPoolRepository ?: return fail("selectPoolUpdate", c.generalId, "유효한 장수 목록이 없습니다.")
        val row = repo.findPoolEntry(c.uniqueName, ownerId, now)
            ?: return fail("selectPoolUpdate", c.generalId, "유효한 장수 목록이 없습니다.")
        if (row.ownerUserId != ownerId) return fail("selectPoolUpdate", c.generalId, "유효한 장수 목록이 없습니다.")
        val reservedUntil = row.reservedUntil
        if (reservedUntil == null || reservedUntil.isBefore(now)) {
            return fail("selectPoolUpdate", c.generalId, "유효한 장수 목록이 없습니다.")
        }
        val ownerProfile = repo.findOwnerProfile(ownerId)
            ?: return fail("selectPoolUpdate", c.generalId, "멤버 정보를 가져오지 못했습니다.")
        val stats = statsFrom(row.info, false, null, null, null, fallback = me.stats)
            ?: return fail("selectPoolUpdate", c.generalId, "스탯의 총 합이 올바르지 않습니다.")
        val turnTerm = (world.getState().meta["turnterm"] as? Number)?.toLong() ?: 60L
        val nextMeta = LinkedHashMap(me.meta)
        nextMeta["next_change"] = world.getState().lastTurnTime.plus(Duration.ofMinutes(12 * turnTerm)).toString()
        nextMeta["owner_name"] = ownerProfile.name
        for (i in 1..5) int(row.info, "dex$i", "dex", index = i - 1)?.let { nextMeta["dex$i"] = it }
        str(row.info, "picture")?.let { nextMeta["picture"] = it }
        int(row.info, "imgsvr")?.let { nextMeta["image_server"] = it }
        val next = me.copy(
            name = str(row.info, "generalName") ?: me.name,
            stats = stats,
            role = roleFromPool(row.info, me.role),
            meta = nextMeta,
        )
        recorder.diffGeneral(PerTurnOverlay.toLogicGeneral(me), PerTurnOverlay.toLogicGeneral(next))
        world.applyGeneralDirtyFree(next)
        recorder.recordSelectPoolUpdate(c.uniqueName, ownerId, c.generalId, now)
        val josaYi = JosaUtil.pick(ownerProfile.name, "이")
        val josaRo = JosaUtil.pick(next.name, "로")
        world.pushLog(
            LogEntryDraft(
                scope = "general",
                category = "history",
                text = "장수를 <Y>${me.name}</>에서 <Y>${next.name}</>${josaRo} 변경",
                generalId = next.id,
                nationId = next.nationId,
            ),
        )
        world.pushLog(
            LogEntryDraft(
                scope = "global",
                category = "action",
                text = "<Y>${ownerProfile.name}</>${josaYi} 장수를 <Y>${me.name}</>에서 <Y>${next.name}</>${josaRo} 변경합니다.",
                generalId = next.id,
                nationId = next.nationId,
            ),
        )
        return SelectPoolActionResult(type = "selectPoolUpdate", ok = true, generalId = c.generalId)
    }

    private fun statsFrom(
        info: Map<String, Any?>,
        editable: Boolean,
        leadership: Int?,
        strength: Int?,
        intel: Int?,
        fallback: GeneralStats = GeneralStats(15, 15, 15),
    ): GeneralStats? {
        val l: Int
        val s: Int
        val i: Int
        if (editable) {
            l = (leadership ?: 15).coerceIn(15, 80)
            s = (leadership ?: 15).coerceIn(15, 80)
            i = (leadership ?: 15).coerceIn(15, 80)
            if (l + s + i > 165) return null
        } else {
            l = int(info, "leadership") ?: fallback.leadership
            s = int(info, "strength") ?: fallback.strength
            i = int(info, "intel") ?: fallback.intelligence
        }
        val politics = int(info, "politics") ?: fallback.politics
        val charm = int(info, "charm") ?: fallback.charm
        return GeneralStats(l, s, i, politics, charm)
    }

    private fun roleFromPool(info: Map<String, Any?>, fallback: GeneralRole = GeneralRole()): GeneralRole =
        fallback.copy(
            personality = str(info, "ego") ?: fallback.personality,
            specialDomestic = str(info, "specialDomestic") ?: fallback.specialDomestic,
            specialWar = str(info, "specialWar") ?: fallback.specialWar,
        )

    private fun str(info: Map<String, Any?>, key: String): String? = info[key] as? String

    private fun int(info: Map<String, Any?>, key: String, arrayKey: String? = null, index: Int? = null): Int? {
        val direct = info[key]
        if (direct is Number) return direct.toInt()
        if (direct is String) return direct.toIntOrNull()
        if (arrayKey != null && index != null) {
            val list = info[arrayKey] as? List<*> ?: return null
            return when (val v = list.getOrNull(index)) {
                is Number -> v.toInt()
                is String -> v.toIntOrNull()
                else -> null
            }
        }
        return null
    }

    private fun fail(type: String, generalId: Int, reason: String) =
        SelectPoolActionResult(type = type, ok = false, generalId = generalId, reason = reason)

    companion object {
        private const val PICK_COUNT = 14
        private const val MAX_NAME_ATTEMPTS = 1_400
        private val SELECT_POOL_SEED_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.of("Asia/Seoul"))
    }
}
