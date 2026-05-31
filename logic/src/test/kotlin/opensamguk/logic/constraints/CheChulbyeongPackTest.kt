package opensamguk.logic.constraints

import opensamguk.logic.actions.war.CheChulbyeong
import opensamguk.logic.stats.GeneralActionPipeline
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * FR1 — che_출병 FULL-pack repair (PARITY-FATAL, R-BRIDGE §2).
 *
 * Port oracle = PHP `che_출병.php:78-88` `fullConditionConstraints`:
 *   [NotOpeningPart(relYear), NotSameDestCity, NotBeNeutral, OccupiedCity,
 *    ReqGeneralCrew, ReqGeneralRice(reqRice), AllowWar, HasRouteWithEnemy]
 *
 * P4 shipped only the trimmed BO3 set `[NotBeNeutral, OccupiedCity, ReqGeneralCrew]`; the P5 AI
 * bridge needs the FULL PHP pack so the AI gate boolean flips identically. The constraint ORDER is
 * itself a parity target (first-deny-wins).
 */
class CheChulbyeongPackTest {

    private val pipeline = GeneralActionPipeline()

    private fun ctx() = ConstraintContext(
        actorId = 1,
        cityId = 1,
        nationId = 1,
        destCityId = 6,
        args = linkedMapOf("destCityID" to 6),
        env = linkedMapOf<String, Any?>("year" to 200, "startYear" to 180),
        mode = ConstraintMode.FULL,
    )

    @Test fun `che_출병 FULL pack has all 8 constraints in PHP order`() {
        val def = CheChulbyeong(pipeline)
        val names = def.buildConstraints(ctx()).map { it.name }
        assertEquals(
            listOf(
                "NotOpeningPart",
                "NotSameDestCity",
                "NotBeNeutral",
                "OccupiedCity",
                "ReqGeneralCrew",
                "ReqGeneralRice",
                "AllowWar",
                "HasRouteWithEnemy",
            ),
            names,
        )
    }
}
