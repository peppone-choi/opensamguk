package opensamguk.logic.actions.develop

import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.common.rng.serializeSeed
import opensamguk.logic.actions.GeneralActionDraft
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.domain.City
import opensamguk.logic.domain.General
import opensamguk.logic.domain.Nation
import opensamguk.logic.domain.WorldEnv
import opensamguk.logic.domestic.UPGRADE_LIMIT
import opensamguk.logic.event.StaticEventHandler
import opensamguk.logic.stats.GeneralActionModule
import opensamguk.logic.stats.GeneralActionPipeline
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CheDanryeonPipelineTest {
    private val month = 3
    private val date = "12:34"
    private val env = WorldEnv(year = 190, startYear = 184, develCost = 120)

    private fun freshRng() = RandUtil(
        LiteHashDrbg(serializeSeed("00000000000000000000000000000000", "generalCommand", 190, month, 42, "che_단련")),
    )

    private fun general() = General(
        id = 42,
        nationId = 1,
        cityId = 7,
        leadership = 70,
        strength = 75,
        intel = 80,
        injury = 0,
        experience = 99.0,
        dedication = 0.0,
        officerLevel = 0,
        gold = 1000,
        rice = 1000,
        crew = 5000,
        train = 100.0,
        atmos = 100.0,
        crewTypeId = 1100,
        meta = linkedMapOf(
            "explevel" to 0,
            "dedlevel" to 0,
            "leadership_exp" to 1,
            "strength_exp" to 2,
            "intel_exp" to 3,
        ),
    )

    private fun city() = City(
        id = 7,
        nationId = 1,
        level = 5,
        commerce = 1000,
        commerceMax = 20000,
        agriculture = 1000,
        agricultureMax = 20000,
        supplyState = 1,
        frontState = 0,
        trust = 50.0,
    )

    private fun nation() = Nation(id = 1, level = 2, capitalCityId = 7)

    @AfterTest
    fun clearStaticHandlers() = StaticEventHandler.clear()

    @Test
    fun `experience folds through a non identity pipeline and emits its PLAIN level log`() {
        val doubledExperience = GeneralActionPipeline(listOf(object : GeneralActionModule {
            override fun onCalcStat(general: General, statName: String, value: Double, aux: Map<String, Any?>): Double =
                if (statName == "experience") value * 2.0 else value
        }))
        val actor = general()

        val identityDraft = GeneralActionDraft(actor, city(), nation())
        cheDanryeon(GeneralActionPipeline()).resolve(
            GeneralActionResolveContext(identityDraft, freshRng(), env, month, date),
        )

        val boostedDraft = GeneralActionDraft(actor, city(), nation())
        val boostedContext = GeneralActionResolveContext(boostedDraft, freshRng(), env, month, date)
        cheDanryeon(doubledExperience).resolve(boostedContext)

        val rawExp = identityDraft.general.experience - actor.experience
        assertEquals(actor.experience + rawExp * 2.0, boostedDraft.general.experience, 1e-9)
        assertEquals(
            listOf("<C>●</><C>Lv 1</>로 <C>레벨업</>!"),
            boostedContext.plainLogs(),
            "PHP addExperience emits the PLAIN level log before the existing checkStatChange tail",
        )
        assertEquals(
            listOf(boostedContext.logs().single()) + boostedContext.plainLogs(),
            boostedContext.orderedLogEvents().map { it.text },
        )
    }

    @Test
    fun `tail sets LastTurn and checks stats before StaticEventHandler`() {
        val actor = general().copy(
            meta = linkedMapOf(
                "explevel" to 0,
                "dedlevel" to 0,
                "leadership_exp" to UPGRADE_LIMIT - 1,
                "strength_exp" to UPGRADE_LIMIT - 1,
                "intel_exp" to UPGRADE_LIMIT - 1,
            ),
        )
        val draft = GeneralActionDraft(actor, city(), nation())
        val context = GeneralActionResolveContext(draft, freshRng(), env, month, date)
        val observedTurns = mutableListOf<String>()
        val observedStatTotals = mutableListOf<Int>()
        val observedPlainLogs = mutableListOf<List<String>>()
        StaticEventHandler.register("che_단련") { eventGeneral, _, _, _ ->
            observedTurns += eventGeneral.lastTurn.command
            observedStatTotals += eventGeneral.leadership + eventGeneral.strength + eventGeneral.intel
            observedPlainLogs += context.plainLogs()
        }

        cheDanryeon(GeneralActionPipeline()).resolve(context)

        assertEquals(listOf("단련"), observedTurns)
        assertEquals(listOf(actor.leadership + actor.strength + actor.intel + 1), observedStatTotals)
        assertTrue(observedPlainLogs.single().any { it.contains("올랐습니다!") })
    }
}
