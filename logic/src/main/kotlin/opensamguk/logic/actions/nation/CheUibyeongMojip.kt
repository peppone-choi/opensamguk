package opensamguk.logic.actions.nation

import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.actions.personnel.CheInjaeTamsaek
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
import opensamguk.logic.world.GeneralBuilder

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
     * che_의병모집.php:run() 충실 포팅 — actor 로그 + exp/ded += 5*(preReqTurn+1)=15 + NPC 생성 + gennum.
     *
     * 생성 NPC 수 `createGenCnt` 와 avg(dex_t)/avg(dex5)/avg(exp)/avg(ded)는 PHP에서 DB 집계 쿼리
     *   (`SELECT 3+round(avg(gennum)/8)`, `SELECT avg(...) FROM general WHERE nation=me`) 결과 — 메모리
     *   드래프트엔 없는 월드 집계라 컨텍스트 args로 받는다. 라이브 경로는 ProcessNationCommand.stageWorldInputs가
     *   world에서 집계해 주입하고, 골든은 draw_stream에서 역산한 값을 fixture input으로 준다.
     *   미주입(createGenCnt 부재) 시 NPC 생성/draw 없음 — 라이브에서 이 분기를 타면 결함이다
     *   (UibyeongMojipLiveDispatchTest가 막는다).
     * 생성된 NPC는 [opensamguk.logic.actions.GeneralActionDraft.createdGenerals]로 실려 나가고 엔진이
     *   recorder.recordGeneralCreate로 영속화한다. PHP `game_env.npccount`는 의병장 일련번호 카운터일 뿐
     *   (run()에서 읽기만 하고 이름·id에 쓰지 않음) 코틀린 월드엔 대응 키가 없어 포팅하지 않는다.
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

        // 3. NPC 장수 생성 — createGenCnt 명. 월드 집계 입력은 args로 주입(라이브=stageWorldInputs, 골든=fixture).
        val createGenCnt = (context.args["createGenCnt"] as? Number)?.toInt() ?: return
        if (createGenCnt <= 0) return
        // avgGen(`SELECT avg(...) FROM general WHERE nation=me`). dex_t만 draw 인자('무' dex 후보 choice)이고
        // 나머지(dex5/exp/ded)는 생성 장수의 값만 정한다. PHP ?int 인자라 소수부는 버린다.
        val avgDexTotal = (context.args["avgDexTotal"] as? Number)?.toDouble() ?: 0.0
        val avgDex5 = (context.args["avgDex5"] as? Number)?.toInt() ?: 0
        val avgExp = (context.args["avgExp"] as? Number)?.toInt()
        val avgDed = (context.args["avgDed"] as? Number)?.toInt()
        // env.turnterm(getRandTurn의 nextRangeInt(0,60*term-1)). 골든 fixture는 args로 120(=7199)을 준다.
        val turnTerm = (context.args["turnterm"] as? Number)?.toInt() ?: 120
        val isFiction = ((context.args["fiction"] as? Number)?.toInt() ?: 0) != 0
        // checkDuplicatedCnt(AbsGeneralPool.php:79)의 DB 조회 대용 — 월드 전체 장수명.
        val existingNames = (context.args["existingGeneralNames"] as? List<*>)?.mapNotNull { it as? String }
            ?: emptyList()

        // pickGeneralFromPool: 전 NPC분 이름 picking을 일괄 선행(루프 밖) — PHP \sammo\pickGeneralFromPool.
        // PHP는 배치 안 중복을 보지 않지만(INSERT 전 일괄 pick) 같은 이름 둘은 버그라 앞서 뽑은 이름도 센다
        // (ⓖ 접두를 붙여 startsWith 매칭에 걸리게). 중복이 없으면 draw 수는 PHP와 같다.
        val pickedNames = ArrayList<String>(createGenCnt)
        repeat(createGenCnt) {
            pickedNames += CheInjaeTamsaek.pickRandomGeneralName(
                context.rng,
                existingNames + pickedNames.map { UIBYEONG_PREFIX + it },
            )
        }

        // build 루프(che_의병모집.php:139-155): setKillturn → fillRemainSpecAsRandom → build/getRandTurn.
        // draw 순서는 GeneralBuilder가 PHP와 draw-for-draw로 갖는다(GeneralBuilderGoldenTest).
        val year = context.env.year
        val pickTypeList = linkedMapOf("무" to 5.0, "지" to 5.0)
        for (npcName in pickedNames) {
            val built = GeneralBuilder(context.rng, npcName, d.general.nationId)
                .setCityID(d.general.cityId)
                .setSpecial("None", "None")
                .setLifeSpan(year - 20, year + 10)
                .setKillturn(context.rng.nextRangeInt(64, 70))
                .setNPCType(4)
                .setMoney(1000, 1000)
                .setSpecYear(19, 19)
                .setExpDed(avgExp, avgDed)
                .fillRemainSpecAsRandom(
                    pickTypeList, avgDexTotal, avgDex5, hasDexAvg = true,
                    year = year, startYear = context.env.startYear, isFiction = isFiction,
                )
                .build(year, context.month, turnTerm, emptyList(), isFictionMode = isFiction)
                ?: continue
            d.createdGenerals += built
        }

        // 4. nation.gennum += createGenCnt, strategic_cmd_limit = onCalcStrategic(name,'globalDelay',9)
        //    (che_의병모집.php:166-169). gennum은 typed/meta 양쪽에 실린다. 재사용 대기가 없으면 3턴마다
        //    의병장을 무한히 찍는다. 국가 성향(종횡가 등)이 globalDelay를 줄이므로 pipeline을 거친다.
        d.nation?.let { n ->
            // meta에 gennum 키가 없는 국가는 typed gennum이 0이다 — 라이브가 센 실제 장수 수를 기준으로 삼는다.
            val baseGennum = (context.args["nationGennum"] as? Number)?.toInt() ?: n.gennum
            val gennum = baseGennum + createGenCnt
            val nextLimit = phpRound(pipeline.onCalcStrategic(d.general, name, "globalDelay", 9.0))
            d.nation = n.copy(
                gennum = gennum,
                meta = LinkedHashMap(n.meta).apply {
                    this["gennum"] = gennum
                    this["strategic_cmd_limit"] = nextLimit
                },
            )
        }
    }

    private companion object {
        /** GeneralBuilder prefixList[4] — 의병장 이름 접두. */
        const val UIBYEONG_PREFIX = "ⓖ"
    }

}
