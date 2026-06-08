package opensamguk.logic.actions

import opensamguk.logic.actions.develop.cheDanryeon
import opensamguk.logic.actions.develop.cheGisulYeongu
import opensamguk.logic.actions.develop.cheGyeonmun
import opensamguk.logic.actions.develop.cheJeongchakJangnyeo
import opensamguk.logic.actions.develop.cheJuminSeonjeong
import opensamguk.logic.actions.develop.cheGunryangMaemae
import opensamguk.logic.actions.develop.cheMuljaJodal
import opensamguk.logic.actions.founding.CheGeobyeong
import opensamguk.logic.actions.founding.CheGeonguk
import opensamguk.logic.actions.founding.CheHaesan
import opensamguk.logic.actions.founding.CheMujakwiGeonguk
import opensamguk.logic.actions.founding.CheSeonyang
import opensamguk.logic.actions.founding.CrGeonguk
import opensamguk.logic.actions.military.CheGanghaeng
import opensamguk.logic.actions.military.CheGwihwan
import opensamguk.logic.actions.military.CheHullyeon
import opensamguk.logic.actions.military.CheIdong
import opensamguk.logic.actions.military.CheJeontuTaese
import opensamguk.logic.actions.military.CheJiphap
import opensamguk.logic.actions.military.CheNpcNeungdong
import opensamguk.logic.actions.military.CheCheobo
import opensamguk.logic.actions.military.CheJeopgyeongGwihwan
import opensamguk.logic.actions.military.cheHwagye
import opensamguk.logic.actions.military.chePagoe
import opensamguk.logic.actions.military.cheSeondong
import opensamguk.logic.actions.military.CheSagiJinjak
import opensamguk.logic.actions.military.CheSojipHaeje
import opensamguk.logic.actions.military.CheSukryeonJeonhwan
import opensamguk.logic.actions.military.CheTalchwi
import opensamguk.logic.actions.military.CrMaenghullyeon
import opensamguk.logic.actions.military.RecruitAlgorithm
import opensamguk.logic.actions.nation.cheBaekseongDongwon
import opensamguk.logic.actions.nation.cheBallyeong
import opensamguk.logic.actions.nation.cheBudaeTaltoejisi
import opensamguk.logic.actions.nation.cheBulgachimJeui
import opensamguk.logic.actions.nation.cheBulgachimPagijeui
import opensamguk.logic.actions.nation.cheBulgachimPagiSuak
import opensamguk.logic.actions.nation.cheBulgachimSuak
import opensamguk.logic.actions.nation.cheCheondo
import opensamguk.logic.actions.nation.cheChotohwa
import opensamguk.logic.actions.nation.cheMobanSido
import opensamguk.logic.actions.nation.crInguIdong
import opensamguk.logic.actions.nation.eventDaegeombyeongYeongu
import opensamguk.logic.actions.nation.eventEumgwibyeongYeongu
import opensamguk.logic.actions.nation.eventGeukbyeongYeongu
import opensamguk.logic.actions.nation.eventHwaryunchaYeongu
import opensamguk.logic.actions.nation.eventHwasibyeongYeongu
import opensamguk.logic.actions.nation.eventMuhuiYeongu
import opensamguk.logic.actions.nation.eventSanjeobyeongYeongu
import opensamguk.logic.actions.nation.eventSangbyeongYeongu
import opensamguk.logic.actions.nation.eventWonyungnobyeongYeongu
import opensamguk.logic.actions.nation.cheGamchuk
import opensamguk.logic.actions.nation.cheGeupseup
import opensamguk.logic.actions.nation.cheGukgiByeongyeong
import opensamguk.logic.actions.nation.cheGukhoByeongyeong
import opensamguk.logic.actions.nation.cheHeobo
import opensamguk.logic.actions.nation.cheIhoGyeongsik
import opensamguk.logic.actions.nation.cheJeungchuk
import opensamguk.logic.actions.nation.cheJongjeonSuak
import opensamguk.logic.actions.nation.cheJongjeonjeui
import opensamguk.logic.actions.nation.cheMolsu
import opensamguk.logic.actions.nation.cheMujakwiSudoIjeon
import opensamguk.logic.actions.nation.cheMuljaWonjo
import opensamguk.logic.actions.nation.chePijangPajang
import opensamguk.logic.actions.nation.chePilsaJeukSaeng
import opensamguk.logic.actions.nation.chePosang
import opensamguk.logic.actions.nation.cheSeonjeonpogo
import opensamguk.logic.actions.nation.cheSumol
import opensamguk.logic.actions.nation.cheUibyeongMojip
import opensamguk.logic.actions.personnel.CheBangrang
import opensamguk.logic.actions.personnel.CheDeungyong
import opensamguk.logic.actions.personnel.CheDeungyongSurak
import opensamguk.logic.actions.personnel.CheEuntwe
import opensamguk.logic.actions.personnel.CheHaya
import opensamguk.logic.actions.personnel.CheImgwan
import opensamguk.logic.actions.personnel.CheInjaeTamsaek
import opensamguk.logic.actions.personnel.CheJangsuDaesangImgwan
import opensamguk.logic.actions.personnel.CheJeontuTeukgiChogihwa
import opensamguk.logic.actions.personnel.CheNaejeongTeukgiChogihwa
import opensamguk.logic.actions.personnel.CheRandomImgwan
import opensamguk.logic.actions.personnel.CheYoyang
import opensamguk.logic.actions.trade.CheHeonnap
import opensamguk.logic.actions.trade.CheJangbiMaemae
import opensamguk.logic.actions.trade.CheJeungyeo
import opensamguk.logic.actions.war.CheChulbyeong
import opensamguk.logic.constraints.*
import opensamguk.logic.stats.GeneralActionPipeline

/**
 * The 휴식 fallback: a no-op rest turn. No constraints, no mutation, no log beyond the rest log
 * (which is empty in P1 — a rest turn produces no mutation/log). Used by the handler whenever the
 * reserved action-code is unknown or denied.
 */
object RestAction : GeneralActionDefinition {
    override val key = "휴식"
    override val name = "휴식"
    override fun buildConstraints(ctx: ConstraintContext): List<Constraint> = emptyList()
    override fun resolve(context: GeneralActionResolveContext) { /* no-op: a rest turn produces no mutation/log in P1 */ }
}

/**
 * action-code → definition. Unknown / deny → the 휴식 fallback. The handler uses this to resolve the
 * reserved action-code and the fallback.
 */
class CommandRegistry(private val pipeline: GeneralActionPipeline, private val maxLevel: Int = 255) {
    fun resolve(actionCode: String): GeneralActionDefinition = when (actionCode) {
        "che_상업투자" -> cheSangeobTuja(pipeline, maxLevel)
        "che_농지개간" -> cheNongjigaegan(pipeline, maxLevel)
        "che_성벽보수" -> cheSeongbyeokBosu(pipeline, maxLevel)
        "che_수비강화" -> cheSubiGanghwa(pipeline, maxLevel)
        "che_치안강화" -> cheChianGanghwa(pipeline, maxLevel)
        "che_기술연구" -> cheGisulYeongu(pipeline, maxLevel)
        "che_정착장려" -> cheJeongchakJangnyeo(pipeline, maxLevel)
        "che_주민선정" -> cheJuminSeonjeong(pipeline, maxLevel)
        "che_물자조달" -> cheMuljaJodal(pipeline, maxLevel)
        "che_군량매매" -> cheGunryangMaemae(pipeline, maxLevel)
        "che_징병" -> RecruitAlgorithm.cheJingbyeong(pipeline, maxLevel)
        "che_모병" -> RecruitAlgorithm.cheMobyeong(pipeline, maxLevel)
        "che_훈련" -> CheHullyeon(pipeline, maxLevel)
        "cr_맹훈련" -> CrMaenghullyeon(pipeline, maxLevel)
        "che_사기진작" -> CheSagiJinjak(pipeline, maxLevel)
        "che_소집해제" -> CheSojipHaeje(pipeline)
        "che_이동" -> CheIdong(pipeline)
        "che_집합" -> CheJiphap(pipeline)
        "che_임관" -> CheImgwan(pipeline)
        "che_장수대상임관" -> CheJangsuDaesangImgwan(pipeline)
        "che_하야" -> CheHaya(pipeline)
        "che_방랑" -> CheBangrang(pipeline)
        "che_랜덤임관" -> CheRandomImgwan(pipeline)
        "che_은퇴" -> CheEuntwe(pipeline)
        "che_등용" -> CheDeungyong(pipeline)
        // --- CMD-FOUNDING (건국/거병) ---
        "che_거병" -> CheGeobyeong(pipeline)
        "che_건국" -> CheGeonguk(pipeline)
        "cr_건국" -> CrGeonguk(pipeline)
        "che_무작위건국" -> CheMujakwiGeonguk(pipeline)
        // --- CMD-NATION-INTERNAL (국가 내정) ---
        "che_감축" -> cheGamchuk(pipeline)
        "che_증축" -> cheJeungchuk(pipeline)
        "che_발령" -> cheBallyeong(pipeline)
        "che_포상" -> chePosang(pipeline)
        "che_국호변경" -> cheGukhoByeongyeong(pipeline)
        "che_국기변경" -> cheGukgiByeongyeong(pipeline)
        "che_천도" -> cheCheondo(pipeline)
        "che_무작위수도이전" -> cheMujakwiSudoIjeon(pipeline)
        // --- CMD-NATION-C3 (전략/인사/외교 사령 12종) — F4 C3 chief-command 포팅 ---
        "che_급습" -> cheGeupseup(pipeline)
        "che_몰수" -> cheMolsu(pipeline)
        "che_물자원조" -> cheMuljaWonjo(pipeline)
        "che_백성동원" -> cheBaekseongDongwon(pipeline)
        "che_부대탈퇴지시" -> cheBudaeTaltoejisi(pipeline)
        "che_수몰" -> cheSumol(pipeline)
        "che_의병모집" -> cheUibyeongMojip(pipeline)
        "che_이호경식" -> cheIhoGyeongsik(pipeline)
        "che_초토화" -> cheChotohwa(pipeline)
        "che_피장파장" -> chePijangPajang(pipeline)
        "che_필사즉생" -> chePilsaJeukSaeng(pipeline)
        "che_허보" -> cheHeobo(pipeline)
        // --- CMD-NATION-EVENT-연구 (병종 연구 9종) — chief-reserved 국가 연구, run() deterministic(draw=0) ---
        "event_극병연구" -> eventGeukbyeongYeongu(pipeline)
        "event_무희연구" -> eventMuhuiYeongu(pipeline)
        "event_상병연구" -> eventSangbyeongYeongu(pipeline)
        "event_화륜차연구" -> eventHwaryunchaYeongu(pipeline)
        "event_원융노병연구" -> eventWonyungnobyeongYeongu(pipeline)
        "event_대검병연구" -> eventDaegeombyeongYeongu(pipeline)
        "event_화시병연구" -> eventHwasibyeongYeongu(pipeline)
        "event_음귀병연구" -> eventEumgwibyeongYeongu(pipeline)
        "event_산저병연구" -> eventSanjeobyeongYeongu(pipeline)
        // --- CMD-WAR (출병) — the SOLE caller of the OUTER processWar() wrapper (BO3). 급습/선전포고 = P6. ---
        "che_출병" -> CheChulbyeong(pipeline)
        // --- CMD-TRADE (증여/헌납/장비매매) ---
        "che_증여" -> CheJeungyeo(pipeline)
        "che_헌납" -> CheHeonnap(pipeline)
        "che_장비매매" -> CheJangbiMaemae(pipeline)
        // --- F-BRIDGE (FR2): the 9 AI-emitted defs MISSING from the registry (R-BRIDGE §2). The AI
        // emits these; the bridge gate (FR3) evaluates each def's FULL pack + argTest. 불가침제의/선전포고
        // resolve()-internals are P6 (m10); 인재탐색/견문/해산 carry downstream-subsystem resolve seams.
        "che_불가침제의" -> cheBulgachimJeui(pipeline)
        "che_종전제의" -> cheJongjeonjeui(pipeline)
        "che_불가침파기제의" -> cheBulgachimPagijeui(pipeline)
        "che_선전포고" -> cheSeonjeonpogo(pipeline)
        // --- CMD-DIPLOMACY-ACCEPT (외교 수락) — triggered by DiplomaticMessage.accept() ---
        "che_불가침수락" -> cheBulgachimSuak(pipeline)
        "che_종전수락" -> cheJongjeonSuak(pipeline)
        "che_불가침파기수락" -> cheBulgachimPagiSuak(pipeline)
        "che_NPC능동" -> CheNpcNeungdong(pipeline)
        "che_귀환" -> CheGwihwan(pipeline)
        "che_인재탐색" -> CheInjaeTamsaek(pipeline)
        "che_견문" -> cheGyeonmun(pipeline)
        "che_해산" -> CheHaesan(pipeline)
        "che_요양" -> CheYoyang(pipeline)        // dispatcher-direct, gate-exempt (G14) — def for execution
        "che_선양" -> CheSeonyang(pipeline)        // ORDER BY RAND quarantine (G4, decision #6)
        // --- CMD-GROUP-A stubs (17 missing PHP commands — widened ONCE, disjoint fill per command) ---
        "che_화계" -> cheHwagye(pipeline, maxLevel)
        "che_파괴" -> chePagoe(pipeline, maxLevel)
        "che_탈취" -> CheTalchwi(pipeline, maxLevel)
        "che_선동" -> cheSeondong(pipeline, maxLevel)
        "che_첩보" -> CheCheobo(pipeline)
        "che_단련" -> cheDanryeon(pipeline, maxLevel)
        "che_접경귀환" -> CheJeopgyeongGwihwan()
        "che_강행" -> CheGanghaeng(pipeline)
        "che_숙련전환" -> CheSukryeonJeonhwan(pipeline)
        "che_전투태세" -> CheJeontuTaese(pipeline, maxLevel)
        "che_모반시도" -> cheMobanSido(pipeline)
        "che_전투특기초기화" -> CheJeontuTeukgiChogihwa(pipeline)
        "che_내정특기초기화" -> CheNaejeongTeukgiChogihwa(pipeline)
        "che_등용수락" -> CheDeungyongSurak(pipeline)
        "cr_인구이동" -> crInguIdong(pipeline)
        else -> RestAction
    }
    val fallback: GeneralActionDefinition get() = RestAction
}
