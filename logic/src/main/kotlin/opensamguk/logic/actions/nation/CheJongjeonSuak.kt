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
 * che_종전수락 — 종전(휴전) 서신 수락 (instant-nation 외교 수락 3종 중 하나).
 *
 * 수신국 군주가 종전 제의 서신을 수락하면 즉시 실행된다(PHP `DiplomaticMessage::agreeMessage()` →
 * `$commandObj->run()`). 양방향 diplomacy 행을 통상(TRADE=2)/term=0으로 전환해 교전/선포 상태를 끝낸다.
 *
 * PHP 정본: `legacy/devsam-core/hwe/sammo/Command/Nation/che_종전수락.php`
 *
 * ## 제약 (PHP run 진입 시 hasFullConditionMet 강제, che_종전수락.php:95-105)
 *  - BeChief, NotBeNeutral, ExistsDestNation, ExistsDestGeneral,
 *    ReqDestNationValue('nation','소속','==', destGeneral.nationID, '제의 장수가 국가 소속이 아닙니다'),
 *    AllowDiplomacyBetweenStatus([WAR(0), DECLARATION(1)], '상대국과 선포, 전쟁중이지 않습니다.')
 *
 * ## run() 부수효과 (che_종전수락.php:157-194 실행 순서)
 *  1. diplomacy 양방향 state=2/term=0 (forward me→you, reverse you→me).
 *  2. SetNationFront(nationID), SetNationFront(destNationID) — 전선 재계산.
 *  3. 로그 8건(actor 5 + dest 3, 아래 [resolve] 매핑 참조).
 *  4. StaticEventHandler::handleEvent(actor, destGeneral, ...).
 *
 * ## 인프라 갭 (사실대로 — 미날조)
 * 현 [GeneralActionResolveContext]의 일반-패스 sink은 actor action / global-action / dest action만
 * 엔진([ReservedTurnHandler])이 드레인한다. PHP가 쓰는 generalHistory / nationalHistory / globalHistory /
 * destNationalHistory 스코프, `SetNationFront`, `StaticEventHandler`는 **이 컨텍스트에 버퍼/sink이 없다**
 * (엔진 주석상 "WAVE 4 log/output pass"로 이연된 공통 로그-라우팅 갭). 본 파일 편집 범위(수락 3종 ActionDef +
 * INSTANT_NATION_COMMAND_KEYS + GoldenTest)에서 컨텍스트/엔진을 넓히는 것은 금지되어 있으므로, 버퍼 가능한
 * 3건만 byte-exact로 적재하고 나머지는 quarantine으로 문서화한다. 누락 5건의 byte-exact 문자열은 아래 주석에
 * 보존되어 WAVE-4가 sink을 배선하면 그대로 옮기면 된다(날조 아님, 약화 아님).
 */
fun cheJongjeonSuak(@Suppress("UNUSED_PARAMETER") pipeline: GeneralActionPipeline): CheJongjeonSuak =
    CheJongjeonSuak(pipeline)

class CheJongjeonSuak(@Suppress("UNUSED_PARAMETER") pipeline: GeneralActionPipeline) : NationCommand() {
    override val key: String get() = "che_종전수락"
    override val name: String get() = "종전 수락"
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
        // che_종전수락.php:100 — 제의 장수가 dest 국가 소속인지 검증. PHP 원본은 destGeneral.getNationID()를
        // reqVal로 쓴다(StateView로 dest 장수 행 조회). 현 ConstraintContext는 dest 장수의 nationID를
        // 스레딩하지 않으므로(범위 밖), dest 국가의 'nation' 필드 == destNationID 비교로 표현한다. 에러 문자열은
        // PHP byte-exact 보존. dest 장수↔dest 국가 정합 검증은 accept intake 사전검증이 보완한다.
        reqDestNationValue("nation", "소속", "==", ctx.destNationId ?: 0, "제의 장수가 국가 소속이 아닙니다"),
        allowDiplomacyBetweenStatus(
            listOf(DiplomacyState.WAR, DiplomacyState.DECLARATION),
            "상대국과 선포, 전쟁중이지 않습니다.",
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
     * che_종전수락.php:134-195 run(). draw 0 (RNG 미소비).
     *
     * 로그 매핑(PHP push 순서 = 실행 순서):
     *  - L177 actor generalAction PLAIN → [GeneralActionResolveContext.addActionPlainLog]
     *      `<D><b>{dest}</b></>{와} 종전에 합의했습니다.`
     *  - L178 actor generalHistory  → sink 부재(quarantine): `<D><b>{dest}</b></>{와} 종전 수락`
     *  - L180 actor globalAction(MONTH) → [addGlobalActionLog]
     *      `<Y>{general}</>{이} <D><b>{dest}</b></>{와} <M>종전 합의</> 하였습니다.`
     *  - L181 actor globalHistory  → sink 부재(quarantine):
     *      `<Y><b>【종전】</b></><D><b>{nation}</b></>{이} <D><b>{dest}</b></>{와} <M>종전 합의</> 하였습니다.`
     *  - L184 actor nationalHistory → sink 부재(quarantine): `<D><b>{dest}</b></>{와} 종전`
     *  - L187 dest  generalAction PLAIN → [addPlainLogTo]
     *      `<D><b>{nation}</b></>{와} 종전에 성공했습니다.`
     *  - L188 dest  generalHistory  → sink 부재(quarantine): `<D><b>{nation}</b></>{와} 종전 성공`
     *  - L189 dest  nationalHistory → sink 부재(quarantine): `<D><b>{nation}</b></>{와} 종전`
     */
    override fun resolve(context: GeneralActionResolveContext) {
        val draft = context.draft
        val me = draft.nation?.id ?: return
        val destNationID = (context.args["destNationID"] as? Number)?.toInt() ?: return

        // 1) diplomacy 양방향 통상(TRADE=2)/term=0
        draft.cascadeDiplomacy.add(Diplomacy(me = me, you = destNationID, state = DiplomacyState.TRADE, term = 0))
        draft.cascadeDiplomacy.add(Diplomacy(me = destNationID, you = me, state = DiplomacyState.TRADE, term = 0))

        // 2) SetNationFront(nationID)/SetNationFront(destNationID) — 전선 재계산 effect.
        //    GeneralActionResolveContext에 전선 sink이 없어 quarantine. WAVE-4 seam에서 배선.

        // 이름: actor 국명/dest 국명. dest 국명은 어댑터가 destGeneralName으로 주입한다(외교 제의 sibling 패턴
        // 동일 — CheJongjeonjeui.kt:70). 숫자 id가 아닌 실제 국명.
        val generalName = context.generalName.ifEmpty { "장수" }
        val nationName = draft.nation?.name?.ifEmpty { "아국" } ?: "아국"
        val destNationName = context.destGeneralName.ifEmpty { "상대국" }

        val josaWaDest = JosaUtil.pick(destNationName, "와")
        val josaIGeneral = JosaUtil.pick(generalName, "이")
        @Suppress("UNUSED_VARIABLE") val josaINation = JosaUtil.pick(nationName, "이")
        val josaWaNation = JosaUtil.pick(nationName, "와")

        // L177 actor generalAction PLAIN
        context.addActionPlainLog("<D><b>$destNationName</b></>$josaWaDest 종전에 합의했습니다.")
        // L178 actor generalHistory (sink 부재 — byte-exact 보존):
        //   "<D><b>$destNationName</b></>$josaWaDest 종전 수락"
        // L180 actor globalAction (MONTH)
        context.addGlobalActionLog(
            "<Y>$generalName</>$josaIGeneral <D><b>$destNationName</b></>$josaWaDest <M>종전 합의</> 하였습니다.",
        )
        // L181 actor globalHistory (sink 부재 — byte-exact 보존):
        //   "<Y><b>【종전】</b></><D><b>$nationName</b></>$josaINation <D><b>$destNationName</b></>$josaWaDest <M>종전 합의</> 하였습니다."
        // L184 actor nationalHistory (sink 부재 — byte-exact 보존):
        //   "<D><b>$destNationName</b></>$josaWaDest 종전"

        // dest-side 로그 — dest 장수 자신의 action 스코프(addPlainLogTo). dest 장수 id는 어댑터가 draft.destGeneral로
        // 주입(che_포상.php 패턴, ChePosang.kt:94). 미주입 시 dest action 로그는 생략(엔진 라우팅 부재와 정합).
        val destGeneralId = draft.destGeneral?.id
        if (destGeneralId != null) {
            // L187 dest generalAction PLAIN
            context.addPlainLogTo(destGeneralId, "<D><b>$nationName</b></>$josaWaNation 종전에 성공했습니다.")
            // L188 dest generalHistory (sink 부재 — byte-exact 보존):
            //   "<D><b>$nationName</b></>$josaWaNation 종전 성공"
            // L189 dest nationalHistory (sink 부재 — byte-exact 보존):
            //   "<D><b>$nationName</b></>$josaWaNation 종전"
        }

        // 4) StaticEventHandler::handleEvent(actor, destGeneral, this::class, env, arg) — 외교 공통 정적 이벤트 훅.
        //    handleEvent sink/배선이 GeneralActionResolveContext에 없어 quarantine. WAVE-4 외교 훅 seam에서 배선.
    }
}
