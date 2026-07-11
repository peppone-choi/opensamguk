package opensamguk.logic.actions

import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.common.rng.serializeSeed
import opensamguk.logic.actions.develop.CheGyeonmun
import opensamguk.logic.actions.personnel.CheInjaeTamsaek
import opensamguk.logic.domain.City
import opensamguk.logic.domain.General
import opensamguk.logic.domain.Nation
import opensamguk.logic.domain.WorldEnv
import opensamguk.logic.domain.metaDouble
import opensamguk.logic.stats.GeneralActionPipeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class AiCommandBodiesTest {
    private val pipeline = GeneralActionPipeline()
    private val env = WorldEnv(200, 184, 100)
    private val city = City(7, 1, 5, 1, 1, 1, 1, 1, 0, 50.0)
    private val nation = Nation(1, 3, 7, name = "위")

    private fun general() = General(
        id = 42, nationId = 1, cityId = 7,
        leadership = 70, strength = 60, intel = 50, injury = 0,
        experience = 0.0, dedication = 0.0, officerLevel = 1, gold = 500, rice = 500,
        meta = linkedMapOf("name" to "조조", "explevel" to 10, "dedlevel" to 5),
    )

    @Test
    fun `gyeonmun applies weighted outcome text deltas and wound draws in PHP order`() {
        val draft = GeneralActionDraft(general(), city, nation)
        val rng = ScriptedRng(weightedIndex = 12, rangeValues = ArrayDeque(listOf(10, 20)))
        val context = GeneralActionResolveContext(draft, rng, env, 7, "13:45")

        CheGyeonmun(pipeline).resolve(context)

        assertEquals(60.0, draft.general.experience)
        assertEquals(30, draft.general.injury)
        assertEquals(
            "<C>●</>7월:위기에 빠진 사람을 구하다가 죽을뻔 했습니다. <1>13:45</>",
            context.logs().single(),
        )
        assertEquals("견문", draft.general.lastTurn.command)
    }

    @Test
    fun `gyeonmun replaces resource placeholder and clamps losses`() {
        val draft = GeneralActionDraft(general().copy(gold = 50), city, nation)
        val context = GeneralActionResolveContext(
            draft,
            ScriptedRng(weightedIndex = 7),
            env,
            7,
            "13:45",
        )

        CheGyeonmun(pipeline).resolve(context)

        assertEquals(0, draft.general.gold)
        assertEquals(30.0, draft.general.experience)
        assertEquals(
            "<C>●</>7월:산적을 만나 금 <C>200</>을 빼앗겼습니다. <1>13:45</>",
            context.logs().single(),
        )
    }

    @Test
    fun `injae tamsaek failure spends cost and awards the failure deltas`() {
        val draft = GeneralActionDraft(general(), city, nation)
        val args = linkedMapOf<String, Any?>(
            "maxGenCnt" to 100,
            "totalGenCnt" to 100,
            "totalNpcCnt" to 100,
            "avgGenDexTotal" to 0.0,
            "avgGenDex5" to 0,
            "year" to 200,
            "startYear" to 184,
            "month" to 7,
            "develCost" to 100,
            "turnterm" to 120,
            "cityPool" to emptyList<Map<String, Int>>(),
        )
        val context = GeneralActionResolveContext(
            draft,
            ScriptedRng(false, "intel_exp"),
            env,
            7,
            "13:45",
            args = args,
        )

        CheInjaeTamsaek(pipeline).resolve(context)

        assertEquals(400, draft.general.gold)
        assertEquals(100.0, draft.general.experience)
        assertEquals(70.0, draft.general.dedication)
        assertEquals(1.0, metaDouble(draft.general.meta, "intel_exp"))
        assertEquals(
            "<C>●</>7월:인재를 찾을 수 없었습니다. <1>13:45</>",
            context.logs().single(),
        )
        assertEquals("인재탐색", draft.general.lastTurn.command)
    }

    @Test
    fun `injae tamsaek success consumes the PHP age death name and builder stream`() {
        val draft = GeneralActionDraft(general(), city, nation)
        val args = linkedMapOf<String, Any?>(
            "maxGenCnt" to 100,
            "totalGenCnt" to 0,
            "totalNpcCnt" to 0,
            "avgGenDexTotal" to 0.0,
            "avgGenDex5" to 0,
            "year" to 200,
            "startYear" to 184,
            "month" to 7,
            "develCost" to 100,
            "turnterm" to 120,
            "cityPool" to listOf(mapOf("id" to 7, "nationId" to 1)),
        )
        val context = GeneralActionResolveContext(
            draft,
            ScriptedRng(true, "leadership_exp", rangeValues = ArrayDeque(listOf(20, 10, 0, 0, 0, 0))),
            env,
            7,
            "13:45",
            args = args,
        )

        val command = CheInjaeTamsaek(pipeline)
        command.resolve(context)

        assertNotNull(command.lastBuiltNpc)
        assertEquals(400, draft.general.gold)
        assertEquals(200.0, draft.general.experience)
        assertEquals(300.0, draft.general.dedication)
        assertEquals(3.0, metaDouble(draft.general.meta, "leadership_exp"))
        assertEquals(1, context.globalActionLogs().size)
    }

    private class ScriptedRng(
        private val boolValue: Boolean = false,
        private val weightedValue: String = "leadership_exp",
        private val weightedIndex: Int = 0,
        private val rangeValues: ArrayDeque<Int> = ArrayDeque(),
    ) : RandUtil(LiteHashDrbg(serializeSeed("00000000000000000000000000000000", "test"))) {
        override fun nextBool(prob: Double): Boolean = boolValue
        override fun nextRangeInt(minInclusive: Int, maxInclusive: Int): Int =
            if (rangeValues.isEmpty()) minInclusive else rangeValues.removeFirst()
        override fun <T> choiceUsingWeightPair(items: List<Pair<T, Double>>): T = items[weightedIndex].first
        override fun choiceUsingWeight(items: Map<String, Double>): String = weightedValue
        override fun <T> choice(items: List<T>): T = items.first()
    }
}
