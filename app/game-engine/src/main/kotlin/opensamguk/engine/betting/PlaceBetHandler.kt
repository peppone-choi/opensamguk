package opensamguk.engine.betting

import opensamguk.common.wire.PlaceBetFail
import opensamguk.common.wire.PlaceBetOk
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.common.wire.TurnDaemonCommandResult
import opensamguk.engine.auction.TurnDaemonCommandHandler
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.LogEntryDraft
import opensamguk.logic.util.jsonEncode

/**
 * 베팅 참여 핸들러 — [TurnDaemonCommand.PlaceBet] 명령 처리.
 *
 * PHP `API/Betting/Bet.php` 의 Kotlin 포팅. 베팅 참여 전체 흐름:
 * 1. 장수 조회 및 검증
 * 2. 베팅 금액 검증 (amount > 0)
 * 3. 자원(금) 충분 여부 확인
 * 4. 금 차감 ([InMemoryTurnWorld.updateGeneral])
 * 5. `ng_betting` INSERT ([ChangeRecorder.recordBettingInsert])
 * 6. 로그 작성
 * 7. 결과 반환 ([PlaceBetOk] / [PlaceBetFail])
 *
 * [AuctionBidHandler]와 동일하게 per-run plain 클래스다. Spring `@Component` 미등록,
 * 턴 파이프라인이 직접 인스턴스화한다.
 */
class PlaceBetHandler(
    private val world: InMemoryTurnWorld,
    private val recorder: ChangeRecorder,
) : TurnDaemonCommandHandler<TurnDaemonCommand.PlaceBet> {

    override fun handle(command: TurnDaemonCommand.PlaceBet): TurnDaemonCommandResult {
        val bettingId = command.bettingId
        val generalId = command.generalId
        val amount = command.amount
        val bettingType = command.bettingType

        // ── 1. 장수 조회 ─────────────────────────────────────────────────────
        val general = world.getGeneralById(generalId)
            ?: return PlaceBetFail(bettingId = bettingId, reason = "장수가 존재하지 않습니다.")

        // ── 2. 금액 검증 ─────────────────────────────────────────────────────
        if (amount <= 0) {
            return PlaceBetFail(bettingId = bettingId, reason = "베팅 금액은 0보다 커야 합니다.")
        }

        // ── 3. 자원(금) 충분 여부 확인 ─────────────────────────────────────────
        if (general.gold < amount) {
            return PlaceBetFail(bettingId = bettingId, reason = "금이 부족합니다. (보유: ${general.gold}, 필요: $amount)")
        }

        // ── 4. 금 차감 ───────────────────────────────────────────────────────
        val updatedGeneral = general.copy(gold = general.gold - amount)
        world.updateGeneral(updatedGeneral)

        // ── 5. ng_betting INSERT ─────────────────────────────────────────────
        recorder.recordBettingInsert(
            linkedMapOf(
                "betting_id" to bettingId,
                "general_id" to generalId,
                "user_id" to (general.meta["user_id"] as? Int),
                "betting_type" to jsonEncode(bettingType),
                "amount" to amount,
            )
        )

        // ── 6. 로그 작성 ─────────────────────────────────────────────────────
        world.pushLog(
            LogEntryDraft(
                scope = "action",
                category = "betting",
                text = "${general.name} 이(가) 베팅 #${bettingId} 에 ${amount} 금을 베팅했습니다.",
                generalId = generalId,
            )
        )

        // ── 7. 결과 반환 ─────────────────────────────────────────────────────
        return PlaceBetOk(
            bettingId = bettingId,
            generalId = generalId,
            amount = amount,
        )
    }
}
