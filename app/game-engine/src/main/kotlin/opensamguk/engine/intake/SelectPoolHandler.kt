package opensamguk.engine.intake

import opensamguk.common.wire.SelectPoolActionResult
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.common.wire.TurnDaemonCommandResult
import opensamguk.common.constants.GameConst
import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.common.rng.serializeSeed
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.GeneralRole
import opensamguk.engine.turn.GeneralStats
import opensamguk.engine.turn.GeneralAccessLog
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.PerTurnOverlay
import opensamguk.engine.turn.TurnGeneral
import opensamguk.infra.read.SelectPoolRepository
import opensamguk.infra.persistence.SelectPoolCandidate
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 장수 선택 풀 (pick/update) intake 핸들러 — `j_pick_general.php` /
 * `j_update_picked_general.php` `launch()` 본문의 faithful 포팅 (W6f 장수 선택 풀, RNG-BEARING).
 * per-run (world + recorder + select-pool read seam), [BoardHandler]+[VoteHandler]를 미러링.
 *
 * 풀 항목 owner/expiry gate, npcmode gate, 생성/수정 효과, next_change 쿨다운을 [ChangeRecorder]로
 * 기록한다. select_pool claim/swap SQL은 이 핸들러나 read repository가 직접 실행하지 않고 같은 tick의
 * JdbcFlushExecutor 트랜잭션에서 조건부 갱신한다.
 */
class SelectPoolHandler(
    private val world: InMemoryTurnWorld,
    private val recorder: ChangeRecorder,
    private val selectPoolRepository: SelectPoolRepository? = null,
    private val accessNowProvider: () -> Instant = Instant::now,
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

    fun handlePick(c: TurnDaemonCommand.SelectPoolPick): TurnDaemonCommandResult {
        val now = world.getState().lastTurnTime
        val ownerId = c.ownerUserId ?: return fail("selectPoolPick", c.generalId, "멤버 정보를 가져오지 못했습니다.")
        val repo = selectPoolRepository ?: return fail("selectPoolPick", c.generalId, "유효한 장수 목록이 없습니다.")
        val row = repo.findPoolEntry(c.uniqueName, ownerId, now)
            ?: return fail("selectPoolPick", c.generalId, "유효한 장수 목록이 없습니다.")
        if (row.ownerUserId != ownerId) return fail("selectPoolPick", c.generalId, "유효한 장수 목록이 없습니다.")
        val reservedUntil = row.reservedUntil
        if (reservedUntil == null || reservedUntil.isBefore(now)) {
            return fail("selectPoolPick", c.generalId, "유효한 장수 목록이 없습니다.")
        }
        if ((world.getState().meta["npcmode"] as? Number)?.toInt() != 2) {
            return fail("selectPoolPick", c.generalId, "선택 가능한 서버가 아닙니다")
        }
        if (world.listGenerals().any { it.userId == ownerId.toString() }) {
            return fail("selectPoolPick", c.generalId, "이미 장수를 생성했습니다.")
        }
        val maxGeneral = (world.getState().meta["maxgeneral"] as? Number)?.toInt() ?: Int.MAX_VALUE
        if (world.listGenerals().count { it.npcState < 2 } >= maxGeneral) {
            return fail("selectPoolPick", c.generalId, "더 이상 등록 할 수 없습니다.")
        }

        val stats = statsFrom(row.info, row.statEditable, c.leadership, c.strength, c.intel)
            ?: return fail("selectPoolPick", c.generalId, "스탯의 총 합이 올바르지 않습니다.")
        val id = world.allocateGeneralId()
        val name = str(row.info, "generalName") ?: c.uniqueName
        val cityId = int(row.info, "cityId") ?: world.listCities().firstOrNull()?.id ?: 0
        val turnTerm = (world.getState().meta["turnterm"] as? Number)?.toLong() ?: 60L
        val meta = linkedMapOf<String, Any?>(
            "owner_name" to str(row.info, "ownerName"),
            "permission" to "normal",
            "officer_city" to 0,
            "next_change" to world.getState().lastTurnTime.plus(Duration.ofMinutes(12 * turnTerm)).toString(),
            "picture" to (str(row.info, "picture") ?: "default.jpg"),
            "image_server" to (int(row.info, "imgsvr") ?: 0),
            "dex1" to int(row.info, "dex1", "dex", index = 0),
            "dex2" to int(row.info, "dex2", "dex", index = 1),
            "dex3" to int(row.info, "dex3", "dex", index = 2),
            "dex4" to int(row.info, "dex4", "dex", index = 3),
            "dex5" to int(row.info, "dex5", "dex", index = 4),
        )
        val general = TurnGeneral(
            id = id,
            userId = ownerId.toString(),
            name = name,
            nationId = 0,
            cityId = cityId,
            troopId = 0,
            stats = stats,
            experience = 0,
            dedication = 0,
            officerLevel = 0,
            role = role(row.info, c.personalityName),
            gold = 1000,
            rice = 1000,
            crewTypeId = 1100,
            turnTime = world.getState().lastTurnTime,
            meta = meta.filterValues { it != null },
        )
        recorder.recordGeneralCreate(world, general)
        recorder.recordAccessLogUpsert(
            world,
            GeneralAccessLog(generalId = id, userId = ownerId.toLong(), lastRefresh = accessNowProvider()),
        )
        recorder.recordSelectPoolPick(c.uniqueName, ownerId, id, now)
        return SelectPoolActionResult(type = "selectPoolPick", ok = true, generalId = id)
    }

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
        val stats = statsFrom(row.info, false, null, null, null, fallback = me.stats)
            ?: return fail("selectPoolUpdate", c.generalId, "스탯의 총 합이 올바르지 않습니다.")
        val turnTerm = (world.getState().meta["turnterm"] as? Number)?.toLong() ?: 60L
        val nextMeta = LinkedHashMap(me.meta)
        nextMeta["next_change"] = world.getState().lastTurnTime.plus(Duration.ofMinutes(12 * turnTerm)).toString()
        for (i in 1..5) int(row.info, "dex$i", "dex", index = i - 1)?.let { nextMeta["dex$i"] = it }
        str(row.info, "picture")?.let { nextMeta["picture"] = it }
        int(row.info, "imgsvr")?.let { nextMeta["image_server"] = it }
        val next = me.copy(
            name = str(row.info, "generalName") ?: me.name,
            stats = stats,
            role = role(row.info, c.personalityName, me.role),
            meta = nextMeta,
        )
        recorder.diffGeneral(PerTurnOverlay.toLogicGeneral(me), PerTurnOverlay.toLogicGeneral(next))
        world.applyGeneralDirtyFree(next)
        recorder.recordSelectPoolUpdate(c.uniqueName, ownerId, c.generalId, now)
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
            s = (strength ?: 15).coerceIn(15, 80)
            i = (intel ?: 15).coerceIn(15, 80)
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

    private fun role(info: Map<String, Any?>, personalityName: String?, fallback: GeneralRole = GeneralRole()): GeneralRole =
        fallback.copy(
            personality = personalityName?.takeUnless { it == "Random" } ?: str(info, "ego") ?: fallback.personality,
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
