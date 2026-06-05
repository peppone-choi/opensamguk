package opensamguk.engine.intake

import opensamguk.common.wire.SelectPoolActionResult
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.common.wire.TurnDaemonCommandResult
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.infra.read.SelectPoolRepository

/**
 * 장수 선택 풀 (pick/update) intake 핸들러 — `j_pick_general.php` /
 * `j_update_picked_general.php` `launch()` 본문의 faithful 포팅 (W6f 장수 선택 풀, RNG-BEARING).
 * per-run (world + recorder + select-pool read seam), [BoardHandler]+[VoteHandler]를 미러링.
 *
 * **현 단계 = FOUNDATION 스텁.** 시그니처/디스패처 배선만 고정하고, 본문은 deny-only로
 * `SelectPoolActionResult(ok=false, reason="미구현")`를 반환한다. 실제 본문(가중 추첨 `allStat^1.5`
 * RandUtil(LiteHashDrbg) draw-for-draw, mark-then-swap 동시성(general_id=-id then =id, affectedRows==0
 * deny), next_change 쿨다운(now+12*turnterm) in general.aux, 스탯합 strict `>`, 모든 deny/log 문자열)은
 * W6f 슬라이스에서 채운다. **RNG draw-for-draw 골든 게이트는 /parity-wave로 이관** — 절대 fabricate 금지.
 * 부수 효과는 [ChangeRecorder]를 통해서만 기록한다(one-daemon-write).
 *
 * [selectPoolRepository]는 nullable — null이면 stub-empty(풀 항목 없음)로 동작한다.
 */
class SelectPoolHandler(
    @Suppress("unused") private val world: InMemoryTurnWorld,
    @Suppress("unused") private val recorder: ChangeRecorder,
    @Suppress("unused") private val selectPoolRepository: SelectPoolRepository? = null,
) {
    /** 선택 풀 픽 (RNG-bearing). W6f 슬라이스에서 본문을 채운다. 스텁: deny-only. */
    fun handlePick(c: TurnDaemonCommand.SelectPoolPick): TurnDaemonCommandResult {
        // W6f 슬라이스 미구현 — deny-only 스텁. (RNG 골든은 /parity-wave)
        return SelectPoolActionResult(type = "selectPoolPick", ok = false, generalId = c.generalId, reason = "미구현")
    }

    /** 선택 풀 장수 갱신. W6f 슬라이스에서 본문을 채운다. 스텁: deny-only. */
    fun handleUpdate(c: TurnDaemonCommand.SelectPoolUpdate): TurnDaemonCommandResult {
        // W6f 슬라이스 미구현 — deny-only 스텁.
        return SelectPoolActionResult(type = "selectPoolUpdate", ok = false, generalId = c.generalId, reason = "미구현")
    }
}
