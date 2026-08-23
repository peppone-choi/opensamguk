package opensamguk.logic.actions.nation

import opensamguk.common.josa.JosaUtil
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.constraints.Constraint
import opensamguk.logic.constraints.ConstraintContext
import opensamguk.logic.constraints.beChief
import opensamguk.logic.constraints.disallowDiplomacyBetweenStatus
import opensamguk.logic.constraints.existsDestGeneral
import opensamguk.logic.constraints.existsDestNation
import opensamguk.logic.constraints.notBeNeutral
import opensamguk.logic.constraints.occupiedCity
import opensamguk.logic.constraints.reqDestNationValue
import opensamguk.logic.constraints.suppliedCity
import opensamguk.logic.diplomacy.DiplomacyState
import opensamguk.logic.domain.Diplomacy
import opensamguk.logic.stats.GeneralActionPipeline

/**
 * che_불가침수락 — 불가침 서신 수락 (instant-nation 외교 수락 3종 중 하나).
 *
 * 수신국 군주가 불가침 제의 서신을 수락하면 즉시 실행된다. 양방향 diplomacy 행을 불가침(NON_AGGRESSION=7)으로
 * 전환하고 term = (제의 만기 - 현재월)을 설정한다.
 *
 * 역사 PHP 기준 (ADR-LITE-042; 현재 제품 정본 아님): `legacy/devsam-core/hwe/sammo/Command/Nation/che_불가침수락.php`
 *
 * ## 제약 (che_불가침수락.php:121-140 — reqMonth<=currentMonth면 AlwaysFail '이미 기한이 지났습니다.')
 *  - BeChief, NotBeNeutral, OccupiedCity, SuppliedCity, ExistsDestNation, ExistsDestGeneral,
 *    ReqDestNationValue('nation','소속','==', destGeneral.nationID, '제의 장수가 국가 소속이 아닙니다'),
 *    DisallowDiplomacyBetweenStatus([WAR=>'아국과 이미 교전중입니다.', DECLARATION=>'아국과 이미 선포중입니다.'])
 *
 * ## run() 부수효과 (che_불가침수락.php:171-233 실행 순서, draw 0)
 *  1. dest 국가 nation_env KV: `resp_assist["n{me}"] = [me, recv_assist["n{me}"][1] ?? 0]` (L194-195).
 *  2. term = reqMonth - currentMonth, reqMonth = year*12 + month, currentMonth = env.year*12 + env.month - 1 (L203-204).
 *  3. diplomacy 양방향 state=7/term (forward + reverse).
 *  4. 로그 4건(actor generalAction PLAIN + generalHistory, dest generalAction PLAIN + generalHistory).
 *  5. StaticEventHandler::handleEvent.
 *
 * ## 인프라 갭 (사실대로 — 미날조)
 * PHP `term = reqMonth - currentMonth`이며 `reqMonth = year*12+month`(−1 없음). run() 내부에는 6개월 하한
 * 가드가 **없다** — 6개월 게이트는 제의(che_불가침제의) 시점 제약이지 수락 run()의 조건이 아니다. (이전 orphaned
 * 구현의 `reqYearMonth = year*12+month-1` off-by-one + `if(term<6) return` 가드는 PHP에 없는 날조였으므로
 * 제거했다.)  resp_assist KV는 [GeneralActionResolveContext]에 KV sink이 없어 buffer 불가 → quarantine.
 * generalHistory 스코프 2건도 sink 부재 → byte-exact 문자열을 주석에 보존(WAVE-4 배선 시 이식). StaticEventHandler
 * 동일. 버퍼 가능한 actor/dest action PLAIN 2건만 byte-exact 적재.
 */
fun cheBulgachimSuak(@Suppress("UNUSED_PARAMETER") pipeline: GeneralActionPipeline): CheBulgachimSuak =
    CheBulgachimSuak(pipeline)

class CheBulgachimSuak(@Suppress("UNUSED_PARAMETER") pipeline: GeneralActionPipeline) : NationCommand() {
    override val key: String get() = "che_불가침수락"
    override val name: String get() = "불가침 수락"
    override val category: String get() = "외교"
    override val argsSchema: Map<String, Any?> get() = linkedMapOf(
        "destNationID" to "int",
        "destGeneralID" to "int",
        "year" to "int",
        "month" to "int",
    )

    override fun getPreReqTurn(): Int = 0

    override fun buildMinConstraints(ctx: ConstraintContext): List<Constraint> = listOf(
        beChief(), notBeNeutral(),
    )

    override fun buildConstraints(ctx: ConstraintContext): List<Constraint> = listOf(
        beChief(),
        notBeNeutral(),
        occupiedCity(),
        suppliedCity(),
        existsDestNation(),
        existsDestGeneral(),
        // che_불가침수락.php:135 — dest 소속 검증 (CheJongjeonSuak 동일 표현 한계 주석 참조).
        reqDestNationValue("nation", "소속", "==", ctx.destNationId ?: 0, "제의 장수가 국가 소속이 아닙니다"),
        disallowDiplomacyBetweenStatus(
            linkedMapOf(
                DiplomacyState.WAR to "아국과 이미 교전중입니다.",
                DiplomacyState.DECLARATION to "아국과 이미 선포중입니다.",
            ),
        ),
    )

    override fun parseArgs(raw: Map<String, Any?>): Map<String, Any?> {
        val destNationID = (raw["destNationID"] as? Number)?.toInt() ?: return emptyMap()
        if (destNationID < 1) return emptyMap()
        val destGeneralID = (raw["destGeneralID"] as? Number)?.toInt() ?: return emptyMap()
        if (destGeneralID <= 0) return emptyMap()
        val year = (raw["year"] as? Number)?.toInt() ?: return emptyMap()
        val month = (raw["month"] as? Number)?.toInt() ?: return emptyMap()
        if (month < 1 || month > 12) return emptyMap()
        return linkedMapOf(
            "destNationID" to destNationID,
            "destGeneralID" to destGeneralID,
            "year" to year,
            "month" to month,
        )
    }

    /**
     * che_불가침수락.php:171-233 run(). draw 0.
     *
     * 로그 매핑:
     *  - L220 actor generalAction PLAIN → [GeneralActionResolveContext.addActionPlainLog]
     *      `<D><b>{dest}</b></>{와} <C>{year}</>년 <C>{month}</>월까지 불가침에 성공했습니다.`
     *  - L221 actor generalHistory → sink 부재(quarantine):
     *      `<D><b>{dest}</b></>{와} {year}년 {month}월까지 불가침 성공`
     *  - L225 dest generalAction PLAIN → [addPlainLogTo]
     *      `<D><b>{nation}</b></>{와} <C>{year}</>년 <C>{month}</>월까지 불가침에 성공했습니다.`
     *  - L226 dest generalHistory → sink 부재(quarantine):
     *      `<D><b>{nation}</b></>{와} {year}년 {month}월까지 불가침 성공`
     */
    override fun resolve(context: GeneralActionResolveContext) {
        val draft = context.draft

        // DIVERGENCE (fiveStatLogic ON): 수락측 장수 정치력 부족 → 수락 실패. 외교 효과 없음(아래 cascadeDiplomacy
        // 미실행). 패러티 오라클 없음 → 한글 실패 로그만 남긴다. 플래그 OFF면 baseline byte-identical.
        if (context.env.fiveStatLogic && draft.general.politics < DiplomacyDivergence.POLITICS_DIPLOMACY_BAR) {
            context.addActionPlainLog(DiplomacyDivergence.POLITICS_FAIL_LOG)
            return
        }

        val me = draft.nation?.id ?: return
        val destNationID = (context.args["destNationID"] as? Number)?.toInt() ?: return
        val year = (context.args["year"] as? Number)?.toInt() ?: return
        val month = (context.args["month"] as? Number)?.toInt() ?: return

        // 1) dest 국가 nation_env KV: resp_assist["n{me}"] = [me, recv_assist["n{me}"][1] ?? 0].
        //    KV sink이 GeneralActionResolveContext에 없어 quarantine. WAVE-4 KV seam에서 배선.

        // 2) term = reqMonth - currentMonth (PHP byte-exact: reqMonth는 -1 없음).
        val currentMonth = context.env.year * 12 + context.month - 1
        val reqMonth = year * 12 + month
        val term = reqMonth - currentMonth

        // 3) diplomacy 양방향 불가침(NON_AGGRESSION=7)/term
        draft.cascadeDiplomacy.add(Diplomacy(me = me, you = destNationID, state = DiplomacyState.NON_AGGRESSION, term = term))
        draft.cascadeDiplomacy.add(Diplomacy(me = destNationID, you = me, state = DiplomacyState.NON_AGGRESSION, term = term))

        // 이름 — dest 국명은 어댑터가 destGeneralName으로 주입(숫자 id 아님).
        val nationName = draft.nation?.name?.ifEmpty { "아국" } ?: "아국"
        val destNationName = context.destGeneralName.ifEmpty { "상대국" }
        val josaWaDest = JosaUtil.pick(destNationName, "와")
        val josaWaNation = JosaUtil.pick(nationName, "와")

        // L220 actor generalAction PLAIN
        context.addActionPlainLog(
            "<D><b>$destNationName</b></>$josaWaDest <C>$year</>년 <C>$month</>월까지 불가침에 성공했습니다.",
        )
        // L221 actor generalHistory (sink 부재 — byte-exact 보존):
        //   "<D><b>$destNationName</b></>$josaWaDest ${year}년 ${month}월까지 불가침 성공"

        val destGeneralId = draft.destGeneral?.id
        if (destGeneralId != null) {
            // L225 dest generalAction PLAIN
            context.addPlainLogTo(
                destGeneralId,
                "<D><b>$nationName</b></>$josaWaNation <C>$year</>년 <C>$month</>월까지 불가침에 성공했습니다.",
            )
            // L226 dest generalHistory (sink 부재 — byte-exact 보존):
            //   "<D><b>$nationName</b></>$josaWaNation ${year}년 ${month}월까지 불가침 성공"
        }

        // 5) StaticEventHandler::handleEvent — quarantine (sink 부재).
    }
}
