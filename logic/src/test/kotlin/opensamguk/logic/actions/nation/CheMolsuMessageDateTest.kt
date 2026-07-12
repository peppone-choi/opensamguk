package opensamguk.logic.actions.nation

import opensamguk.common.rng.NoRng
import opensamguk.common.rng.RandUtil
import opensamguk.logic.actions.GeneralActionDraft
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.domain.City
import opensamguk.logic.domain.General
import opensamguk.logic.domain.Nation
import opensamguk.logic.domain.WorldEnv
import opensamguk.logic.message.MessageType
import opensamguk.logic.stats.GeneralActionPipeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CheMolsuMessageDateTest {

    @Test
    fun `npc seizure message carries a database-compatible timestamp`() {
        val actor = General(1, 1, 7, 80, 80, 80, 0, 0.0, 0.0, 12, 1000, 1000)
        val city = City(7, 1, 5, 0, 0, 0, 0, 1, 0, 50.0)
        val nation = Nation(1, 5, 7, name = "촉", color = "#00ff00")
        val dest = General(2, 1, 7, 70, 70, 70, 0, 0.0, 0.0, 1, 1000, 1000, npcType = 2)
        val draft = GeneralActionDraft(actor, city, nation).also { it.destGeneral = dest }
        val context = GeneralActionResolveContext(
            draft = draft,
            rng = object : RandUtil(NoRng()) {
                override fun nextBool(prob: Double): Boolean = true
                override fun <T> choice(items: List<T>): T = items.first()
            },
            env = WorldEnv(year = 200, startYear = 190, develCost = 100),
            month = 1,
            date = "12:34",
            args = mapOf("isGold" to true, "amount" to 100, "destGeneralID" to 2),
        )

        cheMolsu(GeneralActionPipeline()).resolve(context)

        val message = context.messages().single()
        assertEquals(MessageType.PUBLIC, message.msgType)
        assertTrue(message.date.matches(Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}")))
    }
}
