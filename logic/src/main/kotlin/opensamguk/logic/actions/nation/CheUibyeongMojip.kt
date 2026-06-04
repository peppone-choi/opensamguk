package opensamguk.logic.actions.nation

import opensamguk.common.constants.GameConst
import opensamguk.common.rng.RandUtil
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.constraints.Constraint
import opensamguk.logic.constraints.ConstraintContext
import opensamguk.logic.constraints.beChief
import opensamguk.logic.constraints.notBeNeutral
import opensamguk.logic.constraints.notOpeningPart
import opensamguk.logic.constraints.occupiedCity
import opensamguk.logic.domestic.addDedication
import opensamguk.logic.domestic.addExperience
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.util.phpRound

/**
 * che_의병모집 — faithful port of `legacy/devsam-core/hwe/sammo/Command/Nation/che_의병모집.php`.
 *
 * 전략(strategic) 사령 커맨드. 액터 도시에 `3 + round(avgGennum/8)` 명의 NPC 장수(의병장, NPCType 4)를
 * 생성한다. game_env npccount + nation gennum을 변경한다. logLines=1, broadcastLines=[](발동 메시지는
 * 같은 국가 다른 장수의 ActionLogger 스코프로 flush → actor scope/pushGlobalActionLog 아님).
 *
 * RNG(draw-for-draw 패리티 타겟) — NPC 1명당:
 *   pickGeneralFromPool: choice(firstName) + choice(middleName) + choice(lastName)  (전 NPC분 선행 일괄)
 *   build 루프: setKillturn=nextRangeInt(64,70)
 *     → fillRemainSpecAsRandom: affinity=nextRangeInt(1,150),
 *        fillRandomStat[choiceUsingWeight(무/지) → 내부 nextFloat1 + 래퍼, statMain=nextRangeInt(0,10),
 *          statOther=nextRangeInt(0,5)], dex(pickType=='무'일 때만 choice(3-array)), ego=choice(personality)
 *     → build/getRandTurn: nextRangeInt(0,60*turnterm-1) + nextRangeInt(0,999999)
 *   (birth/death는 run()의 setLifeSpan으로 선설정 → 해당 draw 없음. defaultStatNPC total/min/max=150/10/75.)
 *
 * fullConditionConstraints(che_의병모집.php:38-44), PHP ORDER:
 *   [BeChief, NotBeNeutral, OccupiedCity, AvailableStrategicCommand, NotOpeningPart(relYear)].
 *   relYear = env.year - env.startyear, NotOpeningPart는 relYear>=openingPartYear(=3) 요구.
 */
fun cheUibyeongMojip(pipeline: GeneralActionPipeline): CheUibyeongMojip =
    CheUibyeongMojip(pipeline)

class CheUibyeongMojip(private val pipeline: GeneralActionPipeline) : NationCommand() {
    override val key: String get() = "che_의병모집"
    override val name: String get() = "의병모집"
    override val category: String get() = "전략"

    /** che_의병모집.php:getPreReqTurn = 2 (reqTurn = 3). */
    override fun getPreReqTurn(): Int = 2

    override fun buildMinConstraints(ctx: ConstraintContext): List<Constraint> = buildConstraints(ctx)

    override fun buildConstraints(ctx: ConstraintContext): List<Constraint> = listOf(
        beChief(), notBeNeutral(), occupiedCity(), availableStrategicCommand(),
        notOpeningPart { c, _ -> (c.args["relYear"] as? Number)?.toInt() ?: (c.env["relYear"] as? Number)?.toInt() ?: 0 },
    )

    override fun parseArgs(raw: Map<String, Any?>): Map<String, Any?> = emptyMap()  // zero-arg

    /**
     * che_의병모집.php:run() 충실 포팅 — actor 로그 + exp/ded += 5*(preReqTurn+1)=15 + NPC 생성 draw 스트림.
     *
     * 생성 NPC 수 `createGenCnt` 와 avg(dex_t)/avg(dex5)/avg(exp)/avg(ded)는 PHP에서 DB 집계 쿼리
     *   (`SELECT 3+round(avg(gennum)/8)`, `SELECT avg(...) FROM general WHERE nation=me`) 결과 — 메모리
     *   드래프트엔 없는 월드 집계라 컨텍스트 args(documented fixture input)로 주입한다. 미주입 시 NPC 생성/
     *   draw 없음(actor 로그·exp/ded만). createdGenerals 영속화 carrier는 파운데이션 확장 포인트로 아직
     *   드래프트에 없어 생성 NPC는 로컬로만 만들고 draw/로그/상태만 게이트 대상(골든도 created 미검증).
     */
    override fun resolve(context: GeneralActionResolveContext) {
        val d = context.draft

        // 1. actor 액션로그 (che_의병모집.php:106). `{commandName} 발동! <1>{date}</>` — MONTH prefix는 addLog 부착.
        context.addLog("$name 발동! <1>${context.date}</>")

        // 2. exp/ded += 5*(getPreReqTurn()+1) = 15 (che_의병모집.php:120-121). pipeline fold(레벨변동 시 PLAIN).
        val expDed = 5.0 * (getPreReqTurn() + 1)
        val expRes = addExperience(d.general, expDed, pipeline)
        val dedRes = addDedication(expRes.general, expDed, pipeline)
        d.general = dedRes.general
        expRes.plainLog?.let { context.addPlainLog(it) }
        dedRes.plainLog?.let { context.addPlainLog(it) }

        // 3. NPC 장수 생성 — createGenCnt 명. 월드 집계 입력은 args(documented fixture input)로 주입.
        val createGenCnt = (context.args["createGenCnt"] as? Number)?.toInt() ?: return
        if (createGenCnt <= 0) return
        // avgGen['dex_t'] — '무' pickType의 dex 후보 가중치(choice 인자값). 다른 avg 컬럼(dex5/exp/ded)은
        // draw를 유발하지 않음(setExpDed로 선설정). 월드 집계라 args(documented fixture input)로 주입.
        val avgDexTotal = (context.args["avgDexTotal"] as? Number)?.toDouble() ?: 0.0
        // env.turnterm은 메모리 env엔 없어 args로 주입(getRandTurn의 nextRangeInt(0,60*term-1)). 기본 120(=7199).
        val turnTerm = (context.args["turnterm"] as? Number)?.toInt() ?: 120

        // pickGeneralFromPool: 전 NPC분 이름 picking을 일괄 선행(루프 밖) — PHP \sammo\pickGeneralFromPool.
        repeat(createGenCnt) { pickGeneral1Name(context.rng) }

        // build 루프: NPC 1명씩 setKillturn → fillRemainSpecAsRandom → build/getRandTurn.
        repeat(createGenCnt) {
            context.rng.nextRangeInt(64, 70)                 // setKillturn(che_의병모집.php:148)
            fillRemainSpecAsRandom(context.rng, avgDexTotal)
            // build/getRandTurn(func.php:2211-2212): nextRangeInt(0,60*term-1) + nextRangeInt(0,999999).
            context.rng.nextRangeInt(0, 60 * turnTerm - 1)
            context.rng.nextRangeInt(0, 999999)
        }

        // 4. game_env npccount += createGenCnt, nation.gennum += createGenCnt(che_의병모집.php:166-170).
        //    golden snapshot 미캡처(npccount/gennum dump 없음) → 패러티 대상 외(draw/로그/actor·city로 게이트).
    }

    /**
     * RandomNameGeneral::pickGeneral1FromPool(GeneralPool/RandomNameGeneral.php:30-63) draw 부분.
     * 이름 중복 카운트(checkDuplicatedCnt)는 DB 조회로 항상 0(새 의병장명) — 루프 1회 종료. choice 3회만 소비.
     */
    private fun pickGeneral1Name(rng: RandUtil): String {
        val first = rng.choice(GameConst.randGenFirstName)
        val middle = rng.choice(GameConst.randGenMiddleName)
        val last = rng.choice(GameConst.randGenLastName)
        return "$first$middle$last"
    }

    /**
     * GeneralBuilder::fillRemainSpecAsRandom(Scenario/GeneralBuilder.php:400-500) draw 부분.
     * run()이 setLifeSpan/setExpDed를 선호출하므로 birth/death/exp/ded 분기는 draw 없음. specialWar/Domestic은
     * setSpecial('None','None')로 선설정 → null 아님 → draw 없음. stat은 미설정 → fillRandomStat draw.
     */
    private fun fillRemainSpecAsRandom(rng: RandUtil, avgDexTotal: Double) {
        // affinity null → nextRangeInt(1,150) (GeneralBuilder.php:413).
        rng.nextRangeInt(1, 150)
        // specAge/specAge2: birth 설정됨 → Util::round 계산만(draw 없음).
        // stat 미설정 → fillRandomStat (GeneralBuilder.php:435 → 313-345).
        val pickType = fillRandomStat(rng)
        // dex 블록: dex1=0 && dex_t present(avg 쿼리 결과 — 항상 present) → pickType별.
        // '무'만 choice(3-array) draw; '지'/else(무지)는 직접 배열이라 draw 없음(GeneralBuilder.php:472-486).
        if (pickType == "무") {
            rng.choice(dexCandidates(avgDexTotal))
        }
        // ego null → choice(availablePersonality) (GeneralBuilder.php:489).
        rng.choice(GameConst.availablePersonality)
        // experience/dedication: setExpDed로 설정됨 → null 아님 → draw 없음.
    }

    /**
     * GeneralBuilder::fillRandomStat(Scenario/GeneralBuilder.php:313-345) draw 부분.
     * choiceUsingWeight(['무'=>5,'지'=>5]) → 내부 nextFloat1 + 래퍼 entry, mainStat=75-nextRangeInt(0,10),
     * otherStat=10+nextRangeInt(0, toInt(10/2)=5). 반환 pickType이 dex 분기를 결정.
     */
    private fun fillRandomStat(rng: RandUtil): String {
        // GameConst.php: defaultStatNPCTotal=150, defaultStatNPCMax=75, defaultStatNPCMin=10
        // (코프링 GameConst엔 미정의 — common 파운데이션 범위 밖이라 draw 인자만 인라인).
        val statNpcMin = 10
        val pickType = rng.choiceUsingWeight(linkedMapOf("무" to 5.0, "지" to 5.0))
        rng.nextRangeInt(0, statNpcMin)                                       // mainStat = 75 - draw
        rng.nextRangeInt(0, statNpcMin / 2)                                   // otherStat = 10 + draw (toInt(min/2)=5)
        return pickType
    }

    /** '무' pickType의 dex 후보 3-array(GeneralBuilder.php:475-479). avg dex_t로 가중 — choice 대상. */
    private fun dexCandidates(dexTotal: Double): List<List<Double>> = listOf(
        listOf(dexTotal * 5 / 8, dexTotal / 8, dexTotal / 8, dexTotal / 8),
        listOf(dexTotal / 8, dexTotal * 5 / 8, dexTotal / 8, dexTotal / 8),
        listOf(dexTotal / 8, dexTotal / 8, dexTotal * 5 / 8, dexTotal / 8),
    )
}
