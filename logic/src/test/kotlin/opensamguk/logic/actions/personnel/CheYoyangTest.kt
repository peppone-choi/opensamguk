package opensamguk.logic.actions.personnel

import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.common.rng.serializeSeed
import opensamguk.logic.actions.GeneralActionDraft
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.domain.City
import opensamguk.logic.domain.General
import opensamguk.logic.domain.WorldEnv
import opensamguk.logic.domain.metaInt
import opensamguk.logic.event.StaticEventHandler
import opensamguk.logic.stats.GeneralActionModule
import opensamguk.logic.stats.GeneralActionPipeline
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CheYoyangTest {

    @AfterTest
    fun clearStaticHandlers() = StaticEventHandler.clear()

    @Test
    fun `che_요양 folds experience and dedication before their level bookkeeping in PHP order`() {
        val pipeline = GeneralActionPipeline(listOf(doubledExperienceAndDedication))
        val actor = General(
            id = 42,
            nationId = 0,
            cityId = 0,
            leadership = 70,
            strength = 70,
            intel = 80,
            injury = 9,
            experience = 990.0,
            dedication = 90.0,
            officerLevel = 0,
            gold = 1000,
            rice = 1000,
            meta = linkedMapOf("explevel" to 9, "dedlevel" to 1),
        )
        val draft = GeneralActionDraft(actor, neutralCity(), null)
        val context = GeneralActionResolveContext(
            draft = draft,
            rng = freshRng(),
            env = WorldEnv(year = 200, startYear = 184, develCost = 120),
            month = 4,
            date = "11:11",
        )

        CheYoyang(pipeline).resolve(context)

        assertEquals(0, draft.general.injury)
        assertEquals(1010.0, draft.general.experience)
        assertEquals(10, metaInt(draft.general.meta, "explevel"))
        assertEquals(104.0, draft.general.dedication)
        assertEquals(2, metaInt(draft.general.meta, "dedlevel"))
        assertEquals(
            listOf(
                "<C>●</><C>Lv 10</>으로 <C>레벨업</>!",
                "<C>●</><Y>29품관</>으로 <C>승급</>하여 봉록이 <C>800</>으로 <C>상승</>했습니다!",
            ),
            context.plainLogs(),
        )
    }

    @Test
    fun `che_요양 writes LastTurn then finalizes stats and dispatches its event after the plain tail`() {
        val actor = General(
            id = 42,
            nationId = 0,
            cityId = 0,
            leadership = 70,
            strength = 70,
            intel = 80,
            injury = 9,
            experience = 0.0,
            dedication = 90.0,
            officerLevel = 0,
            gold = 1000,
            rice = 1000,
            meta = linkedMapOf("explevel" to 0, "dedlevel" to 1, "strength_exp" to 30),
        )
        val draft = GeneralActionDraft(actor, neutralCity(), null)
        val context = GeneralActionResolveContext(
            draft = draft,
            rng = freshRng(),
            env = WorldEnv(year = 200, startYear = 184, develCost = 120),
            month = 4,
            date = "11:11",
        )
        var event: EventObservation? = null
        StaticEventHandler.register("che_요양") { general, destGeneral, eventEnv, eventArgs ->
            event = EventObservation(general, destGeneral, eventEnv, eventArgs, context.plainLogs())
        }

        CheYoyang(GeneralActionPipeline()).resolve(context)

        assertEquals("요양", draft.general.lastTurn.command)
        assertNull(draft.general.lastTurn.arg)
        assertEquals(71, draft.general.strength)
        assertEquals(0.0, draft.general.meta["strength_exp"])
        val expectedPlain = listOf("<C>●</><S>무력</>이 <C>1</> 올랐습니다!")
        assertEquals(expectedPlain, context.plainLogs())

        val observed = assertNotNull(event)
        assertEquals("요양", observed.general.lastTurn.command)
        assertEquals(71, observed.general.strength)
        assertEquals(0.0, observed.general.meta["strength_exp"])
        assertNull(observed.destGeneral)
        assertEquals(emptyMap(), observed.eventEnv)
        assertEquals(emptyMap(), observed.eventArgs)
        assertEquals(expectedPlain, observed.plainLogsAtDispatch)
    }

    private fun neutralCity() = City(
        id = 0,
        nationId = 0,
        level = 0,
        commerce = 0,
        commerceMax = 0,
        agriculture = 0,
        agricultureMax = 0,
        supplyState = 1,
        frontState = 0,
        trust = 0.0,
    )

    private fun freshRng() = RandUtil(
        LiteHashDrbg(serializeSeed("00000000000000000000000000000000", "generalCommand", 200, 4, 42, "che_요양")),
    )

    private data class EventObservation(
        val general: General,
        val destGeneral: General?,
        val eventEnv: Map<String, Any?>,
        val eventArgs: Map<String, Any?>,
        val plainLogsAtDispatch: List<String>,
    )

    private companion object {
        val doubledExperienceAndDedication = object : GeneralActionModule {
            override fun onCalcStat(
                general: General,
                statName: String,
                value: Double,
                aux: Map<String, Any?>,
            ): Double = if (statName == "experience" || statName == "dedication") value * 2.0 else value
        }
    }
}
