package opensamguk.logic.actions.nation

import opensamguk.common.josa.JosaUtil
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.constraints.Constraint
import opensamguk.logic.constraints.ConstraintContext
import opensamguk.logic.constraints.ConstraintResult
import opensamguk.logic.constraints.RequirementKey
import opensamguk.logic.constraints.StateView
import opensamguk.logic.constraints.beChief
import opensamguk.logic.constraints.notBeNeutral
import opensamguk.logic.constraints.notLord
import opensamguk.logic.constraints.occupiedCity
import opensamguk.logic.constraints.suppliedCity
import opensamguk.logic.stats.GeneralActionPipeline

/**
 * che_모반시도 — `legacy/devsam-core/hwe/sammo/Command/General/che_모반시도.php` 충실 포팅.
 *
 * 모반(찬탈) — 수뇌(chief, officer_level>4, 비군주)가 자국 군주(officer_level=12)의 자리를 찬탈한다.
 * **draw COUNT = 0** (run()이 RNG를 한 번도 끌지 않음 → docker 골든 불필요, [CheMobanSidoGoldenTest]가
 * run() body를 byte-exact로 대신한다).
 *
 * 부수효과(che_모반시도.php:81-85, 실행순서 = 로그순서):
 *  - actor general:      officer_level=12, officer_city=0                (:81-82)
 *  - lord general(군주):  officer_level=1,  officer_city=0, experience*=0.7 (:83-85)
 *    (`multiplyVar('experience',0.7)` = 원시 곱연산. 군주에겐 checkStatChange가 호출되지 않으므로
 *     explevel 재계산 없음 — 단순 raw 곱.)
 *
 * 군주(lordGeneral)는 `SELECT no FROM general WHERE nation=%i AND officer_level=12`로 적재되는
 * "두 번째로 변이/영속되는 장수"이므로, [GeneralActionDraft.destGeneral] 캐리어로 어댑터가 주입한다
 * (발령/포상/종전수락이 dest 장수를 destGeneral로 받는 sibling 패턴 동일). 군주명은 [destGeneralName].
 *
 * 로그(che_모반시도.php:89-96):
 *  - **버퍼 가능(현 [GeneralActionResolveContext] sink)**:
 *    · actor generalAction (:92, MONTH 포맷) → [addLog]
 *    · lord  generalAction (:93, MONTH 포맷, dest 스코프) → [addLogTo](lordId, raw body)
 *  - **sink 부재(history 스코프) → 아래 4건은 byte-exact 보존(quarantine, WAVE 후속 seam에서 배선,
 *    날조/약화 없음)** — [CheJongjeonSuak] 선례 동일:
 *    · globalHistory   (:89): "<Y><b>【모반】</b></><Y>{generalName}</>{josaYi} <D><b>{nationName}</b></>의 군주 자리를 찬탈했습니다."
 *    · nationalHistory (:90): "<Y>{generalName}</>{josaYi} <Y>{lordName}</>에게서 군주자리를 찬탈"
 *    · actor generalHistory (:95): "모반으로 <D><b>{nationName}</b></>의 군주자리를 찬탈"
 *    · lord  generalHistory (:96): "<D><b>{generalName}</b></>의 모반으로 인해 <D><b>{nationName}</b></>의 군주자리를 박탈당함"
 *
 * constraints(che_모반시도.php:35-42, PHP ORDER):
 *   [NotBeNeutral, BeChief, OccupiedCity, SuppliedCity, NotLord, AllowRebellion].
 *
 * getCost=[0,0] (:45-47), getPreReqTurn=0 (:49-51), getPostReqTurn=0 (:53-55), argTest=null/true (:22-25).
 * increaseInheritancePoint(active_action, 1) (:99)은 P6 유산포인트 seam — 골든 미캡처(이 레이어 무변, 쓰기 없음).
 */
fun cheMobanSido(@Suppress("UNUSED_PARAMETER") pipeline: GeneralActionPipeline): NationCommand = object : NationCommand() {
    override val key: String get() = "che_모반시도"
    override val name: String get() = "모반시도"
    override val reservable: Boolean get() = true

    /** che_모반시도.php:49-51 getPreReqTurn = 0. */
    override fun getPreReqTurn(): Int = 0

    /** che_모반시도.php:35-42 constraints, PHP ORDER. */
    override fun buildConstraints(ctx: ConstraintContext): List<Constraint> = listOf(
        notBeNeutral(), beChief(), occupiedCity(), suppliedCity(), notLord(), allowRebellion(),
    )

    /** argTest(:22-25) → arg=null (인자 없음). */
    override fun parseArgs(raw: Map<String, Any?>): Map<String, Any?> = emptyMap()

    /**
     * che_모반시도.php:57-106 run() 충실 포팅 — draw COUNT = 0.
     */
    override fun resolve(context: GeneralActionResolveContext) {
        val d = context.draft
        val lord = d.destGeneral ?: return            // SELECT … officer_level=12 (어댑터가 destGeneral로 주입)

        val generalName = context.generalName
        val lordName = context.destGeneralName
        val nationName = d.nation?.name ?: ""

        // --- 부수효과 (che_모반시도.php:81-85) ---
        // actor → 군주(officer_level=12), officer_city=0.
        d.general = d.general.copy(officerLevel = 12, officerCity = 0)
        // lord → 일반(officer_level=1), officer_city=0, experience *= 0.7 (raw multiplyVar, 레벨 재계산 없음).
        d.destGeneral = lord.copy(officerLevel = 1, officerCity = 0, experience = lord.experience * 0.7)

        // (:88) josaYi — global/national history(아래 quarantine 4건)에서 쓰이는 조사. history sink 부재로
        // 현재 코드 경로에서는 미소비이나, history 후속 seam 배선 시 그대로 사용한다.
        @Suppress("UNUSED_VARIABLE")
        val josaYi = JosaUtil.pick(generalName, "이")

        // --- 버퍼 가능 로그 ---
        // (:92) actor generalAction (MONTH 포맷; addLog가 '<C>●</>{month}월:' 프리픽스 부착).
        context.addLog("모반에 성공했습니다. <1>${context.date}</>")
        // (:93) lord generalAction (MONTH 포맷, dest 스코프). addLogTo는 raw body — lord 로거가 flush 시 자신의 월 프리픽스 부착.
        context.addLogTo(lord.id, "<Y>${generalName}</>에게 군주의 자리를 뺏겼습니다.")

        // --- sink 부재(history 스코프) — byte-exact 보존(quarantine, 후속 seam 배선) ---
        // (:89) globalHistory:
        //   "<Y><b>【모반】</b></><Y>${generalName}</>${josaYi} <D><b>${nationName}</b></>의 군주 자리를 찬탈했습니다."
        // (:90) nationalHistory:
        //   "<Y>${generalName}</>${josaYi} <Y>${lordName}</>에게서 군주자리를 찬탈"
        // (:95) actor generalHistory:
        //   "모반으로 <D><b>${nationName}</b></>의 군주자리를 찬탈"
        // (:96) lord generalHistory:
        //   "<D><b>${generalName}</b></>의 모반으로 인해 <D><b>${nationName}</b></>의 군주자리를 박탈당함"

        // (:99) increaseInheritancePoint(active_action, 1) — P6 유산포인트 seam(이 레이어 무변, 쓰기 없음).
        // (:100) checkStatChange() / (:101) StaticEventHandler — actor 스코프 미노출(골든 미캡처).
    }
}

/**
 * `Constraint/AllowRebellion.php` 충실 전사(轉寫) — 모반시도 전용 nation 패밀리 로컬 제약.
 *
 * PHP(AllowRebellion.php)는 `SELECT no, killturn, npc FROM general WHERE nation=%i AND officer_level=12`로
 * 군주를 적재한 뒤:
 *  - 군주가 actor 자신이면          → deny "이미 군주입니다."
 *  - 군주 killturn >= env.killturn  → deny "군주가 활동중입니다."   (활동중인 군주는 모반 불가)
 *  - 군주 npc ∈ {2,3,6,9}           → deny "군주가 NPC입니다."
 *  - 그 외                          → allow
 *
 * 군주 데이터(lordNo/lordKillturn/lordNpc) + env.killturn은 월드/DB 집계라 logic 엔티티에 없다 →
 * precheck/엔진 staging seam이 `ctx.env`/`ctx.args`의 `__lord*` 키로 주입한다([availableStrategicCommand]
 * 동일 idiom). staging 미주입 시(정적 선결조건) 통과 — 골든 게이트는 제약을 평가하지 않고 resolve()를
 * 직접 구동하므로(P2GoldenSupport 패턴) 게이트 무영향. C-PURE 승격 시 Presets로 이동.
 */
internal fun allowRebellion(): Constraint = object : Constraint {
    override val name = "AllowRebellion"
    override fun requires(ctx: ConstraintContext): List<RequirementKey> =
        listOf(RequirementKey.General(ctx.actorId))

    override fun test(ctx: ConstraintContext, view: StateView): ConstraintResult {
        val lordNo = (ctx.env["__lordNo"] as? Number)?.toInt()
            ?: (ctx.args["__lordNo"] as? Number)?.toInt()
            ?: return ConstraintResult.Allow                       // staging 미주입 → 통과
        if (lordNo == ctx.actorId) return ConstraintResult.Deny("이미 군주입니다.")

        val envKillturn = (ctx.env["killturn"] as? Number)?.toInt()
            ?: (ctx.args["killturn"] as? Number)?.toInt()
        val lordKillturn = (ctx.env["__lordKillturn"] as? Number)?.toInt()
            ?: (ctx.args["__lordKillturn"] as? Number)?.toInt()
        if (envKillturn != null && lordKillturn != null && lordKillturn >= envKillturn) {
            return ConstraintResult.Deny("군주가 활동중입니다.")
        }

        val lordNpc = (ctx.env["__lordNpc"] as? Number)?.toInt()
            ?: (ctx.args["__lordNpc"] as? Number)?.toInt()
        if (lordNpc != null && lordNpc in intArrayOf(2, 3, 6, 9)) {
            return ConstraintResult.Deny("군주가 NPC입니다.")
        }

        return ConstraintResult.Allow
    }
}
