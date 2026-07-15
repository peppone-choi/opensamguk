package opensamguk.logic.actions.military

import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.common.rng.serializeSeed
import opensamguk.common.constants.GameConst
import opensamguk.logic.actions.GeneralActionDraft
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.constraints.ConstraintContext
import opensamguk.logic.constraints.ConstraintMode
import opensamguk.logic.constraints.ConstraintResult
import opensamguk.logic.constraints.evaluateConstraints
import opensamguk.logic.domain.City
import opensamguk.logic.domain.General
import opensamguk.logic.domain.Nation
import opensamguk.logic.domain.WorldEnv
import opensamguk.logic.domain.metaDouble
import opensamguk.logic.event.StaticEventHandler
import opensamguk.logic.statview.MemoryStateView
import opensamguk.logic.stats.GeneralActionPipeline
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Port-faithful resolve test for the shared RecruitAlgorithm (PHP che_징병.php run() + che_모병.php).
 * Pins: maxCrew cap; cost on the APPLIED (post-cap) crew with costOffset BEFORE round; RAW-float
 * train/atmos blend (no round); trust loss / costOffset (모병 halves the 징병 loss); 모병 vs 징병
 * deltas; and ZERO turn-RNG draws (the score path never touches the rng — only the trailing lottery).
 */
class RecruitAlgorithmTest {

    private val pipeline = GeneralActionPipeline()
    private val MONTH = 3
    private val date = "12:34"

    private fun general(
        leadership: Int = 70, crew: Int = 0, crewTypeId: Int = 1100, cityId: Int = 7,
        train: Double = 0.0, atmos: Double = 0.0, gold: Int = 1_000_000, rice: Int = 1_000_000,
    ) = General(
        id = 42, nationId = 1, cityId = cityId,
        leadership = leadership, strength = 70, intel = 70, injury = 0,
        experience = 0.0, dedication = 0.0, officerLevel = 1,
        gold = gold, rice = rice, crew = crew, train = train, atmos = atmos, crewTypeId = crewTypeId,
        meta = linkedMapOf("leadership_exp" to 0.0),
    )

    private fun city(id: Int = 7, pop: Int = 100_000, trust: Double = 50.0) = City(
        id = id, nationId = 1, level = 5,
        commerce = 1000, commerceMax = 20000, agriculture = 1000, agricultureMax = 20000,
        supplyState = 1, frontState = 0, trust = trust, population = pop, populationMax = 200000,
    )

    private val nation = Nation(id = 1, level = 7, capitalCityId = 99, tech = 0.0)
    private val env = WorldEnv(year = 190, startYear = 184, develCost = 120)

    private fun rng(seed: String = "00000000000000000000000000000000", cls: String = "che_징병") =
        RandUtil(LiteHashDrbg(serializeSeed(seed, "generalCommand", 190, MONTH, 42, cls)))

    private fun ctx(draft: GeneralActionDraft, args: Map<String, Any?>, rng: RandUtil = rng()) =
        GeneralActionResolveContext(draft, rng, env, MONTH, date, args = args)

    private fun constraintCtx(
        args: Map<String, Any?>,
        mode: ConstraintMode = ConstraintMode.FULL,
        cityId: Int = 7,
        env: Map<String, Any?> = emptyMap(),
    ) = ConstraintContext(actorId = 42, cityId = cityId, nationId = 1, args = args, env = env, mode = mode)

    private fun view(
        g: General = general(),
        c: City = city(id = g.cityId),
        n: Nation = nation,
    ) = MemoryStateView(
        generals = linkedMapOf(g.id to g),
        cities = linkedMapOf(c.id to c),
        nations = linkedMapOf(n.id to n),
        env = emptyMap(),
    )

    private fun jingbyeong() = RecruitAlgorithm.cheJingbyeong(pipeline)
    private fun mobyeong() = RecruitAlgorithm.cheMobyeong(pipeline)

    @AfterTest
    fun clearStaticHandlers() = StaticEventHandler.clear()

    @Test
    fun `key name category for 징병 and 모병`() {
        assertEquals("che_징병", jingbyeong().key); assertEquals("징병", jingbyeong().name)
        assertEquals("che_모병", mobyeong().key); assertEquals("모병", mobyeong().name)
        assertEquals("군사", jingbyeong().category)
    }

    @Test
    fun `parseArgs accepts PHP numeric amount strings and rejects invalid crewType without throwing`() {
        assertEquals(linkedMapOf("crewType" to 1100, "amount" to 123), jingbyeong().parseArgs(mapOf("crewType" to 1100, "amount" to "123.9")))
        assertEquals(emptyMap(), jingbyeong().parseArgs(mapOf("crewType" to 999, "amount" to 100)))
        assertEquals(emptyMap(), jingbyeong().parseArgs(mapOf("crewType" to 1100, "amount" to -1)))
    }

    @Test
    fun `FULL constraint list matches PHP che_jingbyeong initWithArg order`() {
        val names = jingbyeong().buildConstraints(constraintCtx(linkedMapOf("crewType" to 1100, "amount" to 500))).map { it.name }
        assertEquals(
            listOf(
                "NotBeNeutral",
                "OccupiedCity",
                "ReqCityCapacity",
                "ReqCityTrust",
                "ReqGeneralGold",
                "ReqGeneralRice",
                "ReqGeneralCrewMargin",
                "AvailableRecruitCrewType",
            ),
            names,
        )

        val minNames = jingbyeong().buildMinConstraints(constraintCtx(emptyMap())).map { it.name }
        assertEquals(listOf("NotBeNeutral", "OccupiedCity", "ReqCityCapacity", "ReqCityTrust"), minNames)
    }

    @Test
    fun `FULL population gate uses requested crew before leadership cap`() {
        val args = linkedMapOf<String, Any?>("crewType" to 1100, "amount" to 9999)
        val ctx = constraintCtx(args)
        val pop = GameConst.minAvailableRecruitPop + 9999 - 1
        val result = evaluateConstraints(jingbyeong().buildConstraints(ctx), ctx, view(g = general(leadership = 70), c = city(pop = pop)))
        assertEquals(ConstraintResult.Deny("주민이 부족합니다.", "ReqCityCapacity"), result)
    }

    @Test
    fun `FULL gold and rice gates use applied crew after leadership cap`() {
        val args = linkedMapOf<String, Any?>("crewType" to 1100, "amount" to 9999)

        val goldCtx = constraintCtx(args)
        val goldResult = evaluateConstraints(
            jingbyeong().buildConstraints(goldCtx),
            goldCtx,
            view(g = general(leadership = 70, gold = 629, rice = 70), c = city(pop = 100_000)),
        )
        assertEquals(ConstraintResult.Deny("자금이 모자랍니다.", "ReqGeneralGold"), goldResult)

        val riceCtx = constraintCtx(args)
        val riceResult = evaluateConstraints(
            jingbyeong().buildConstraints(riceCtx),
            riceCtx,
            view(g = general(leadership = 70, gold = 630, rice = 69), c = city(pop = 100_000)),
        )
        assertEquals(ConstraintResult.Deny("군량이 모자랍니다.", "ReqGeneralRice"), riceResult)
    }

    @Test
    fun `FULL crew margin uses computed leadership and current crewType`() {
        val args = linkedMapOf<String, Any?>("crewType" to 1100, "amount" to 100)
        val ctx = constraintCtx(args)
        val result = evaluateConstraints(
            jingbyeong().buildConstraints(ctx),
            ctx,
            view(g = general(leadership = 70, crew = 7000, crewTypeId = 1100), c = city(pop = 100_000)),
        )
        assertEquals(ConstraintResult.Deny("이미 많은 병력을 보유하고 있습니다.", "ReqGeneralCrewMargin"), result)
    }

    @Test
    fun `FULL recruitable unit gate denies PHP Impossible unit and respects tech city requirements`() {
        val castleArgs = linkedMapOf<String, Any?>("crewType" to 1000, "amount" to 100)
        val castleCtx = constraintCtx(castleArgs)
        val castleResult = evaluateConstraints(
            jingbyeong().buildConstraints(castleCtx),
            castleCtx,
            view(g = general(leadership = 70, crewTypeId = 1100), c = city(pop = 100_000)),
        )
        assertEquals(ConstraintResult.Deny("현재 선택할 수 없는 병종입니다.", "AvailableRecruitCrewType"), castleResult)

        val eliteArgs = linkedMapOf<String, Any?>("crewType" to 1104, "amount" to 100)
        val denyCtx = constraintCtx(eliteArgs, cityId = 7)
        val denyResult = evaluateConstraints(
            jingbyeong().buildConstraints(denyCtx),
            denyCtx,
            view(g = general(cityId = 7), c = city(id = 7, pop = 100_000), n = nation.copy(tech = 3000.0)),
        )
        assertEquals(ConstraintResult.Deny("현재 선택할 수 없는 병종입니다.", "AvailableRecruitCrewType"), denyResult)

        val allowCtx = constraintCtx(eliteArgs, cityId = 3)
        val allowResult = evaluateConstraints(
            jingbyeong().buildConstraints(allowCtx),
            allowCtx,
            view(g = general(cityId = 3), c = city(id = 3, pop = 100_000), n = nation.copy(tech = 3000.0)),
        )
        assertEquals(ConstraintResult.Allow, allowResult)
    }

    @Test
    fun `same recruitment view yields identical PRECHECK and FULL results`() {
        val args = linkedMapOf<String, Any?>("crewType" to 1100, "amount" to 1000)
        val freshView = view(g = general(leadership = 80, gold = 1000, rice = 1000), c = city(pop = 100_000))
        val preCtx = constraintCtx(args, mode = ConstraintMode.PRECHECK)
        val fullCtx = constraintCtx(args, mode = ConstraintMode.FULL)

        val precheck = evaluateConstraints(jingbyeong().buildConstraints(preCtx), preCtx, freshView)
        val full = evaluateConstraints(jingbyeong().buildConstraints(fullCtx), fullCtx, freshView)

        assertEquals(ConstraintResult.Allow, precheck)
        assertEquals(precheck, full)
    }

    @Test
    fun `maxCrew caps amount at leadership times 100 (fresh crewType, no current crew)`() {
        // leadership 70 → maxCrew 7000; amount 9999 capped to 7000.
        val draft = GeneralActionDraft(general(leadership = 70, crew = 0, crewTypeId = 1100), city(), nation)
        jingbyeong().resolve(ctx(draft, linkedMapOf("crewType" to 1100, "amount" to 9999)))
        assertEquals(7000, draft.general.crew, "crew capped at leadership*100")
        assertEquals(1100, draft.general.crewTypeId)
    }

    @Test
    fun `same crewType subtracts current crew from maxCrew`() {
        // leadership 70 → 7000; same crewType 1100, currentCrew 2000 → maxCrew 5000; amount 9999 → applied 5000.
        val draft = GeneralActionDraft(general(leadership = 70, crew = 2000, crewTypeId = 1100,
            train = 100.0, atmos = 100.0), city(), nation)
        jingbyeong().resolve(ctx(draft, linkedMapOf("crewType" to 1100, "amount" to 9999)))
        // additive branch: crew = 2000 + 5000 = 7000
        assertEquals(7000, draft.general.crew)
    }

    @Test
    fun `cost is on applied crew with costOffset before round — 모병 doubles 징병`() {
        // amount 1000, footman cost 9 rice 9, tech 0 → costWithTech = 9 (per 100) * 1000/100 = 90.
        // 징병: reqGold = round(90 * 1) = 90; reqRice = round(1000/100) = 10.
        val dj = GeneralActionDraft(general(leadership = 100, crew = 0), city(), nation)
        jingbyeong().resolve(ctx(dj, linkedMapOf("crewType" to 1100, "amount" to 1000)))
        assertEquals(1_000_000 - 90, dj.general.gold, "징병 gold (cost 90)")
        assertEquals(1_000_000 - 10, dj.general.rice, "징병 rice (cost 10)")

        // 모병: reqGold = round(90 * 2) = 180; reqRice unchanged (rice path has no costOffset).
        val dm = GeneralActionDraft(general(leadership = 100, crew = 0), city(), nation)
        mobyeong().resolve(ctx(dm, linkedMapOf("crewType" to 1100, "amount" to 1000), rng("0".repeat(32), "che_모병")))
        assertEquals(1_000_000 - 180, dm.general.gold, "모병 gold (cost 180 = 2x)")
        assertEquals(1_000_000 - 10, dm.general.rice, "모병 rice (no costOffset)")
    }

    @Test
    fun `fresh crewType sets train atmos to the Low (징병) and High (모병) defaults`() {
        val dj = GeneralActionDraft(general(leadership = 100, crew = 0), city(), nation)
        jingbyeong().resolve(ctx(dj, linkedMapOf("crewType" to 1100, "amount" to 1000)))
        assertEquals(40.0, dj.general.train, "징병 train Low 40"); assertEquals(40.0, dj.general.atmos, "징병 atmos Low 40")

        val dm = GeneralActionDraft(general(leadership = 100, crew = 0), city(), nation)
        mobyeong().resolve(ctx(dm, linkedMapOf("crewType" to 1100, "amount" to 1000), rng("0".repeat(32), "che_모병")))
        assertEquals(70.0, dm.general.train, "모병 train High 70"); assertEquals(70.0, dm.general.atmos, "모병 atmos High 70")
    }

    @Test
    fun `train atmos RAW-float blend on additive recruit (no round)`() {
        // currentCrew 1000 (train 80, atmos 60), add 500 of same crewType (징병 Low 40/40):
        // train = (1000*80 + 500*40)/1500 = 100000/1500 = 66.6666… (RAW, not rounded)
        // atmos = (1000*60 + 500*40)/1500 = 80000/1500 = 53.3333…
        val draft = GeneralActionDraft(general(leadership = 100, crew = 1000, crewTypeId = 1100,
            train = 80.0, atmos = 60.0), city(), nation)
        jingbyeong().resolve(ctx(draft, linkedMapOf("crewType" to 1100, "amount" to 500)))
        assertEquals((1000 * 80.0 + 500 * 40.0) / 1500.0, draft.general.train, 1e-9)
        assertEquals((1000 * 60.0 + 500 * 40.0) / 1500.0, draft.general.atmos, 1e-9)
        // explicitly NOT an integer-rounded value
        assertTrue(draft.general.train % 1.0 != 0.0, "train is a raw float")
    }

    @Test
    fun `trust loss divides by costOffset — 모병 halves the 징병 loss and pop decremented`() {
        // amount 1000, pop 100000. reqCrewDown=1000. loss = (1000/100000)/costOffset*100.
        // 징병 (offset 1): 1.0 ; 모병 (offset 2): 0.5
        val dj = GeneralActionDraft(general(leadership = 100, crew = 0), city(pop = 100_000, trust = 50.0), nation)
        jingbyeong().resolve(ctx(dj, linkedMapOf("crewType" to 1100, "amount" to 1000)))
        assertEquals(49.0, dj.city.trust, 1e-9, "징병 trust loss 1.0")
        assertEquals(99_000, dj.city.population, "pop -= reqCrewDown")

        val dm = GeneralActionDraft(general(leadership = 100, crew = 0), city(pop = 100_000, trust = 50.0), nation)
        mobyeong().resolve(ctx(dm, linkedMapOf("crewType" to 1100, "amount" to 1000), rng("0".repeat(32), "che_모병")))
        assertEquals(49.5, dm.city.trust, 1e-9, "모병 trust loss 0.5 (halved)")
    }

    @Test
    fun `exp ded leadership_exp armType aux and lastTurn`() {
        // amount 1000 → exp = ded = round(1000/100) = 10.
        val draft = GeneralActionDraft(general(leadership = 100, crew = 0), city(), nation)
        jingbyeong().resolve(ctx(draft, linkedMapOf("crewType" to 1100, "amount" to 1000)))
        assertEquals(10.0, draft.general.experience, 1e-9)
        assertEquals(10.0, draft.general.dedication, 1e-9)
        assertEquals(1.0, metaDouble(draft.general.meta, "leadership_exp"), 1e-9)
        // addDex footman (armType 1) — dex1 = appliedCrew/100 = 10 (no WIZARD/SIEGE 0.9 multiplier)
        assertEquals(10.0, metaDouble(draft.general.meta, "dex1"), 1e-9)
        @Suppress("UNCHECKED_CAST")
        val aux = draft.general.meta["aux"] as Map<String, Any?>
        assertEquals(UnitSetTable.T_FOOTMAN, aux["armType"])
        assertEquals("징병", draft.general.lastTurn.command)
    }

    @Test
    fun `tail order is lastTurn checkStat staticEvent setAux then unique intent`() {
        val observed = mutableListOf<Pair<String, Any?>>()
        StaticEventHandler.register("che_징병") { general, _, _, _ ->
            @Suppress("UNCHECKED_CAST")
            val aux = general.meta["aux"] as? Map<String, Any?>
            observed += general.lastTurn.command to aux?.get("armType")
        }

        val command = jingbyeong()
        val draft = GeneralActionDraft(general(leadership = 100, crew = 0), city(), nation)
        command.resolve(ctx(draft, linkedMapOf("crewType" to 1100, "amount" to 1000)))

        assertEquals(listOf<Pair<String, Any?>>("징병" to null), observed)
        @Suppress("UNCHECKED_CAST")
        val aux = draft.general.meta["aux"] as Map<String, Any?>
        assertEquals(UnitSetTable.T_FOOTMAN, aux["armType"])
        assertEquals("징병", draft.general.lastTurn.command)
        assertEquals("징병", command.lastUniqueLotteryIntent?.seedReason)
        assertEquals("아이템", command.lastUniqueLotteryIntent?.acquireType)
        assertEquals("setResultTurn>checkStatChange>StaticEventHandler>setAux", command.lastUniqueLotteryIntent?.afterTail)
    }

    @Test
    fun `addDex applies the 0_9 wizard multiplier`() {
        // wizard 귀병 1400 (armType 4). amount 1000 → dex4 = (1000/100) * 0.9 = 9.0
        val draft = GeneralActionDraft(general(leadership = 100, crew = 0, crewTypeId = 1400), city(), nation)
        jingbyeong().resolve(ctx(draft, linkedMapOf("crewType" to 1400, "amount" to 1000)))
        assertEquals(9.0, metaDouble(draft.general.meta, "dex4"), 1e-9)
    }

    @Test
    fun `additive recruit log uses 추가 prefix and fresh recruit does not`() {
        // additive branch: same crewType + currentCrew > 0 → "추가징병"
        val add = GeneralActionDraft(general(leadership = 100, crew = 1000, crewTypeId = 1100,
            train = 50.0, atmos = 50.0), city(), nation)
        val cAdd = ctx(add, linkedMapOf("crewType" to 1100, "amount" to 500))
        jingbyeong().resolve(cAdd)
        assertEquals(1, cAdd.logs().size, "exactly one log")
        assertTrue(cAdd.logs()[0].startsWith("<C>●</>${MONTH}월:보병 <C>500</>명을 추가징병했습니다. "), cAdd.logs()[0])
        assertTrue(cAdd.logs()[0].endsWith("<1>$date</>"), cAdd.logs()[0])

        // fresh branch: different crewType → "징병" (no 추가)
        val fresh = GeneralActionDraft(general(leadership = 100, crew = 0, crewTypeId = 1100), city(), nation)
        val cFresh = ctx(fresh, linkedMapOf("crewType" to 1200, "amount" to 500))
        jingbyeong().resolve(cFresh)
        assertTrue(cFresh.logs()[0].startsWith("<C>●</>${MONTH}월:궁병 <C>500</>명을 징병했습니다. "), cFresh.logs()[0])
    }

    @Test
    fun `resolve consumes ZERO turn-RNG draws (different seeds yield identical result)`() {
        // The score/cost/mutation path never touches the rng — only the trailing lottery does. So two
        // resolves with DIFFERENT seeds must produce byte-identical drafts.
        val a = GeneralActionDraft(general(leadership = 100, crew = 0), city(), nation)
        jingbyeong().resolve(GeneralActionResolveContext(a, rng("a".repeat(32)), env, MONTH, date,
            args = linkedMapOf("crewType" to 1100, "amount" to 1000)))
        val b = GeneralActionDraft(general(leadership = 100, crew = 0), city(), nation)
        jingbyeong().resolve(GeneralActionResolveContext(b, rng("f".repeat(32)), env, MONTH, date,
            args = linkedMapOf("crewType" to 1100, "amount" to 1000)))

        assertEquals(a.general.crew, b.general.crew)
        assertEquals(a.general.gold, b.general.gold)
        assertEquals(a.general.train, b.general.train)
        assertEquals(a.general.atmos, b.general.atmos)
        assertEquals(a.city.trust, b.city.trust)
        assertEquals(a.city.population, b.city.population)
    }
}
