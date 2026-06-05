package opensamguk.engine.intake

import opensamguk.common.wire.GeneralBoolResult
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.common.wire.TurnDaemonCommandResult
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld

/**
 * 건국 후보(거병) intake 핸들러 — `buildNationCandidate` wire 명령 처리 (W6d, RNG-BEARING).
 * per-run (world + recorder), [BoardHandler]를 미러링.
 *
 * **현 단계 = FOUNDATION 스텁.** 시그니처/디스패처 배선만 고정하고, 본문은 deny-only로
 * `GeneralBoolResult(ok=false, reason="미구현")`를 반환한다. 실제 본문(개설 조건 검증, `che_거병`
 * 인라인 실행 — `tryUniqueItemLottery` RNG 포함, nation INSERT(color #330000, type che_중립, gennum=1) +
 * 기존 국가별 diplomacy 2행(state=2,term=0) + 24× nation_turn, 모든 deny/log 문자열)은 W6d 슬라이스에서
 * 채운다. **RNG draw-for-draw 골든 게이트는 /parity-wave로 이관** — 절대 fabricate 금지. 부수 효과는
 * [ChangeRecorder]를 통해서만 기록한다(one-daemon-write).
 *
 * **Q-D1 RESOLVED.** `buildNationCandidate`는 `BOOLEAN_OK_TYPES`에 유지되므로 결과는
 * [GeneralBoolResult]다(`BuildNationCandidateResult` 미사용). nationId echo가 load-bearing이 되면
 * 그때 result-file 콜랩스를 교체한다.
 */
class BuildNationCandidateHandler(
    @Suppress("unused") private val world: InMemoryTurnWorld,
    @Suppress("unused") private val recorder: ChangeRecorder,
) {
    /** 건국 후보(거병). W6d 슬라이스에서 본문을 채운다. 스텁: deny-only. */
    fun handle(c: TurnDaemonCommand.BuildNationCandidate): TurnDaemonCommandResult {
        // W6d 슬라이스 미구현 — deny-only 스텁. (RNG 골든은 /parity-wave)
        return GeneralBoolResult(type = "buildNationCandidate", ok = false, generalId = c.generalId, reason = "미구현")
    }
}
