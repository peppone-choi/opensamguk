package opensamguk.engine.intake

import opensamguk.common.josa.JosaUtil
import opensamguk.common.wire.GeneralBoolResult
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.common.wire.TurnDaemonCommandResult
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.GeneralAccessLog
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.PerTurnOverlay
import opensamguk.logic.util.jsonDecodeAny
import java.time.Instant

/**
 * B2 장수빙의(claim NPC) 데몬 핸들러.
 *
 * PHP `hwe/j_select_npc.php:93-102`의 충실 포팅 — general row game-state mutation.
 * Account-side `general_owner` INSERT는 `GeneralPossessionService`가 JPA로 처리;
 * 이 핸들러는 game-state side-effect만 담당 (memory-centric CQRS).
 *
 * 변경 필드:
 *   npc=2 → 1, owner=userId, owner_name=userNick,
 *   killturn=6, defence_train=80, permission='normal',
 *   aux.pickYearMonth = year+month, penalty = userPenalty.
 *
 * 로그:
 *   pushGeneralHistoryLog + pushGlobalActionLog (빙의)
 */
class ClaimNpcHandler(
    private val world: InMemoryTurnWorld,
    private val recorder: ChangeRecorder,
    private val nowProvider: () -> Instant = Instant::now,
) {

    fun handle(command: TurnDaemonCommand.ClaimNpc): TurnDaemonCommandResult {
        val generalId = command.generalId
        val pre = world.getGeneralById(generalId)
            ?: return GeneralBoolResult(type = "claimNpc", ok = false, generalId = generalId, reason = "장수를 찾을 수 없습니다.")

        // PHP WHERE: owner <= 0 AND npc = 2 AND no = $pick
        val currentOwner = pre.userId?.toLongOrNull() ?: 0L
        if (pre.npcState != 2 || currentOwner > 0) {
            return GeneralBoolResult(type = "claimNpc", ok = false, generalId = generalId, reason = "빙의 가능한 장수가 아닙니다.")
        }

        val state = world.getState()
        val year = state.currentYear
        val month = state.currentMonth
        val pickYearMonth = year * 12 + (month - 1) // PHP Util::joinYearMonth

        val preLogic = PerTurnOverlay.toLogicGeneral(pre)

        // aux 누적
        val aux = LinkedHashMap<String, Any?>(pre.meta)
        aux["pickYearMonth"] = pickYearMonth
        val userPenalty = runCatching { jsonDecodeAny(command.userPenaltyJson) as? Map<String, Any?> }.getOrNull() ?: emptyMap<String, Any?>()
        if (userPenalty.isNotEmpty()) {
            aux["penalty"] = LinkedHashMap(userPenalty)
        }

        val post = pre.copy(
            npcState = 1,
            userId = command.userId.toString(),
            meta = aux,
            // defence_train, permission, killturn, owner_name은 meta에 저장
            // (TurnGeneral에 직접 필드가 없음)
        ).let { g ->
            // defence_train=80, permission='normal', killturn=6, owner_name 저장
            val enriched = LinkedHashMap<String, Any?>(g.meta)
            enriched["defence_train"] = 80
            enriched["permission"] = "normal"
            enriched["killturn"] = 6
            enriched["owner_name"] = command.userNick
            g.copy(meta = enriched)
        }

        world.applyGeneralDirtyFree(post)
        recorder.diffGeneral(preLogic, PerTurnOverlay.toLogicGeneral(post))
        recorder.recordAccessLogUpsert(
            world,
            GeneralAccessLog(generalId = generalId, userId = command.userId, lastRefresh = nowProvider()),
        )

        // 로그 — PHP j_select_npc.php:121-122
        val josaYi = JosaUtil.pick(command.userNick, "이")
        val historyLog = "<Y>${post.name}</>의 육체에 <Y>${command.userNick}</>${josaYi} 빙의되다."
        val globalLog = "<Y>${post.name}</>의 육체에 <Y>${command.userNick}</>${josaYi} <S>빙의</>됩니다!"

        world.pushLog(
            opensamguk.engine.turn.LogEntryDraft(
                scope = "general",
                category = "history",
                text = historyLog,
                generalId = generalId,
                nationId = post.nationId,
            )
        )
        world.pushLog(
            opensamguk.engine.turn.LogEntryDraft(
                scope = "global",
                category = "action",
                text = globalLog,
                generalId = generalId,
                nationId = post.nationId,
            )
        )

        return GeneralBoolResult(type = "claimNpc", ok = true, generalId = generalId)
    }
}
