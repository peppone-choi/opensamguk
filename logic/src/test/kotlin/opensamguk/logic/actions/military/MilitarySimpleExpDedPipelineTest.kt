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
import kotlin.test.assertTrue

class MilitarySimpleExpDedPipelineTest {

    private data class RewardCase(
        val key: String,
        val command: String,
        val experience: Double,
        val dedication: Double,
        val args: Map<String, Any?> = emptyMap(),
        val factory: (GeneralActionPipeline) -> GeneralActionDefinition,
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
                    "experience" -> value * 2
                    "dedication" -> value * 3
                    else -> value
                }
            },
        ),
    )

    @AfterTest
    fun clearStaticHandlers() = StaticEventHandler.clear()

    @Test
    fun `귀환 folds helpers and finalizes leadership before its static event`() {
        val case = RewardCase(
            key = "che_귀환",
            command = "귀환",
            experience = 70.0,
            dedication = 100.0,
            factory = ::CheGwihwan,
        )

        assertExperienceDedicationLeadershipTail(case)
    }

    @Test
    fun `simple exp ded leadership commands fold before check and static event`() {
        listOf(
            RewardCase("che_집합", "집합", 70.0, 100.0, factory = ::CheJiphap),
            RewardCase("che_사기진작", "사기진작", 100.0, 70.0, factory = ::CheSagiJinjak),
            RewardCase("che_훈련", "훈련", 100.0, 70.0, factory = ::CheHullyeon),
            RewardCase("cr_맹훈련", "맹훈련", 150.0, 100.0, factory = ::CrMaenghullyeon),
        ).forEach(::assertExperienceDedicationLeadershipTail)
    }

    @Test
    fun `movement commands fold experience before check and static event`() {
        listOf(
            RewardCase(
                "che_강행",
                "강행",
                100.0,
                0.0,
                linkedMapOf("destCityID" to 9),
                ::CheGanghaeng,
            ),
            RewardCase(
                "che_이동",
                "이동",
                50.0,
                0.0,
                linkedMapOf("destCityID" to 9),
                ::CheIdong,
            ),
        ).forEach(::assertExperienceLeadershipTail)
    }

    @Test
    fun `소집해제 folds helpers without leadership exp or action local lottery draw`() {
        val draft = draft()
        val context = context(draft, rng = FailOnDrawRng())
        val eventSnapshots = mutableListOf<Pair<Int, String>>()
        StaticEventHandler.register("che_소집해제") { general, _, _, _ ->
            eventSnapshots += general.leadership to general.lastTurn.command
        }

        CheSojipHaeje(boostedPipeline).resolve(context)

        val general = draft.general
        assertEquals(220.0, general.experience, 1e-9, "experience must use General::addExperience")
        assertEquals(390.0, general.dedication, 1e-9, "dedication must use General::addDedication")
        assertEquals(0, general.crew)
        assertEquals(50, general.leadership, "PHP 소집해제 does not grant leadership_exp")
        assertEquals(29.0, metaDouble(general.meta, "leadership_exp"), 1e-9)
        assertEquals("소집해제", general.lastTurn.command)
        assertEquals(listOf(50 to "소집해제"), eventSnapshots, "event sees the finalized no-leadership tail")
        assertLevelLogs(context, expectedCount = 2)
    }

    private fun assertExperienceDedicationLeadershipTail(case: RewardCase) {
        StaticEventHandler.clear()
        val draft = draft()
        val context = context(draft, args = case.args)
        val eventSnapshots = mutableListOf<Pair<Int, String>>()
        StaticEventHandler.register(case.key) { general, _, _, _ ->
            eventSnapshots += general.leadership to general.lastTurn.command
        }

        case.factory(boostedPipeline).resolve(context)

        val general = draft.general
        assertEquals(80.0 + case.experience * 2, general.experience, 1e-9, "${case.key} must use General::addExperience")
        assertEquals(90.0 + case.dedication * 3, general.dedication, 1e-9, "${case.key} must use General::addDedication")
        assertEquals(51, general.leadership, "${case.key} must finalize leadership_exp exactly once")
        assertEquals(0.0, metaDouble(general.meta, "leadership_exp"), 1e-9)
        assertEquals(case.command, general.lastTurn.command)
        assertEquals(listOf(51 to case.command), eventSnapshots, "${case.key} event must run after LastTurn and checkStatChange")
        assertLevelLogs(context, expectedCount = 3)
        assertEquals("<C>●</><S>통솔</>이 <C>1</> 올랐습니다!", context.plainLogs()[2])
    }

    private fun assertExperienceLeadershipTail(case: RewardCase) {
        StaticEventHandler.clear()
        val draft = draft()
        val context = context(draft, args = case.args)
        val eventSnapshots = mutableListOf<Pair<Int, String>>()
        StaticEventHandler.register(case.key) { general, _, _, _ ->
            eventSnapshots += general.leadership to general.lastTurn.command
        }

        case.factory(boostedPipeline).resolve(context)

        val general = draft.general
        assertEquals(80.0 + case.experience * 2, general.experience, 1e-9, "${case.key} must use General::addExperience")
        assertEquals(90.0, general.dedication, 1e-9, "${case.key} has no PHP dedication reward")
        assertEquals(51, general.leadership, "${case.key} must finalize leadership_exp exactly once")
        assertEquals(0.0, metaDouble(general.meta, "leadership_exp"), 1e-9)
        assertEquals(case.command, general.lastTurn.command)
        assertEquals(listOf(51 to case.command), eventSnapshots, "${case.key} event must run after LastTurn and checkStatChange")
        assertEquals(2, context.plainLogs().size)
        assertTrue(context.plainLogs()[0].contains("레벨업"), "experience-level PLAIN log precedes stat finalization")
        assertEquals("<C>●</><S>통솔</>이 <C>1</> 올랐습니다!", context.plainLogs()[1])
    }

    private fun assertLevelLogs(context: GeneralActionResolveContext, expectedCount: Int) {
        assertEquals(expectedCount, context.plainLogs().size)
        assertTrue(context.plainLogs()[0].contains("레벨업"), "experience helper PLAIN log must be first")
        assertTrue(context.plainLogs()[1].contains("승급"), "dedication helper PLAIN log must follow experience")
    }

    private fun draft(): GeneralActionDraft {
        val actor = General(
            id = 42,
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
            crew = 1000,
            crewTypeId = 1100,
            meta = linkedMapOf(
                "explevel" to 0,
                "dedlevel" to 1,
                "leadership_exp" to 29.0,
            ),
        )
        val draft = GeneralActionDraft(actor, city(1), Nation(id = 1, level = 7, capitalCityId = 1))
        draft.destCity = city(9)
        return draft
    }

    private fun city(id: Int) = City(
        id = id,
        nationId = 1,
        level = 5,
        commerce = 1000,
        commerceMax = 20_000,
        agriculture = 1000,
        agricultureMax = 20_000,
        supplyState = 1,
        frontState = 0,
        trust = 50.0,
        population = 100_000,
        populationMax = 200_000,
    )

    private fun context(
        draft: GeneralActionDraft,
        args: Map<String, Any?> = emptyMap(),
        rng: RandUtil = RandUtil(LiteHashDrbg("military-simple-exp-ded")),
    ) = GeneralActionResolveContext(
        draft = draft,
        rng = rng,
        env = WorldEnv(year = 190, startYear = 184, develCost = 120),
        month = 3,
        date = "12:34",
        args = args,
    )

    private class FailOnDrawRng : RandUtil(LiteHashDrbg("military-simple-exp-ded-no-draw")) {
        override fun nextBool(prob: Double): Boolean = error("소집해제 must not run an action-local lottery")

        override fun nextRange(min: Double, max: Double): Double = error("소집해제 must not run an action-local lottery")

        override fun nextRangeInt(minInclusive: Int, maxInclusive: Int): Int =
            error("소집해제 must not run an action-local lottery")
    }
}
