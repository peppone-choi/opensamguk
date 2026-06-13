package opensamguk.logic.actions.nation

import opensamguk.common.josa.JosaUtil
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.constraints.Constraint
import opensamguk.logic.constraints.ConstraintContext
import opensamguk.logic.constraints.allowDiplomacyBetweenStatus
import opensamguk.logic.constraints.beChief
import opensamguk.logic.constraints.existsDestGeneral
import opensamguk.logic.constraints.existsDestNation
import opensamguk.logic.constraints.notBeNeutral
import opensamguk.logic.constraints.reqDestNationValue
import opensamguk.logic.diplomacy.DiplomacyState
import opensamguk.logic.domain.Diplomacy
import opensamguk.logic.stats.GeneralActionPipeline

/**
 * che_불가침파기수락 — 불가침 파기 서신 수락 (instant-nation 외교 수락 3종 중 하나).
 *
 * 수신국 군주가 불가침 파기 서신을 수락하면 즉시 실행된다. 양방향 diplomacy 행을 통상(TRADE=2)/term=0으로
 * 전환해 불가침 조약을 해소한다.
 *
 * PHP 정본: `legacy/devsam-core/hwe/sammo/Command/Nation/che_불가침파기수락.php`
 *
 * ## 제약 (che_불가침파기수락.php:87-97)
 *  - BeChief, NotBeNeutral, ExistsDestNation, ExistsDestGeneral,
 *    ReqDestNationValue('nation','소속','==', destGeneral.nationID, '제의 장수가 국가 소속이 아닙니다'),
 *    AllowDiplomacyBetweenStatus([NON_AGGRESSION(7)], '불가침 중인 상대국에게만 가능합니다.')
 *
 * ## run() 부수효과 (che_불가침파기수락.php:149-179 실행 순서, draw 0)
 *  1. diplomacy 양방향 state=2/term=0 (forward + reverse).
 *  2. 로그 6건(actor generalAction PLAIN + generalHistory + globalAction + globalHistory,
 *     dest generalAction PLAIN + generalHistory).
 *  3. StaticEventHandler::handleEvent.
 *
 * ## 인프라 갭 (사실대로 — 미날조)
 * generalHistory / globalHistory / dest generalHistory 스코프 + StaticEventHandler는
 * [GeneralActionResolveContext]에 sink이 없다(WAVE-4 이연). 버퍼 가능한 actor action PLAIN(L166) +
 * actor globalAction(L169) + dest action PLAIN(L174) 3건만 byte-exact 적재, 나머지 3건은 byte-exact 문자열을
 * 주석에 보존. 로그 문자열은 `{와}의`(josa 와/과 + literal '의') byte-exact.
 */
fun cheBulgachimPagiSuak(@Suppress("UNUSED_PARAMETER") pipeline: GeneralActionPipeline): CheBulgachimPagiSuak =
    CheBulgachimPagiSuak(pipeline)

class CheBulgachimPagiSuak(@Suppress("UNUSED_PARAMETER") pipeline: GeneralActionPipeline) : NationCommand() {
    override val key: String get() = "che_불가침파기수락"
    override val name: String get() = "불가침 파기 수락"
    override val category: String get() = "외교"
    override val argsSchema: Map<String, Any?> get() = linkedMapOf(
        "destNationID" to "int",
        "destGeneralID" to "int",
    )

    override fun getPreReqTurn(): Int = 0

    override fun buildMinConstraints(ctx: ConstraintContext): List<Constraint> = listOf(
        beChief(), notBeNeutral(),
    )

    override fun buildConstraints(ctx: ConstraintContext): List<Constraint> = listOf(
        beChief(),
        notBeNeutral(),
        existsDestNation(),
        existsDestGeneral(),
        // che_불가침파기수락.php:92 — dest 소속 검증 (CheJongjeonSuak 동일 표현 한계 주석 참조).
        reqDestNationValue("nation", "소속", "==", ctx.destNationId ?: 0, "제의 장수가 국가 소속이 아닙니다"),
        allowDiplomacyBetweenStatus(
            listOf(DiplomacyState.NON_AGGRESSION),
            "불가침 중인 상대국에게만 가능합니다.",
        ),
    )

    override fun parseArgs(raw: Map<String, Any?>): Map<String, Any?> {
        val destNationID = (raw["destNationID"] as? Number)?.toInt() ?: return emptyMap()
        if (destNationID < 1) return emptyMap()
        val destGeneralID = (raw["destGeneralID"] as? Number)?.toInt() ?: return emptyMap()
        if (destGeneralID <= 0) return emptyMap()
        return linkedMapOf("destNationID" to destNationID, "destGeneralID" to destGeneralID)
    }

    /**
     * che_불가침파기수락.php:126-182 run(). draw 0.
     *
     * 로그 매핑:
     *  - L166 actor generalAction PLAIN → [GeneralActionResolveContext.addActionPlainLog]
     *      `<D><b>{dest}</b></>{와}의 불가침을 파기했습니다.`
     *  - L167 actor generalHistory → sink 부재(quarantine): `<D><b>{dest}</b></>{와}의 불가침 파기 수락`
     *  - L169 actor globalAction(MONTH) → [addGlobalActionLog]
     *      `<Y>{general}</>{이} <D><b>{dest}</b></>{와}의 불가침 조약을 <M>파기</> 하였습니다.`
     *  - L170 actor globalHistory → sink 부재(quarantine):
     *      `<Y><b>【파기】</b></><D><b>{nation}</b></>{이} <D><b>{dest}</b></>{와}의 불가침 조약을 <M>파기</> 하였습니다.`
     *  - L174 dest generalAction PLAIN → [addPlainLogTo]
     *      `<D><b>{nation}</b></>{와}의 불가침 파기에 성공했습니다.`
     *  - L175 dest generalHistory → sink 부재(quarantine): `<D><b>{nation}</b></>{와}의 불가침 파기 성공`
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

        // 1) diplomacy 양방향 통상(TRADE=2)/term=0
        draft.cascadeDiplomacy.add(Diplomacy(me = me, you = destNationID, state = DiplomacyState.TRADE, term = 0))
        draft.cascadeDiplomacy.add(Diplomacy(me = destNationID, you = me, state = DiplomacyState.TRADE, term = 0))

        val generalName = context.generalName.ifEmpty { "장수" }
        val nationName = draft.nation?.name?.ifEmpty { "아국" } ?: "아국"
        val destNationName = context.destGeneralName.ifEmpty { "상대국" }

        val josaWaDest = JosaUtil.pick(destNationName, "와")
        val josaIGeneral = JosaUtil.pick(generalName, "이")
        @Suppress("UNUSED_VARIABLE") val josaINation = JosaUtil.pick(nationName, "이")
        val josaWaNation = JosaUtil.pick(nationName, "와")

        // L166 actor generalAction PLAIN
        context.addActionPlainLog("<D><b>$destNationName</b></>${josaWaDest}의 불가침을 파기했습니다.")
        // L167 actor generalHistory (sink 부재 — byte-exact 보존):
        //   "<D><b>$destNationName</b></>${josaWaDest}의 불가침 파기 수락"
        // L169 actor globalAction (MONTH)
        context.addGlobalActionLog(
            "<Y>$generalName</>$josaIGeneral <D><b>$destNationName</b></>${josaWaDest}의 불가침 조약을 <M>파기</> 하였습니다.",
        )
        // L170 actor globalHistory (sink 부재 — byte-exact 보존):
        //   "<Y><b>【파기】</b></><D><b>$nationName</b></>$josaINation <D><b>$destNationName</b></>${josaWaDest}의 불가침 조약을 <M>파기</> 하였습니다."

        val destGeneralId = draft.destGeneral?.id
        if (destGeneralId != null) {
            // L174 dest generalAction PLAIN
            context.addPlainLogTo(destGeneralId, "<D><b>$nationName</b></>${josaWaNation}의 불가침 파기에 성공했습니다.")
            // L175 dest generalHistory (sink 부재 — byte-exact 보존):
            //   "<D><b>$nationName</b></>${josaWaNation}의 불가침 파기 성공"
        }

        // 3) StaticEventHandler::handleEvent — quarantine (sink 부재).
    }
}
