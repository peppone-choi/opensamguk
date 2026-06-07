package opensamguk.logic.actions.personnel

import opensamguk.common.constants.GameConst
import opensamguk.common.josa.JosaUtil
import opensamguk.logic.actions.GeneralActionDefinition
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.constraints.Constraint
import opensamguk.logic.constraints.ConstraintContext
import opensamguk.logic.constraints.allowJoinDestNation
import opensamguk.logic.constraints.beNeutral
import opensamguk.logic.constraints.differentDestNation
import opensamguk.logic.constraints.existsDestNation
import opensamguk.logic.constraints.reqDestNationValue
import opensamguk.logic.constraints.reqEnvValue
import opensamguk.logic.constraints.reqGeneralValue
import opensamguk.logic.domain.NpcType
import opensamguk.logic.domain.metaInt
import opensamguk.logic.domain.withMeta
import opensamguk.logic.domestic.addDedication
import opensamguk.logic.domestic.addExperience
import opensamguk.logic.stats.GeneralActionPipeline

/**
 * che_등용수락 — 등용(망명 권유) 서신 수락 (충실 포팅: `Command/General/che_등용수락.php` run()).
 *
 * che_등용(망명 권유 서신 발송)의 짝. 수신 장수가 등용장을 **수락**하면 즉시 실행되어, 수락 장수(actor)가
 * 제의국(destNation)으로 망명(이적)한다. **예약 불가능 커맨드**(PHP `che_등용수락.php:66` permissionConstraints =
 * AlwaysFail) → opensamguk에서는 [reservable] = false 로 표현된다(인터페이스 주석이 정본 예시로 명시).
 * 외교 수락 3종(불가침/종전/불가침파기)과 동일한 메시지-수락(accept-trigger) intake 경로로 흐른다.
 *
 * ## run() draw COUNT = 0
 * PHP run(RandUtil $rng)는 RNG를 단 한 번도 끌지 않는다(말미 unique 로터리 없음 — 망명 액션은 로터리 미발생,
 * che_하야와 동일). 따라서 docker 골든 불필요 — [CheDeungyongSurakGoldenTest]가 0-draw + after-state + 로그
 * byte를 검증한다.
 *
 * ## run() 부수효과 (che_등용수락.php:105-216 실행 순서 — 그대로 이식)
 *  1. destGeneral.addExperience(100) / addDedication(100)  ← 제의 장수(등용 성공) 보상(affectTrigger 기본 TRUE).
 *  2. setOriginalNationValues = { gennum: gennum-1 }        ← 떠나는 옛 국가.
 *     setScoutNationValues   = { gennum: gennum+1 }         ← 망명 대상국.
 *  3. **if nationID != 0** (재야 아님):
 *       - 기본 금액(defaultGold/Rice) 남기고 환수: gold>defaultGold면 옛 국가 gold += (gold-defaultGold),
 *         본인 gold = defaultGold. rice도 동형.
 *       - experience *= (1 - 0.1*betray); addExperience(0, affectTrigger=false) ← 레벨만 재계산.
 *       - dedication *= (1 - 0.1*betray); addDedication(0, false).
 *       - betray + 1, cap maxBetrayCnt(9).
 *     **else** (재야): addExperience(100) / addDedication(100) ← 본인 추가 보상.
 *  4. if getNPCType() < 2: killturn = env['killturn'].
 *  5. 로그 5건(아래 [resolve] 매핑 참조).
 *  6. 옛 국가 gennum -1 (nationID!=0일 때만) + gold/rice 환수, 대상국 gennum +1.
 *  7. increaseInheritancePoint(active_action, 1) — 계승(유산) seam(OQ7/P6), 여기서 write 없음(형제 일관).
 *  8. if getNPCType() < 2: setAuxVar(max_belong, max(belong, aux.max_belong ?? 0)) — 계승 seam, write 없음.
 *  9. setVar 이적: permission=normal, belong=1, officer_level=1, officer_city=0, nation=destNationID,
 *     city=destNation.capital, troop=0.
 * 10. if isTroopLeader(generalID == troop): 부대원 모두 troop=0 + 부대 삭제.
 *
 * ## 인프라 갭 (사실대로 — 미날조, 형제 che_하야/임관/종전수락과 동일 입장)
 *  - generalHistory/destGeneralHistory 스코프(2건): 현 [GeneralActionResolveContext] sink이 actor action /
 *    global action / dest action만 버퍼한다. history 버킷 sink 부재 → byte-exact 문자열을 아래 주석에 보존
 *    (WAVE-4 history seam 배선 시 그대로 옮긴다).
 *  - increaseInheritancePoint(active_action) / setAuxVar(max_belong): 계승 서브시스템 seam — 형제(임관/거병/하야)
 *    와 동일하게 여기서 state write 없음(OQ7/P6).
 *  - `killturn`/`belong`은 [opensamguk.logic.domain.General.meta]에 적재된다(V1 dedicated 컬럼 없음).
 *    env baseline `killturn`은 logic [opensamguk.logic.domain.WorldEnv]에 없는 월드/엔진 데이터라
 *    어댑터가 `context.args["__killturn"]`로 주입한다(che_모반시도 `ctx.args["killturn"]`와 동일 관용).
 */
class CheDeungyongSurak(private val pipeline: GeneralActionPipeline) : GeneralActionDefinition {

    override val key: String get() = "che_등용수락"
    override val name: String get() = "등용 수락"
    override val category: String get() = "인사"

    /** PHP `che_등용수락.php:17` $reqArg = true — destGeneralID + destNationID. */
    override val argsSchema: Map<String, Any?> get() = linkedMapOf(
        "destGeneralID" to "int",
        "destNationID" to "int",
    )

    /** PHP `che_등용수락.php:66` permissionConstraints = AlwaysFail('예약 불가능 커맨드') — 예약 불가능. */
    override val reservable: Boolean get() = false

    /**
     * PHP `che_등용수락.php:19-57` argTest — destGeneralID/destNationID는 양의 정수, 자기 자신/자국 아님.
     * (자기 장수 != destGeneralID, 자국 != destNationID 검증은 accept intake 사전검증과 함께 게이트를 이룬다.)
     */
    override fun parseArgs(raw: Map<String, Any?>): Map<String, Any?> {
        val destGeneralID = (raw["destGeneralID"] as? Number)?.toInt() ?: return emptyMap()
        if (destGeneralID <= 0) return emptyMap()
        val destNationID = (raw["destNationID"] as? Number)?.toInt() ?: return emptyMap()
        if (destNationID <= 0) return emptyMap()
        return linkedMapOf("destGeneralID" to destGeneralID, "destNationID" to destNationID)
    }

    /**
     * PHP `che_등용수락.php:78-86` fullConditionConstraints (initWithArg). hasFullConditionMet 강제
     * (run 진입 시 불만족이면 RuntimeException — opensamguk에서는 [opensamguk.engine.turn.ReservedTurnHandler]
     * FULL-mode constraint 평가가 담당).
     */
    override fun buildConstraints(ctx: ConstraintContext): List<Constraint> = listOf(
        // ReqEnvValue('join_mode', '!=', 'onlyRandom', '랜덤 임관만 가능합니다')
        reqEnvValue("join_mode", "!=", "onlyRandom", "랜덤 임관만 가능합니다"),
        existsDestNation(),
        beNeutral(),
        // AllowJoinDestNation($relYear) — relYear는 어댑터가 args["relYear"]로 주입(임관 패턴 동일).
        allowJoinDestNation { c, _ -> (c.args["actorNpcType"] as? Number)?.toInt() ?: 2 },
        // ReqDestNationValue('level', '국가규모', '>', 0, '방랑군에는 임관할 수 없습니다.')
        reqDestNationValue("level", "국가규모", ">", 0, "방랑군에는 임관할 수 없습니다."),
        differentDestNation(),
        // ReqGeneralValue('officer_level', '직위', '!=', 12, '군주는 등용장을 수락할 수 없습니다')
        reqGeneralValue("officer_level", "직위", "!=", 12, "군주는 등용장을 수락할 수 없습니다"),
    )

    override fun resolve(context: GeneralActionResolveContext) {
        val d = context.draft
        val destNation = d.destNation ?: error("che_등용수락 requires draft.destNation (망명 대상국)")
        val destNationID = destNation.id
        val destNationName = destNation.name

        var g = d.general
        val generalName = (g.meta["name"] as? String) ?: ""
        val generalId = g.id
        val originalNationID = g.nationId
        val isTroopLeader = (generalId == g.troop)

        // 1) destGeneral.addExperience(100)/addDedication(100) — 제의 장수(등용 성공) 보상(affectTrigger TRUE).
        //    제의 장수의 레벨업/레벨다운 PLAIN 로그는 dest 장수 자신의 스코프(addPlainLogTo)로 라우팅한다.
        val destGeneral = d.destGeneral
        if (destGeneral != null) {
            var dg = destGeneral
            val dgExp = addExperience(dg, 100.0, pipeline); dg = dgExp.general
            dgExp.plainLog?.let { context.addPlainLogTo(destGeneral.id, it) }
            val dgDed = addDedication(dg, 100.0, pipeline); dg = dgDed.general
            dgDed.plainLog?.let { context.addPlainLogTo(destGeneral.id, it) }
            d.destGeneral = dg
        }

        // 3) nationID != 0 (재야 아님): gold/rice 환수 + betray 감쇠 + betray+1. else: 본인 +exp100/+ded100.
        var lostGold = 0
        var lostRice = 0
        if (originalNationID != 0) {
            // 기본 금액(defaultGold/Rice) 남기고 옛 국가로 환수.
            if (g.gold > GameConst.defaultGold) {
                lostGold = g.gold - GameConst.defaultGold
                g = g.copy(gold = GameConst.defaultGold)
            }
            if (g.rice > GameConst.defaultRice) {
                lostRice = g.rice - GameConst.defaultRice
                g = g.copy(rice = GameConst.defaultRice)
            }

            // 재야가 아니면 명성/공헌을 betray*10% 감소; addExperience/addDedication(0, false)로 레벨만 재계산.
            // 레벨 변동 시 PLAIN 레벨업/레벨다운 로그(General::addExperience php:464-468)는 본인 PLAIN 스코프로 라우팅.
            val betray = metaInt(g.meta, "betray")
            val factor = 1 - 0.1 * betray
            g = g.copy(experience = g.experience * factor)
            val expRecalc = addExperience(g, 0.0, pipeline, affectTrigger = false); g = expRecalc.general
            expRecalc.plainLog?.let { context.addPlainLog(it) }
            g = g.copy(dedication = g.dedication * factor)
            val dedRecalc = addDedication(g, 0.0, pipeline, affectTrigger = false); g = dedRecalc.general
            dedRecalc.plainLog?.let { context.addPlainLog(it) }

            // betray + 1 cap maxBetrayCnt(9).
            val nextBetray = minOf(betray + 1, GameConst.maxBetrayCnt)
            g = g.copy(meta = withMeta(g.meta, "betray" to nextBetray))
        } else {
            // 재야: 본인 +exp100/+ded100 (affectTrigger TRUE). 레벨 변동 PLAIN은 본인 스코프(addPlainLog).
            val expRes = addExperience(g, 100.0, pipeline); g = expRes.general
            expRes.plainLog?.let { context.addPlainLog(it) }
            val dedRes = addDedication(g, 100.0, pipeline); g = dedRes.general
            dedRes.plainLog?.let { context.addPlainLog(it) }
        }

        // 4) getNPCType() < 2: killturn = env['killturn'] (어댑터가 args["__killturn"]로 주입; 부재 시 미설정).
        if (NpcType.getNpcType(g) < 2) {
            val killturnEnv = (context.args["__killturn"] as? Number)?.toInt()
            if (killturnEnv != null) {
                g = g.copy(meta = withMeta(g.meta, "killturn" to killturnEnv))
            }
        }

        // 5) 로그 5건 (che_등용수락.php:168-176, push 순서 = 실행 순서).
        val josaRo = JosaUtil.pick(destNationName, "로")
        val josaYi = JosaUtil.pick(generalName, "이")
        // L170 actor generalAction (MONTH): "<D>{dest}</>{로} 망명하여 수도로 이동합니다."
        context.addLog("<D>$destNationName</>$josaRo 망명하여 수도로 이동합니다.")
        // L171 dest generalAction (MONTH): "<Y>{general}</> 등용에 성공했습니다." — dest 장수 자신의 스코프.
        if (destGeneral != null) {
            context.addLogTo(destGeneral.id, "<C>●</>${context.month}월:<Y>$generalName</> 등용에 성공했습니다.")
        }
        // L173 actor generalHistory (sink 부재 — byte-exact 보존): "<D><b>{dest}</b></>{로} 망명"
        // L174 dest generalHistory  (sink 부재 — byte-exact 보존): "<Y>{general}</> 등용에 성공"
        // L176 actor globalAction (MONTH global): "<Y>{general}</>{이} <D><b>{dest}</b></>{로} <S>망명</>하였습니다."
        context.addGlobalActionLog("<Y>$generalName</>$josaYi <D><b>$destNationName</b></>$josaRo <S>망명</>하였습니다.")

        // 6) 옛 국가 gennum-1 + gold/rice 환수 (nationID!=0일 때만), 대상국 gennum+1.
        if (originalNationID != 0) {
            val oldNation = d.nation
            if (oldNation != null && oldNation.id == originalNationID) {
                d.nation = oldNation.copy(
                    gennum = oldNation.gennum - 1,
                    gold = oldNation.gold + lostGold,
                    rice = oldNation.rice + lostRice,
                    meta = withMeta(oldNation.meta, "gennum" to oldNation.gennum - 1),
                )
            }
        }
        d.destNation = destNation.copy(
            gennum = destNation.gennum + 1,
            meta = withMeta(destNation.meta, "gennum" to destNation.gennum + 1),
        )

        // 7) increaseInheritancePoint(active_action, 1) — 계승 seam(OQ7/P6); 여기서 write 없음.
        // 8) getNPCType() < 2: setAuxVar(max_belong, max(belong, aux.max_belong ?? 0)) — 계승 seam; write 없음.

        // 9) setVar 이적 — permission/belong/officer_level/officer_city/nation/city/troop.
        g = g.copy(
            nationId = destNationID,
            officerLevel = 1,
            cityId = destNation.capitalCityId ?: g.cityId,
            troop = 0,
            meta = withMeta(g.meta, "permission" to "normal", "belong" to 1, "officer_city" to 0),
        )

        // 10) isTroopLeader: 부대원 모두 탈퇴(troop=0) + 부대 삭제(cascade).
        if (isTroopLeader) {
            for (i in d.cascadeGenerals.indices) {
                if (d.cascadeGenerals[i].troop == generalId) {
                    d.cascadeGenerals[i] = d.cascadeGenerals[i].copy(troop = 0)
                }
            }
            // 부대 삭제(troop row delete)는 엔진 cascade seam에 위임 — d.cascadeGenerals 멤버 troop=0 적재가 정본.
        }

        d.general = g
    }
}
