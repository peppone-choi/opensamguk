package opensamguk.logic.actions.military

import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.logic.actions.GeneralActionDefinition
import opensamguk.logic.actions.GeneralActionDraft
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.domain.City
import opensamguk.logic.domain.General
import opensamguk.logic.domain.Nation
import opensamguk.logic.domain.WorldEnv
import opensamguk.logic.domain.metaDouble
import opensamguk.logic.event.StaticEventHandler
import opensamguk.logic.stats.GeneralActionModule
import opensamguk.logic.stats.GeneralActionPipeline
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MilitaryComplexExpDedPipelineTest {

    private data class SabotageCase(
        val key: String,
        val statExpKey: String,
        val statNick: String,
        val factory: (GeneralActionPipeline) -> GeneralActionDefinition,
    )

    private val sabotageCases = listOf(
        SabotageCase("che_선동", "leadership_exp", "통솔", ::cheSeondong),
        SabotageCase("che_화계", "intel_exp", "지력", ::cheHwagye),
        SabotageCase("che_파괴", "strength_exp", "무력", ::chePagoe),
        SabotageCase("che_탈취", "strength_exp", "무력", ::cheTalchwi),
    )

    private val boostedPipeline = GeneralActionPipeline(
        listOf(
            object : GeneralActionModule {
                override fun onCalcStat(
                    general: General,
                    statName: String,
                    value: Double,
                    aux: Map<String, Any?>,
                ): Double = when (statName) {
                    "experience", "dedication" -> value * 2
                    else -> value
                }
            },
        ),
    )

    @AfterTest
    fun clearStaticHandlers() = StaticEventHandler.clear()

    @Test
    fun `recruit folds boosted exp ded and keeps check event lottery tail`() {
        val command = RecruitAlgorithm.cheJingbyeong(boostedPipeline)
        val draft = draft("leadership_exp")
        val context = context(draft, BranchRng(success = false), linkedMapOf("crewType" to 1100, "amount" to 1000))
        val eventLeadership = mutableListOf<Int>()
        StaticEventHandler.register("che_징병") { general, _, _, _ -> eventLeadership += general.leadership }

        command.resolve(context)

        assertFoldedTail(draft, context, "leadership_exp", "통솔", expDelta = 10, dedDelta = 10)
        assertEquals(listOf(51), eventLeadership, "PHP recruit runs checkStatChange before StaticEventHandler")
        assertNotNull(command.lastUniqueLotteryIntent)
        assertEquals("setResultTurn>checkStatChange>StaticEventHandler>setAux", command.lastUniqueLotteryIntent?.afterTail)
    }

    @Test
    fun `spy folds boosted exp ded and keeps static event before check tail`() {
        val command = cheCheobo(boostedPipeline)
        val draft = draft("leadership_exp")
        val context = context(draft, BranchRng(success = false), linkedMapOf("destCityID" to 2))
        val eventLeadership = mutableListOf<Int>()
        StaticEventHandler.register("che_첩보") { general, _, _, _ -> eventLeadership += general.leadership }

        command.resolve(context)

        assertFoldedTail(draft, context, "leadership_exp", "통솔", expDelta = 10, dedDelta = 10)
        assertEquals(listOf(50), eventLeadership, "PHP spy dispatches StaticEventHandler before checkStatChange")
    }

    @Test
    fun `all sabotage failure branches fold boosted exp ded and finalize once`() {
        for (case in sabotageCases) {
            StaticEventHandler.clear()
            val events = mutableListOf<Int>()
            StaticEventHandler.register(case.key) { general, _, _, _ -> events += statValue(general, case.statExpKey) }
            val draft = draft(case.statExpKey)
            val context = context(draft, BranchRng(success = false), linkedMapOf("destCityID" to 2))

            case.factory(boostedPipeline).resolve(context)

            assertFoldedTail(draft, context, case.statExpKey, case.statNick, expDelta = 10, dedDelta = 10)
            assertTrue(events.isEmpty(), "${case.key} PHP failure branch does not dispatch StaticEventHandler")
        }
    }

    @Test
    fun `all sabotage success branches fold boosted exp ded preserve item and dispatch before check`() {
        for (case in sabotageCases) {
            StaticEventHandler.clear()
            val eventStats = mutableListOf<Int>()
            StaticEventHandler.register(case.key) { general, _, _, _ -> eventStats += statValue(general, case.statExpKey) }
            val draft = draft(case.statExpKey, item = "che_계략_향낭")
            val context = context(draft, BranchRng(success = true), linkedMapOf("destCityID" to 2))

            case.factory(boostedPipeline).resolve(context)

            assertFoldedTail(draft, context, case.statExpKey, case.statNick, expDelta = 210, dedDelta = 150)
            assertEquals("None", draft.general.item, "${case.key} keeps its successful item-consumption effect")
            assertSuccessPlainOrder(context, case.key)
            assertEquals(1, draft.rankIncrements.size, "${case.key} preserves the single success rank increment")
            assertEquals(listOf(50), eventStats, "${case.key} dispatches StaticEventHandler before checkStatChange")
        }
    }

    private fun assertFoldedTail(
        draft: GeneralActionDraft,
        context: GeneralActionResolveContext,
        statExpKey: String,
        statNick: String,
        expDelta: Int,
        dedDelta: Int,
    ) {
        val general = draft.general
        assertEquals(80.0 + expDelta * 2.0, general.experience, "experience must use General::addExperience")
        assertEquals(90.0 + dedDelta * 2.0, general.dedication, "dedication must use General::addDedication")
        assertEquals(51, statValue(general, statExpKey), "checkStatChange must run exactly once after ${statExpKey}++")
        assertEquals(0.0, metaDouble(general.meta, statExpKey), "checkStatChange consumes the threshold once")

        val expLevel = if (expDelta == 10) 1 else 5
        assertEquals(
            listOf(
                "<C>●</><C>Lv $expLevel</>로 <C>레벨업</>!",
                "<C>●</><Y>29품관</>으로 <C>승급</>하여 봉록이 <C>800</>으로 <C>상승</>했습니다!",
                "<C>●</><S>$statNick</>이 <C>1</> 올랐습니다!",
            ),
            context.plainLogs(),
            "exp/ded level logs must precede the single stat-change PLAIN log",
        )
    }

    private fun assertSuccessPlainOrder(context: GeneralActionResolveContext, key: String) {
        val ordered = context.orderedLogEvents().map { it.text }
        val itemIndex = ordered.indexOf("<C>●</><C>향낭(계략)</>을 사용!")
        val experienceIndex = ordered.indexOf("<C>●</><C>Lv 5</>로 <C>레벨업</>!")
        val dedicationIndex = ordered.indexOf("<C>●</><Y>29품관</>으로 <C>승급</>하여 봉록이 <C>800</>으로 <C>상승</>했습니다!")
        val statIndex = ordered.indexOfFirst { it.contains("<S>") && it.contains("<C>1</> 올랐습니다!") }

        assertTrue(itemIndex >= 0, "$key emits the consumed-item PLAIN log")
        assertTrue(itemIndex < experienceIndex, "$key emits item PLAIN before experience PLAIN")
        assertTrue(experienceIndex < dedicationIndex, "$key keeps experience before dedication PLAIN")
        assertTrue(dedicationIndex < statIndex, "$key keeps dedication before checkStatChange PLAIN")
    }

    private fun draft(statExpKey: String, item: String = "None"): GeneralActionDraft {
        val actor = General(
            id = 1,
            nationId = 1,
            cityId = 1,
            leadership = 50,
            strength = 50,
            intel = 50,
            injury = 0,
            experience = 80.0,
            dedication = 90.0,
            officerLevel = 1,
            gold = 100_000,
            rice = 100_000,
            crew = 0,
            crewTypeId = 1100,
            item = item,
            meta = linkedMapOf(
                "explevel" to 0,
                "dedlevel" to 1,
                statExpKey to 29.0,
            ),
        )
        val ownCity = City(
            id = 1,
            nationId = 1,
            level = 8,
            commerce = 2_000,
            commerceMax = 10_000,
            agriculture = 2_000,
            agricultureMax = 10_000,
            supplyState = 1,
            frontState = 0,
            trust = 100.0,
            population = 100_000,
            populationMax = 200_000,
            security = 2_000,
            securityMax = 10_000,
            defense = 2_000,
            defenseMax = 10_000,
            wall = 2_000,
            wallMax = 10_000,
        )
        val draft = GeneralActionDraft(actor, ownCity, Nation(id = 1, level = 2, capitalCityId = 1, gold = 100_000, rice = 100_000))
        draft.destCity = ownCity.copy(id = 2, nationId = 2)
        draft.destNation = Nation(id = 2, level = 2, capitalCityId = 2, gold = 100_000, rice = 100_000)
        return draft
    }

    private fun context(
        draft: GeneralActionDraft,
        rng: RandUtil,
        args: Map<String, Any?>,
    ): GeneralActionResolveContext = GeneralActionResolveContext(
        draft = draft,
        rng = rng,
        env = WorldEnv(year = 200, startYear = 200, develCost = 20),
        month = 1,
        date = "00:00",
        args = args,
        candidateGenerals = emptyList(),
        cityDistance = 1,
    )

    private fun statValue(general: General, statExpKey: String): Int = when (statExpKey) {
        "leadership_exp" -> general.leadership
        "strength_exp" -> general.strength
        "intel_exp" -> general.intel
        else -> error("unsupported stat key: $statExpKey")
    }

    private class BranchRng(private val success: Boolean) : RandUtil(LiteHashDrbg("military-exp-ded-pipeline")) {
        override fun nextBool(prob: Double): Boolean = success

        override fun nextRangeInt(minInclusive: Int, maxInclusive: Int): Int = when (minInclusive to maxInclusive) {
            1 to 100 -> 10
            1 to 70 -> 10
            100 to 800 -> 100
            201 to 300 -> 210
            141 to 210 -> 150
            else -> minInclusive
        }

        override fun nextRange(min: Double, max: Double): Double = min
    }
}
