package opensamguk.logic.actions.nation

import opensamguk.logic.constraints.Constraint
import opensamguk.logic.constraints.ConstraintContext
import opensamguk.logic.constraints.ConstraintResult
import opensamguk.logic.constraints.RequirementKey
import opensamguk.logic.constraints.StateView

/**
 * C3 전략(strategic) 커맨드 전용 로컬 제약 헬퍼.
 *
 * 12개 C3 사령(chief) 커맨드(급습/몰수/물자원조/백성동원/부대탈퇴지시/수몰/의병모집/이호경식/초토화/
 * 피장파장/필사즉생/허보)가 공유하는, 아직 C-PURE(`constraints/Presets.kt`)에 없는 두 제약을
 * nation 패밀리 로컬로 둔다. 둘 다 PHP `ConstraintHelper`의 충실한 전사(轉寫)이며, 골든 게이트는
 * 제약을 평가하지 않고 `resolve()`를 직접 구동하므로(P2GoldenSupport 패턴) 게이트에는 영향을 주지
 * 않는다. C-PURE로 승격할 때(공유 확장점) 이 파일을 비우고 Presets로 이동한다.
 */

/**
 * `ConstraintHelper::AvailableStrategicCommand()` — 국가의 `strategic_cmd_limit`(전략 커맨드 잔여
 * 사용 횟수)이 남아있어야 한다. PHP는 `setNation(['strategic_cmd_limit'])`로 적재한 nation 값을 본다.
 *
 * 기존 `allowStrategicCommand()`(C-PURE, `AllowStrategicCommand` = nation.war==0 전쟁금지 게이트)와는
 * 다른 PHP 제약이다 — 혼동 금지. 매니페스트(manifest_c3.json)대로 전략 게이트는 문서화된 정적 선결
 * 조건(static precondition)이므로, staging 시드(`ctx.env["__strategicCmdLimit"]`)가 없으면 통과로
 * 처리한다(precheck staging seam 미구현 → P7 read-side TODO). 음수/0이면 deny.
 */
internal fun availableStrategicCommand(): Constraint = object : Constraint {
    override val name = "AvailableStrategicCommand"
    override fun requires(ctx: ConstraintContext): List<RequirementKey> = emptyList()
    override fun test(ctx: ConstraintContext, view: StateView): ConstraintResult {
        // staging seam이 채워지면 실제 잔여 횟수를 본다; 부재 시(정적 선결조건) 통과.
        val remain = (ctx.env["__strategicCmdLimit"] as? Number)?.toInt()
            ?: (ctx.args["__strategicCmdLimit"] as? Number)?.toInt()
            ?: return ConstraintResult.Allow
        return if (remain > 0) ConstraintResult.Allow
        else ConstraintResult.Deny("전략 커맨드 제한 횟수를 초과했습니다.")
    }
}

/**
 * `ConstraintHelper::DisallowDiplomacyStatus(nationID, {state: msg, …})` — 아국이 지정한 외교상태에
 * 있으면 deny(초토화: state 0(교전) 금지 = 평시에만 가능). `DisallowDiplomacyBetweenStatus`(양방향
 * dest 국가와의 상태)와 달리, 이쪽은 아국의 ANY 외교관계가 금지 상태에 걸리면 deny한다.
 *
 * 인접/외교 인접성은 F-BFS preload(staging) 대상이다. precheck staging seam 미구현 시
 * `ctx.env["__disallowDiplomacyHit"]`(Boolean: 금지 상태에 걸린 관계가 하나라도 있는가)가 없으면
 * 통과로 처리한다(P7 read-side TODO). PHP는 `getMyNationStaticInfo` + 모든 외교행을 순회한다.
 */
internal fun disallowDiplomacyStatus(disallowStatus: Map<Int, String>): Constraint = object : Constraint {
    override val name = "DisallowDiplomacyStatus"
    override fun requires(ctx: ConstraintContext): List<RequirementKey> = emptyList()
    override fun test(ctx: ConstraintContext, view: StateView): ConstraintResult {
        val hit = (ctx.env["__disallowDiplomacyHit"] as? Boolean)
            ?: (ctx.args["__disallowDiplomacyHit"] as? Boolean)
            ?: return ConstraintResult.Allow
        if (!hit) return ConstraintResult.Allow
        // 어떤 state가 걸렸는지는 staging이 알려준다; 부재 시 첫 메시지로 deny(게이트 미평가 경로).
        val hitState = (ctx.env["__disallowDiplomacyHitState"] as? Number)?.toInt()
        val msg = disallowStatus[hitState] ?: disallowStatus.values.firstOrNull() ?: "외교상태 제한입니다."
        return ConstraintResult.Deny(msg)
    }
}
