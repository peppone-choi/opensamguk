package opensamguk.logic.actions

import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.common.rng.serializeSeed
import opensamguk.logic.actions.develop.CheGyeonmun
import opensamguk.logic.actions.develop.SightseeingExternalOutcome
import opensamguk.logic.actions.develop.SightseeingExternalSelector
import opensamguk.logic.actions.military.CheNpcNeungdong
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
    fun `npc active movement preserves its canonical args in last turn`() {
        val draft = GeneralActionDraft(general(), city, nation)
        val args = linkedMapOf<String, Any?>("optionText" to "순간이동", "destCityID" to 18)
        val context = GeneralActionResolveContext(
            draft,
            ScriptedRng(),
            env,
            7,
            "13:45",
            args = args,
        )

        CheNpcNeungdong(pipeline).resolve(context)

        assertEquals(18, draft.general.cityId)
        assertEquals("NPC능동", draft.general.lastTurn.command)
        assertEquals(args, draft.general.lastTurn.arg)
    }

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
    fun `gyeonmun uses the PHP ambient table weights while retaining production action RNG picks`() {
        val draft = GeneralActionDraft(general(), city, nation)
        val rng = SightseeingTableRecordingRng()
        val context = GeneralActionResolveContext(draft, rng, env, 7, "13:45")

        CheGyeonmun(pipeline).resolve(context)

        assertEquals(
            listOf(1.0, 1.0, 2.0, 2.0, 2.0) + List(12) { 1.0 },
            rng.outcomeWeights,
            "SightseeingMessage.php keeps its first five weighted groups distinct",
        )
        assertEquals(1, rng.weightedPickCount, "production fallback still consumes the action RNG outcome pick")
        assertEquals(1, rng.textPickCount, "production fallback still consumes the action RNG text pick")
    }

    @Test
    fun `gyeonmun replays actor 102 phase one then phase two without action RNG draws`() {
        data class ReplayStep(val date: String, val outcome: SightseeingExternalOutcome)

        val stream = ArrayDeque(
            listOf(
                ReplayStep(
                    "00:01",
                    SightseeingExternalOutcome(
                        type = 18,
                        text = "어느 집의 도망친 가축을 되찾아 주었습니다.",
                        woundedDraw = null,
                        heavyWoundedDraw = null,
                    ),
                ),
                ReplayStep(
                    "02:01",
                    SightseeingExternalOutcome(
                        type = 34,
                        text = "동네 장사와 힘겨루기를 하여 멋지게 이겼습니다.",
                        woundedDraw = null,
                        heavyWoundedDraw = null,
                    ),
                ),
            ),
        )
        val selector = SightseeingExternalSelector { actor, year, month, date, candidates ->
            val step = stream.removeFirst()
            assertEquals(102, actor)
            assertEquals(181, year)
            assertEquals(1, month)
            assertEquals(step.date, date)
            assertEquals(
                listOf(1 to 1.0, 2 to 1.0, 18 to 2.0, 34 to 2.0, 66 to 2.0) +
                    listOf(257, 513, 1025, 2049, 4097, 4098, 8193, 12290, 290, 546, 322, 578)
                        .map { it to 1.0 },
                candidates.map { it.type to it.weight },
            )
            step.outcome
        }
        val command = CheGyeonmun(pipeline, selector)
        val noActionDrawRng = NoActionDrawRng()
        val phaseOne = GeneralActionDraft(
            general().copy(id = 102, experience = 2500.0, gold = 1000, rice = 1000),
            city,
            nation,
        )

        val phaseOneContext =
            GeneralActionResolveContext(phaseOne, noActionDrawRng, WorldEnv(181, 184, 100), 1, "00:01")
        command.resolve(phaseOneContext)

        assertEquals(2560.0, phaseOne.general.experience)
        assertEquals(2.0, metaDouble(phaseOne.general.meta, "leadership_exp"))
        assertEquals(
            "<C>●</>1월:어느 집의 도망친 가축을 되찾아 주었습니다. <1>00:01</>",
            phaseOneContext.logs().single(),
        )

        val phaseTwo = GeneralActionDraft(phaseOne.general, city, nation)
        val phaseTwoContext =
            GeneralActionResolveContext(phaseTwo, noActionDrawRng, WorldEnv(181, 184, 100), 1, "02:01")
        command.resolve(phaseTwoContext)

        assertEquals(2620.0, phaseTwo.general.experience)
        assertEquals(2.0, metaDouble(phaseTwo.general.meta, "leadership_exp"))
        assertEquals(2.0, metaDouble(phaseTwo.general.meta, "strength_exp"))
        assertEquals(
            "<C>●</>1월:동네 장사와 힘겨루기를 하여 멋지게 이겼습니다. <1>02:01</>",
            phaseTwoContext.logs().single(),
        )
        assertEquals(0, stream.size)
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
        assertEquals(1, context.generalHistoryLogs().size)
        assertEquals(2, context.plainLogs().size)
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

    private class SightseeingTableRecordingRng : RandUtil(
        LiteHashDrbg(serializeSeed("00000000000000000000000000000000", "sightseeing-table")),
    ) {
        var outcomeWeights: List<Double> = emptyList()
        var weightedPickCount = 0
        var textPickCount = 0

        override fun <T> choiceUsingWeightPair(items: List<Pair<T, Double>>): T {
            weightedPickCount++
            outcomeWeights = items.map { it.second }
            return items.first().first
        }

        override fun <T> choice(items: List<T>): T {
            textPickCount++
            return items.first()
        }
    }

    private class NoActionDrawRng : RandUtil(
        LiteHashDrbg(serializeSeed("00000000000000000000000000000000", "sightseeing-replay")),
    ) {
        override fun nextRangeInt(minInclusive: Int, maxInclusive: Int): Int =
            error("external sightseeing replay must not consume action range draws")

        override fun <T> choiceUsingWeightPair(items: List<Pair<T, Double>>): T =
            error("external sightseeing replay must not consume action weighted draws")

        override fun <T> choice(items: List<T>): T =
            error("external sightseeing replay must not consume action text draws")
    }
}
