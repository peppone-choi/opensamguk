package opensamguk.logic.actions.founding

import opensamguk.common.constants.GameConst
import opensamguk.common.josa.JosaUtil
import opensamguk.logic.actions.GeneralActionDefinition
import opensamguk.logic.actions.GeneralActionDraft
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.constraints.Constraint
import opensamguk.logic.constraints.ConstraintContext
import opensamguk.logic.constraints.beLord
import opensamguk.logic.constraints.wanderingNation
import opensamguk.logic.domain.General
import opensamguk.logic.domain.NpcType
import opensamguk.logic.domain.metaInt
import opensamguk.logic.domain.withMeta
import opensamguk.logic.stats.GeneralActionPipeline

/**
 * che_해산 — faithful port of `legacy/devsam-core/hwe/sammo/Command/General/che_해산.php` run()
 * (che_해산.php:62-119) + the `deleteNation` cascade (func.php:1713-1805).
 *
 * 군주(BeLord)가 방랑군(WanderingNation)을 완전히 **해산**한다. 방랑(che_방랑)이 국가를 wandering 상태로
 * REVERT 하는 것과 달리, 해산은 국가를 **삭제**하고 전 장수를 재야(nation=0, officer_level=0,
 * permission=normal)로 돌려보낸다. 추첨/RNG 없음(draw 0).
 *
 * argTest (che_해산.php:30-34): `arg = []` → 인자 없음.
 *
 * fullConditionConstraints (che_해산.php:44-47), PHP ORDER(first-deny-wins): [BeLord, WanderingNation].
 *
 * getCost [0,0], getPreReqTurn/getPostReqTurn 0.
 *
 * --- run() 순서(load-bearing) ---
 *  1. <init-turn 가드 (che_해산.php:74-80): `joinYearMonth(year,month) <= joinYearMonth(init_year,init_month)`
 *     이면 "다음 턴부터 해산할 수 있습니다." 로그 + alternative=che_인재탐색 + early-return(쓰기 없음).
 *     init_year/init_month는 logic WorldEnv에 없는 엔진 데이터 → 어댑터가 `sameMonthOrBefore` arg로 선적재
 *     (CheGeonguk/CheMujakwiGeonguk 동일 idiom). `lastAlternative`로 노출.
 *  2. 국가 전 장수 자원 절삭 (che_해산.php:90-98):
 *       (a) `UPDATE general SET gold=defaultGold WHERE nation=me AND gold>defaultGold` — gold>1000 절삭.
 *       (b) `UPDATE general SET rice=defaultRice WHERE nation=me AND gold>defaultRice` — **레거시 버그**:
 *           rice 절삭 조건이 `rice>` 가 아니라 `gold>defaultRice` 다(php:93-95 복붙 버그). (a) 직후 모든
 *           gold가 ≤1000(=defaultRice) 으로 절삭됐으므로 이 UPDATE는 **0행 매치 → 멤버 rice는 손대지 않음**.
 *           byte-동일 재현을 위해 멤버 rice는 절대 절삭하지 않는다.
 *       (c) 군주 본인은 별도로 `increaseVarWithLimit('gold',0,0,defaultGold)` +
 *           `increaseVarWithLimit('rice',0,0,defaultRice)` — 군주 gold/rice 둘 다 [0,1000] 무조건 절삭(php:97-98).
 *  3. refreshNationStaticInfo() — 정적 캐시 갱신(엔진 seam, 델타 없음).
 *  4. 로그 3종 (che_해산.php:103-105):
 *       general action: "세력을 해산했습니다. <1>{date}</>"
 *       global action : "<Y>{generalName}</>{josaYi} 세력을 해산했습니다."
 *       general history: "<D><b>{nationName}</b></>{josaUl} 해산"  ← HISTORY 버킷(GATE-RUNTIME seam, 미assert)
 *  5. deleteNation cascade (func.php:1713-1805, applyDB=false):
 *       - DeleteConflict(nationID) — 분쟁 기록 purge(글로벌 히스토리 seam).
 *       - global HISTORY: "<R><b>【멸망】</b></><D><b>{nationName}</b></>{josaUn} <R>멸망</>했습니다." (seam).
 *       - 전 장수(멤버 ascending PK + 군주 LAST, func.php:1735/1740): npcType<2 면 aux.max_belong=max(belong,
 *         aux.max_belong); belong=0, troop=0, officer_level=0, officer_city=0, nation=0, permission='normal';
 *         PLAIN action 로그 "<D><b>{nationName}</b></>{josaYi} <R>멸망</>했습니다." + general history
 *         "<D><b>{nationName}</b></>{josaYi} <R>멸망</>" (history seam).
 *       - 도시 nation=0, front=0 (func.php:1781-1784) — cascadeCities.
 *       - troop / nation / nation_turn / diplomacy(me OR you) 삭제 — 엔진 삭제 seam(아래 deletedNationId).
 *       - ng_old_nations 보존(insert) — 엔진 seam.
 *       - refreshNationStaticInfo + nationStor.resetValues — 엔진 seam.
 *  6. 군주 makelimit=12 (che_해산.php:109). (deleteNation의 nation=0 등은 군주에게도 적용; 5에서 함께 처리.)
 *  7. StaticEventHandler + TurnExecutionHelper::runEventHandler(OccupyCity) (che_해산.php:113/117) —
 *     **엔진 seam**(OccupyCity 이벤트 핸들러는 데몬 위임; resolve는 호출하지 않는다).
 *
 * draft 모델링:
 *   - actor(군주) = [GeneralActionDraft.general]; 나머지 멤버 = [GeneralActionDraft.cascadeGenerals]
 *     (어댑터가 ascending PK 로 선적재; 군주는 cascade에 포함하지 않고 자기 step 으로 마지막에 처리).
 *   - 도시 = [GeneralActionDraft.cascadeCities] (nation=me 도시들; 어댑터 선적재).
 *   - 국가/nation_turn/troop/diplomacy 삭제는 logic draft에 nation-삭제 셋이 없으므로 [lastDeletedNationId]
 *     로 노출 → 엔진(ChangeRecorder/JdbcFlushExecutor)이 tombstone + ng_old_nations 보존을 수행(seam).
 *     이는 ConquerCity 의 `deletedNationId` 와 동일한 nation-teardown idiom 이다.
 *   - 히스토리 로그(general history / 【멸망】 global history) + DeleteConflict + OccupyCity 이벤트는 모두
 *     GATE-RUNTIME seam(resolve 컨텍스트에 history 버킷이 없음) — 액션/글로벌 ACTION 로그만 byte-assert.
 */
class CheHaesan(@Suppress("UNUSED_PARAMETER") private val pipeline: GeneralActionPipeline) : GeneralActionDefinition {
    override val key: String get() = "che_해산"
    override val name: String get() = "해산"
    override val category: String get() = "국가"

    /** che_해산.php:74-80 — <init-turn 가드 시 swap-in 되는 대체 명령. */
    var lastAlternative: String? = null
        private set

    /** deleteNation 으로 tombstone 될 국가 id(엔진 삭제 seam). early-return/미실행 시 null. */
    var lastDeletedNationId: Int? = null
        private set

    override fun buildConstraints(ctx: ConstraintContext): List<Constraint> = listOf(
        beLord(),
        wanderingNation(),
    )

    /** che_해산.php:30-34 argTest — 인자 없음. */
    override fun parseArgs(raw: Map<String, Any?>): Map<String, Any?> = emptyMap()

    override fun resolve(context: GeneralActionResolveContext) {
        lastAlternative = null
        lastDeletedNationId = null

        val d = context.draft
        val nation = d.nation ?: return

        // 1. <init-turn 가드 (che_해산.php:74-80) — joinYearMonth(year,month) <= joinYearMonth(init_year,init_month).
        //    엔진/env 산술(init_year/init_month는 logic WorldEnv에 없음) → `sameMonthOrBefore` arg 로 선적재.
        if (context.args["sameMonthOrBefore"] == true) {
            context.addLog("다음 턴부터 해산할 수 있습니다. <1>${context.date}</>")
            lastAlternative = ALTERNATIVE_BLOCKED
            return
        }

        val generalName = (d.general.meta["name"] as? String) ?: context.generalName
        val josaYi = JosaUtil.pick(generalName, "이")
        val nationName = nation.name
        @Suppress("UNUSED_VARIABLE")
        val josaUl = JosaUtil.pick(nationName, "을")   // general history "…{josaUl} 해산" — history seam(미assert)

        // 2. 국가 전 장수 자원 절삭 (che_해산.php:90-98).
        //    (a) gold>defaultGold → defaultGold (멤버 + 군주 모두 DB 대상; 군주는 (c)에서 다시 절삭).
        for (i in d.cascadeGenerals.indices) {
            val m = d.cascadeGenerals[i]
            if (m.gold > GameConst.defaultGold) {
                d.cascadeGenerals[i] = m.copy(gold = GameConst.defaultGold)
            }
            // (b) 레거시 버그: rice 절삭 조건이 `gold>defaultRice`. (a) 직후 gold≤1000(=defaultRice)이라
            //     0행 매치 → 멤버 rice 는 절대 손대지 않는다(byte-동일).
        }
        // (c) 군주 본인 gold/rice 무조건 [0,defaultGold]/[0,defaultRice] 절삭 (increaseVarWithLimit, php:97-98).
        d.general = d.general.copy(
            gold = d.general.gold.coerceIn(0, GameConst.defaultGold),
            rice = d.general.rice.coerceIn(0, GameConst.defaultRice),
        )

        // 3. refreshNationStaticInfo() — 엔진 정적 캐시 seam(델타 없음).

        // 4. 로그 3종 (che_해산.php:103-105). general/global ACTION 만 byte-assert; general history 는 seam.
        context.addLog("세력을 해산했습니다. <1>${context.date}</>")
        context.addGlobalActionLog("<Y>$generalName</>$josaYi 세력을 해산했습니다.")
        // general history: "<D><b>$nationName</b></>$josaUl 해산" — HISTORY 버킷(GATE-RUNTIME seam).

        // 5. deleteNation cascade (func.php:1713-1805, applyDB=false).
        //    멸망 로그(PLAIN action) — 모든 장수에게 동일 문구.
        val destroyLog = "<D><b>$nationName</b></>$josaYi <R>멸망</>했습니다."
        // global HISTORY 【멸망】 + general history "…멸망" — 모두 GATE-RUNTIME history seam(미assert).

        // 전 장수 재야로 (func.php:1753-1778). 순서: 멤버 ascending PK(어댑터 선적재) + 군주 LAST.
        for (i in d.cascadeGenerals.indices) {
            d.cascadeGenerals[i] = neutralizeMember(d.cascadeGenerals[i])
        }
        // 군주 본인(LAST) — 재야화 + makelimit=12 (che_해산.php:109).
        val neutralLord = neutralizeMember(d.general)
        d.general = neutralLord.copy(meta = withMeta(neutralLord.meta, "makelimit" to GameConst.maxChiefTurn))
        // 멤버 PLAIN action 멸망 로그는 dest-general 스코프(각 장수 자기 로거)로 적재.
        for (m in d.cascadeGenerals) {
            context.addPlainLogTo(m.id, destroyLog)
        }
        // 군주 본인 PLAIN action 멸망 로그(자기 액션 스트림).
        context.addActionPlainLog(destroyLog)

        // 도시 공백지로 (func.php:1781-1784): nation=0, front=0.
        for (i in d.cascadeCities.indices) {
            val c = d.cascadeCities[i]
            d.cascadeCities[i] = c.copy(nationId = 0, frontState = 0)
        }

        // troop/nation/nation_turn/diplomacy 삭제 + ng_old_nations 보존 — 엔진 tombstone seam.
        lastDeletedNationId = nation.id

        // 7. StaticEventHandler + OccupyCity 이벤트 핸들러(che_해산.php:113/117) — 엔진 seam(여기선 미호출).
    }

    /**
     * deleteNation 의 per-general 재야화 (func.php:1753-1778):
     *   npcType<2 → aux.max_belong = max(belong, aux.max_belong);
     *   belong=0, troop=0, officer_level=0, officer_city=0, nation=0, permission='normal'.
     */
    private fun neutralizeMember(g: General): General {
        var meta = g.meta
        if (NpcType.getNpcType(g) < 2) {
            @Suppress("UNCHECKED_CAST")
            val aux = (meta["aux"] as? Map<String, Any?>) ?: emptyMap()
            val prevMaxBelong = (aux["max_belong"] as? Number)?.toInt() ?: 0
            val belong = metaInt(meta, "belong")
            val nextAux = LinkedHashMap(aux)
            nextAux["max_belong"] = maxOf(belong, prevMaxBelong)
            meta = withMeta(meta, "aux" to nextAux)
        }
        meta = withMeta(meta, "belong" to 0, "officer_city" to 0, "permission" to "normal")
        return g.copy(
            troop = 0,
            officerLevel = 0,
            officerCity = 0,
            nationId = 0,
            meta = meta,
        )
    }

    companion object {
        /** <init-turn 가드(che_해산.php:78)에서 swap-in 되는 대체 명령. */
        const val ALTERNATIVE_BLOCKED: String = "che_인재탐색"
    }
}
